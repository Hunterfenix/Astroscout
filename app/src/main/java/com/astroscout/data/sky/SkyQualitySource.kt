package com.astroscout.data.sky

import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType

/**
 * Astrazione sulla fonte dell'inquinamento luminoso. Il repository dipende solo da questa
 * interfaccia: cambiare provider (DarkSkySites, raster VIIRS offline, altro servizio) non tocca
 * né il repository né la UI.
 *
 * Le implementazioni lanciano eccezioni (IOException, HttpException, SerializationException…):
 * è il repository a mapparle in [com.astroscout.domain.model.DataError] e a ripiegare sulla cache.
 */
interface SkyQualitySource {
    /** Identifica la sorgente: se cambia, i valori in cache di un'altra sorgente vanno riscaricati. */
    val type: SkyQualitySourceType

    suspend fun fetch(point: GeoPoint): SkyQuality
}
