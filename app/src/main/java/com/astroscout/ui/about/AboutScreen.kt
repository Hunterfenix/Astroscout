package com.astroscout.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.astroscout.BuildConfig
import com.astroscout.R

private data class Credit(val title: Int, val body: Int, val url: String)

/** Crediti e attribuzioni richieste dalle licenze/policy delle fonti dati. */
private val CREDITS = listOf(
    Credit(R.string.credit_osm_title, R.string.credit_osm_body, "https://www.openstreetmap.org/copyright"),
    Credit(R.string.credit_overpass_title, R.string.credit_overpass_body, "https://overpass-api.de/"),
    Credit(R.string.credit_nominatim_title, R.string.credit_nominatim_body, "https://nominatim.org/"),
    Credit(R.string.credit_7timer_title, R.string.credit_7timer_body, "https://www.7timer.info/"),
    Credit(R.string.credit_atlas_title, R.string.credit_atlas_body, "https://doi.org/10.5880/GFZ.1.4.2016.001"),
    Credit(R.string.credit_darksky_title, R.string.credit_darksky_body, "https://www.darkskysites.com/"),
    Credit(R.string.credit_maplibre_title, R.string.credit_maplibre_body, "https://maplibre.org/"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, isSkySimulated: Boolean) {
    val uri = LocalUriHandler.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.about)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("AstroScout ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_intro), Modifier.padding(vertical = 8.dp))
            if (isSkySimulated) {
                Text(stringResource(R.string.about_simulated), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            CREDITS.forEach { c ->
                ElevatedCard(Modifier.padding(vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(c.title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(c.body), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { uri.openUri(c.url) }) { Text(c.url) }
                    }
                }
            }
            Text(stringResource(R.string.about_score_formula), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
