package com.astroscout.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astroscout.R
import com.astroscout.domain.model.Parking
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.model.Spot
import com.astroscout.domain.scoring.Bortle
import com.astroscout.ui.Formatters
import com.astroscout.ui.errorText
import com.astroscout.ui.map.MapBadges
import com.astroscout.ui.map.ParkingUiState
import com.astroscout.ui.theme.BortleBright
import com.astroscout.ui.theme.BortleDark
import com.astroscout.ui.theme.BortleModerate
import com.astroscout.ui.theme.Unknown
import java.util.Locale

/**
 * Contenuto della bottom sheet. Tutto in una LazyColumn: la lista dei parcheggi può essere lunga
 * (zone turistiche) e la sheet deve restare scrollabile anche espansa a metà.
 */
@Composable
fun SpotDetailContent(
    spot: Spot,
    parkings: ParkingUiState,
    radiusMeters: Int,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onRetryParkings: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item { Header(spot, onToggleFavorite, onRefresh) }
        item { Badges(spot) }
        item { ScoreBlock(spot) }
        item { Breakdown(spot) }
        item {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.parking_title, Formatters.distance(radiusMeters.toDouble())),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when (parkings) {
            ParkingUiState.Idle -> Unit
            ParkingUiState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
            is ParkingUiState.Error -> item {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(errorText(parkings.error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetryParkings) { Text(stringResource(R.string.retry)) }
                }
            }
            is ParkingUiState.Loaded -> {
                parkings.fromCacheError?.let { err ->
                    item { Text(stringResource(R.string.from_cache_because, errorText(err)), style = MaterialTheme.typography.bodySmall) }
                }
                if (parkings.items.isEmpty()) {
                    item { Text(stringResource(R.string.parking_none), Modifier.padding(vertical = 8.dp)) }
                }
                items(parkings.items, key = { "${it.osmType}/${it.osmId}" }) { p ->
                    ParkingRow(p, onClick = {
                        // geo: URI → l'app di navigazione preferita dall'utente (Maps, OsmAnd, Organic Maps...).
                        val label = Uri.encode(p.name ?: context.getString(R.string.parking_unnamed))
                        val uri = Uri.parse("geo:${p.point.lat},${p.point.lon}?q=${p.point.lat},${p.point.lon}($label)")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    })
                }
            }
        }
    }
}

@Composable
private fun Header(spot: Spot, onToggleFavorite: () -> Unit, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                spot.name ?: stringResource(R.string.spot_unnamed),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(Formatters.coords(spot.point.lat, spot.point.lon), style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (spot.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(if (spot.isFavorite) R.string.favorite_remove else R.string.favorite_add),
                tint = if (spot.isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Badges(spot: Spot) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (spot.sky?.source == SkyQualitySourceType.SIMULATED) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.badge_simulated)) },
                leadingIcon = { Icon(Icons.Filled.Warning, null, Modifier.size(16.dp)) })
        }
        if (spot.isStale) {
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.badge_cached)) })
        }
    }
}

