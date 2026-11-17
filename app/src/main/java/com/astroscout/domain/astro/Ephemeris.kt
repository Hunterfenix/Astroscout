package com.astroscout.domain.astro

import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Effemeridi a bassa precisione per Sole, Luna e centro galattico, calcolate sul dispositivo.
 *
 * Algoritmi: J. Meeus, *Astronomical Algorithms* (2ª ed.):
 * - Sole: cap. 25 "low accuracy" — errore ~0.01°;
 * - Luna: cap. 47 con i termini periodici principali (14 in longitudine, 6 in latitudine,
 *   5 in distanza) — errore tipico ~0.1–0.3°, ampiamente sufficiente per sorgere/tramonto
 *   (errore di pochi minuti) e per la fase;
 * - Tempo siderale: cap. 12. Coordinate orizzontali: cap. 13.
 *
 * Nessuna rete, nessuna libreria: ~100 operazioni trigonometriche per istante.
 * Convenzioni: angoli in gradi, longitudine Est positiva, azimut da Nord verso Est.
 */
object Ephemeris {

    data class Equatorial(val raDeg: Double, val decDeg: Double)
    data class Horizontal(val altDeg: Double, val azDeg: Double)

    /** Posizione lunare con dati extra per fase e parallasse. */
    data class MoonPosition(
        val equatorial: Equatorial,
        val eclipticLonDeg: Double,
        val eclipticLatDeg: Double,
        val distanceKm: Double,
    )

    /** Centro galattico (Sgr A*), J2000. La precessione (~0.4° dal 2000) è trascurabile qui. */
    val GALACTIC_CENTER = Equatorial(raDeg = 266.405, decDeg = -28.936)

    private const val AU_KM = 149_597_870.7
    private const val EARTH_RADIUS_KM = 6378.14

    fun julianDay(t: Instant): Double = t.toEpochMilli() / 86_400_000.0 + 2_440_587.5

    private fun centuries(jd: Double) = (jd - 2_451_545.0) / 36_525.0

    // --------------------------------------------------------------------------------- Sole

    data class SunPosition(val equatorial: Equatorial, val apparentLonDeg: Double)

    fun sun(t: Instant): SunPosition {
        val tc = centuries(julianDay(t))
        val l0 = norm(280.46646 + 36_000.76983 * tc + 0.0003032 * tc * tc)
        val m = norm(357.52911 + 35_999.05029 * tc - 0.0001537 * tc * tc)
        val c = (1.914602 - 0.004817 * tc - 0.000014 * tc * tc) * sind(m) +
            (0.019993 - 0.000101 * tc) * sind(2 * m) + 0.000289 * sind(3 * m)
        val omega = 125.04 - 1934.136 * tc
        val lambda = l0 + c - 0.00569 - 0.00478 * sind(omega) // longitudine apparente
        val eps = obliquity(tc) + 0.00256 * cosd(omega)
        return SunPosition(eclipticToEquatorial(lambda, 0.0, eps), norm(lambda))
    }

    // --------------------------------------------------------------------------------- Luna

    fun moon(t: Instant): MoonPosition {
        val tc = centuries(julianDay(t))
        val lp = norm(218.3164477 + 481_267.88123421 * tc)   // longitudine media
        val d = norm(297.8501921 + 445_267.1114034 * tc)     // elongazione media
        val m = norm(357.5291092 + 35_999.0502909 * tc)      // anomalia media del Sole
        val mp = norm(134.9633964 + 477_198.8675055 * tc)    // anomalia media della Luna
        val f = norm(93.2720950 + 483_202.0175233 * tc)      // argomento di latitudine

        val lon = lp +
            6.288774 * sind(mp) + 1.274027 * sind(2 * d - mp) + 0.658314 * sind(2 * d) +
            0.213618 * sind(2 * mp) - 0.185116 * sind(m) - 0.114332 * sind(2 * f) +
            0.058793 * sind(2 * d - 2 * mp) + 0.057066 * sind(2 * d - m - mp) +
            0.053322 * sind(2 * d + mp) + 0.045758 * sind(2 * d - m) - 0.040923 * sind(m - mp) -
            0.034720 * sind(d) - 0.030383 * sind(m + mp) + 0.015327 * sind(2 * d - 2 * f)
        val lat = 5.128122 * sind(f) + 0.280602 * sind(mp + f) + 0.277693 * sind(mp - f) +
            0.173237 * sind(2 * d - f) + 0.055413 * sind(2 * d - mp + f) + 0.046271 * sind(2 * d - mp - f)
        val dist = 385_000.56 - 20_905.355 * cosd(mp) - 3_699.111 * cosd(2 * d - mp) -
            2_955.968 * cosd(2 * d) - 569.925 * cosd(2 * mp)

        val eq = eclipticToEquatorial(lon, lat, obliquity(tc))
        return MoonPosition(eq, norm(lon), lat, dist)
    }

