package com.astroscout.domain.scoring

/**
 * Conversioni tra SQM (mag/arcsec²) e scala di Bortle, e classificazione cromatica per la mappa.
 *
 * Soglie SQM→Bortle: tabella di riferimento comunemente usata (es. lightpollutionmap.info /
 * Unihedron). La scala di Bortle è qualitativa: la mappatura è un'approssimazione condivisa.
 */
object Bortle {

    /** Limiti inferiori di SQM per le classi 1..8 (sotto 17.80 → classe 9). */
    private val THRESHOLDS = listOf(
        1 to 21.99,
        2 to 21.89,
        3 to 21.69,
        4 to 20.49,
        5 to 19.50,
        6 to 18.94,
        7 to 18.38,
        8 to 17.80,
    )

    fun fromSqm(sqm: Double): Int = THRESHOLDS.firstOrNull { (_, min) -> sqm >= min }?.first ?: 9

    /** Valore centrale di SQM per una classe: usato solo come fallback quando c'è solo il Bortle. */
    fun representativeSqm(bortle: Int): Double = when (bortle.coerceIn(1, 9)) {
        1 -> 22.0
        2 -> 21.94
        3 -> 21.79
        4 -> 21.09
        5 -> 20.0
        6 -> 19.22
        7 -> 18.66
        8 -> 18.09
        else -> 17.5
    }

    enum class Band { DARK, MODERATE, BRIGHT }

    /** Verde ≤ 4 (cielo rurale o migliore), giallo 5–6 (suburbano), rosso ≥ 7 (urbano). */
    fun band(bortle: Int): Band = when {
        bortle <= 4 -> Band.DARK
        bortle <= 6 -> Band.MODERATE
        else -> Band.BRIGHT
    }

    /** I punti con Bortle ≤ 4 ricevono l'alone verde sulla mappa. */
    fun deservesHalo(bortle: Int): Boolean = bortle <= 4
}
