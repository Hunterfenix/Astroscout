package com.astroscout.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SpotEntity::class, ForecastEntity::class, ParkingCacheEntity::class,
        MeteoEntity::class, ElevationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AstroDatabase : RoomDatabase() {
    abstract fun spotDao(): SpotDao
    abstract fun forecastDao(): ForecastDao
    abstract fun parkingDao(): ParkingDao
    abstract fun meteoDao(): MeteoDao
    abstract fun elevationDao(): ElevationDao

    companion object {
        /**
         * v1 → v2: aggiunge le tabelle `meteo` (Open-Meteo) ed `elevations`. Solo CREATE TABLE:
         * nessun dato esistente viene toccato, quindi preferiti e cache sopravvivono all'update.
         * L'SQL deve coincidere ESATTAMENTE con quello che Room genera dalle entity (Room lo
         * verifica all'apertura e lancia IllegalStateException se lo schema non torna).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `meteo` (`cellId` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`cellId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `elevations` (`pointId` TEXT NOT NULL, `meters` REAL NOT NULL, " +
                        "PRIMARY KEY(`pointId`))"
                )
            }
        }

        fun build(context: Context): AstroDatabase =
            Room.databaseBuilder(context, AstroDatabase::class.java, "astroscout.db")
                .addMigrations(MIGRATION_1_2)
                // Rete di sicurezza solo per salti di versione senza migrazione (mai per 1→2).
                .fallbackToDestructiveMigration()
                .build()
    }
}
