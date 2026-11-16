package com.astroscout.domain

import com.astroscout.domain.model.NightForecast
import com.astroscout.domain.model.ScoreWeights
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.model.WeatherCondition
import com.astroscout.domain.scoring.AstroScoreCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AstroScoreCalculatorTest {

    private val calc = AstroScoreCalculator()

    private fun sky(sqm: Double?, bortle: Int) = SkyQuality(sqm, bortle, SkyQualitySourceType.SIMULATED)

    private fun night(cloud: Double, seeing: Double? = 3.0, transp: Double? = 3.0, rain: Boolean = false) = NightForecast(
        windowStart = Instant.EPOCH, windowEnd = Instant.EPOCH, samples = 3,
        avgCloudCoverPct = cloud, maxCloudCoverPct = cloud,
        avgSeeingIndex = seeing, avgSeeingArcsec = null, avgTransparencyIndex = transp,
        hasPrecipitation = rain, condition = WeatherCondition.CLEAR, modelInit = Instant.EPOCH,
    )

    @Test
    fun `perfect conditions score 100`() {
        val s = calc.compute(sky(22.0, 1), night(0.0, seeing = 1.0, transp = 1.0), ScoreWeights.DEFAULT)!!
        assertEquals(100, s.total)
        assertFalse(s.isPartial)
    }

    @Test
    fun `default weights 50-30-20 combine as documented`() {
        // LP: (21.0-17.5)/4.5 = 0.7778 ; cloud: 1-0.25 = 0.75 ; seeing: (8-3)/7 = 0.7143
        // 100 * (0.5*0.7778 + 0.3*0.75 + 0.2*0.7143) = 75.67 → 76
        val s = calc.compute(sky(21.0, 4), night(25.0, seeing = 3.0), ScoreWeights.DEFAULT)!!
        assertEquals(76, s.total)
        assertEquals(0.7778, s.lightPollution!!, 1e-4)
    }

    @Test
    fun `weights do not need to sum to one`() {
        val a = calc.compute(sky(21.0, 4), night(25.0), ScoreWeights(5.0, 3.0, 2.0, 0.0))!!
        val b = calc.compute(sky(21.0, 4), night(25.0), ScoreWeights(0.5, 0.3, 0.2, 0.0))!!
        assertEquals(a.total, b.total)
    }

    @Test
    fun `missing forecast renormalizes and marks partial`() {
        val s = calc.compute(sky(22.0, 1), null, ScoreWeights.DEFAULT)!!
        assertEquals(100, s.total) // solo LP disponibile → 100 % del peso a LP
        assertTrue(s.isPartial)
    }

    @Test
    fun `bortle is used when sqm is missing`() {
        val s = calc.compute(sky(null, 5), null, ScoreWeights(1.0, 0.0, 0.0, 0.0))!!
        assertEquals(50, s.total) // (9-5)/8 = 0.5
    }

    @Test
    fun `overcast or rain caps the score`() {
        val overcast = calc.compute(sky(22.0, 1), night(90.0, 1.0, 1.0), ScoreWeights.DEFAULT)!!
        assertEquals(20, overcast.total)
        val rain = calc.compute(sky(22.0, 1), night(10.0, 1.0, 1.0, rain = true), ScoreWeights.DEFAULT)!!
        assertEquals(0.0, rain.cloudCover!!, 0.0)
        assertEquals(20, rain.total)
        val uncapped = AstroScoreCalculator(overcastCap = false).compute(sky(22.0, 1), night(90.0, 1.0, 1.0), ScoreWeights.DEFAULT)!!
        assertEquals(73, uncapped.total) // 50 + 3 + 20
    }

    @Test
    fun `no data yields null`() {
        assertNull(calc.compute(null, null, ScoreWeights.DEFAULT))
    }
}
