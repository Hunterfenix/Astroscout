package com.astroscout.data.sky

import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.SkyQuality

/**
 * Astrazione sulla fonte dell'inquinamento luminoso. Il repository dipende solo da questa
 * interfaccia: cambiare provider (DarkSkySites, raster VIIRS offline, altro servizio) non tocca
 * né il repository né la UI.
 *
 * Le implementazioni lanciano eccezioni (IOException, HttpException, SerializationException…):
 * è il repository a mapparle in [com.astroscout.domain.model.DataError] e a ripiegare sulla cache.
 */
interface SkyQualitySource {
    suspend fun fetch(point: GeoPoint): SkyQuality
}
