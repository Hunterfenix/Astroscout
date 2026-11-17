package com.astroscout.domain.forecast

import com.astroscout.data.remote.dto.OpenMeteoResponse
import java.time.Instant

/** Riassunto Open-Meteo sulla finestra buia della notte. Percentuali 0..100, °C, m/s, mm. */
data class WeatherDetail(
    val samples: Int,
    val avgCloudPct: Double,
    val avgLowPct: Double?,
    val avgMidPct: Double?,
    val avgHighPct: Double?,
    val minTempC: Double?,
    /** Minimo di (temperatura − punto di rugiada): sotto ~2 °C l'ottica si appanna. */
    val minDewSpreadC: Double?,
    val maxHumidityPct: Double?,
    val avgWindMs: Double?,
    val maxGustMs: Double?,
    val precipitationMm: Double,
    /** Frazione delle ore buie con precipitazione ≥ 0.1 mm/h (0..1). */
    val wetFraction: Double = 0.0,
) {
    /** "Notte piovosa" solo se piove in almeno un terzo delle ore buie, non per un'ora isolata. */
    val isRainy: Boolean get() = wetFraction >= RAINY_FRACTION

    val dewRisk: Boolean
        get() = (minDewSpreadC != null && minDewSpreadC < DEW_SPREAD_RISK_C) ||
            (maxHumidityPct != null && maxHumidityPct >= HUMIDITY_RISK_PCT)

    /** Raffiche ≥ 10 m/s (36 km/h): vibrazioni del treppiede, inseguimento instabile. */
    val windRisk: Boolean get() = (maxGustMs ?: 0.0) >= GUST_RISK_MS

    /** Cirri: velo alto che 'spegne' le stelle deboli pur con copertura totale moderata. */
    val highCloudRisk: Boolean get() = (avgHighPct ?: 0.0) >= 40.0

    companion object {
        const val DEW_SPREAD_RISK_C = 2.0
        const val HUMIDITY_RISK_PCT = 92.0
        const val GUST_RISK_MS = 10.0
        const val WET_HOUR_MM = 0.1
        const val RAINY_FRACTION = 0.34
    }
}

object OpenMeteoInterpreter {

    /**
     * Media delle ore con `start ≤ t ≤ end`. Se la finestra è più corta di un'ora (notti estive
     * brevissime) usa le ore entro ±60 min, per avere almeno un campione.
     */
    fun summarize(resp: OpenMeteoResponse, start: Instant, end: Instant): WeatherDetail? {
        val h = resp.hourly ?: return null
        val s = start.epochSecond
        val e = end.epochSecond
        var idx = h.time.indices.filter { h.time[it] in s..e && h.cloudCover.getOrNull(it) != null }
        if (idx.isEmpty()) idx = h.time.indices.filter { h.time[it] in (s - 3600)..(e + 3600) && h.cloudCover.getOrNull(it) != null }
        if (idx.isEmpty()) return null

        fun List<Double?>.avg(): Double? = idx.mapNotNull { getOrNull(it) }.takeIf { it.isNotEmpty() }?.average()
        fun List<Double?>.max(): Double? = idx.mapNotNull { getOrNull(it) }.maxOrNull()
        fun List<Double?>.min(): Double? = idx.mapNotNull { getOrNull(it) }.minOrNull()

        val spreads = idx.mapNotNull { i ->
            val t = h.temperature.getOrNull(i)
            val d = h.dewPoint.getOrNull(i)
            if (t != null && d != null) t - d else null
        }
        return WeatherDetail(
            samples = idx.size,
            avgCloudPct = h.cloudCover.avg()!!,
            avgLowPct = h.cloudCoverLow.avg(),
            avgMidPct = h.cloudCoverMid.avg(),
            avgHighPct = h.cloudCoverHigh.avg(),
            minTempC = h.temperature.min(),
            minDewSpreadC = spreads.minOrNull(),
            maxHumidityPct = h.humidity.max(),
            avgWindMs = h.windSpeed.avg(),
            maxGustMs = h.windGusts.max(),
            precipitationMm = idx.sumOf { h.precipitation.getOrNull(it) ?: 0.0 },
            wetFraction = idx.count { (h.precipitation.getOrNull(it) ?: 0.0) >= WeatherDetail.WET_HOUR_MM }.toDouble() / idx.size,
        )
    }
}
