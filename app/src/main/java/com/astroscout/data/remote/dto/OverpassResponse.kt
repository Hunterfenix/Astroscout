package com.astroscout.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Risposta Overpass API con `[out:json]` e `out center tags;`.
 *
 * - I `node` hanno `lat`/`lon` diretti.
 * - `way` e `relation` (parcheggi disegnati come area) non hanno coordinate proprie:
 *   con `out center` Overpass aggiunge il baricentro in [center].
 * - [remark] è valorizzato quando la query è andata in errore lato server (es. timeout,
 *   "runtime error: Query timed out") pur con HTTP 200: va trattato come errore.
 */
@Serializable
data class OverpassResponse(
    val version: Double? = null,
    val generator: String? = null,
    val elements: List<OverpassElement> = emptyList(),
    val remark: String? = null,
)

@Serializable
data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenter? = null,
    val tags: Map<String, String> = emptyMap(),
) {
    val latitude: Double? get() = lat ?: center?.lat
    val longitude: Double? get() = lon ?: center?.lon
}

@Serializable
data class OverpassCenter(val lat: Double, val lon: Double)
