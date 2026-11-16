package com.astroscout.domain.model

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

enum class SkyQualitySourceType { DARKSKYSITES, SIMULATED }

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
)

/** Pesi relativi (non serve che sommino a 1: [AstroScoreCalculator] normalizza). */
data class ScoreWeights(
    val lightPollution: Double = 0.5,
    val cloudCover: Double = 0.3,
    val seeing: Double = 0.2,
    val transparency: Double = 0.0,
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
    val skyUpdatedAt: Instant?,
    val forecastUpdatedAt: Instant?,
    /** Dati presenti ma oltre la TTL (es. offline): la UI mostra un badge "cache". */
    val isStale: Boolean,
) {
    val isLoaded: Boolean get() = sky != null || forecast != null
}
