package com.astroscout.data

import com.astroscout.data.remote.OverpassClient
import com.astroscout.data.remote.api.OverpassApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

class OverpassClientTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun ok(id: Int) = MockResponse().setBody("""{"elements":[{"type":"node","id":$id,"lat":1.0,"lon":2.0}]}""")

    private fun api(base: MockWebServer): OverpassApi = Retrofit.Builder()
        .baseUrl(base.url("/"))
        .client(OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(OverpassApi::class.java)

    @Test
    fun `slow first mirror - the hedged second answers first`() = runBlocking {
        MockWebServer().use { slow ->
            MockWebServer().use { fast ->
                slow.enqueue(ok(1).setBodyDelay(4, TimeUnit.SECONDS))
                fast.enqueue(ok(2))
                val client = OverpassClient(api(slow), listOf(slow.url("/i").toString(), fast.url("/i").toString()), hedgeDelayMs = 300)
                val t0 = System.nanoTime()
                val r = client.query("q")
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertEquals(2L, r.elements.single().id)
                assertTrue("ci ha messo $ms ms", ms < 2_000)
            }
        }
    }

    @Test
    fun `error on first mirror - second starts immediately without waiting the hedge delay`() = runBlocking {
        MockWebServer().use { a ->
            MockWebServer().use { b ->
                a.enqueue(MockResponse().setResponseCode(429))
                b.enqueue(ok(3))
                val client = OverpassClient(api(a), listOf(a.url("/i").toString(), b.url("/i").toString()), hedgeDelayMs = 5_000)
                val t0 = System.nanoTime()
                assertEquals(3L, client.query("q").elements.single().id)
                assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
            }
        }
    }

    @Test
    fun `remark error counts as failure and all-failed rethrows the last error`() = runBlocking {
        MockWebServer().use { a ->
            MockWebServer().use { b ->
                a.enqueue(MockResponse().setBody("""{"elements":[],"remark":"runtime error: Query timed out"}"""))
                b.enqueue(MockResponse().setResponseCode(504))
                val client = OverpassClient(api(a), listOf(a.url("/i").toString(), b.url("/i").toString()), hedgeDelayMs = 5_000)
                val e = runCatching { client.query("q") }.exceptionOrNull()
                assertTrue("eccezione: $e", e is HttpException || e is OverpassClient.RemarkException)
            }
        }
    }
}
