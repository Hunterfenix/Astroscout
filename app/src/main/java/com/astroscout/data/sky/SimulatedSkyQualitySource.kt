package com.astroscout.data.sky

import com.astroscout.domain.geo.Geo
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.scoring.Bortle
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Sorgente **simulata** e deterministica, per sviluppo/test/demo finché l'endpoint DarkSkySites
 * non è confermato. NON va usata per decisioni reali: la UI la marca come "simulata".
 *
 * Modello (ispirato alla legge di Walker, brillanza del cielo ∝ P · d^-2.5):
 * ```
 * B(p)  = Σ_città  P_k · (d_k + r_k)^-2.5        d in km, r_k = R_SCALE·√P_k = "raggio" della città
 * SQM   = SQM_natural − 2.5 · log10(1 + K · B)   somma di flussi lineari → magnitudini
 * ```
 * Il termine r_k tratta la città come sorgente **estesa**: con sorgenti puntiformi la brillanza
 * cala troppo in fretta (Monza, a 15 km da Milano, risulterebbe Bortle 3). Con R_SCALE = 0.2 e
 * K = 10 si ottiene (verificato nei test): Milano ≈ 17.4 (B9), Monza ≈ 19.3 (B6), Valtellina ≈ 21.5 (B4), Sila ≈ 21.9 (B2).
 */
class SimulatedSkyQualitySource(
    private val sources: List<LightSource> = ITALIAN_CITIES,
) : SkyQualitySource {

    data class LightSource(val name: String, val point: GeoPoint, val populationK: Double)

    override val type = SkyQualitySourceType.SIMULATED

    override suspend fun fetch(point: GeoPoint): SkyQuality = compute(point)

    fun compute(point: GeoPoint): SkyQuality {
        val brightness = sources.sumOf { s ->
            val dKm = Geo.distanceMeters(point, s.point) / 1000.0
            s.populationK * (dKm + R_SCALE * sqrt(s.populationK)).pow(-2.5)
        }
        val sqm = (SQM_NATURAL - 2.5 * log10(1.0 + K * brightness)).coerceIn(16.0, SQM_NATURAL)
        val rounded = Math.round(sqm * 100) / 100.0
        return SkyQuality(sqm = rounded, bortle = Bortle.fromSqm(rounded), source = SkyQualitySourceType.SIMULATED)
    }

    companion object {
        private const val SQM_NATURAL = 22.0
        private const val R_SCALE = 0.2
        private const val K = 10.0

        /** Popolazione area urbana (migliaia, ordine di grandezza). */
        val ITALIAN_CITIES = listOf(
            LightSource("Milano", GeoPoint(45.4642, 9.1900), 4300.0),
            LightSource("Roma", GeoPoint(41.9028, 12.4964), 4300.0),
            LightSource("Napoli", GeoPoint(40.8518, 14.2681), 3100.0),
            LightSource("Torino", GeoPoint(45.0703, 7.6869), 1700.0),
            LightSource("Palermo", GeoPoint(38.1157, 13.3615), 850.0),
            LightSource("Bari", GeoPoint(41.1171, 16.8719), 750.0),
            LightSource("Bologna", GeoPoint(44.4949, 11.3426), 800.0),
            LightSource("Firenze", GeoPoint(43.7696, 11.2558), 1000.0),
            LightSource("Genova", GeoPoint(44.4056, 8.9463), 650.0),
            LightSource("Venezia-Padova", GeoPoint(45.4384, 12.0), 1600.0),
            LightSource("Verona", GeoPoint(45.4384, 10.9916), 500.0),
            LightSource("Catania", GeoPoint(37.5079, 15.0830), 750.0),
            LightSource("Brescia-Bergamo", GeoPoint(45.62, 9.94), 1400.0),
            LightSource("Cagliari", GeoPoint(39.2238, 9.1217), 420.0),
        )
    }
}
