package com.astroscout.data.sky

import com.astroscout.data.remote.api.DarkSkySitesApi
import com.astroscout.data.remote.dto.LightPollutionResponse
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.scoring.Bortle
import kotlinx.serialization.SerializationException

class DarkSkySitesSource(
    private val api: DarkSkySitesApi,
    private val path: String,
    private val apiKey: String?,
) : SkyQualitySource {

    override suspend fun fetch(point: GeoPoint): SkyQuality {
        val raw = api.skyQuality(path.trimStart('/'), point.lat, point.lon, apiKey?.takeIf { it.isNotBlank() })
        val parsed = LightPollutionResponse.fromJson(raw)
        if (parsed.isEmpty) throw SerializationException("DarkSkySites: nessun campo sqm/bortle in $raw")
        // Se manca il Bortle lo deriviamo dall'SQM; se manca l'SQM teniamo null (lo score usa il Bortle).
        val bortle = parsed.bortle ?: Bortle.fromSqm(parsed.sqm!!)
        return SkyQuality(sqm = parsed.sqm, bortle = bortle, source = SkyQualitySourceType.DARKSKYSITES)
    }
}
