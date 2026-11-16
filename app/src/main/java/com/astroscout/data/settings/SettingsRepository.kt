package com.astroscout.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.astroscout.domain.model.ScoreWeights
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val weights: ScoreWeights = ScoreWeights.DEFAULT,
    /** Lato della griglia di candidati (dispari: 3, 5, 7). */
    val gridSize: Int = 5,
    val gridStepKm: Double = 5.0,
    val parkingRadiusMeters: Int = 3_000,
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
                ),
                gridSize = p[Keys.GRID_SIZE] ?: d.gridSize,
                gridStepKm = p[Keys.GRID_STEP] ?: d.gridStepKm,
                parkingRadiusMeters = p[Keys.PARKING_RADIUS] ?: d.parkingRadiusMeters,
            )
        }
        .distinctUntilChanged()

    suspend fun setWeights(w: ScoreWeights) {
        context.dataStore.edit {
            it[Keys.W_LP] = w.lightPollution
            it[Keys.W_CLOUD] = w.cloudCover
            it[Keys.W_SEEING] = w.seeing
            it[Keys.W_TRANSP] = w.transparency
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

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }
}
