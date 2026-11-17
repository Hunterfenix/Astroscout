package com.astroscout.data.sky

import com.astroscout.data.sky.atlas.OutOfAtlasCoverageException
import com.astroscout.data.sky.atlas.SkyTile
import com.astroscout.data.sky.atlas.SkyTileStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SkyAtlasTest {

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    /** Tile 5° con 600×600 px: SQM che cresce da ovest (18.0) a est (21.0). */
    private fun gradientTile(latS: Int = 45, lonW: Int = 5): ByteArray {
        val w = 600; val h = 600
        val data = ByteArray(w * h) { i ->
            val col = i % w
            val sqm = 18.0 + 3.0 * col / (w - 1)
            Math.round((sqm - 16.0) / 0.025).toInt().coerceIn(0, 254).toByte()
        }
        return SkyTile.encodeGzip(SkyTile(latS, lonW, 5, w, h, 16f, 0.025f, data), data)
    }

    @Test
    fun `artificial brightness to sqm`() {
        assertEquals(22.0, SkyTile.artificialToSqm(0.0), 0.005)
        // 10× il fondo naturale → 2.5·log10(11) ≈ 2.6 mag più chiaro
        assertEquals(22.0 - 2.5 * kotlin.math.log10(11.0), SkyTile.artificialToSqm(10 * SkyTile.NATURAL_MCD), 0.005)
    }

    @Test
    fun `tile ids`() {
        assertEquals("N45E005", SkyTile.tileIdFor(45.46, 9.19, 5))
        assertEquals("N40E010", SkyTile.tileIdFor(41.9, 12.5, 5))
        assertEquals("S35W075", SkyTile.tileIdFor(-33.4, -70.6, 5))
        assertEquals("N00W005", SkyTile.tileIdFor(0.2, -0.1, 5))
    }

    @Test
    fun `encode decode and bilinear sampling`() {
        val t = SkyTile.decodeGzip(ByteArrayInputStream(gradientTile()))
        assertEquals("N45E005", t.id)
        assertEquals(18.0, t.sample(47.5, 5.0)!!, 0.02)   // bordo ovest
        assertEquals(21.0, t.sample(47.5, 10.0)!!, 0.02)  // bordo est
        assertEquals(19.5, t.sample(47.5, 7.5)!!, 0.03)   // centro: interpolazione lineare
    }

    private fun server(tiles: Map<String, ByteArray>, manifest: String, hits: AtomicInteger) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = request.path!!.substringAfterLast('/')
                if (name == "manifest.json") return MockResponse().setBody(manifest)
                val id = name.removeSuffix(".bin.gz")
                val body = tiles[id] ?: return MockResponse().setResponseCode(404)
                hits.incrementAndGet()
                return MockResponse().setBody(Buffer().write(body))
            }
        }
    }

    private val manifest = """{"format":1,"version":"v1","tileDeg":5,"latMin":-60,"latMax":85,"darkSqm":21.98,"tiles":["N45E005"]}"""

    @Test
    fun `store downloads once, serves from disk and handles dark and uncovered tiles`() = runBlocking {
        val hits = AtomicInteger()
        server(mapOf("N45E005" to gradientTile()), manifest, hits).use { s ->
            val dir = tmp.newFolder("atlas")
            val store = SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), dir, json)

            // 20 letture concorrenti nella stessa tile → un solo download (lock per tile)
            (1..20).map { async { store.sample(47.5, 7.5) } }.awaitAll()
            assertEquals(1, hits.get())
            assertTrue(java.io.File(dir, "v1/N45E005.bin.gz").exists())

            // tile non in elenco = buia → darkSqm, senza rete
            val dark = store.sample(60.0, 100.0)
            assertTrue(dark.isDarkDefault)
            assertEquals(21.98, dark.sqm, 1e-9)

            // fuori copertura
            assertTrue(runCatching { store.sample(89.0, 0.0) }.exceptionOrNull() is OutOfAtlasCoverageException)

            // una nuova istanza (riavvio app) legge da disco: nessun nuovo download
            val store2 = SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), dir, json)
            assertEquals(19.5, store2.sample(47.5, 7.5).sqm, 0.03)
            assertEquals(1, hits.get())
        }
    }

    @Test
    fun `offline with cached manifest still works, offline without anything fails`() = runBlocking {
        val hits = AtomicInteger()
        val dir = tmp.newFolder("atlas2")
        server(mapOf("N45E005" to gradientTile()), manifest, hits).use { s ->
            SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), dir, json).sample(47.5, 7.5)
        }
        // server spento: forceRefresh del manifest fallisce ma si usa quello su disco
        val offline = SkyTileStore(OkHttpClient(), "http://127.0.0.1:1/sky/", dir, json)
        offline.manifest(forceRefresh = true)
        assertEquals(19.5, offline.sample(47.5, 7.5).sqm, 0.03)

        val empty = SkyTileStore(OkHttpClient(), "http://127.0.0.1:1/sky/", tmp.newFolder("none"), json)
        assertTrue(runCatching { empty.sample(47.5, 7.5) }.exceptionOrNull() is IOException)
    }

    @Test
    fun `regional pack - outside bbox is an error, never dark`() = runBlocking {
        val europe = """{"format":1,"version":"eu","tileDeg":5,"latMin":30,"latMax":75,"lonMin":-25,"lonMax":45,"tiles":["N45E005"]}"""
        server(mapOf("N45E005" to gradientTile()), europe, AtomicInteger()).use { s ->
            val store = SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), tmp.newFolder("eu"), json)
            assertEquals(19.5, store.sample(47.5, 7.5).sqm, 0.03)          // Europa, tile presente
            assertTrue(store.sample(60.0, 40.0).isDarkDefault)              // Europa, tile omessa = buia
            // USA (40° N, -100°): latitudine nel range ma longitudine fuori → errore, non "Bortle 1"
            assertTrue(runCatching { store.sample(40.0, -100.0) }.exceptionOrNull() is OutOfAtlasCoverageException)
        }
    }

    @Test
    fun `new manifest version discards old tiles`() = runBlocking {
        val hits = AtomicInteger()
        val dir = tmp.newFolder("atlas3")
        server(mapOf("N45E005" to gradientTile()), manifest, hits).use { s ->
            SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), dir, json).sample(47.5, 7.5)
        }
        server(mapOf("N45E005" to gradientTile()), manifest.replace("\"v1\"", "\"v2\""), hits).use { s ->
            val store = SkyTileStore(OkHttpClient(), s.url("/sky/").toString(), dir, json)
            store.manifest(forceRefresh = true)
            assertTrue(!java.io.File(dir, "v1").exists())
            store.sample(47.5, 7.5)
            assertTrue(java.io.File(dir, "v2/N45E005.bin.gz").exists())
            assertEquals(2, hits.get())
        }
    }
}
