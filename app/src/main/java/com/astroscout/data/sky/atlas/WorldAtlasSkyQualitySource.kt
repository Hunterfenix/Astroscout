package com.astroscout.data.sky.atlas

import com.astroscout.data.sky.SkyQualitySource
import com.astroscout.domain.model.GeoPoint
import com.astroscout.domain.model.SkyQuality
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.scoring.Bortle
import kotlin.math.roundToLong

/**
 * Qualità del cielo dal *World Atlas of Artificial Night Sky Brightness* (Falchi et al. 2016),
 * letta dalle tile scaricate su richiesta da [SkyTileStore].
 *
 * Il valore è la brillanza **allo zenit** modellata (luce artificiale propagata in atmosfera da
 * dati satellitari VIIRS 2014, calibrata con misure SQM a terra), più il fondo naturale.
 */
class WorldAtlasSkyQualitySource(private val store: SkyTileStore) : SkyQualitySource {

    override val type = SkyQualitySourceType.WORLD_ATLAS_2015

    override suspend fun fetch(point: GeoPoint): SkyQuality {
        val sample = store.sample(point.lat, point.lon)
        val sqm = (sample.sqm * 100).roundToLong() / 100.0
        return SkyQuality(sqm = sqm, bortle = Bortle.fromSqm(sqm), source = type)
    }
}
