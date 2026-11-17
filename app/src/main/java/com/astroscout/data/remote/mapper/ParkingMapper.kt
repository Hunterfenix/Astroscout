package com.astroscout.data.remote.mapper

import com.astroscout.data.remote.dto.OverpassResponse
import com.astroscout.domain.geo.Geo
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.Parking
import java.util.Locale

object ParkingMapper {

    private val PRIVATE_ACCESS = setOf("private", "no")

    /**
     * Costruisce la query Overpass QL per i parcheggi entro [radiusMeters] da [center].
     *
     * - `nwr` = node + way + relation (i parcheggi sono spesso aree, cioè way).
     * - I parcheggi privati (`access=private|no`) si scartano sul telefono: un filtro regex lato
     *   server rallenta la query senza ridurre molto la risposta.
     * - `qt` ordina per quadtile invece che per id: più veloce lato server (niente sort globale).
     * - `out center tags` restituisce il baricentro per way/relation e solo i tag (niente geometria
     *   completa: payload molto più piccolo).
     * - `[timeout:20]` rende esplicito il limite lato server, così un mirror lento risponde con
     *   `remark` invece di restare appeso.
     */
    fun buildQuery(center: GeoPoint, radiusMeters: Int): String {
        val lat = String.format(Locale.ROOT, "%.6f", center.lat)
        val lon = String.format(Locale.ROOT, "%.6f", center.lon)
        return """
            [out:json][timeout:20];
            nwr["amenity"="parking"](around:$radiusMeters,$lat,$lon);
            out center tags qt;
        """.trimIndent()
    }

    fun toParkings(response: OverpassResponse, from: GeoPoint): List<Parking> =
        response.elements.mapNotNull { e ->
            if (e.tags["access"]?.lowercase() in PRIVATE_ACCESS) return@mapNotNull null
            val lat = e.latitude ?: return@mapNotNull null
            val lon = e.longitude ?: return@mapNotNull null
            val p = GeoPoint(lat, lon)
            Parking(
                osmId = e.id,
                osmType = e.type,
                name = e.tags["name"]?.takeIf { it.isNotBlank() },
                point = p,
                distanceMeters = Geo.distanceMeters(from, p),
                // capacity in OSM è testo libero: "50", "~30", "50;10"... prendiamo il primo intero.
                capacity = e.tags["capacity"]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() },
                fee = e.tags["fee"]?.takeIf { it.isNotBlank() },
                access = e.tags["access"],
                surface = e.tags["surface"],
            )
        }
            .distinctBy { it.osmType to it.osmId }
            .sortedBy { it.distanceMeters }
}
