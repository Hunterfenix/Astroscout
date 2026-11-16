package com.astroscout.domain.forecast

/**
 * Conversione degli indici 7Timer! ASTRO in grandezze fisiche (valore centrale di ciascuna fascia).
 * Fonte: documentazione 7Timer! ("Meteogram Explanation" / API doc).
 */
object SevenTimerScales {

    /** cloudcover 1..9 → % copertura (centro fascia). */
    fun cloudCoverPercent(index: Int): Double? = when (index) {
        1 -> 3.0      // 0–6 %
        2 -> 12.5     // 6–19 %
        3 -> 25.0     // 19–31 %
        4 -> 37.5     // 31–44 %
        5 -> 50.0     // 44–56 %
        6 -> 62.5     // 56–69 %
        7 -> 75.0     // 69–81 %
        8 -> 87.5     // 81–94 %
        9 -> 97.0     // 94–100 %
        else -> null
    }

    /** seeing 1..8 → FWHM in arcosecondi. */
    fun seeingArcsec(index: Int): Double? = when (index) {
        1 -> 0.4      // < 0.5″
        2 -> 0.625    // 0.5–0.75″
        3 -> 0.875    // 0.75–1″
        4 -> 1.125    // 1–1.25″
        5 -> 1.375    // 1.25–1.5″
        6 -> 1.75     // 1.5–2″
        7 -> 2.25     // 2–2.5″
        8 -> 2.75     // > 2.5″
        else -> null
    }

    /** transparency 1..8 → estinzione allo zenit in magnitudini. */
    fun transparencyMag(index: Int): Double? = when (index) {
        1 -> 0.25     // < 0.3
        2 -> 0.35     // 0.3–0.4
        3 -> 0.45     // 0.4–0.5
        4 -> 0.55     // 0.5–0.6
        5 -> 0.65     // 0.6–0.7
        6 -> 0.775    // 0.7–0.85
        7 -> 0.925    // 0.85–1
        8 -> 1.1      // > 1
        else -> null
    }

    fun isValidSeeing(index: Int) = index in 1..8
    fun isValidTransparency(index: Int) = index in 1..8
}
