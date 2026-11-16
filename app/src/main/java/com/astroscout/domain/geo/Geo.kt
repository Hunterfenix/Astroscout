package com.astroscout.domain.geo

import com.astroscout.domain.model.GeoPoint
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8 // raggio medio IUGG
    private const val METERS_PER_DEG_LAT = 111_320.0

    /** Distanza ortodromica (haversine): errore < 0.5 % rispetto all'ellissoide, ok per pochi km. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Griglia N×N centrata su [center] con passo [stepKm]. Un grado di longitudine si accorcia
     * con cos(lat), quindi il passo in gradi lungo X va diviso per cos(lat) per avere celle "quadrate".
     * [size] dispari garantisce che il centro sia un nodo della griglia.
     */
    fun grid(center: GeoPoint, size: Int, stepKm: Double): List<GeoPoint> {
        require(size >= 1) { "size must be >= 1" }
        val dLat = stepKm * 1000.0 / METERS_PER_DEG_LAT
        val dLon = dLat / cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        val half = (size - 1) / 2.0
        return buildList {
            for (row in 0 until size) for (col in 0 until size) {
                add(
                    GeoPoint(
                        lat = (center.lat + (row - half) * dLat).coerceIn(-85.0, 85.0),
                        lon = normalizeLon(center.lon + (col - half) * dLon),
                    )
                )
            }
        }
    }

    /**
     * Cella di cache per il meteo: 7Timer! usa GFS (~0.25–0.5°), quindi punti a pochi km danno lo
     * stesso forecast. Arrotondando a 0.1° (~11 km) una griglia 5×5 da 5 km fa ~4–9 chiamate, non 25.
     */
    fun forecastCellId(p: GeoPoint, cellDeg: Double = 0.1): String {
        val lat = floor(p.lat / cellDeg) * cellDeg + cellDeg / 2
        val lon = floor(p.lon / cellDeg) * cellDeg + cellDeg / 2
        return String.format(Locale.ROOT, "%.2f_%.2f", lat, lon)
    }

    fun cellCenter(cellId: String): GeoPoint {
        val (lat, lon) = cellId.split('_').map { it.toDouble() }
        return GeoPoint(lat, lon)
    }

    private fun normalizeLon(lon: Double): Double = ((lon + 540.0) % 360.0) - 180.0
}