    /**
     * Frazione illuminata del disco lunare 0..1 (Meeus cap. 48):
     * elongazione ψ dal Sole, angolo di fase i = atan2(R·sinψ, Δ − R·cosψ), k = (1 + cos i)/2.
     */
    fun moonIllumination(t: Instant): Double {
        val s = sun(t)
        val mo = moon(t)
        val cosPsi = cosd(mo.eclipticLatDeg) * cosd(mo.eclipticLonDeg - s.apparentLonDeg)
        val psi = Math.toDegrees(kotlin.math.acos(cosPsi.coerceIn(-1.0, 1.0)))
        val i = Math.toDegrees(atan2(AU_KM * sind(psi), mo.distanceKm - AU_KM * cosd(psi)))
        return (1 + cosd(i)) / 2
    }

    /** true se la Luna è crescente (elongazione verso Est 0..180°). */
    fun isMoonWaxing(t: Instant): Boolean = norm(moon(t).eclipticLonDeg - sun(t).apparentLonDeg) < 180.0

    /** Parallasse orizzontale equatoriale della Luna (≈ 0.9–1.0°). */
    fun moonParallaxDeg(distanceKm: Double): Double = Math.toDegrees(asin(EARTH_RADIUS_KM / distanceKm))

    // ---------------------------------------------------------------------- coordinate locali

    /** Tempo siderale medio di Greenwich (gradi), Meeus 12.4. */
    fun gmstDeg(t: Instant): Double {
        val jd = julianDay(t)
        val tc = centuries(jd)
        return norm(280.46061837 + 360.98564736629 * (jd - 2_451_545.0) + 0.000387933 * tc * tc - tc * tc * tc / 38_710_000.0)
    }

    fun horizontal(eq: Equatorial, t: Instant, latDeg: Double, lonDeg: Double): Horizontal {
        val h = norm(gmstDeg(t) + lonDeg - eq.raDeg) // angolo orario locale
        val alt = Math.toDegrees(asin(sind(latDeg) * sind(eq.decDeg) + cosd(latDeg) * cosd(eq.decDeg) * cosd(h)))
        // Meeus 13.5 dà l'azimut da Sud; +180 lo porta alla convenzione da Nord.
        val azSouth = Math.toDegrees(atan2(sind(h), cosd(h) * sind(latDeg) - tan(Math.toRadians(eq.decDeg)) * cosd(latDeg)))
        return Horizontal(alt, norm(azSouth + 180.0))
    }

    fun sunAltitude(t: Instant, lat: Double, lon: Double): Double = horizontal(sun(t).equatorial, t, lat, lon).altDeg

    /**
     * Altitudine **topocentrica** del centro della Luna: alla distanza della Luna la parallasse
     * (~1°) abbassa la posizione apparente di π·cos(h) rispetto a quella geocentrica.
     */
    fun moonAltitude(t: Instant, lat: Double, lon: Double): Double {
        val mo = moon(t)
        val geo = horizontal(mo.equatorial, t, lat, lon).altDeg
        return geo - moonParallaxDeg(mo.distanceKm) * cosd(geo)
    }

    // ----------------------------------------------------------------------------- utilità

    private fun obliquity(tc: Double) = 23.439291 - 0.0130042 * tc

    private fun eclipticToEquatorial(lonDeg: Double, latDeg: Double, epsDeg: Double): Equatorial {
        val ra = Math.toDegrees(atan2(sind(lonDeg) * cosd(epsDeg) - tan(Math.toRadians(latDeg)) * sind(epsDeg), cosd(lonDeg)))
        val dec = Math.toDegrees(asin(sind(latDeg) * cosd(epsDeg) + cosd(latDeg) * sind(epsDeg) * sind(lonDeg)))
        return Equatorial(norm(ra), dec)
    }

    internal fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
    private fun sind(d: Double) = sin(Math.toRadians(d))
    private fun cosd(d: Double) = cos(Math.toRadians(d))

    /** Distanza angolare tra due posizioni equatoriali (gradi). */
    fun separationDeg(a: Equatorial, b: Equatorial): Double {
        val c = sind(a.decDeg) * sind(b.decDeg) + cosd(a.decDeg) * cosd(b.decDeg) * cosd(a.raDeg - b.raDeg)
        return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }
}
