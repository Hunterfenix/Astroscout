package com.astroscout.data.repository

import com.astroscout.data.sky.atlas.HttpStatusException
import com.astroscout.data.sky.atlas.OutOfAtlasCoverageException
import com.astroscout.domain.model.DataError
import com.astroscout.domain.model.DataError.Service
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.cancellation.CancellationException

/** Traduce eccezioni di rete/parsing in [DataError]. L'ordine dei rami conta (sottoclassi prima). */
fun Throwable.toDataError(service: Service): DataError = when (this) {
    is SocketTimeoutException -> DataError.Timeout(service)
    // OkHttp lancia InterruptedIOException("timeout") quando scatta il callTimeout.
    is InterruptedIOException -> DataError.Timeout(service)
    is UnknownHostException, is ConnectException -> DataError.NoConnection(service)
    is SSLException -> DataError.Unknown(service, "TLS: $message")
    is HttpException -> when (code()) {
        429 -> DataError.RateLimited(service)
        // Overpass risponde 504 "Gateway Timeout" quando è sovraccarico: semanticamente è un rate-limit.
        503, 504 -> if (service == Service.PARKING) DataError.RateLimited(service) else DataError.Http(service, code())
        else -> DataError.Http(service, code())
    }
    is HttpStatusException -> if (code == 429) DataError.RateLimited(service) else DataError.Http(service, code)
    is OutOfAtlasCoverageException -> DataError.OutOfCoverage(service)
    is SerializationException, is IllegalArgumentException -> DataError.InvalidResponse(service, message)
    is IOException -> DataError.NoConnection(service)
    else -> DataError.Unknown(service, message)
}

/**
 * Come `runCatching`, ma **rilancia** [CancellationException]: inghiottirla romperebbe la
 * structured concurrency (un viewModelScope cancellato continuerebbe a "lavorare").
 */
inline fun <T> catching(service: Service, block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(DataErrorException(e.toDataError(service), e))
    }

class DataErrorException(val error: DataError, cause: Throwable) : Exception(cause.message, cause)

/** Errore di dominio di un [Result] prodotto da [catching]. */
fun Result<*>.dataErrorOrNull(service: Service): DataError? =
    exceptionOrNull()?.let { (it as? DataErrorException)?.error ?: it.toDataError(service) }
