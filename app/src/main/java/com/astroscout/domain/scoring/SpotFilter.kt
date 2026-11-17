package com.astroscout.domain.scoring

import com.astroscout.domain.model.Spot

/** Filtro rapido "solo spot buoni": Bortle < 5 **e** nuvolosità media < 30 % nella finestra notturna. */
data class SpotFilter(
    val maxBortleExclusive: Int = 5,
    val maxCloudPctExclusive: Double = 30.0,
) {
    /** Uno spot senza dati per un criterio non lo soddisfa: il filtro deve essere conservativo. */
    fun matches(spot: Spot): Boolean {
        val bortle = spot.sky?.bortle ?: return false
        val cloud = spot.cloudPct ?: return false
        return bortle < maxBortleExclusive && cloud < maxCloudPctExclusive
    }
}
