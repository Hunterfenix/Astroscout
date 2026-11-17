package com.astroscout.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo `/v1/forecast` con `timeformat=unixtime`, `timezone=GMT`, `wind_speed_unit=ms`.
 *
 * ```json
 * { "latitude": 46.0, "longitude": 9.0, "elevation": 812.0,
 *   "hourly": { "time": [1789000000, ...], "cloud_cover": [12, ...], "cloud_cover_low": [...],
 *               "cloud_cover_mid": [...], "cloud_cover_high": [...], "temperature_2m": [...],
 *               "dew_point_2m": [...], "relative_humidity_2m": [...], "wind_speed_10m": [...],
 *               "wind_gusts_10m": [...], "precipitation": [...] } }
 * ```
 * Le serie sono array paralleli a `time`; i valori possono essere `null` (modello senza quel dato).
 * `elevation` è la quota del punto da DEM a 90 m (Copernicus), non quella della cella del modello.
 */
@Serializable
data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double? = null,
    val hourly: OpenMeteoHourly? = null,
)

@Serializable
data class OpenMeteoHourly(
    val time: List<Long> = emptyList(),
    @SerialName("cloud_cover") val cloudCover: List<Double?> = emptyList(),
    @SerialName("cloud_cover_low") val cloudCoverLow: List<Double?> = emptyList(),
    @SerialName("cloud_cover_mid") val cloudCoverMid: List<Double?> = emptyList(),
    @SerialName("cloud_cover_high") val cloudCoverHigh: List<Double?> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("dew_point_2m") val dewPoint: List<Double?> = emptyList(),
    @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
)

/** Open-Meteo `/v1/elevation?latitude=a,b&longitude=c,d` → `{"elevation":[812.0, 1430.0]}`. */
@Serializable
data class ElevationResponse(val elevation: List<Double> = emptyList())
