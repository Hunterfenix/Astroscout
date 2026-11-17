package com.astroscout.data.remote

import com.astroscout.BuildConfig
import com.astroscout.data.remote.api.DarkSkySitesApi
import com.astroscout.data.remote.api.NominatimApi
import com.astroscout.data.remote.api.OpenMeteoApi
import com.astroscout.data.remote.api.OverpassApi
import com.astroscout.data.remote.api.SevenTimerApi
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Costruzione di OkHttp/Retrofit senza framework DI (vedi [com.astroscout.di.AppContainer]).
 *
 * Scelte:
 * - **Un solo OkHttpClient** condiviso (pool di connessioni e thread condivisi), specializzato con
 *   `newBuilder()` dove servono timeout diversi: Overpass può impiegare >20 s.
 * - **Cache HTTP su disco** (10 MB): aiuta con risposte ripetute; la cache "semantica" (per punto,
 *   con TTL e fallback offline) resta comunque in Room.
 * - **Json tollerante**: `ignoreUnknownKeys` (le API aggiungono campi), `coerceInputValues`
 *   (null su campi non-null → default), `isLenient` (7Timer! non è sempre JSON rigoroso).
 */
class NetworkModule(cacheDir: File) {

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val jsonConverter = json.asConverterFactory("application/json".toMediaType())

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cache(Cache(File(cacheDir, "http"), 10L * 1024 * 1024))
        .addInterceptor(UserAgentInterceptor(BuildConfig.HTTP_USER_AGENT))
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
        }
        .build()

    private fun retrofit(baseUrl: String, client: OkHttpClient = okHttp): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl.ensureTrailingSlash())
            .client(client)
            .addConverterFactory(jsonConverter)
            .build()

    val sevenTimer: SevenTimerApi = retrofit(
        BuildConfig.SEVENTIMER_BASE_URL,
        okHttp.newBuilder().addInterceptor(HttpsFallbackInterceptor(setOf("7timer.info"))).build(),
    ).create(SevenTimerApi::class.java)

    val darkSky: DarkSkySitesApi = retrofit(BuildConfig.DARKSKY_BASE_URL).create(DarkSkySitesApi::class.java)

    val overpass: OverpassApi = retrofit(
        OVERPASS_ENDPOINTS.first(),
        okHttp.newBuilder().readTimeout(35, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
    ).create(OverpassApi::class.java)

    val openMeteo: OpenMeteoApi = retrofit("https://api.open-meteo.com/").create(OpenMeteoApi::class.java)

    val nominatim: NominatimApi = retrofit("https://nominatim.openstreetmap.org/").create(NominatimApi::class.java)

    companion object {
        /** Istanza principale + mirror pubblico: si passa al successivo su 429/504/timeout. */
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )

        private fun String.ensureTrailingSlash() = if (endsWith("/")) this else "$this/"
    }
}

/** Le policy OSM (Nominatim, Overpass, tile) chiedono un User-Agent che identifichi l'app. */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/**
 * Se una richiesta HTTPS verso uno degli [hosts] fallisce durante l'handshake TLS (certificato
 * scaduto/assente, come capita storicamente con 7Timer!), la riprova in HTTP.
 *
 * Perché un interceptor e non due base URL: il fallback è trasparente per Retrofit e per il
 * repository, e scatta **solo** per errori TLS, non per timeout o 5xx (che riproveremmo inutilmente).
 * Il cleartext è ammesso solo per quei domini da `network_security_config.xml`.
 */
class HttpsFallbackInterceptor(private val hosts: Set<String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val eligible = request.url.isHttps && hosts.any { request.url.host == it || request.url.host.endsWith(".$it") }
        return try {
            chain.proceed(request)
        } catch (e: SSLException) {
            if (!eligible) throw e
            val httpUrl = request.url.newBuilder().scheme("http").port(80).build()
            chain.proceed(request.newBuilder().url(httpUrl).build())
        }
    }
}
