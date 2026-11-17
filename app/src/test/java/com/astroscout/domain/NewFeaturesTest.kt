package com.astroscout.domain

import com.astroscout.data.remote.dto.OpenMeteoResponse
import com.astroscout.data.sky.atlas.SkyOverlayRenderer
import com.astroscout.data.sky.atlas.SkyTile
import com.astroscout.domain.astro.AstroNight
import com.astroscout.domain.astro.DarknessLevel
import com.astroscout.domain.astro.MilkyWayCore
import com.astroscout.domain.astro.TimeSpan
import com.astroscout.domain.forecast.OpenMeteoInterpreter
import com.astroscout.domain.model.ScoreWeights
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.scoring.AstroScoreCalculator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class NewFeaturesTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true; explicitNulls = false }
    private val meteo = json.decodeFromString(
        OpenMeteoResponse.serializer(),
        javaClass.classLoader!!.getResource("openmeteo_sample.json")!!.readText(),
    )

    @Test
    fun `open-meteo summary over the dark window skips nulls and flags risks`() {
        // Finestra: dalla 2ª alla 5ª ora (1792173600..1792184400) → cloud 10, 5, null, 20
        val w = OpenMeteoInterpreter.summarize(meteo, Instant.ofEpochSecond(1792173600), Instant.ofEpochSecond(1792184400))!!
        assertEquals(3, w.samples)
        assertEquals((10 + 5 + 20) / 3.0, w.avgCloudPct, 1e-9)
        assertEquals(1420.0, meteo.elevation!!, 0.0)
        // ore valide (nuvolosità non null): 4.5−1.5 = 3.0, 3.0−1.8 = 1.2, 2.0−0.5 = 1.5 → minimo 1.2 °C
        assertEquals(1.2, w.minDewSpreadC!!, 1e-9)
        assertEquals(90.0, w.maxHumidityPct!!, 0.0)
        assertTrue(w.dewRisk)
        assertTrue(w.windRisk)                           // raffica 11.5 m/s
        assertTrue(w.highCloudRisk)                      // nubi alte medie ≥ 40 %
        assertEquals(0.0, w.precipitationMm, 0.0)
    }

    @Test
    fun `a single wet hour does not make the whole night rainy`() {
        // Tutte le 6 ore: pioggia solo nell'ultima (1.2 mm) → 1 ora bagnata su 5 valide = 0.2
        val w = OpenMeteoInterpreter.summarize(meteo, Instant.ofEpochSecond(1792170000), Instant.ofEpochSecond(1792188000))!!
        assertEquals(0.2, w.wetFraction, 1e-9)
        assertFalse(w.isRainy)
        val sky = SkyQuality(22.0, 1, SkyQualitySourceType.WORLD_ATLAS_2015)
        val s = AstroScoreCalculator().compute(sky, null, ScoreWeights(0.5, 0.3, 0.0, 0.0, 0.0, 0.0), w)!!
        // nessun tetto a 20: cloud = (1 − 0.41)·(1 − 0.2) = 0.472 → (0.5 + 0.3·0.472)/0.8 = 0.802 → 80
        assertEquals(80, s.total)
    }

    @Test
    fun `rain in most of the night caps the score`() {
        val rainy = meteo.copy(hourly = meteo.hourly!!.copy(precipitation = listOf(0.5, 0.5, 0.5, 0.0, 0.5, 0.5)))
        val w = OpenMeteoInterpreter.summarize(rainy, Instant.ofEpochSecond(1792170000), Instant.ofEpochSecond(1792188000))!!
        assertTrue(w.isRainy)
        val sky = SkyQuality(22.0, 1, SkyQualitySourceType.WORLD_ATLAS_2015)
        assertEquals(20, AstroScoreCalculator().compute(sky, null, ScoreWeights.DEFAULT, w)!!.total)
    }

    @Test
    fun `open-meteo returns null when the window is outside the forecast`() {
        assertNull(OpenMeteoInterpreter.summarize(meteo, Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700003600)))
    }

    private fun night(illum: Double, moonUp: Int, dark: Int = 600, level: DarknessLevel = DarknessLevel.ASTRONOMICAL): AstroNight {
        val t0 = Instant.parse("2026-01-01T18:00:00Z")
        return AstroNight(
            nightOf = LocalDate.of(2026, 1, 1), sunset = null, sunrise = null, darkness = level,
            dark = TimeSpan(t0, t0.plusSeconds(dark * 60L)),
            moonIllumination = illum, moonWaxing = true, moonrise = null, moonset = null,
            moonUpMinutes = moonUp, bestMoonFree = null,
            milkyWay = MilkyWayCore(-10.0, t0, 0.0, 0, null),
        )
    }

    @Test
    fun `moon score - new moon 1, full moon all night 0, half night with full moon 0_5`() {
        assertEquals(1.0, AstroScoreCalculator.moonScore(night(0.0, 600)), 1e-9)
        assertEquals(0.0, AstroScoreCalculator.moonScore(night(1.0, 600)), 1e-9)
        assertEquals(0.5, AstroScoreCalculator.moonScore(night(1.0, 300)), 1e-9)
        assertEquals(0.6, AstroScoreCalculator.moonScore(night(0.0, 0, level = DarknessLevel.NAUTICAL)), 1e-9)
    }

    @Test
    fun `full score uses open-meteo clouds, moon and altitude`() {
        val sky = SkyQuality(22.0, 1, SkyQualitySourceType.WORLD_ATLAS_2015)
        val w = OpenMeteoInterpreter.summarize(meteo, Instant.ofEpochSecond(1792173600), Instant.ofEpochSecond(1792184400))!!
        val s = AstroScoreCalculator().compute(sky, null, ScoreWeights.DEFAULT, w, night(0.0, 0), 2000.0)!!
        // LP 1, cloud 1−0.1167=0.8833, moon 1, alt 1; seeing/transp assenti (pesi 0.2/0.0) → parziale
        // (0.5·1 + 0.3·0.8833 + 0.25·1 + 0.05·1) / (0.5 + 0.3 + 0.25 + 0.05) = 1.065 / 1.1 = 0.968 → 97
        assertEquals(97, s.total)
        assertTrue(s.isPartial)
        assertEquals(1.0, s.moon!!, 0.0)
    }

    @Test
    fun `overlay colors - natural sky transparent, city opaque, nodata transparent`() {
        assertEquals(0, SkyOverlayRenderer.colorFor(22.0) ushr 24)
        assertTrue((SkyOverlayRenderer.colorFor(18.0) ushr 24) > 200)
        assertTrue((SkyOverlayRenderer.colorFor(21.0) ushr 24) in 140..180)
    }

    @Test
    fun `overlay mercator reprojection maps rows to the right latitude`() {
        // tile 45–50 N, colonna unica: riga r ha SQM crescente verso Nord (valore = riga sorgente)
        val h = 600
        val data = ByteArray(h) { r -> (254 - r * 254 / (h - 1)).toByte() } // Nord 254 … Sud 0
        val t = SkyTile(45, 5, 5, 1, h, 16f, 0.01f, data)
        val px = SkyOverlayRenderer.render(t)
        assertEquals(h, px.size)
        // riga centrale dell'output = y Mercator a metà ⇒ latitudine ≈ 47.54°, non 47.5° (stiramento)
        val yMid = (SkyOverlayRenderer.mercatorY(50.0) + SkyOverlayRenderer.mercatorY(45.0)) / 2
        val latMid = SkyOverlayRenderer.latFromMercatorY(yMid)
        assertTrue("lat media Mercator $latMid", latMid > 47.5 && latMid < 47.6)
        assertFalse(px.all { it == px[0] })
    }
}
