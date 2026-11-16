package com.astroscout.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.astroscout.AstroScoutApp
import com.astroscout.data.repository.AstroRepository
import com.astroscout.data.repository.RefreshReport
import com.astroscout.data.settings.AppSettings
import com.astroscout.data.settings.SettingsRepository
import com.astroscout.domain.geo.Geo
import com.astroscout.domain.model.DataError
import com.astroscout.domain.model.Fetched
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.Parking
import com.astroscout.domain.model.PlaceResult
import com.astroscout.domain.model.Spot
import com.astroscout.domain.model.SpotOrigin
import com.astroscout.domain.scoring.SpotFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

// ------------------------------------------------------------------------------------ UI state

data class MapUiState(
    /** Tutti gli spot noti (candidati + preferiti). */
    val allSpots: List<Spot> = emptyList(),
    /** Spot da disegnare dopo il filtro rapido. */
    val visibleSpots: List<Spot> = emptyList(),
    val selected: Spot? = null,
    val parkings: ParkingUiState = ParkingUiState.Idle,
    val onlyGoodSpots: Boolean = false,
    val isScanning: Boolean = false,
    val search: SearchUiState = SearchUiState(),
    val parkingRadiusMeters: Int = AppSettings().parkingRadiusMeters,
    val isSkySimulated: Boolean = false,
)

sealed interface ParkingUiState {
    data object Idle : ParkingUiState
    data object Loading : ParkingUiState
    data class Loaded(val items: List<Parking>, val fromCacheError: DataError? = null) : ParkingUiState
    data class Error(val error: DataError) : ParkingUiState
}

data class SearchUiState(
    val query: String = "",
    val results: List<PlaceResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: DataError? = null,
)

/** Eventi one-shot (non stato): consumati una volta sola dalla UI. */
sealed interface MapEvent {
    data class MoveCamera(val point: GeoPoint, val zoom: Double) : MapEvent
    data class RefreshIssues(val report: RefreshReport) : MapEvent
    data object FilterHidesEverything : MapEvent
}

// ------------------------------------------------------------------------------------ ViewModel

