package com.astroscout.data.remote

import com.astroscout.data.remote.api.OverpassApi
import com.astroscout.data.remote.dto.OverpassResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InterruptedIOException

/**
 * Overpass con **hedged requests** sui mirror pubblici.
 *
 * Le istanze Overpass hanno latenze molto variabili (code, carico): aspettare il timeout del primo
 * server prima di provare il secondo porta a 45 s + 45 s nel caso peggiore. Strategia:
 *
 * ```
 * t=0     → richiesta al mirror 1
 * t=5 s   → se ancora nessuna risposta: richiesta IN PARALLELO al mirror 2
 * t=10 s  → idem mirror 3
 * il primo successo vince, le richieste ancora aperte vengono cancellate (OkHttp chiude il socket)
 * un errore (429, 504, timeout) fa partire SUBITO il mirror successivo, senza attendere il ritardo
 * ```
 * Con [hedgeDelayMs] = 5 s si aggiunge carico solo quando il primo server è già lento: è il
 * compromesso usato nei sistemi distribuiti (Dean & Barroso, "The Tail at Scale", 2013).
 */
class OverpassClient(
    private val api: OverpassApi,
    private val endpoints: List<String> = NetworkModule.OVERPASS_ENDPOINTS,
    private val hedgeDelayMs: Long = 5_000,
) {
    class RemarkException(remark: String) : InterruptedIOException("Overpass: $remark")

    suspend fun query(query: String): OverpassResponse = coroutineScope {
        require(endpoints.isNotEmpty())
        val results = Channel<Result<OverpassResponse>>(endpoints.size)
        val jobs = mutableListOf<Job>()
        var started = 0

        fun startNext() {
            val endpoint = endpoints[started++]
            jobs += launch {
                val r = try {
                    Result.success(
                        api.interpreter(endpoint, query).also { resp ->
                            // HTTP 200 con "remark: runtime error ..." = query fallita lato server.
                            if (resp.elements.isEmpty() && resp.remark?.contains("error", ignoreCase = true) == true) {
                                throw RemarkException(resp.remark)
                            }
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                results.send(r)
            }
        }

        startNext()
        var finished = 0
        var lastError: Throwable? = null
        while (finished < started) {
            val r = if (started < endpoints.size) withTimeoutOrNull(hedgeDelayMs) { results.receive() } else results.receive()
            if (r == null) {            // il più veloce tra quelli avviati è ancora lento: si aggiunge un mirror
                startNext()
                continue
            }
            finished++
            r.onSuccess { resp ->
                jobs.forEach { it.cancel() }
                return@coroutineScope resp
            }
            lastError = r.exceptionOrNull()
            if (finished == started && started < endpoints.size) startNext() // tutti falliti: subito il prossimo
        }
        throw lastError ?: IllegalStateException("Overpass: nessuna risposta")
    }
}
