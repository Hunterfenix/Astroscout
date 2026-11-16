package com.astroscout.domain

import com.astroscout.data.remote.dto.AstroForecastResponse
import com.astroscout.domain.forecast.NightForecastInterpreter
import com.astroscout.domain.model.WeatherCondition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class NightForecastInterpreterTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val sample: AstroForecastResponse = json.decodeFromString(
        AstroForecastResponse.serializer(),
        javaClass.classLoader!!.getResource("seventimer_astro_sample.json")!!.readText(),
    )
    private val rome = ZoneId.of("Europe/Rome") // UTC+1 a novembre
    private val interpreter = NightForecastInterpreter()

    @Test
    fun `parses 7Timer sample including missing values`() {
        assertEquals("astro", sample.product)
        assertEquals(7, sample.dataseries.size)
        assertEquals(-9999, sample.dataseries.last().cloudcover)
        assertEquals("rain", sample.dataseries.first().precType)
    }

    @Test
    fun `init is parsed as UTC`() {
        assertEquals(Instant.parse("2026-11-16T12:00:00Z"), NightForecastInterpreter.parseInit("2026111612"))
        assertNull(NightForecastInterpreter.parseInit("garbage"))
    }

    @Test
    fun `window in the afternoon is tonight 20-04`() {
        val w = interpreter.nextNightWindow(ZonedDateTime.of(2026, 11, 16, 15, 0, 0, 0, rome))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 20, 0, 0, 0, rome).toInstant(), w.start)
        assertEquals(ZonedDateTime.of(2026, 11, 17, 4, 0, 0, 0, rome).toInstant(), w.end)
    }

    @Test
    fun `window after midnight is the night already in progress`() {
        val w = interpreter.nextNightWindow(ZonedDateTime.of(2026, 11, 17, 2, 30, 0, 0, rome))
        assertEquals(ZonedDateTime.of(2026, 11, 16, 20, 0, 0, 0, rome).toInstant(), w.start)
        assertEquals(ZonedDateTime.of(2026, 11, 17, 4, 0, 0, 0, rome).toInstant(), w.end)
    }

    @Test
    fun `summarizes only timepoints inside the night window`() {
        // init 12Z. Finestra 20:00–04:00 Roma = 19Z–03Z → timepoint 9 (21Z), 12 (00Z), 15 (03Z).
        // Il tp 3 (15Z, pioggia) e il tp 18 (06Z, coperto) sono fuori finestra.
        val night = interpreter.summarize(sample, ZonedDateTime.of(2026, 11, 16, 15, 0, 0, 0, rome))
        assertNotNull(night)
        night!!
        assertEquals(3, night.samples)
        assertEquals((12.5 + 25.0 + 3.0) / 3, night.avgCloudCoverPct, 1e-9)
        assertEquals((3 + 4 + 2) / 3.0, night.avgSeeingIndex!!, 1e-9)
        assertEquals((3 + 3 + 2) / 3.0, night.avgTransparencyIndex!!, 1e-9)
        assertFalse(night.hasPrecipitation)
        assertEquals(WeatherCondition.CLEAR, night.condition)
    }

    @Test
    fun `returns null when forecast does not cover the window`() {
        val old = sample.copy(init = "2026110100")
        assertNull(interpreter.summarize(old, ZonedDateTime.of(2026, 11, 16, 15, 0, 0, 0, rome)))
    }

    @Test
    fun `classification thresholds`() {
        assertEquals(WeatherCondition.PRECIPITATION, NightForecastInterpreter.classify(5.0, true))
        assertEquals(WeatherCondition.CLEAR, NightForecastInterpreter.classify(19.9, false))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, NightForecastInterpreter.classify(20.0, false))
        assertEquals(WeatherCondition.CLOUDY, NightForecastInterpreter.classify(60.0, false))
    }
}