/**
 * Stato della schermata mappa come **unico StateFlow** ([uiState]) derivato da flussi più piccoli.
 *
 * Perché un solo stato immutabile: Compose ricompone in base all'uguaglianza; un'unica data class
 * rende ogni frame una funzione pura dello stato (niente combinazioni incoerenti tra più flow).
 *
 * Gli eventi one-shot (spostare la camera, snackbar) passano da un [Channel]: uno StateFlow li
 * riemetterebbe a ogni rotazione dello schermo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModel(
    private val repository: AstroRepository,
    private val settingsRepository: SettingsRepository,
    private val isSkySimulated: Boolean,
) : ViewModel() {

    private val gridIds = MutableStateFlow<Set<String>>(emptySet())
    private val pinnedIds = MutableStateFlow<Set<String>>(emptySet()) // long-press + ricerca
    private val selectedId = MutableStateFlow<String?>(null)
    private val onlyGood = MutableStateFlow(false)
    private val scanning = MutableStateFlow(false)
    private val parkings = MutableStateFlow<ParkingUiState>(ParkingUiState.Idle)
    private val search = MutableStateFlow(SearchUiState())

    private val filter = SpotFilter()
    private val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val spots: Flow<List<Spot>> =
        combine(gridIds, pinnedIds) { g, p -> g + p }
            .flatMapLatest { ids -> repository.observeSpots(ids) }

    private val _events = Channel<MapEvent>(Channel.BUFFERED)
    val events: Flow<MapEvent> = _events.receiveAsFlow()

    private data class Transient(val scanning: Boolean, val search: SearchUiState, val radius: Int)

    private val transient = combine(scanning, search, settings) { sc, se, st -> Transient(sc, se, st.parkingRadiusMeters) }

    val uiState: StateFlow<MapUiState> =
        combine(spots, selectedId, onlyGood, parkings, transient) { all, sel, good, park, t ->
            val visible = if (good) all.filter(filter::matches) else all
            MapUiState(
                allSpots = all,
                // Lo spot selezionato resta visibile anche se il filtro lo escluderebbe.
                visibleSpots = visible + all.filter { it.id == sel && it !in visible },
                selected = all.firstOrNull { it.id == sel },
                parkings = park,
                onlyGoodSpots = good,
                isScanning = t.scanning,
                search = t.search,
                parkingRadiusMeters = t.radius,
                isSkySimulated = isSkySimulated,
            )
        }.stateIn(
            scope = viewModelScope,
            // 5 s: sopravvive a una rotazione senza ri-sottoscrivere Room, ma si ferma in background.
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MapUiState(isSkySimulated = isSkySimulated),
        )

    private var scanJob: Job? = null
    private var parkingJob: Job? = null

    /** Ultima camera nota: la mappa la ripristina quando torna in composizione (es. da Info). */
    var lastCamera: Pair<GeoPoint, Double> = GeoPoint(42.5, 12.5) to 5.5 // Italia
        private set

    fun onCameraIdle(center: GeoPoint, zoom: Double) {
        lastCamera = center to zoom
    }

    // ------------------------------------------------------------------------------- azioni UI

    /** "Scansiona quest'area": genera la griglia N×N attorno al centro della mappa. */
    fun onScanArea(center: GeoPoint) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanning.value = true
            try {
                val s = settings.value
                val points = Geo.grid(center, s.gridSize, s.gridStepKm)
                val ids = repository.registerCandidates(points, SpotOrigin.GRID)
                gridIds.value = ids.toSet() // i marker compaiono subito (grigi), poi si colorano
                val report = repository.refresh(ids)
                if (report.hasProblems) _events.send(MapEvent.RefreshIssues(report))
                if (onlyGood.value && uiState.value.allSpots.none(filter::matches)) {
                    _events.send(MapEvent.FilterHidesEverything)
                }
            } finally {
                scanning.value = false
            }
        }
    }

    /** Long-press sulla mappa: candidato manuale, selezionato subito. */
    fun onMapLongPress(point: GeoPoint) {
        viewModelScope.launch {
            val id = repository.registerCandidates(listOf(point), SpotOrigin.MANUAL).first()
            pinnedIds.update { it + id }
            select(id, point)
            repository.refreshSpot(id)?.let { r ->
                if (r.errors.isNotEmpty()) {
                    _events.send(
                        MapEvent.RefreshIssues(
                            RefreshReport(1, 0, if (r.anyData) 1 else 0, if (r.anyData) 0 else 1, r.errors)
                        )
                    )
                }
            }
        }
    }

    fun onSpotClick(id: String) {
        val spot = uiState.value.allSpots.firstOrNull { it.id == id } ?: return
        select(id, spot.point)
    }

    fun onDismissDetail() {
        parkingJob?.cancel()
        selectedId.value = null
        parkings.value = ParkingUiState.Idle
    }

    fun onToggleFavorite(id: String) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    fun onToggleGoodFilter() {
        onlyGood.update { !it }
        if (onlyGood.value && uiState.value.allSpots.isNotEmpty() && uiState.value.allSpots.none(filter::matches)) {
            viewModelScope.launch { _events.send(MapEvent.FilterHidesEverything) }
        }
    }

    /** Refresh forzato dello spot selezionato (ignora TTL) + parcheggi. */
    fun onRefreshSelected() {
        val spot = uiState.value.selected ?: return
        viewModelScope.launch {
            val r = repository.refreshSpot(spot.id, force = true)
            if (r != null && r.errors.isNotEmpty()) {
                _events.send(MapEvent.RefreshIssues(RefreshReport(1, 0, 1, 0, r.errors)))
            }
        }
        loadParkings(spot.id, spot.point, force = true)
    }

    fun onRetryParkings() {
        uiState.value.selected?.let { loadParkings(it.id, it.point, force = true) }
    }

    // ---------------------------------------------------------------------------------- ricerca

    fun onSearchQueryChange(q: String) = search.update { it.copy(query = q, error = null) }

    fun onSearchSubmit() {
        val q = search.value.query
        viewModelScope.launch {
            search.update { it.copy(isSearching = true, error = null) }
            when (val r = repository.search(q, Locale.getDefault().toLanguageTag())) {
                is Fetched.Fresh -> search.update { it.copy(results = r.value, isSearching = false) }
                is Fetched.Cached -> search.update { it.copy(results = r.value, isSearching = false, error = r.error) }
                is Fetched.Failed -> search.update { it.copy(results = emptyList(), isSearching = false, error = r.error) }
            }
        }
    }

    fun onSearchClear() = search.update { SearchUiState() }

    /** Scelta di un risultato: centra la mappa, crea lo spot nel punto e scansiona i dintorni. */
    fun onSearchResultChosen(place: PlaceResult) {
        search.update { SearchUiState(query = place.title) }
        viewModelScope.launch {
            _events.send(MapEvent.MoveCamera(place.point, zoom = 10.5))
            val id = repository.registerCandidates(listOf(place.point), SpotOrigin.SEARCH, name = place.title).first()
            pinnedIds.update { it + id }
            select(id, place.point)
            repository.refreshSpot(id)
        }
        onScanArea(place.point)
    }

    // --------------------------------------------------------------------------------- interni

    private fun select(id: String, point: GeoPoint) {
        selectedId.value = id
        loadParkings(id, point, force = false)
    }

    private fun loadParkings(id: String, point: GeoPoint, force: Boolean) {
        parkingJob?.cancel() // una nuova selezione annulla la query Overpass precedente
        parkingJob = viewModelScope.launch {
            parkings.value = ParkingUiState.Loading
            val radius = settings.value.parkingRadiusMeters
            parkings.value = when (val r = repository.nearbyParkings(id, point, radius, force)) {
                is Fetched.Fresh -> ParkingUiState.Loaded(r.value)
                is Fetched.Cached -> ParkingUiState.Loaded(r.value, fromCacheError = r.error)
                is Fetched.Failed -> ParkingUiState.Error(r.error)
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AstroScoutApp
                MapViewModel(app.container.repository, app.container.settings, app.container.isSkyQualitySimulated)
            }
        }
    }
}
