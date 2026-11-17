package com.astroscout.domain.model

/**
 * Errori di dominio, indipendenti da Retrofit/OkHttp: la UI li traduce in messaggi localizzati.
 */
sealed interface DataError {
    val service: Service

    enum class Service { SKY_QUALITY, FORECAST, WEATHER, PARKING, GEOCODING }

    data class NoConnection(override val service: Service) : DataError
    data class Timeout(override val service: Service) : DataError
    /** HTTP 429, o Overpass 504 "server too busy". */
    data class RateLimited(override val service: Service) : DataError
    data class Http(override val service: Service, val code: Int) : DataError
    /** Il punto è fuori dall'area coperta dalla fonte dati (es. atlante oltre 85° N). */
    data class OutOfCoverage(override val service: Service) : DataError
    data class InvalidResponse(override val service: Service, val detail: String?) : DataError
    data class Unknown(override val service: Service, val detail: String?) : DataError
}

/** Esito di un'operazione con fallback su cache. */
sealed interface Fetched<out T> {
    val value: T?

    /** Dato appena scaricato. */
    data class Fresh<T>(override val value: T) : Fetched<T>
    /** Rete fallita, ma c'era un valore in cache (eventualmente scaduto). */
    data class Cached<T>(override val value: T, val error: DataError) : Fetched<T>
    /** Nessun dato disponibile. */
    data class Failed(val error: DataError) : Fetched<Nothing> { override val value: Nothing? = null }
}
