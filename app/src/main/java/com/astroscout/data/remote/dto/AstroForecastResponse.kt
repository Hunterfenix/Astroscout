package com.astroscout.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 7Timer! prodotto ASTRO (`product=astro&output=json`).
 *
 * Esempio:
 * ```json
 * { "product":"astro", "init":"2026111612",
 *   "dataseries":[ { "timepoint":3, "cloudcover":2, "seeing":6, "transparency":3,
 *                    "lifted_index":10, "rh2m":8, "wind10m":{"direction":"N","speed":2},
 *                    "temp2m":5, "prec_type":"none" }, ... ] }
 * ```
 *
 * - `init`: run del modello (GFS) in UTC, formato `yyyyMMddHH`.
 * - `timepoint`: ore dall'`init` (3, 6, … 72) → l'istante reale è `init + timepoint h`.
 * - Tutti i parametri sono **indici**, non grandezze fisiche: la conversione è in
 *   [com.astroscout.domain.forecast.SevenTimerScales].
 * - Valore `-9999` = dato mancante (convenzione 7Timer!).
 */
@Serializable
data class AstroForecastResponse(
    val product: String = "",
    val init: String,
    val dataseries: List<AstroDataPoint> = emptyList(),
)

@Serializable
data class AstroDataPoint(
    val timepoint: Int,
    /** 1..9: 1 = 0–6 %, 9 = 94–100 %. */
    val cloudcover: Int = MISSING,
    /** 1..8: 1 = < 0.5″, 8 = > 2.5″ (FWHM). Più basso = atmosfera più stabile. */
    val seeing: Int = MISSING,
    /** 1..8: estinzione 1 = < 0.3 mag, 8 = > 1 mag. Più basso = cielo più trasparente. */
    val transparency: Int = MISSING,
    /** Indice di instabilità (-10..15): negativo = convettivo/temporalesco. */
    @SerialName("lifted_index") val liftedIndex: Int = MISSING,
    /** Umidità relativa a 2 m come indice -4..16 (-4 = 0–5 %, 16 = 100 %). */
    val rh2m: Int = MISSING,
    val wind10m: Wind10m? = null,
    /** Temperatura a 2 m in °C. */
    val temp2m: Int = MISSING,
    /** "none", "rain", "snow", "frzr" (pioggia gelata), "icep" (granuli di ghiaccio). */
    @SerialName("prec_type") val precType: String = "none",
) {
    companion object {
        const val MISSING = -9999
    }
}

@Serializable
data class Wind10m(
    val direction: String = "",
    /** Indice 1..8 (1 = calma < 0.3 m/s, 8 = > 32.6 m/s). */
    val speed: Int = AstroDataPoint.MISSING,
)
