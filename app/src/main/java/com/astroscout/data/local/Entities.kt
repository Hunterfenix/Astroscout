package com.astroscout.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.astroscout.domain.model.SkyQualitySourceType
import com.astroscout.domain.model.SpotOrigin

/**
 * Un punto candidato con l'**ultimo valore valido** di qualità del cielo.
 * Il meteo non sta qui: è condiviso per cella (vedi [ForecastEntity]).
 * Gli enum sono salvati come TEXT dal converter nativo di Room.
 */
@Entity(tableName = "spots", indices = [Index("isFavorite"), Index("lastViewedAt")])
data class SpotEntity(
    @PrimaryKey val id: String,
    val lat: Double,
    val lon: Double,
    val name: String? = null,
    val origin: SpotOrigin,
    val isFavorite: Boolean = false,
    val sqm: Double? = null,
    val bortle: Int? = null,
    val skySource: SkyQualitySourceType? = null,
    /** epoch millis dell'ultimo fetch riuscito della qualità del cielo. */
    val skyUpdatedAt: Long? = null,
    val createdAt: Long,
    /** usato per potare i candidati di griglia vecchi e non preferiti. */
    val lastViewedAt: Long,
)

/**
 * Forecast 7Timer! per una cella di ~0.1°. Si salva il JSON **grezzo** e non il riassunto notturno:
 * offline si può ricalcolare la finestra della notte corrente (es. alle 02:00) da dati di ieri.
 */
@Entity(tableName = "forecasts")
data class ForecastEntity(
    @PrimaryKey val cellId: String,
    val json: String,
    val fetchedAt: Long,
)

/** Lista parcheggi serializzata per (spot, raggio). */
@Entity(tableName = "parkings")
data class ParkingCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

/** Risposta Open-Meteo grezza per cella meteo (stessa cella 0.1° di [ForecastEntity]). */
@Entity(tableName = "meteo")
data class MeteoEntity(
    @PrimaryKey val cellId: String,
    val json: String,
    val fetchedAt: Long,
)

/**
 * Quota s.l.m. per punto (id = [com.astroscout.domain.model.GeoPoint.id], ~11 m).
 * La quota non cambia: nessuna scadenza. Usata per spot e parcheggi.
 */
@Entity(tableName = "elevations")
data class ElevationEntity(
    @PrimaryKey val pointId: String,
    val meters: Double,
)
