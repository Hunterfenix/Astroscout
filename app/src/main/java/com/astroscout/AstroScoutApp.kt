package com.astroscout

import android.app.Application
import com.astroscout.di.AppContainer
import com.astroscout.work.RefreshWorker
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

class AstroScoutApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // MapLibre va inizializzato prima di creare qualsiasi MapView. Senza API key: usiamo
        // uno style JSON locale che punta ai tile OSM raster (vedi MapStyle).
        MapLibre.getInstance(this)
        // Le richieste di tile passano da un derivato del nostro OkHttp: User-Agent conforme alla tile
        // policy OSM e connection pool condiviso, ma SENZA la cache HTTP (MapLibre ha già la sua
        // ambient cache SQLite per i tile; non vogliamo che i tile sfrattino le risposte API).
        HttpRequestUtil.setOkHttpClient(container.network.okHttp.newBuilder().cache(null).build())

        RefreshWorker.schedule(this)
    }
}
