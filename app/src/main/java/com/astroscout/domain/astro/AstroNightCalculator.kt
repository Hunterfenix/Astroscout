package com.astroscout.domain.astro

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Quanto diventa buio il cielo stanotte in quel luogo. */
enum class DarknessLevel {
    /** Sole sotto −18°: nessun contributo del Sole al fondo cielo. */
    ASTRONOMICAL,
    /** Il Sole non scende sotto −18° (es. estate oltre ~48.5° N), ma sotto −12°. */
    NAUTICAL,
    /** Nemmeno crepuscolo nautico (notti bianche): cielo mai veramente buio. */
    NONE,
}

data class TimeSpan(val start: Instant, val end: Instant) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
}

/** Visibilità del nucleo galattico (Sagittario) durante la finestra buia. */
data class MilkyWayCore(
    /** Altitudine massima raggiunta durante il buio (gradi; negativa = mai sopra l'orizzonte). */
    val maxAltitudeDeg: Double,
    val timeOfMax: Instant,
    /** Azimut al momento della massima altezza (0 = N, 90 = E, 180 = S). */
    val azimuthAtMaxDeg: Double,
    /** Minuti con nucleo ≥ 10°, cielo buio e Luna sotto l'orizzonte. */
    val goodMinutes: Int,
    val goodWindow: TimeSpan?,
)

data class AstroNight(
    /** Data della sera in cui inizia la notte. */
    val nightOf: LocalDate,
    val sunset: Instant?,
    val sunrise: Instant?,
    val darkness: DarknessLevel,
    /** Finestra "buia" usata per meteo e score (astronomica, o la migliore disponibile). */
    val dark: TimeSpan,
    /** Frazione illuminata della Luna a metà finestra (0 = nuova, 1 = piena). */
    val moonIllumination: Double,
    val moonWaxing: Boolean,
    val moonrise: Instant?,
    val moonset: Instant?,
    /** Minuti della finestra buia con la Luna sopra l'orizzonte. */
    val moonUpMinutes: Int,
    /** Intervallo più lungo, dentro la finestra buia, con la Luna sotto l'orizzonte. */
    val bestMoonFree: TimeSpan?,
    val milkyWay: MilkyWayCore,
) {
    val darkMinutes: Long get() = dark.minutes
    val moonFreeFraction: Double
        get() = if (darkMinutes <= 0) 0.0 else (1.0 - moonUpMinutes.toDouble() / darkMinutes).coerceIn(0.0, 1.0)
}

/**
 * Calcola la prossima notte per un luogo campionando Sole, Luna e centro galattico ogni
 * [stepMinutes] minuti da mezzogiorno a mezzogiorno (ora locale): 289 istanti con passo 5′.
 * Gli istanti di attraversamento (tramonto, inizio buio, sorgere della Luna…) sono poi
 * raffinati per interpolazione lineare tra i due campioni: errore < 1 minuto.
 *
 * Perché campionare invece di formule chiuse per sorgere/tramonto: con un solo passaggio
 * otteniamo anche "quanti minuti di Luna alta durante il buio" e la finestra della Via Lattea,
 * che non hanno formule chiuse. Costo: ~300 × (Sole + Luna + stella) ≈ < 1 ms.
 */
