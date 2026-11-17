package com.astroscout.domain.model

import com.astroscout.domain.astro.AstroNight
import com.astroscout.domain.forecast.NightForecastInterpreter
import com.astroscout.domain.forecast.WeatherDetail
import java.time.Instant
import java.util.Locale

data class GeoPoint(val lat: Double, val lon: Double) {
    /**
     * ID stabile del punto: coordinate arrotondate a 4 decimali (≈ 11 m), così lo stesso luogo
     * toccato due volte o generato due volte dalla griglia finisce sulla stessa riga di cache.
     */
    val id: String get() = String.format(Locale.ROOT, "%.4f_%.4f", lat, lon)
}

/** Da dove nasce un candidato: serve per il ciclo di vita in cache e per la UI. */
enum class SpotOrigin { GRID, MANUAL, SEARCH }

enum class SkyQualitySourceType { WORLD_ATLAS_2015, DARKSKYSITES, SIMULATED }

data class SkyQuality(
    val sqm: Double?,
    val bortle: Int,
    val source: SkyQualitySourceType,
)

/** Icona meteo sintetica per la finestra notturna. */
enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, PRECIPITATION }

data class NightForecast(
    val windowStart: Instant,
    val windowEnd: Instant,
    /** Numero di time-step 7Timer! (da 3 h) che cadono nella finestra. */
    val samples: Int,
    val avgCloudCoverPct: Double,
    val maxCloudCoverPct: Double,
    /** Indice medio 1..8 (1 = migliore). */
    val avgSeeingIndex: Double?,
    val avgSeeingArcsec: Double?,
    /** Indice medio 1..8 (1 = migliore). */
    val avgTransparencyIndex: Double?,
    val hasPrecipitation: Boolean,
    val condition: WeatherCondition,
    /** Run del modello (UTC): serve a mostrare "aggiornato alle …". */
    val modelInit: Instant,
)

data class Parking(
    val osmId: Long,
    val osmType: String,
    val name: String?,
    val point: GeoPoint,
    val distanceMeters: Double,
    val capacity: Int?,
    /** "yes" / "no" / testo libero OSM (es. "5 EUR/day"); null se non taggato. */
    val fee: String?,
    val access: String?,
    val surface: String?,
    /** Quota s.l.m. in metri (Open-Meteo elevation, DEM 90 m); null se non disponibile. */
    val elevationM: Double? = null,
)

/** Pesi relativi (non serve che sommino a 1: [AstroScoreCalculator] normalizza). */
data class ScoreWeights(
    val lightPollution: Double = 0.5,
    val cloudCover: Double = 0.3,
    val seeing: Double = 0.2,
    val transparency: Double = 0.0,
    /** Luna (fase × tempo sopra l'orizzonte) e qualità del crepuscolo. */
    val moon: Double = 0.25,
    /** Quota: sopra nebbie e foschie di pianura, aria più secca e trasparente. */
    val altitude: Double = 0.05,
) {
    companion object { val DEFAULT = ScoreWeights() }
}

data class AstroScore(
    /** 0..100 */
    val total: Int,
    /** Sotto-punteggi 0..1 (null = componente non disponibile). */
    val lightPollution: Double?,
    val cloudCover: Double?,
    val seeing: Double?,
    val transparency: Double?,
    val moon: Double? = null,
    val altitude: Double? = null,
    /** true se mancava almeno una componente con peso > 0 e i pesi sono stati rinormalizzati. */
    val isPartial: Boolean,
)

/** Risultato di geocoding (Nominatim). */
data class PlaceResult(
    val id: Long,
    val title: String,
    val subtitle: String,
    val point: GeoPoint,
)

data class Spot(
    val id: String,
    val point: GeoPoint,
    val name: String?,
    val origin: SpotOrigin,
    val isFavorite: Boolean,
    val sky: SkyQuality?,
    val forecast: NightForecast?,
    val score: AstroScore?,
    /** Effemeridi della notte (crepuscolo, Luna, Via Lattea): calcolate localmente, sempre disponibili. */
    val astro: AstroNight? = null,
    /** Meteo Open-Meteo sulla finestra buia (nubi per strato, rugiada, vento). */
    val weather: WeatherDetail? = null,
    val elevationM: Double? = null,
    val skyUpdatedAt: Instant?,
    val forecastUpdatedAt: Instant?,
    /** Dati presenti ma oltre la TTL (es. offline): la UI mostra un badge "cache". */
    val isStale: Boolean,
) {
    val isLoaded: Boolean get() = sky != null || forecast != null

    /** Nuvolosità migliore disponibile: Open-Meteo (modelli fini) se c'è, altrimenti 7Timer!. */
    val cloudPct: Double? get() = weather?.avgCloudPct ?: forecast?.avgCloudCoverPct

    val hasPrecipitation: Boolean
        get() = (weather?.precipitationMm ?: 0.0) >= 0.2 || forecast?.hasPrecipitation == true

    val condition: WeatherCondition?
        get() = cloudPct?.let { NightForecastInterpreter.classify(it, hasPrecipitation) }
}
