package com.astroscout.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.astroscout.AstroScoutApp
import com.astroscout.R
import com.astroscout.data.settings.AppSettings
import com.astroscout.data.settings.SettingsRepository
import com.astroscout.domain.model.ScoreWeights
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setWeights(w: ScoreWeights) = viewModelScope.launch { repo.setWeights(w) }
    fun setGrid(size: Int, step: Double) = viewModelScope.launch { repo.setGrid(size, step) }
    fun setParkingRadius(m: Int) = viewModelScope.launch { repo.setParkingRadius(m) }
    fun reset() = viewModelScope.launch { repo.reset() }

    companion object {
        val Factory = viewModelFactory {
            initializer { SettingsViewModel((this[APPLICATION_KEY] as AstroScoutApp).container.settings) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val w = s.weights
    val sum = (w.lightPollution + w.cloudCover + w.seeing + w.transparency).takeIf { it > 0 } ?: 1.0
    fun pct(x: Double) = "${(100 * x / sum).roundToInt()} %"

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.weights_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.weights_help), style = MaterialTheme.typography.bodySmall)
            WeightSlider(stringResource(R.string.metric_light_pollution), w.lightPollution, pct(w.lightPollution)) { vm.setWeights(w.copy(lightPollution = it)) }
            WeightSlider(stringResource(R.string.metric_clouds), w.cloudCover, pct(w.cloudCover)) { vm.setWeights(w.copy(cloudCover = it)) }
            WeightSlider(stringResource(R.string.metric_seeing), w.seeing, pct(w.seeing)) { vm.setWeights(w.copy(seeing = it)) }
            WeightSlider(stringResource(R.string.metric_transparency), w.transparency, pct(w.transparency)) { vm.setWeights(w.copy(transparency = it)) }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.grid_title), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 7).forEach { n ->
                    FilterChip(selected = s.gridSize == n, onClick = { vm.setGrid(n, s.gridStepKm) }, label = { Text("$n×$n") })
                }
            }
            Text(stringResource(R.string.grid_step, String.format(Locale.getDefault(), "%.0f", s.gridStepKm)))
            Slider(value = s.gridStepKm.toFloat(), onValueChange = { vm.setGrid(s.gridSize, it.roundToInt().toDouble()) }, valueRange = 2f..10f, steps = 7)

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.parking_radius, String.format(Locale.getDefault(), "%.1f", s.parkingRadiusMeters / 1000.0)), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = s.parkingRadiusMeters / 1000f,
                onValueChange = { vm.setParkingRadius(((it * 2).roundToInt() / 2f * 1000).roundToInt()) }, // step 0.5 km
                valueRange = 1f..5f,
                steps = 7,
            )
            OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.padding(vertical = 16.dp)) { Text(stringResource(R.string.reset_defaults)) }
        }
    }
}

@Composable
private fun WeightSlider(label: String, value: Double, normalized: String, onChange: (Double) -> Unit) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(normalized, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange((it * 20).roundToInt() / 20.0) }, valueRange = 0f..1f, steps = 19)
    }
}
