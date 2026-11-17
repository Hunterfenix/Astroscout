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
import com.astroscout.data.sky.atlas.SkyTileStore
import com.astroscout.data.sky.atlas.WorldAtlasSkyQualitySource
import com.astroscout.domain.model.SkyQualitySourceType
import java.io.File

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

    /**
     * Sorgente della qualità del cielo, decisa da `sky.source` in local.properties.
     * `auto`: atlante se configurato → DarkSkySites se c'è la chiave → simulata.
     */
    val skySourceType: SkyQualitySourceType = when (BuildConfig.SKY_SOURCE.lowercase()) {
        "atlas" -> SkyQualitySourceType.WORLD_ATLAS_2015
        "darksky" -> SkyQualitySourceType.DARKSKYSITES
        "simulated" -> SkyQualitySourceType.SIMULATED
        else -> when {
            BuildConfig.DARKSKY_USE_SIMULATED -> SkyQualitySourceType.SIMULATED
            BuildConfig.SKY_ATLAS_BASE_URL.isNotBlank() -> SkyQualitySourceType.WORLD_ATLAS_2015
            BuildConfig.DARKSKY_API_KEY.isNotBlank() -> SkyQualitySourceType.DARKSKYSITES
            else -> SkyQualitySourceType.SIMULATED
        }
    }

    val isSkyQualitySimulated: Boolean get() = skySourceType == SkyQualitySourceType.SIMULATED

    /**
     * Tile dell'atlante in `filesDir` (non `cacheDir`): Android può svuotare la cache quando manca
     * spazio, e perdere le tile in montagna senza rete vanificherebbe l'uso offline.
     * Client OkHttp senza cache HTTP: le tile sono già persistite dallo store.
     */
    val skyAtlas: SkyTileStore? by lazy {
        if (skySourceType != SkyQualitySourceType.WORLD_ATLAS_2015) null
        else SkyTileStore(
            http = network.okHttp.newBuilder().cache(null).build(),
            baseUrl = BuildConfig.SKY_ATLAS_BASE_URL,
            rootDir = File(appContext.filesDir, "sky-atlas"),
            json = network.json,
        )
    }

    val skyQualitySource: SkyQualitySource by lazy {
        when (skySourceType) {
            SkyQualitySourceType.WORLD_ATLAS_2015 -> WorldAtlasSkyQualitySource(skyAtlas!!)
            SkyQualitySourceType.DARKSKYSITES ->
                DarkSkySitesSource(network.darkSky, BuildConfig.DARKSKY_PATH, BuildConfig.DARKSKY_API_KEY)
            SkyQualitySourceType.SIMULATED -> SimulatedSkyQualitySource()
        }
    }

    val repository by lazy {
        AstroRepository(
            db = database,
            skySource = skyQualitySource,
            sevenTimer = network.sevenTimer,
            overpassClient = network.overpassClient,
            nominatim = network.nominatim,
            openMeteo = network.openMeteo,
            json = network.json,
            settings = settings,
        )
    }
}
