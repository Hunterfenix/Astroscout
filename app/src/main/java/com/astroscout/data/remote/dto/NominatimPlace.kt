package com.astroscout.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Elemento di `/search?format=jsonv2`. Nota: Nominatim restituisce lat/lon come **stringhe**.
 */
@Serializable
data class NominatimPlace(
    @SerialName("place_id") val placeId: Long,
    val lat: String,
    val lon: String,
    @SerialName("display_name") val displayName: String,
    val name: String? = null,
    val category: String? = null,
    val type: String? = null,
    val importance: Double? = null,
)