@Composable
private fun ScoreBlock(spot: Spot) {
    val score = spot.score
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
            if (score != null) {
                CircularProgressIndicator(
                    progress = { score.total / 100f },
                    modifier = Modifier.size(88.dp),
                    strokeWidth = 8.dp,
                    color = scoreColor(score.total),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${score.total}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            } else {
                CircularProgressIndicator(Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.astro_score), style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    score == null -> stringResource(R.string.loading)
                    score.total >= 75 -> stringResource(R.string.verdict_excellent)
                    score.total >= 55 -> stringResource(R.string.verdict_good)
                    score.total >= 35 -> stringResource(R.string.verdict_fair)
                    else -> stringResource(R.string.verdict_poor)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (score?.isPartial == true) {
                Text(stringResource(R.string.score_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Breakdown(spot: Spot) {
    val sky = spot.sky
    val f = spot.forecast
    val s = spot.score
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricRow(
            label = stringResource(R.string.metric_light_pollution),
            value = sky?.let {
                val band = stringResource(
                    when (Bortle.band(it.bortle)) {
                        Bortle.Band.DARK -> R.string.band_dark
                        Bortle.Band.MODERATE -> R.string.band_moderate
                        Bortle.Band.BRIGHT -> R.string.band_bright
                    }
                )
                "Bortle ${it.bortle} · $band" + (it.sqm?.let { q -> String.format(Locale.getDefault(), " · SQM %.2f", q) } ?: "")
            } ?: "—",
            sub = s?.lightPollution,
            color = sky?.let { bandColor(it.bortle) } ?: Unknown,
        )
        sky?.let {
            Text(
                stringResource(
                    R.string.sky_source,
                    stringResource(
                        when (it.source) {
                            SkyQualitySourceType.WORLD_ATLAS_2015 -> R.string.source_atlas
                            SkyQualitySourceType.DARKSKYSITES -> R.string.source_darksky
                            SkyQualitySourceType.SIMULATED -> R.string.source_simulated
                        }
                    ),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (f != null) {
            Text(
                stringResource(R.string.night_window, Formatters.time(f.windowStart), Formatters.time(f.windowEnd)) +
                    "  " + MapBadges.weatherEmoji(f.condition),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        MetricRow(
            label = stringResource(R.string.metric_clouds),
            value = f?.let {
                String.format(Locale.getDefault(), "%.0f %% (max %.0f %%)", it.avgCloudCoverPct, it.maxCloudCoverPct) +
                    if (it.hasPrecipitation) " · " + stringResource(R.string.precipitation) else ""
            } ?: "—",
            sub = s?.cloudCover,
        )
        MetricRow(
            label = stringResource(R.string.metric_seeing),
            value = f?.avgSeeingArcsec?.let { String.format(Locale.getDefault(), "%.2f″", it) } ?: "—",
            sub = s?.seeing,
        )
        MetricRow(
            label = stringResource(R.string.metric_transparency),
            value = f?.avgTransparencyIndex?.let { String.format(Locale.getDefault(), "%.1f / 8", it) } ?: "—",
            sub = s?.transparency,
        )
        spot.forecastUpdatedAt?.let {
            Text(stringResource(R.string.updated_at, Formatters.dateTime(it)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, sub: Double?, color: Color = MaterialTheme.colorScheme.primary) {
    Column {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        if (sub != null) {
            LinearProgressIndicator(
                progress = { sub.toFloat() },
                modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
                color = color,
            )
        }
    }
}

@Composable
private fun ParkingRow(p: Parking, onClick: () -> Unit) {
    val details = buildList {
        p.capacity?.let { add(stringResource(R.string.parking_capacity, it)) }
        p.fee?.let {
            add(
                when (it.lowercase()) {
                    "yes" -> stringResource(R.string.parking_fee_yes)
                    "no" -> stringResource(R.string.parking_fee_no)
                    else -> stringResource(R.string.parking_fee_other, it)
                }
            )
        }
        p.surface?.let { add(it) }
    }.joinToString(" · ")
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Filled.Place, null) },
        headlineContent = { Text(p.name ?: stringResource(R.string.parking_unnamed)) },
        supportingContent = if (details.isNotEmpty()) ({ Text(details) }) else null,
        trailingContent = { Text(Formatters.distance(p.distanceMeters), fontWeight = FontWeight.SemiBold) },
    )
}

private fun bandColor(bortle: Int) = when (Bortle.band(bortle)) {
    Bortle.Band.DARK -> BortleDark
    Bortle.Band.MODERATE -> BortleModerate
    Bortle.Band.BRIGHT -> BortleBright
}

private fun scoreColor(score: Int) = when {
    score >= 70 -> BortleDark
    score >= 40 -> BortleModerate
    else -> BortleBright
}
