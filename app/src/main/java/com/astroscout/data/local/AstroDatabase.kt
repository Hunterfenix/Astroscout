package com.astroscout.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SpotEntity::class, ForecastEntity::class, ParkingCacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AstroDatabase : RoomDatabase() {
    abstract fun spotDao(): SpotDao
    abstract fun forecastDao(): ForecastDao
    abstract fun parkingDao(): ParkingDao

    companion object {
        fun build(context: Context): AstroDatabase =
            Room.databaseBuilder(context, AstroDatabase::class.java, "astroscout.db")
                // È solo cache (i preferiti sono l'unico dato "utente"): finché siamo alla v1 va bene
                // ricreare il DB. Dalla prima release pubblica: migrazioni esplicite + schema esportato.
                .fallbackToDestructiveMigration() // Room 2.6: senza argomenti (dropAllTables arriva in 2.7)
                .build()
    }
}
