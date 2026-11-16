package com.astroscout.ui.map

import android.graphics.RectF
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.Spot
import com.astroscout.domain.scoring.Bortle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Stato "imperativo" della mappa esposto a Compose (stesso pattern di `CameraPositionState` di
 * maps-compose): permette allo schermo di muovere la camera o leggerne il centro senza che il
 * ViewModel conosca MapLibre.
 */
@Stable
class SpotMapState {
    internal var map: MapLibreMap? by mutableStateOf(null)

    fun animateTo(point: GeoPoint, zoom: Double, durationMs: Int = 900) {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lon), zoom), durationMs)
    }

    fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }
}

@Composable
fun rememberSpotMapState(): SpotMapState = remember { SpotMapState() }

private const val SOURCE_SPOTS = "spots"
private const val LAYER_HALO = "spots-halo"
private const val LAYER_CIRCLE = "spots-circle"
private const val LAYER_SELECTED = "spots-selected"
private const val LAYER_BADGE = "spots-badge"

/**
 * MapLibre in Compose via [AndroidView].
 *
 * **Come sono disegnati i punti**: una sola `GeoJsonSource` con una Feature per spot e proprietà
 * (`color`, `halo`, `badge`, `id`); più layer di stile la leggono con *data-driven styling*.
 * Rispetto a Marker/Annotation (deprecati) è tutto renderizzato su GPU in un unico draw call per
 * layer, e aggiornare N spot = un solo `setGeoJson`.
 *
 * Ordine dei layer (dal basso): alone verde (solo Bortle ≤ 4) → cerchio colorato per Bortle →
 * anello di selezione → badge meteo+score.
 */
@Composable
fun SpotMap(
    spots: List<Spot>,
    selectedId: String?,
    state: SpotMapState,
    initialCamera: Pair<GeoPoint, Double>,
    darkStyle: Boolean,
    onSpotClick: (String) -> Unit,
    onLongPress: (GeoPoint) -> Unit,
    onCameraIdle: (GeoPoint, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Le lambda possono cambiare a ogni ricomposizione; i listener MapLibre sono registrati una volta.
    val currentOnSpotClick by rememberUpdatedState(onSpotClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)

    var style by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(initialCamera.first.lat, initialCamera.first.lon))
                    .zoom(initialCamera.second)
                    .build()
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false

                map.setStyle(Style.Builder().fromJson(MapStyle.json(darkStyle))) { loaded ->
                    installLayers(loaded)
                    style = loaded
                }

                map.addOnMapClickListener { latLng ->
                    val p = map.projection.toScreenLocation(latLng)
                    val r = 14f * density // area di tocco generosa (≈ 28 dp)
                    val hit = map.queryRenderedFeatures(
                        RectF(p.x - r, p.y - r, p.x + r, p.y + r), LAYER_BADGE, LAYER_CIRCLE, LAYER_HALO,
                    ).firstNotNullOfOrNull { it.getStringProperty("id") }
                    if (hit != null) currentOnSpotClick(hit)
                    hit != null // true = evento consumato
                }
                map.addOnMapLongClickListener { latLng ->
                    currentOnLongPress(GeoPoint(latLng.latitude, latLng.longitude))
                    true
                }
                map.addOnCameraIdleListener {
                    map.cameraPosition.target?.let { t ->
                        currentOnCameraIdle(GeoPoint(t.latitude, t.longitude), map.cameraPosition.zoom)
                    }
                }
                state.map = map
            }
        }
    }

    // Inoltra il lifecycle dell'host alla MapView (obbligatorio: gestisce il GL surface e i thread).
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> { mapView.onStop(); started = false }
                else -> Unit // ON_DESTROY gestito in onDispose
            }
        }
        lifecycle.addObserver(observer) // riceve subito gli eventi fino allo stato corrente
        onDispose {
            lifecycle.removeObserver(observer)
            // Il composable può uscire mentre l'Activity è RESUMED (navigazione): chiudiamo in ordine.
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            state.map = null
            mapView.onDestroy()
        }
    }

    // Aggiorna la sorgente quando cambiano gli spot o lo style è pronto.
    LaunchedEffect(style, spots) {
        val s = style ?: return@LaunchedEffect
        spots.forEach { spot ->
            val id = MapBadges.badgeId(spot)
            if (s.getImage(id) == null) s.addImage(id, MapBadges.render(spot, density))
        }
        s.getSourceAs<GeoJsonSource>(SOURCE_SPOTS)?.setGeoJson(FeatureCollection.fromFeatures(spots.map(::toFeature)))
    }

    LaunchedEffect(style, selectedId) {
        val s = style ?: return@LaunchedEffect
        (s.getLayer(LAYER_SELECTED) as? CircleLayer)?.setFilter(
            Expression.eq(Expression.get("id"), Expression.literal(selectedId ?: "__none__"))
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun toFeature(spot: Spot): Feature =
    Feature.fromGeometry(Point.fromLngLat(spot.point.lon, spot.point.lat)).apply {
        addStringProperty("id", spot.id)
        addStringProperty("color", MapBadges.colorHex(spot))
        addBooleanProperty("halo", spot.sky?.bortle?.let(Bortle::deservesHalo) == true)
        addBooleanProperty("favorite", spot.isFavorite)
        addStringProperty("badge", MapBadges.badgeId(spot))
    }

private fun installLayers(style: Style) {
    style.addSource(GeoJsonSource(SOURCE_SPOTS, FeatureCollection.fromFeatures(emptyList())))

    // Alone: cerchio grande sfumato solo per Bortle ≤ 4.
    style.addLayer(
        CircleLayer(LAYER_HALO, SOURCE_SPOTS)
            .withFilter(Expression.eq(Expression.get("halo"), true))
            .withProperties(
                PropertyFactory.circleRadius(
                    // Raggio che cresce con lo zoom (interpolazione lineare tra stop).
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(5, 10f), Expression.stop(12, 30f),
                    )
                ),
                PropertyFactory.circleColor("#4CAF50"),
                PropertyFactory.circleOpacity(0.35f),
                PropertyFactory.circleBlur(0.8f),
            )
    )

    style.addLayer(
        CircleLayer(LAYER_CIRCLE, SOURCE_SPOTS).withProperties(
            PropertyFactory.circleRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(5, 4f), Expression.stop(12, 10f),
                )
            ),
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            // Preferiti: bordo dorato, altrimenti bianco.
            PropertyFactory.circleStrokeColor(
                Expression.switchCase(Expression.eq(Expression.get("favorite"), true), Expression.color(0xFFFFC107.toInt()), Expression.color(0xFFFFFFFF.toInt()))
            ),
            PropertyFactory.circleStrokeWidth(2f),
        )
    )

    style.addLayer(
        CircleLayer(LAYER_SELECTED, SOURCE_SPOTS)
            .withFilter(Expression.eq(Expression.get("id"), Expression.literal("__none__")))
            .withProperties(
                PropertyFactory.circleRadius(16f),
                PropertyFactory.circleColor("rgba(0,0,0,0)"),
                PropertyFactory.circleStrokeColor("#8C9EFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
    )

    style.addLayer(
        SymbolLayer(LAYER_BADGE, SOURCE_SPOTS).withProperties(
            PropertyFactory.iconImage(Expression.get("badge")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),
            PropertyFactory.iconOffset(arrayOf(6f, -6f)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
    )
}
