package com.astroscout.data.remote.api

import com.astroscout.data.remote.dto.AstroForecastResponse
import com.astroscout.data.remote.dto.NominatimPlace
import com.astroscout.data.remote.dto.OverpassResponse
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

/** 7Timer! — `bin/api.pl?lon=..&lat=..&product=astro&output=json`. Nessuna chiave richiesta. */
interface SevenTimerApi {
    @GET("bin/api.pl")
    suspend fun astro(
        @Query("lon") lon: Double,
        @Query("lat") lat: Double,
        @Query("product") product: String = "astro",
        @Query("output") output: String = "json",
        /** 0 = non applicare la correzione per l'altitudine al suolo (default dell'API). */
        @Query("ac") altitudeCorrection: Int = 0,
        @Query("unit") unit: String = "metric",
    ): AstroForecastResponse
}

/**
 * DarkSkySites. Il path è passato come `@Url` (relativo alla base URL) perché non è documentato e
 * deve poter cambiare via `local.properties` senza ricompilare il codice. La risposta resta un
 * [JsonElement] grezzo: il parsing tollerante è in `LightPollutionResponse.fromJson`.
 * Retrofit omette i parametri `@Query` null, quindi senza chiave non viene inviato `key=`.
 */
interface DarkSkySitesApi {
    @GET
    suspend fun skyQuality(
        @Url path: String,
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("key") apiKey: String?,
    ): JsonElement
}

/**
 * Overpass API. Si usa POST form-encoded (`data=<query>`) invece di GET: le query QL contengono
 * caratteri (`[`, `"`, `~`) che in un URL richiedono escaping e possono superare i limiti di lunghezza.
 */
interface OverpassApi {
    @FormUrlEncoded
    @POST
    suspend fun interpreter(@Url endpoint: String, @Field("data") query: String): OverpassResponse
}

/**
 * Nominatim. Policy d'uso: max 1 richiesta/s, User-Agent identificativo, niente autocompletamento
 * a ogni tasto (cerchiamo solo all'invio), attribuzione OSM visibile.
 */
interface NominatimApi {
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 8,
        @Query("accept-language") language: String? = null,
    ): List<NominatimPlace>
}
