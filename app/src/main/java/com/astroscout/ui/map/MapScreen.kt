package com.astroscout.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.astroscout.R
import com.astroscout.ui.detail.SpotDetailContent
import com.astroscout.ui.errorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
) {
    // collectAsStateWithLifecycle: smette di raccogliere quando l'app va in background (STOPPED),
    // a differenza di collectAsState che continuerebbe a tenere attive le query Room.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberSpotMapState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val focus = LocalFocusManager.current

    // Eventi one-shot dal ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is MapEvent.MoveCamera -> mapState.animateTo(e.point, e.zoom)
                is MapEvent.RefreshIssues -> snackbar.showSnackbar(
                    if (e.report.failed > 0 && e.report.fromCache == 0)
                        context.getString(R.string.refresh_failed, e.report.failed)
                    else context.getString(R.string.refresh_partial_cache, e.report.fromCache + e.report.failed)
                )
                MapEvent.FilterHidesEverything -> snackbar.showSnackbar(context.getString(R.string.filter_empty))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { mapState.center()?.let(viewModel::onScanArea) },
                icon = {
                    if (state.isScanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null)
                },
                text = { Text(stringResource(R.string.scan_area)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            SpotMap(
                spots = state.visibleSpots,
                selectedId = state.selected?.id,
                state = mapState,
                initialCamera = viewModel.lastCamera,
                darkStyle = isSystemInDarkTheme(),
                onSpotClick = viewModel::onSpotClick,
                onLongPress = viewModel::onMapLongPress,
                onCameraIdle = viewModel::onCameraIdle,
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchBar(
                    state = state.search,
                    onQueryChange = viewModel::onSearchQueryChange,
                    onSubmit = { focus.clearFocus(); viewModel.onSearchSubmit() },
                    onClear = viewModel::onSearchClear,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                )
                if (state.search.results.isNotEmpty() || state.search.error != null) {
                    ElevatedCard {
                        LazyColumn(Modifier.heightIn(max = 280.dp)) {
                            state.search.error?.let { err ->
                                item { Text(errorText(err), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                            }
                            items(state.search.results, key = { it.id }) { place ->
                                ListItem(
                                    headlineContent = { Text(place.title) },
                                    supportingContent = { Text(place.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable { viewModel.onSearchResultChosen(place) },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = state.onlyGoodSpots,
                        onClick = viewModel::onToggleGoodFilter,
                        label = { Text(stringResource(R.string.filter_good)) },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
                    )
                    if (state.isSkySimulated) {
                        Text(
                            stringResource(R.string.simulated_banner),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.allSpots.isEmpty()) {
                    Text(
                        stringResource(R.string.hint_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }

    val selected = state.selected
    if (selected != null) {
        ModalBottomSheet(
            onDismissRequest = viewModel::onDismissDetail,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            SpotDetailContent(
                spot = selected,
                parkings = state.parkings,
                radiusMeters = state.parkingRadiusMeters,
                onToggleFavorite = { viewModel.onToggleFavorite(selected.id) },
                onRefresh = viewModel::onRefreshSelected,
                onRetryParkings = viewModel::onRetryParkings,
            )
        }
    }
}

@Composable
private fun SearchBar(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    ElevatedCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(4.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = {
                    if (state.isSearching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                },
                // Ricerca solo all'invio: rispetta la policy Nominatim (niente richieste per ogni tasto).
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            )
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            IconButton(onClick = onOpenAbout) { Icon(Icons.Filled.Info, stringResource(R.string.about)) }
        }
    }
}
