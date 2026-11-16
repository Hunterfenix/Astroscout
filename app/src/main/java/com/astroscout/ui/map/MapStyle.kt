package com.astroscout.ui.map

import com.astroscout.BuildConfig
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Style MapLibre (spec v8) costruito a runtime: una sola sorgente raster OSM.
 *
 * - Nessuna chiave richiesta; l'URL dei tile è configurabile (`map.tileUrl`).
 * - L'attribuzione è nella sorgente: MapLibre la mostra nel pulsante (i) della mappa.
 * - In tema scuro i tile vengono desaturati e scuriti via paint properties `raster-*`
 *   (elaborazione GPU, nessun tile alternativo da scaricare).
 * - Niente `glyphs`: i marker non usano testo nei layer, i badge sono bitmap (vedi MapBadges).
 */
object MapStyle {
    fun json(dark: Boolean): String = buildJsonObject {
        put("version", 8)
        put("name", "AstroScout OSM")
        putJsonObject("sources") {
            putJsonObject("osm") {
                put("type", "raster")
                putJsonArray("tiles") { add(BuildConfig.MAP_TILE_URL) }
                put("tileSize", 256)
                put("maxzoom", 19)
                put("attribution", "© <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors")
            }
        }
        putJsonArray("layers") {
            add(buildJsonObject {
                put("id", "osm")
                put("type", "raster")
                put("source", "osm")
                if (dark) {
                    putJsonObject("paint") {
                        put("raster-saturation", -0.6)
                        put("raster-brightness-max", 0.55)
                        put("raster-contrast", 0.1)
                    }
                }
            })
        }
    }.toString()
}
