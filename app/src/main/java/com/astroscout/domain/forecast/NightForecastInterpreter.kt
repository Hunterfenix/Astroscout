package com.astroscout.domain.forecast

import com.astroscout.data.remote.dto.AstroForecastResponse
import com.astroscout.domain.model.NightForecast
import com.astroscout.domain.model.WeatherCondition
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Trasforma la serie 72 h di 7Timer! in un riassunto della **prossima finestra notturna**.
 *
 * Finestra di default 20:00 → 04:00 (ora locale del dispositivo). Regola:
 * - se adesso sono le 00:00–03:59 siamo *dentro* la notte iniziata ieri alle 20:00;
 * - altrimenti la finestra è stasera 20:00 → domani 04:00.
 *
 * Un time-step 7Timer! rappresenta lo stato a quell'istante (step 3 h): includiamo tutti gli
 * istanti `t` con `start ≤ t ≤ end`. Con step di 3 h ciò dà 2–3 campioni per notte.
 */
class NightForecastInterpreter(
    private val nightStart: LocalTime = LocalTime.of(20, 0),
    private val nightEnd: LocalTime = LocalTime.of(4, 0),
) {

    data class Window(val start: Instant, val end: Instant)

    fun nextNightWindow(now: ZonedDateTime): Window {
        val today = now.toLocalDate()
        val crossesMidnight = nightEnd < nightStart
        val startDate = if (crossesMidnight && now.toLocalTime() < nightEnd) today.minusDays(1) else today
        val start = ZonedDateTime.of(startDate, nightStart, now.zone)
        val endDate = if (crossesMidnight) startDate.plusDays(1) else startDate
        val end = ZonedDateTime.of(endDate, nightEnd, now.zone)
        return Window(start.toInstant(), end.toInstant())
    }

    /** Finestra fissa 20:00–04:00 (fallback quando non c'è la finestra buia astronomica). */
    fun summarize(
        response: AstroForecastResponse,
        now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()),
    ): NightForecast? {
        val window = nextNightWindow(now)
        return summarize(response, window.start, window.end)
    }

    /**
     * Riassunto sulla finestra [start, end] (tipicamente il buio astronomico calcolato).
     * 7Timer! ha passo 3 h: se nella finestra cadono meno di 2 campioni (notti estive brevi)
     * si includono anche quelli entro ±90 min, cioè i due step adiacenti.
     */
    fun summarize(response: AstroForecastResponse, start: Instant, end: Instant): NightForecast? {
        val init = parseInit(response.init) ?: return null
        val window = Window(start, end)
        fun timeOf(p: com.astroscout.data.remote.dto.AstroDataPoint) = init.plusSeconds(p.timepoint * 3600L)

        var inWindow = response.dataseries.filter { p ->
            val t = timeOf(p)
            !t.isBefore(window.start) && !t.isAfter(window.end)
        }
        if (inWindow.count { SevenTimerScales.cloudCoverPercent(it.cloudcover) != null } < 2) {
            val ps = window.start.minusSeconds(PAD_SECONDS)
            val pe = window.end.plusSeconds(PAD_SECONDS)
            inWindow = response.dataseries.filter { p -> val t = timeOf(p); !t.isBefore(ps) && !t.isAfter(pe) }
        }
        val clouds = inWindow.mapNotNull { SevenTimerScales.cloudCoverPercent(it.cloudcover) }
        if (clouds.isEmpty()) return null // forecast troppo vecchio o troppo corto per questa notte

        val seeingIdx = inWindow.map { it.seeing }.filter(SevenTimerScales::isValidSeeing)
        val transpIdx = inWindow.map { it.transparency }.filter(SevenTimerScales::isValidTransparency)
        val seeingArcsec = seeingIdx.mapNotNull(SevenTimerScales::seeingArcsec)
        val precip = inWindow.any { it.precType.isNotBlank() && it.precType != "none" }

        val avgCloud = clouds.average()
        return NightForecast(
            windowStart = window.start,
            windowEnd = window.end,
            samples = clouds.size,
            avgCloudCoverPct = avgCloud,
            maxCloudCoverPct = clouds.max(),
            avgSeeingIndex = seeingIdx.takeIf { it.isNotEmpty() }?.average(),
            avgSeeingArcsec = seeingArcsec.takeIf { it.isNotEmpty() }?.average(),
            avgTransparencyIndex = transpIdx.takeIf { it.isNotEmpty() }?.average(),
            hasPrecipitation = precip,
            condition = classify(avgCloud, precip),
            modelInit = init,
        )
    }

    companion object {
        private const val PAD_SECONDS = 90 * 60L
        private val INIT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH")

        fun parseInit(init: String): Instant? = runCatching {
            LocalDateTime.parse(init.trim(), INIT_FORMAT).toInstant(ZoneOffset.UTC)
        }.getOrNull()

        /** Soglie scelte per l'astrofotografia: già il 20 % di nubi disturba le pose lunghe. */
        fun classify(avgCloudPct: Double, precipitation: Boolean): WeatherCondition = when {
            precipitation -> WeatherCondition.PRECIPITATION
            avgCloudPct < 20.0 -> WeatherCondition.CLEAR
            avgCloudPct < 60.0 -> WeatherCondition.PARTLY_CLOUDY
            else -> WeatherCondition.CLOUDY
        }
    }
}
