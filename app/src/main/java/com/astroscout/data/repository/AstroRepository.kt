package com.astroscout.data.repository

import com.astroscout.data.local.AstroDatabase
import com.astroscout.data.local.ElevationEntity
import com.astroscout.data.local.ForecastEntity
import com.astroscout.data.local.MeteoEntity
import com.astroscout.data.local.ParkingCacheEntity
import com.astroscout.data.local.SpotEntity
import com.astroscout.data.remote.NetworkModule
import com.astroscout.data.remote.api.NominatimApi
import com.astroscout.data.remote.api.OpenMeteoApi
import com.astroscout.data.remote.api.OverpassApi
import com.astroscout.data.remote.api.SevenTimerApi
import com.astroscout.data.remote.dto.AstroForecastResponse
import com.astroscout.data.remote.dto.OpenMeteoResponse
import com.astroscout.data.remote.dto.OverpassResponse
import com.astroscout.data.remote.mapper.ParkingMapper
import com.astroscout.data.settings.SettingsRepository
import com.astroscout.data.sky.SkyQualitySource
import com.astroscout.domain.astro.AstroNight
import com.astroscout.domain.astro.AstroNightCalculator
import com.astroscout.domain.forecast.NightForecastInterpreter
import com.astroscout.domain.forecast.OpenMeteoInterpreter
import com.astroscout.domain.forecast.WeatherDetail
import com.astroscout.domain.geo.Geo
import com.astroscout.domain.model.DataError
import com.astroscout.domain.model.DataError.Service
import com.astroscout.domain.model.Fetched
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.NightForecast
import com.astroscout.domain.model.Parking
import com.astroscout.domain.model.PlaceResult
import com.astroscout.domain.model.ScoreWeights
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.Spot
import com.astroscout.domain.model.SpotOrigin
import com.astroscout.domain.scoring.AstroScoreCalculator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Esito di un refresh multiplo, per un messaggio riassuntivo nella UI. */
data class RefreshReport(
    val total: Int,
    val fresh: Int,
    val fromCache: Int,
    val failed: Int,
    val errors: List<DataError>,
) {
    val hasProblems: Boolean get() = fromCache > 0 || failed > 0
}

data class SpotRefresh(
    val sky: Fetched<SkyQuality>,
    val forecast: Fetched<AstroForecastResponse>,
    val meteo: Fetched<OpenMeteoResponse>,
) {
    val anyFresh get() = sky is Fetched.Fresh || forecast is Fetched.Fresh || meteo is Fetched.Fresh
    val anyData get() = sky.value != null || forecast.value != null || meteo.value != null
    val errors get() = listOf(sky, forecast, meteo).mapNotNull { (it as? Fetched.Cached)?.error ?: (it as? Fetched.Failed)?.error }
}

