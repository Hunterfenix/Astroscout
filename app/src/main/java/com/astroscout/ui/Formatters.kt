package com.astroscout.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.astroscout.R
import com.astroscout.domain.model.DataError
import com.astroscout.domain.model.DataError.Service
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Formatters {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

    fun distance(meters: Double): String =
        if (meters < 1000) String.format(Locale.getDefault(), "%.0f m", meters)
        else String.format(Locale.getDefault(), "%.1f km", meters / 1000)

    fun time(i: Instant): String = time.format(i.atZone(ZoneId.systemDefault()))
    fun dateTime(i: Instant): String = dateTime.format(i.atZone(ZoneId.systemDefault()))

    fun bytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.getDefault(), "%.0f KB", b / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", b / (1024.0 * 1024.0))
    }

    fun coords(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.4f, %.4f", lat, lon)
}

@Composable
fun serviceName(s: Service): String = stringResource(
    when (s) {
        Service.SKY_QUALITY -> R.string.service_sky
        Service.FORECAST -> R.string.service_forecast
        Service.PARKING -> R.string.service_parking
        Service.GEOCODING -> R.string.service_geocoding
    }
)

/** Messaggio localizzato e "umano" per un errore di dominio. */
@Composable
fun errorText(e: DataError): String {
    val service = serviceName(e.service)
    return when (e) {
        is DataError.NoConnection -> stringResource(R.string.error_no_connection, service)
        is DataError.Timeout -> stringResource(R.string.error_timeout, service)
        is DataError.RateLimited -> stringResource(R.string.error_rate_limited, service)
        is DataError.Http -> stringResource(R.string.error_http, service, e.code)
        is DataError.OutOfCoverage -> stringResource(R.string.error_out_of_coverage, service)
        is DataError.InvalidResponse -> stringResource(R.string.error_invalid, service)
        is DataError.Unknown -> stringResource(R.string.error_unknown, service)
    }
}
