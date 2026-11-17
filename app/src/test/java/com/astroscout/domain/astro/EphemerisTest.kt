package com.astroscout.domain.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Verifica contro eventi astronomici noti (tolleranze adeguate alla bassa precisione). */
class EphemerisTest {

    private val rome = ZoneId.of("Europe/Rome")
    private val milano = 45.4642 to 9.19

    @Test
    fun `sun and moon coincide during the total solar eclipse of 2024-04-08`() {
        // Massimo dell'eclisse a Dallas ~18:42 UTC: Sole e Luna allineati entro pochi decimi di grado.
        val t = Instant.parse("2024-04-08T18:42:00Z")
        val sep = Ephemeris.separationDeg(Ephemeris.sun(t).equatorial, Ephemeris.moon(t).equatorial)
        // separazione geocentrica: include la parallasse lunare (~1°), quindi < 1.2°
        assertTrue("separazione $sep", sep < 1.2)
        assertTrue(Ephemeris.moonIllumination(t) < 0.01)
    }

    @Test
    fun `moon is full during the total lunar eclipse of 2025-03-14`() {
        val k = Ephemeris.moonIllumination(Instant.parse("2025-03-14T06:58:00Z"))
        assertTrue("illuminazione $k", k > 0.99)
    }

    @Test
    fun `first quarter is about half illuminated and waxing`() {
        // Primo quarto: 2024-04-15 19:13 UTC
        val t = Instant.parse("2024-04-15T19:13:00Z")
        assertEquals(0.5, Ephemeris.moonIllumination(t), 0.03)
        assertTrue(Ephemeris.isMoonWaxing(t))
    }

    @Test
    fun `summer solstice in Milano - sunrise sunset and short astronomical night`() {
        val n = AstroNightCalculator(zone = rome).nightOf(milano.first, milano.second, LocalDate.of(2026, 6, 21))
        val sunset = n.sunset!!.atZone(rome).toLocalTime()
        val sunrise = n.sunrise!!.atZone(rome).toLocalTime()
        println("MILANO 21/6 tramonto=$sunset alba=$sunrise buio=${n.dark.start.atZone(rome).toLocalTime()}-${n.dark.end.atZone(rome).toLocalTime()} ${n.darkness}")
        // Effemeridi pubblicate per Milano: tramonto ≈ 21:15, alba ≈ 05:35 (CEST)
        assertEquals(21 * 60 + 15.0, sunset.toSecondOfDay() / 60.0, 6.0)
        assertEquals(5 * 60 + 35.0, sunrise.toSecondOfDay() / 60.0, 6.0)
        assertEquals(DarknessLevel.ASTRONOMICAL, n.darkness)
        // Verifica a mano: mezzogiorno solare 13:24, cos H = (sin(−18°) − sinφ·sinδ)/(cosφ·cosδ) = −0.921
        // → H = 157.1° = 10 h 28′ → buio astronomico 23:52 → 02:56 (≈ 3 h).
        assertEquals(23 * 60 + 52.0, n.dark.start.atZone(rome).toLocalTime().toSecondOfDay() / 60.0, 6.0)
        assertTrue("buio ${n.darkMinutes} min", n.darkMinutes in 170..200)
    }

    @Test
    fun `winter solstice in Milano - long astronomical night`() {
        val n = AstroNightCalculator(zone = rome).nightOf(milano.first, milano.second, LocalDate.of(2026, 12, 21))
        val start = n.dark.start.atZone(rome).toLocalTime()
        println("MILANO 21/12 buio da $start, ${n.darkMinutes} min")
        assertEquals(18 * 60 + 40.0, start.toSecondOfDay() / 60.0, 15.0) // ≈ 18:40
        assertTrue(n.darkMinutes > 10 * 60)
    }

    @Test
    fun `no astronomical darkness in Oslo at midsummer`() {
        val n = AstroNightCalculator(zone = ZoneId.of("Europe/Oslo")).nightOf(59.91, 10.75, LocalDate.of(2026, 6, 21))
        assertTrue(n.darkness != DarknessLevel.ASTRONOMICAL)
    }

    @Test
    fun `galactic center culminates at about 90 - lat - 29 degrees`() {
        // A luglio il nucleo culmina nella notte: a 45.46° N l'altezza massima è ≈ 15.6°, a Sud.
        val n = AstroNightCalculator(zone = rome).nightOf(milano.first, milano.second, LocalDate.of(2026, 7, 15))
        val mw = n.milkyWay
        println("MILANO 15/7 Via Lattea max=${"%.1f".format(mw.maxAltitudeDeg)}° az=${"%.0f".format(mw.azimuthAtMaxDeg)} alle ${mw.timeOfMax.atZone(rome).toLocalTime()} good=${mw.goodMinutes}min")
        assertEquals(90 - 45.46 - 28.94, mw.maxAltitudeDeg, 1.0)
        assertEquals(180.0, mw.azimuthAtMaxDeg, 15.0)
    }

    @Test
    fun `moon rise and set times are consistent with full moon`() {
        // Luna piena 2025-03-14: sorge vicino al tramonto, tramonta vicino all'alba → quasi tutta la notte su.
        val n = AstroNightCalculator(zone = rome).nightOf(milano.first, milano.second, LocalDate.of(2025, 3, 13))
        println("MILANO 13/3/2025 luna ${"%.2f".format(n.moonIllumination)} sorge=${n.moonrise?.atZone(rome)?.toLocalTime()} tramonta=${n.moonset?.atZone(rome)?.toLocalTime()} su=${n.moonUpMinutes}/${n.darkMinutes}")
        assertTrue(n.moonIllumination > 0.95)
        assertTrue(n.moonFreeFraction < 0.15)
        val gap = Duration.between(n.sunset!!, n.moonrise!!).abs().toMinutes()
        assertTrue("sorgere luna lontano dal tramonto: $gap min", gap < 120)
    }
}