class AstroNightCalculator(
    private val stepMinutes: Long = 5,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        /** Bordo superiore del disco a −50′ (rifrazione 34′ + semidiametro 16′). */
        const val RISE_SET_ALT = -0.833
        const val ASTRONOMICAL_ALT = -18.0
        const val NAUTICAL_ALT = -12.0
        const val MILKY_WAY_MIN_ALT = 10.0
    }

    /**
     * Notte "rilevante" adesso: quella in corso, oppure la prossima se quella precedente è finita.
     * Prima delle 12:00 si considera la notte iniziata ieri sera.
     */
    fun nextNight(lat: Double, lon: Double, now: ZonedDateTime = ZonedDateTime.now(zone)): AstroNight {
        val local = now.withZoneSameInstant(zone)
        val first = if (local.hour >= 12) local.toLocalDate() else local.toLocalDate().minusDays(1)
        val n = nightOf(lat, lon, first)
        return if (n.dark.end.isBefore(now.toInstant())) nightOf(lat, lon, first.plusDays(1)) else n
    }

    fun nightOf(lat: Double, lon: Double, evening: LocalDate): AstroNight {
        val start = ZonedDateTime.of(evening, LocalTime.NOON, zone).toInstant()
        val n = (24 * 60 / stepMinutes).toInt() + 1
        val times = Array(n) { start.plusSeconds(it * stepMinutes * 60) }
        val sunAlt = DoubleArray(n) { Ephemeris.sunAltitude(times[it], lat, lon) }
        val moonAlt = DoubleArray(n) { Ephemeris.moonAltitude(times[it], lat, lon) }
        val gc = Array(n) { Ephemeris.horizontal(Ephemeris.GALACTIC_CENTER, times[it], lat, lon) }

        val sunset = crossing(times, sunAlt, RISE_SET_ALT, falling = true)
        val sunrise = sunset?.let { s -> crossing(times, sunAlt, RISE_SET_ALT, falling = false, after = s) }
            ?: crossing(times, sunAlt, RISE_SET_ALT, falling = false)

        // Finestra buia: la run più lunga sotto la soglia più severa disponibile.
        val (darkness, darkIdx) = listOf(
            DarknessLevel.ASTRONOMICAL to ASTRONOMICAL_ALT,
            DarknessLevel.NAUTICAL to NAUTICAL_ALT,
        ).firstNotNullOfOrNull { (level, thr) ->
            longestRun(n) { sunAlt[it] < thr }?.let { level to it }
        } ?: run {
            // Notte bianca: ±1 h attorno al minimo del Sole
            val iMin = sunAlt.indices.minBy { sunAlt[it] }
            val half = (60 / stepMinutes).toInt()
            DarknessLevel.NONE to ((iMin - half).coerceAtLeast(0)..(iMin + half).coerceAtMost(n - 1))
        }
        val dark = TimeSpan(
            refine(times, sunAlt, darkIdx.first, darkIdx.first - 1, darkness),
            refine(times, sunAlt, darkIdx.last, darkIdx.last + 1, darkness),
        )

        val moonUp = BooleanArray(n) { moonAlt[it] > RISE_SET_ALT }
        val moonUpSteps = darkIdx.count { moonUp[it] }
        val moonUpMinutes = ((moonUpSteps * stepMinutes).toInt()).coerceAtMost(dark.minutes.toInt())
        val bestFree = longestRun(darkIdx) { !moonUp[it] }?.let { r ->
            TimeSpan(maxOf(times[r.first], dark.start), minOf(times[r.last], dark.end))
        }

        val mid = Instant.ofEpochMilli((dark.start.toEpochMilli() + dark.end.toEpochMilli()) / 2)

        // Via Lattea
        val iMax = darkIdx.maxBy { gc[it].altDeg }
        val good: (Int) -> Boolean = { gc[it].altDeg >= MILKY_WAY_MIN_ALT && !moonUp[it] }
        val goodRun = longestRun(darkIdx, good)
        val milky = MilkyWayCore(
            maxAltitudeDeg = gc[iMax].altDeg,
            timeOfMax = times[iMax],
            azimuthAtMaxDeg = gc[iMax].azDeg,
            goodMinutes = (darkIdx.count(good) * stepMinutes).toInt(),
            goodWindow = goodRun?.let { TimeSpan(times[it.first], times[it.last]) },
        )

        return AstroNight(
            nightOf = evening,
            sunset = sunset,
            sunrise = sunrise,
            darkness = darkness,
            dark = dark,
            moonIllumination = Ephemeris.moonIllumination(mid),
            moonWaxing = Ephemeris.isMoonWaxing(mid),
            moonrise = crossing(times, moonAlt, RISE_SET_ALT, falling = false),
            moonset = crossing(times, moonAlt, RISE_SET_ALT, falling = true),
            moonUpMinutes = moonUpMinutes,
            bestMoonFree = bestFree,
            milkyWay = milky,
        )
    }

    // ------------------------------------------------------------------------------- helpers

    /** Primo attraversamento della soglia (in discesa o in salita), interpolato linearmente. */
    private fun crossing(t: Array<Instant>, v: DoubleArray, thr: Double, falling: Boolean, after: Instant? = null): Instant? {
        for (i in 0 until v.size - 1) {
            if (after != null && !t[i + 1].isAfter(after)) continue
            val hit = if (falling) v[i] >= thr && v[i + 1] < thr else v[i] < thr && v[i + 1] >= thr
            if (hit) return interpolate(t[i], t[i + 1], v[i], v[i + 1], thr)
        }
        return null
    }

    /** Estremo della run buia raffinato verso il campione adiacente fuori run (se esiste). */
    private fun refine(t: Array<Instant>, v: DoubleArray, inside: Int, outside: Int, level: DarknessLevel): Instant {
        if (level == DarknessLevel.NONE || outside !in v.indices) return t[inside]
        val thr = if (level == DarknessLevel.ASTRONOMICAL) ASTRONOMICAL_ALT else NAUTICAL_ALT
        return interpolate(t[inside], t[outside], v[inside], v[outside], thr)
    }

    private fun interpolate(t0: Instant, t1: Instant, v0: Double, v1: Double, thr: Double): Instant {
        val f = if (v1 == v0) 0.0 else ((thr - v0) / (v1 - v0)).coerceIn(0.0, 1.0)
        return Instant.ofEpochMilli(t0.toEpochMilli() + ((t1.toEpochMilli() - t0.toEpochMilli()) * f).toLong())
    }

    private fun longestRun(n: Int, pred: (Int) -> Boolean): IntRange? = longestRun(0 until n, pred)

    private fun longestRun(range: IntRange, pred: (Int) -> Boolean): IntRange? {
        var best: IntRange? = null
        var start = -1
        for (i in range.first..range.last + 1) {
            val ok = i <= range.last && pred(i)
            if (ok && start < 0) start = i
            if (!ok && start >= 0) {
                val r = start until i
                if (best == null || r.count() > best.count()) best = r
                start = -1
            }
        }
        return best
    }
}
