package com.astroscout.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.astroscout.domain.model.SkyQualitySourceType
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotDao {

    /** Candidati correnti + tutti i preferiti (che restano sempre sulla mappa). */
    @Query("SELECT * FROM spots WHERE id IN (:ids) OR isFavorite = 1")
    fun observe(ids: List<String>): Flow<List<SpotEntity>>

    @Query("SELECT * FROM spots WHERE id = :id")
    suspend fun get(id: String): SpotEntity?

    @Query("SELECT * FROM spots WHERE isFavorite = 1")
    suspend fun favorites(): List<SpotEntity>

    /** IGNORE: se lo spot esiste già non sovrascriviamo dati validi né il flag preferito. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(spots: List<SpotEntity>)

    @Query("UPDATE spots SET lastViewedAt = :at WHERE id IN (:ids)")
    suspend fun touch(ids: List<String>, at: Long)

    @Query("UPDATE spots SET sqm = :sqm, bortle = :bortle, skySource = :source, skyUpdatedAt = :at WHERE id = :id")
    suspend fun updateSky(id: String, sqm: Double?, bortle: Int, source: SkyQualitySourceType, at: Long)

    @Query("UPDATE spots SET name = :name WHERE id = :id AND name IS NULL")
    suspend fun setNameIfMissing(id: String, name: String)

    @Query("UPDATE spots SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("DELETE FROM spots WHERE isFavorite = 0 AND lastViewedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int
}

@Dao
interface ForecastDao {
    @Query("SELECT * FROM forecasts WHERE cellId IN (:cellIds)")
    fun observe(cellIds: List<String>): Flow<List<ForecastEntity>>

    @Query("SELECT * FROM forecasts WHERE cellId = :cellId")
    suspend fun get(cellId: String): ForecastEntity?

    @Upsert
    suspend fun upsert(entity: ForecastEntity)

    @Query("DELETE FROM forecasts WHERE fetchedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int
}

@Dao
interface ParkingDao {
    @Query("SELECT * FROM parkings WHERE `key` = :key")
    suspend fun get(key: String): ParkingCacheEntity?

    @Upsert
    suspend fun upsert(entity: ParkingCacheEntity)
}

@Dao
interface MeteoDao {
    @Query("SELECT * FROM meteo WHERE cellId IN (:cellIds)")
    fun observe(cellIds: List<String>): Flow<List<MeteoEntity>>

    @Query("SELECT * FROM meteo WHERE cellId = :cellId")
    suspend fun get(cellId: String): MeteoEntity?

    @Upsert
    suspend fun upsert(entity: MeteoEntity)

    @Query("DELETE FROM meteo WHERE fetchedAt < :before")
    suspend fun pruneOlderThan(before: Long): Int
}

@Dao
interface ElevationDao {
    @Query("SELECT * FROM elevations WHERE pointId IN (:ids)")
    fun observe(ids: List<String>): Flow<List<ElevationEntity>>

    @Query("SELECT * FROM elevations WHERE pointId IN (:ids)")
    suspend fun get(ids: List<String>): List<ElevationEntity>

    @Upsert
    suspend fun upsertAll(entities: List<ElevationEntity>)
}
