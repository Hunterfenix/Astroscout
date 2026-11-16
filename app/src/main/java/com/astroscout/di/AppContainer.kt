package com.astroscout.di

import android.content.Context
import com.astroscout.BuildConfig
import com.astroscout.data.local.AstroDatabase
import com.astroscout.data.remote.NetworkModule
import com.astroscout.data.repository.AstroRepository
import com.astroscout.data.settings.SettingsRepository
import com.astroscout.data.sky.DarkSkySitesSource
import com.astroscout.data.sky.SimulatedSkyQualitySource
import com.astroscout.data.sky.SkyQualitySource

/**
 * Service locator manuale: il grafo è piccolo (≈10 oggetti), quindi Hilt aggiungerebbe annotation
 * processing e tempi di build senza un vero beneficio. Se il progetto cresce, ogni `val` qui è già
 * un candidato naturale per un `@Provides` in un modulo Hilt.
 *
 * Tutto è `by lazy`: l'avvio dell'app non paga Room/OkHttp finché non servono.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val network by lazy { NetworkModule(appContext.cacheDir) }
    val database by lazy { AstroDatabase.build(appContext) }
    val settings by lazy { SettingsRepository(appContext) }

    /** true se si sta usando la sorgente simulata: la UI lo mostra chiaramente. */
    val isSkyQualitySimulated: Boolean =
        BuildConfig.DARKSKY_USE_SIMULATED || BuildConfig.DARKSKY_API_KEY.isBlank()

    val skyQualitySource: SkyQualitySource by lazy {
        if (isSkyQualitySimulated) SimulatedSkyQualitySource()
        else DarkSkySitesSource(network.darkSky, BuildConfig.DARKSKY_PATH, BuildConfig.DARKSKY_API_KEY)
    }

    val repository by lazy {
        AstroRepository(
            db = database,
            skySource = skyQualitySource,
            sevenTimer = network.sevenTimer,
            overpass = network.overpass,
            nominatim = network.nominatim,
            json = network.json,
            settings = settings,
        )
    }
}
