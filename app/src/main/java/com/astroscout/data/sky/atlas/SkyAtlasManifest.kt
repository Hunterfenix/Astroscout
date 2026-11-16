package com.astroscout.data.sky.atlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `manifest.json` pubblicato accanto alle tile.
 *
 * ```json
 * { "format": 1, "version": "wa2015-1", "dataset": "World Atlas 2015 (Falchi et al. 2016)",
 *   "license": "CC BY-NC 4.0", "tileDeg": 5, "latMin": -60, "latMax": 85,
 *   "darkSqm": 21.995, "tiles": ["N45E005", "N45E010", ...] }
 * ```
 *
 * - [tiles]: solo le tile che contengono luce artificiale significativa. Una tile assente ma
 *   dentro [latMin]..[latMax] è "tutta buia" (oceano aperto, deserti): vale [darkSqm].
 *   Così il pacchetto mondiale resta sotto il limite di 1000 asset di una GitHub Release.
 * - [version]: cambiando versione l'app scarta la cache e riscarica le tile su richiesta.
 */
@Serializable
data class SkyAtlasManifest(
    val format: Int,
    val version: String,
    val dataset: String = "",
    val license: String = "",
    val tileDeg: Int,
    val latMin: Int,
    val latMax: Int,
    val darkSqm: Double = 21.995,
    val tiles: List<String>,
    @SerialName("generatedAt") val generatedAt: String? = null,
) {
    fun isCovered(lat: Double): Boolean = lat >= latMin && lat < latMax
}
