package com.astroscout.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.astroscout.AstroScoutApp
import java.util.concurrent.TimeUnit

/**
 * Aggiornamento periodico in background dei **preferiti** (meteo + cielo, rispettando le TTL) e
 * pulizia della cache. Così aprendo l'app offline in montagna si trova il forecast più recente.
 *
 * - Periodo 6 h con flex 1 h: allineato agli aggiornamenti di 7Timer! e "batchabile" da Doze.
 * - Vincoli: rete connessa, batteria non scarica.
 * - `KEEP`: non riavvia il timer ad ogni avvio dell'app.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AstroScoutApp).container
        val repo = container.repository
        // Atlante: ricontrolla il manifest (internamente al massimo 1 volta/mese) e assicura che le
        // tile dei preferiti siano su disco, così funzionano offline. Errori qui non bloccano il resto.
        container.skyAtlas?.let { atlas ->
            runCatching { atlas.prefetch(repo.favoritePoints().map { it.lat to it.lon }) }
        }
        val report = repo.refreshFavorites()
        repo.prune()
        // Se tutti i preferiti sono falliti (rete instabile) WorkManager riprova con backoff esponenziale.
        return if (report.total > 0 && report.failed == report.total && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "astro-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
