package com.astroscout.domain

import com.astroscout.data.sky.SimulatedSkyQualitySource
import com.astroscout.domain.geo.Geo
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.scoring.Bortle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoAndBortleTest {

    @Test
    fun `haversine Milano-Roma is about 477 km`() {
        val d = Geo.distanceMeters(GeoPoint(45.4642, 9.19), GeoPoint(41.9028, 12.4964))
        assertEquals(477_000.0, d, 3_000.0)
    }

    @Test
    fun `grid is centered and evenly spaced`() {
        val c = GeoPoint(45.0, 10.0)
        val g = Geo.grid(c, 5, 5.0)
        assertEquals(25, g.size)
        assertEquals(c.lat, g[12].lat, 1e-9) // nodo centrale
        assertEquals(c.lon, g[12].lon, 1e-9)
        // passo ≈ 5 km in entrambe le direzioni anche a 45° di latitudine
        assertEquals(5_000.0, Geo.distanceMeters(g[12], g[13]), 50.0)
        assertEquals(5_000.0, Geo.distanceMeters(g[12], g[17]), 50.0)
    }

    @Test
    fun `nearby points share a forecast cell`() {
        assertEquals(Geo.forecastCellId(GeoPoint(45.01, 10.01)), Geo.forecastCellId(GeoPoint(45.09, 10.09)))
        assertTrue(Geo.forecastCellId(GeoPoint(45.01, 10.01)) != Geo.forecastCellId(GeoPoint(45.11, 10.01)))
        assertEquals(GeoPoint(45.05, 10.05), Geo.cellCenter(Geo.forecastCellId(GeoPoint(45.01, 10.01))))
    }

    @Test
    fun `sqm to bortle table`() {
        assertEquals(1, Bortle.fromSqm(22.0))
        assertEquals(3, Bortle.fromSqm(21.7))
        assertEquals(4, Bortle.fromSqm(21.0))
        assertEquals(5, Bortle.fromSqm(20.0))
        assertEquals(7, Bortle.fromSqm(18.5))
        assertEquals(9, Bortle.fromSqm(17.0))
        assertTrue(Bortle.deservesHalo(4))
        assertTrue(!Bortle.deservesHalo(5))
    }

    @Test
    fun `simulated source is plausible`() {
        val sim = SimulatedSkyQualitySource()
        val milano = sim.compute(GeoPoint(45.4642, 9.19))
        val monza = sim.compute(GeoPoint(45.58, 9.27))
        val valtellina = sim.compute(GeoPoint(46.35, 9.9))
        val sila = sim.compute(GeoPoint(39.3, 16.5))
        println("SIM Milano=${milano.sqm}/${milano.bortle} Monza=${monza.sqm}/${monza.bortle} Valtellina=${valtellina.sqm}/${valtellina.bortle} Sila=${sila.sqm}/${sila.bortle}")
        assertTrue(milano.bortle >= 8)
        assertTrue(monza.bortle in 6..9)
        assertTrue(valtellina.bortle <= 4)
        assertTrue(sila.sqm!! > valtellina.sqm!!)
    }
}
