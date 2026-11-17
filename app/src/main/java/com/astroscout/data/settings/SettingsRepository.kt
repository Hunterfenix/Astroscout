package com.astroscout.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astroscout.domain.model.ScoreWeights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val weights: ScoreWeights = ScoreWeights.DEFAULT,
    /** Lato della griglia di candidati (dispari: 3, 5, 7). */
    val gridSize: Int = 5,
    val gridStepKm: Double = 5.0,
    val parkingRadiusMeters: Int = 3_000,
    /** Notifica "buona notte" per i preferiti. */
    val notifyGoodNights: Boolean = false,
    val notifyThreshold: Int = 70,
    /** Overlay inquinamento luminoso sulla mappa (persistito tra un avvio e l'altro). */
    val showLightPollutionOverlay: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferenze utente su DataStore (asincrono, transazionale, basato su Flow: niente letture
 * bloccanti sul main thread come con SharedPreferences).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val W_LP = doublePreferencesKey("w_light_pollution")
        val W_CLOUD = doublePreferencesKey("w_cloud")
        val W_SEEING = doublePreferencesKey("w_seeing")
        val W_TRANSP = doublePreferencesKey("w_transparency")
        val W_MOON = doublePreferencesKey("w_moon")
        val W_ALT = doublePreferencesKey("w_altitude")
        val NOTIFY = booleanPreferencesKey("notify_good_nights")
        val NOTIFY_THRESHOLD = intPreferencesKey("notify_threshold")
        val LAST_NOTIFIED_NIGHT = stringPreferencesKey("last_notified_night")
        val OVERLAY = booleanPreferencesKey("lp_overlay")
        val GRID_SIZE = intPreferencesKey("grid_size")
        val GRID_STEP = doublePreferencesKey("grid_step_km")
        val PARKING_RADIUS = intPreferencesKey("parking_radius_m")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                weights = ScoreWeights(
                    lightPollution = p[Keys.W_LP] ?: d.weights.lightPollution,
                    cloudCover = p[Keys.W_CLOUD] ?: d.weights.cloudCover,
                    seeing = p[Keys.W_SEEING] ?: d.weights.seeing,
                    transparency = p[Keys.W_TRANSP] ?: d.weights.transparency,
                    moon = p[Keys.W_MOON] ?: d.weights.moon,
                    altitude = p[Keys.W_ALT] ?: d.weights.altitude,
                ),
                gridSize = p[Keys.GRID_SIZE] ?: d.gridSize,
                gridStepKm = p[Keys.GRID_STEP] ?: d.gridStepKm,
                parkingRadiusMeters = p[Keys.PARKING_RADIUS] ?: d.parkingRadiusMeters,
                notifyGoodNights = p[Keys.NOTIFY] ?: d.notifyGoodNights,
                notifyThreshold = p[Keys.NOTIFY_THRESHOLD] ?: d.notifyThreshold,
                showLightPollutionOverlay = p[Keys.OVERLAY] ?: d.showLightPollutionOverlay,
            )
        }
        .distinctUntilChanged()

    suspend fun setWeights(w: ScoreWeights) {
        context.dataStore.edit {
            it[Keys.W_LP] = w.lightPollution
            it[Keys.W_CLOUD] = w.cloudCover
            it[Keys.W_SEEING] = w.seeing
            it[Keys.W_TRANSP] = w.transparency
            it[Keys.W_MOON] = w.moon
            it[Keys.W_ALT] = w.altitude
        }
    }

    suspend fun setGrid(size: Int, stepKm: Double) {
        context.dataStore.edit {
            it[Keys.GRID_SIZE] = size.coerceIn(1, 9).let { s -> if (s % 2 == 0) s + 1 else s }
            it[Keys.GRID_STEP] = stepKm.coerceIn(1.0, 20.0)
        }
    }

    suspend fun setParkingRadius(meters: Int) {
        context.dataStore.edit { it[Keys.PARKING_RADIUS] = meters.coerceIn(500, 10_000) }
    }

    suspend fun setNotifications(enabled: Boolean, threshold: Int) {
        context.dataStore.edit {
            it[Keys.NOTIFY] = enabled
            it[Keys.NOTIFY_THRESHOLD] = threshold.coerceIn(30, 95)
        }
    }

    suspend fun setOverlay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OVERLAY] = enabled }
    }

    /** Data (yyyy-MM-dd) dell'ultima notte notificata: al massimo una notifica per notte. */
    suspend fun lastNotifiedNight(): String? = context.dataStore.data.first()[Keys.LAST_NOTIFIED_NIGHT]

    suspend fun setLastNotifiedNight(date: String) {
        context.dataStore.edit { it[Keys.LAST_NOTIFIED_NIGHT] = date }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