/**
 * Unica porta d'accesso ai dati per i ViewModel e il Worker.
 *
 * **Strategia di cache (stale-while-revalidate, single source of truth = Room):**
 * 1. la UI osserva SEMPRE Room ([observeSpots]): non riceve mai i dati direttamente dalla rete;
 * 2. [refresh] scarica solo ciò che è oltre la TTL e scrive in Room → Room emette → la UI si aggiorna;
 * 3. se la rete fallisce resta in Room l'ultimo valore valido, e la UI lo mostra marcato "da cache".
 *
 * TTL: qualità del cielo 30 giorni (cambia su scala di anni), forecast 3 h (7Timer! ricalcola ogni
 * 6 h su run GFS 00/12 UTC più assimilazioni), parcheggi 7 giorni.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AstroRepository(
    private val db: AstroDatabase,
    private val skySource: SkyQualitySource,
    private val sevenTimer: SevenTimerApi,
    private val overpass: OverpassApi,
    private val nominatim: NominatimApi,
    private val openMeteo: OpenMeteoApi,
    private val json: Json,
    private val settings: SettingsRepository,
    private val scorer: AstroScoreCalculator = AstroScoreCalculator(),
    private val interpreter: NightForecastInterpreter = NightForecastInterpreter(),
    private val astroCalc: AstroNightCalculator = AstroNightCalculator(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val spotDao = db.spotDao()
    private val forecastDao = db.forecastDao()
    private val parkingDao = db.parkingDao()
    private val meteoDao = db.meteoDao()
    private val elevationDao = db.elevationDao()

    /**
     * Cache delle effemeridi per (cella 0.05° ≈ 5 km, data della sera): il crepuscolo cambia di
     * ~1 minuto ogni 10 km, quindi spot vicini condividono lo stesso calcolo.
     */
    private val nightCache = ConcurrentHashMap<String, AstroNight>()

    /** Massimo 4 punti in parallelo: cortesia verso API gratuite e niente burst da 25 richieste. */
    private val networkPermits = Semaphore(MAX_PARALLEL)

    /** Un lock per cella meteo: due spot della stessa cella non scaricano lo stesso forecast. */
    private val cellLocks = ConcurrentHashMap<String, Mutex>()
    private val meteoLocks = ConcurrentHashMap<String, Mutex>()

    private val nominatimLock = Mutex()
    private var lastNominatimCall = 0L

    // ---------------------------------------------------------------------------------- osservazione

    /**
     * Spot candidati ([ids]) + preferiti, con meteo e score già calcolati.
     *
     * Pipeline reattiva:
     * `spots(ids)` → (per le celle di quegli spot) `forecasts(cells)` → combine con `weights` → map.
     * `flatMapLatest` cancella la sottoscrizione ai forecast precedente quando cambia l'insieme di
     * celle, così non si accumulano query Room attive.
     */
    fun observeSpots(ids: Set<String>): Flow<List<Spot>> {
        val joined = spotDao.observe(ids.toList()).flatMapLatest { entities ->
            if (entities.isEmpty()) return@flatMapLatest flowOf(Joined(entities, emptyMap(), emptyMap(), emptyMap()))
            val cells = entities.map { Geo.forecastCellId(GeoPoint(it.lat, it.lon)) }.distinct()
            combine(
                forecastDao.observe(cells),
                meteoDao.observe(cells),
                elevationDao.observe(entities.map { it.id }),
            ) { fc, mt, el ->
                Joined(entities, fc.associateBy { it.cellId }, mt.associateBy { it.cellId }, el.associate { it.pointId to it.meters })
            }
        }
        val weights = settings.settings.map { it.weights }.distinctUntilChanged()

        return combine(joined, weights) { j, w ->
            val now = ZonedDateTime.now(clock)
            j.spots.map { e ->
                val cell = Geo.forecastCellId(GeoPoint(e.lat, e.lon))
                toDomain(e, j.forecasts[cell], j.meteo[cell], j.elevations[e.id], w, now)
            }
        }.flowOn(io)
    }

    private data class Joined(
        val spots: List<SpotEntity>,
        val forecasts: Map<String, ForecastEntity>,
        val meteo: Map<String, MeteoEntity>,
        val elevations: Map<String, Double>,
    )

    /** Notte rilevante per il punto (in corso o prossima), con cache per cella e data. */
    fun astroNight(point: GeoPoint, now: ZonedDateTime = ZonedDateTime.now(clock)): AstroNight {
        val lat = Math.round(point.lat * 20) / 20.0
        val lon = Math.round(point.lon * 20) / 20.0
        fun night(date: LocalDate): AstroNight {
            if (nightCache.size > 4_000) nightCache.clear()
            return nightCache.getOrPut("$lat|$lon|$date") { astroCalc.nightOf(lat, lon, date) }
        }
        val local = now.withZoneSameInstant(ZoneId.systemDefault())
        val first = if (local.hour >= 12) local.toLocalDate() else local.toLocalDate().minusDays(1)
        val n = night(first)
        return if (n.dark.end.isBefore(now.toInstant())) night(first.plusDays(1)) else n
    }

    private fun toDomain(
        e: SpotEntity,
        fc: ForecastEntity?,
        mt: MeteoEntity?,
        elevation: Double?,
        weights: ScoreWeights,
        now: ZonedDateTime,
    ): Spot {
        val point = GeoPoint(e.lat, e.lon)
        val sky = if (e.bortle != null && e.skySource != null) SkyQuality(e.sqm, e.bortle, e.skySource) else null
        val astro = astroNight(point, now)
        // Meteo valutato sulla finestra BUIA reale (crepuscolo astronomico), non su 20–04 fisse.
        val night: NightForecast? = fc?.let { decodeForecast(it.json) }
            ?.let { interpreter.summarize(it, astro.dark.start, astro.dark.end) }
        val weather: WeatherDetail? = mt?.let { decodeMeteo(it.json) }
            ?.let { OpenMeteoInterpreter.summarize(it, astro.dark.start, astro.dark.end) }
        val fcAt = listOfNotNull(fc?.fetchedAt, mt?.fetchedAt).maxOrNull()?.let(Instant::ofEpochMilli)
        val nowInstant = now.toInstant()
        return Spot(
            id = e.id,
            point = point,
            name = e.name,
            origin = e.origin,
            isFavorite = e.isFavorite,
            sky = sky,
            forecast = night,
            score = scorer.compute(sky, night, weights, weather, astro, elevation),
            astro = astro,
            weather = weather,
            elevationM = elevation,
            skyUpdatedAt = e.skyUpdatedAt?.let(Instant::ofEpochMilli),
            forecastUpdatedAt = fcAt,
            isStale = fcAt != null && Duration.between(fcAt, nowInstant) > FORECAST_STALE,
        )
    }

    private fun decodeMeteo(raw: String): OpenMeteoResponse? =
        runCatching { json.decodeFromString(OpenMeteoResponse.serializer(), raw) }.getOrNull()

    /** Preferiti con tutti i dati calcolati (per il Worker delle notifiche). */
    suspend fun favoriteSpots(): List<Spot> = observeSpots(emptySet()).first().filter { it.isFavorite }

    private fun decodeForecast(raw: String): AstroForecastResponse? =
        runCatching { json.decodeFromString(AstroForecastResponse.serializer(), raw) }.getOrNull()

    // ------------------------------------------------------------------------------- candidati

    /** Crea (se assenti) le righe dei candidati: la mappa li mostra subito come "in caricamento". */
    suspend fun registerCandidates(points: List<GeoPoint>, origin: SpotOrigin, name: String? = null): List<String> =
        withContext(io) {
            val now = clock.millis()
            val entities = points.map {
                SpotEntity(id = it.id, lat = it.lat, lon = it.lon, name = name, origin = origin, createdAt = now, lastViewedAt = now)
            }
            spotDao.insertIfAbsent(entities)
            val ids = entities.map { it.id }
            spotDao.touch(ids, now)
            if (name != null) ids.forEach { spotDao.setNameIfMissing(it, name) }
            ids
        }

    suspend fun toggleFavorite(id: String) = withContext(io) { spotDao.toggleFavorite(id) }

    // --------------------------------------------------------------------------------- refresh

    suspend fun refresh(ids: Collection<String>, force: Boolean = false): RefreshReport = coroutineScope {
        // Quote di tutti i punti in UNA chiamata batch (fino a 100 coordinate): non bloccante.
        val points = withContext(io) { ids.mapNotNull { spotDao.get(it) }.map { GeoPoint(it.lat, it.lon) } }
        launch { ensureElevations(points) }
        val results = ids.distinct().map { id ->
            async { networkPermits.withPermit { refreshSpot(id, force) } }
        }.awaitAll().filterNotNull()

        RefreshReport(
            total = results.size,
            fresh = results.count { it.anyFresh && it.errors.isEmpty() },
            fromCache = results.count { it.errors.isNotEmpty() && it.anyData },
            failed = results.count { !it.anyData },
            errors = results.flatMap { it.errors }.distinct(),
        )
    }

    suspend fun favoritePoints(): List<GeoPoint> =
        withContext(io) { spotDao.favorites().map { GeoPoint(it.lat, it.lon) } }

    suspend fun refreshFavorites(): RefreshReport =
        refresh(withContext(io) { spotDao.favorites().map { it.id } })

    /** Scarica (in parallelo) qualità del cielo e forecast di uno spot, rispettando le TTL. */
    suspend fun refreshSpot(id: String, force: Boolean = false): SpotRefresh? = withContext(io) {
        val spot = spotDao.get(id) ?: return@withContext null
        val point = GeoPoint(spot.lat, spot.lon)
        coroutineScope {
            val sky = async { refreshSky(spot, point, force) }
            val fc = async { refreshForecast(point, force) }
            val mt = async { refreshMeteo(point, force) }
            SpotRefresh(sky.await(), fc.await(), mt.await())
        }
    }

    private suspend fun refreshSky(spot: SpotEntity, point: GeoPoint, force: Boolean): Fetched<SkyQuality> {
        val cached = if (spot.bortle != null && spot.skySource != null) SkyQuality(spot.sqm, spot.bortle, spot.skySource) else null
        // Un valore prodotto da un'ALTRA sorgente (es. simulata prima di configurare l'atlante) non è
        // mai considerato fresco: va sostituito appena possibile, ma resta come fallback offline.
        val sameSource = cached?.source == skySource.type
        val fresh = sameSource && spot.skyUpdatedAt?.let { clock.millis() - it < SKY_TTL.toMillis() } == true
        if (cached != null && fresh && !force) return Fetched.Fresh(cached)

        val result = catching(Service.SKY_QUALITY) { skySource.fetch(point) }
        result.getOrNull()?.let { s ->
            spotDao.updateSky(spot.id, s.sqm, s.bortle, s.source, clock.millis())
            return Fetched.Fresh(s)
        }
        val error = result.dataErrorOrNull(Service.SKY_QUALITY)!!
        return if (cached != null) Fetched.Cached(cached, error) else Fetched.Failed(error)
    }

    private suspend fun refreshForecast(point: GeoPoint, force: Boolean): Fetched<AstroForecastResponse> {
        val cellId = Geo.forecastCellId(point)
        val lock = cellLocks.getOrPut(cellId) { Mutex() }
        return lock.withLock {
            // Ricontrollo DENTRO il lock: se un'altra coroutine ha appena scaricato questa cella, la riuso.
            val cachedEntity = forecastDao.get(cellId)
            val cached = cachedEntity?.let { decodeForecast(it.json) }
            val fresh = cachedEntity != null && clock.millis() - cachedEntity.fetchedAt < FORECAST_TTL.toMillis()
            if (cached != null && fresh && !force) return@withLock Fetched.Fresh(cached)

            // Interroghiamo il centro cella: tutti gli spot della cella condividono la stessa risposta.
            val center = Geo.cellCenter(cellId)
            val result = catching(Service.FORECAST) {
                sevenTimer.astro(lon = center.lon, lat = center.lat).also {
                    require(it.dataseries.isNotEmpty()) { "7Timer!: dataseries vuota" }
                }
            }
            result.getOrNull()?.let { r ->
                forecastDao.upsert(
                    ForecastEntity(cellId, json.encodeToString(AstroForecastResponse.serializer(), r), clock.millis())
                )
                return@withLock Fetched.Fresh(r)
            }
            val error = result.dataErrorOrNull(Service.FORECAST)!!
            if (cached != null) Fetched.Cached(cached, error) else Fetched.Failed(error)
        }
    }

    /** Open-Meteo per cella (stessa logica di lock/TTL di 7Timer!). */
    private suspend fun refreshMeteo(point: GeoPoint, force: Boolean): Fetched<OpenMeteoResponse> {
        val cellId = Geo.forecastCellId(point)
        return meteoLocks.getOrPut(cellId) { Mutex() }.withLock {
            val cachedEntity = meteoDao.get(cellId)
            val cached = cachedEntity?.let { decodeMeteo(it.json) }
            val fresh = cachedEntity != null && clock.millis() - cachedEntity.fetchedAt < METEO_TTL.toMillis()
            if (cached != null && fresh && !force) return@withLock Fetched.Fresh(cached)

            val center = Geo.cellCenter(cellId)
            val result = catching(Service.WEATHER) {
                openMeteo.forecast(center.lat, center.lon).also {
                    require(it.hourly?.time?.isNotEmpty() == true) { "Open-Meteo: serie oraria vuota" }
                }
            }
            result.getOrNull()?.let { r ->
                meteoDao.upsert(MeteoEntity(cellId, json.encodeToString(OpenMeteoResponse.serializer(), r), clock.millis()))
                return@withLock Fetched.Fresh(r)
            }
            val error = result.dataErrorOrNull(Service.WEATHER)!!
            if (cached != null) Fetched.Cached(cached, error) else Fetched.Failed(error)
        }
    }

    /**
     * Quote mancanti, scaricate a blocchi di 100 punti. Errori ignorati: la quota è un "di più"
     * (lo score la esclude e rinormalizza), non deve mai far fallire un refresh.
     */
    suspend fun ensureElevations(points: List<GeoPoint>): Map<String, Double> = withContext(io) {
        val unique = points.distinctBy { it.id }
        if (unique.isEmpty()) return@withContext emptyMap()
        val known = elevationDao.get(unique.map { it.id }).associate { it.pointId to it.meters }.toMutableMap()
        val missing = unique.filter { it.id !in known }
        for (chunk in missing.chunked(OpenMeteoApi.MAX_ELEVATION_BATCH)) {
            val r = catching(Service.WEATHER) {
                openMeteo.elevation(
                    chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lat) },
                    chunk.joinToString(",") { String.format(Locale.ROOT, "%.5f", it.lon) },
                )
            }.getOrNull() ?: break
            if (r.elevation.size != chunk.size) break
            val rows = chunk.zip(r.elevation).map { (p, m) -> ElevationEntity(p.id, m) }
            elevationDao.upsertAll(rows)
            rows.forEach { known[it.pointId] = it.meters }
        }
        known
    }

    // -------------------------------------------------------------------------------- parcheggi

    /**
     * Parcheggi entro [radiusMeters] dallo spot, ordinati per distanza. Prova i mirror Overpass in
     * ordine finché uno risponde; per 429/504/timeout passa al successivo.
     */
    suspend fun nearbyParkings(spotId: String, point: GeoPoint, radiusMeters: Int, force: Boolean = false): Fetched<List<Parking>> =
        withContext(io) {
            val key = "${spotId}_$radiusMeters"
            val cachedEntity = parkingDao.get(key)
            val cached = cachedEntity?.let { runCatching { json.decodeFromString(OverpassResponse.serializer(), it.json) }.getOrNull() }
                ?.let { ParkingMapper.toParkings(it, point) }
            val fresh = cachedEntity != null && clock.millis() - cachedEntity.fetchedAt < PARKING_TTL.toMillis()
            if (cached != null && fresh && !force) return@withContext Fetched.Fresh(withElevations(cached))

            val query = ParkingMapper.buildQuery(point, radiusMeters)
            var lastError: DataError = DataError.Unknown(Service.PARKING, null)
            for (endpoint in NetworkModule.OVERPASS_ENDPOINTS) {
                val r = catching(Service.PARKING) {
                    overpass.interpreter(endpoint, query).also { resp ->
                        // HTTP 200 con "remark: runtime error ..." = la query è fallita lato server.
                        if (resp.elements.isEmpty() && resp.remark?.contains("error", ignoreCase = true) == true) {
                            throw OverpassRemarkException(resp.remark)
                        }
                    }
                }
                val resp = r.getOrNull()
                if (resp != null) {
                    parkingDao.upsert(ParkingCacheEntity(key, json.encodeToString(OverpassResponse.serializer(), resp), clock.millis()))
                    return@withContext Fetched.Fresh(withElevations(ParkingMapper.toParkings(resp, point)))
                }
                lastError = r.dataErrorOrNull(Service.PARKING)!!
                val retryable = lastError is DataError.RateLimited || lastError is DataError.Timeout ||
                    (lastError as? DataError.Http)?.code?.let { it >= 500 } == true ||
                    lastError is DataError.InvalidResponse
                if (!retryable) break
            }
            if (cached != null) Fetched.Cached(withElevations(cached), lastError) else Fetched.Failed(lastError)
        }

    private suspend fun withElevations(list: List<Parking>): List<Parking> {
        val el = ensureElevations(list.map { it.point })
        return list.map { it.copy(elevationM = el[it.point.id]) }
    }

    private class OverpassRemarkException(remark: String) : java.io.InterruptedIOException("Overpass: $remark")

    // --------------------------------------------------------------------------------- ricerca

    /** Geocoding Nominatim con throttling a 1 richiesta/s come da policy d'uso. */
    suspend fun search(query: String, language: String?): Fetched<List<PlaceResult>> = withContext(io) {
        val q = query.trim()
        if (q.length < 2) return@withContext Fetched.Fresh(emptyList())
        nominatimLock.withLock {
            val wait = NOMINATIM_MIN_INTERVAL_MS - (clock.millis() - lastNominatimCall)
            if (wait > 0) delay(wait)
            lastNominatimCall = clock.millis()
            val r = catching(Service.GEOCODING) { nominatim.search(q, language = language) }
            r.getOrNull()?.let { places ->
                Fetched.Fresh(places.mapNotNull { p ->
                    val lat = p.lat.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = p.lon.toDoubleOrNull() ?: return@mapNotNull null
                    val parts = p.displayName.split(", ")
                    PlaceResult(
                        id = p.placeId,
                        title = p.name?.takeIf { it.isNotBlank() } ?: parts.first(),
                        subtitle = parts.drop(1).joinToString(", "),
                        point = GeoPoint(lat, lon),
                    )
                })
            } ?: Fetched.Failed(r.dataErrorOrNull(Service.GEOCODING)!!)
        }
    }

    // ----------------------------------------------------------------------------- manutenzione

    /** Pota candidati non preferiti non visti da 14 giorni e forecast più vecchi di 3 giorni. */
    suspend fun prune() = withContext(io) {
        val now = clock.millis()
        spotDao.pruneOlderThan(now - Duration.ofDays(14).toMillis())
        forecastDao.pruneOlderThan(now - Duration.ofDays(3).toMillis())
        meteoDao.pruneOlderThan(now - Duration.ofDays(3).toMillis())
    }

    companion object {
        const val MAX_PARALLEL = 4
        val SKY_TTL: Duration = Duration.ofDays(30)
        val FORECAST_TTL: Duration = Duration.ofHours(3)
        val FORECAST_STALE: Duration = Duration.ofHours(6)
        /** I modelli ICON/ECMWF di Open-Meteo si aggiornano ogni 1–3 h. */
        val METEO_TTL: Duration = Duration.ofHours(2)
        val PARKING_TTL: Duration = Duration.ofDays(7)
        private const val NOMINATIM_MIN_INTERVAL_MS = 1_100L
    }
}
