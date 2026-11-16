package com.astroscout.data.sky.atlas

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Risposta HTTP non 2xx da una chiamata OkHttp diretta (fuori da Retrofit). */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code per $url")

/** Punto fuori dalla copertura dell'atlante (es. oltre 85° N). */
class OutOfAtlasCoverageException(lat: Double) : IllegalArgumentException("Latitudine $lat fuori copertura dell'atlante")

data class SkyAtlasSample(val sqm: Double, val tileId: String, val isDarkDefault: Boolean)

data class SkyAtlasCacheInfo(val version: String?, val tileCount: Int, val bytes: Long)

/**
 * Scarica, conserva e legge le tile del World Atlas.
 *
 * Tre livelli di cache, dal più veloce:
 * 1. **RAM** — LRU delle ultime [memoryTiles] tile decodificate (600×600 B ≈ 360 KB l'una):
 *    una griglia 5×5 cade quasi sempre in 1–2 tile, quindi 25 letture = 0 I/O.
 * 2. **Disco** — `rootDir/<versione>/<id>.bin.gz`, per sempre (finché non cambia versione).
 * 3. **Rete** — `baseUrl/<id>.bin.gz`, solo la prima volta.
 *
 * Il manifest si ricontrolla ogni [manifestMaxAge]; se la rete manca si usa quello su disco anche
 * se vecchio: l'atlante è statico, una versione vecchia è comunque valida.
 *
 * Scritture **atomiche** (file `.tmp` + rename): un download interrotto (tunnel, batteria) non lascia
 * mai una tile troncata che verrebbe poi letta come valida.
 */
class SkyTileStore(
    private val http: OkHttpClient,
    baseUrl: String,
    private val rootDir: File,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
    private val manifestMaxAge: Duration = Duration.ofDays(30),
    private val memoryTiles: Int = 6,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    private val manifestFile get() = File(rootDir, "manifest.json")

    private val manifestLock = Mutex()
    @Volatile private var manifest: SkyAtlasManifest? = null
    @Volatile private var tileSet: Set<String> = emptySet()

    private val tileLocks = ConcurrentHashMap<String, Mutex>()
    private val memory = object : LinkedHashMap<String, SkyTile>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SkyTile>?) = size > memoryTiles
    }

    // ------------------------------------------------------------------------------- manifest

    suspend fun manifest(forceRefresh: Boolean = false): SkyAtlasManifest = withContext(io) {
        manifestLock.withLock {
            manifest?.takeIf { !forceRefresh && isFresh(manifestFile) }?.let { return@withLock it }

            val onDisk = if (manifestFile.exists()) runCatching { parseManifest(manifestFile.readText()) }.getOrNull() else null
            if (onDisk != null && !forceRefresh && isFresh(manifestFile)) return@withLock remember(onDisk)

            try {
                val text = get("${baseUrl}manifest.json", CacheControl.FORCE_NETWORK).toString(Charsets.UTF_8)
                val fresh = parseManifest(text)
                rootDir.mkdirs()
                writeAtomically(manifestFile, text.toByteArray())
                if (onDisk != null && onDisk.version != fresh.version) deleteOtherVersions(fresh.version)
                remember(fresh)
            } catch (e: IOException) {
                // Offline: un manifest vecchio va benissimo (dataset statico). Senza nulla, si propaga.
                onDisk?.let { remember(it) } ?: throw e
            }
        }
    }

    private fun parseManifest(text: String): SkyAtlasManifest =
        json.decodeFromString(SkyAtlasManifest.serializer(), text).also {
            require(it.format == SkyTile.FORMAT_VERSION) { "manifest formato ${it.format} non supportato" }
            require(it.tileDeg in 1..30) { "tileDeg non valido" }
        }

    private fun remember(m: SkyAtlasManifest): SkyAtlasManifest {
        tileSet = m.tiles.toHashSet()
        manifest = m
        return m
    }

    private fun isFresh(f: File) = f.exists() && clock.millis() - f.lastModified() < manifestMaxAge.toMillis()

    // ---------------------------------------------------------------------------------- lettura

    /** SQM nel punto. Scarica la tile solo se non è già su disco. */
    suspend fun sample(lat: Double, lon: Double): SkyAtlasSample {
        val m = manifest()
        if (!m.isCovered(lat)) throw OutOfAtlasCoverageException(lat)
        val id = SkyTile.tileIdFor(lat, lon, m.tileDeg)
        if (id !in tileSet) return SkyAtlasSample(m.darkSqm, id, isDarkDefault = true)
        val sqm = tile(m, id).sample(lat, lon) ?: m.darkSqm // tile presente ma pixel nodata (es. mare)
        return SkyAtlasSample(sqm, id, isDarkDefault = false)
    }

    /** Scarica in anticipo le tile che coprono i punti dati (uso offline). Ritorna quante ne ha scaricate. */
    suspend fun prefetch(points: List<Pair<Double, Double>>): Int {
        val m = manifest()
        val ids = points.filter { m.isCovered(it.first) }
            .map { SkyTile.tileIdFor(it.first, it.second, m.tileDeg) }
            .filter { it in tileSet }
            .distinct()
        var downloaded = 0
        for (id in ids) {
            if (!tileFile(m, id).exists()) { tile(m, id); downloaded++ }
        }
        return downloaded
    }

    private suspend fun tile(m: SkyAtlasManifest, id: String): SkyTile {
        synchronized(memory) { memory[id] }?.let { return it }
        val lock = tileLocks.getOrPut(id) { Mutex() }
        return lock.withLock {
            // ricontrollo dentro il lock: un'altra coroutine potrebbe averla appena caricata
            synchronized(memory) { memory[id] }?.let { return@withLock it }
            withContext(io) {
                val file = tileFile(m, id)
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    writeAtomically(file, get("$baseUrl$id.bin.gz", CacheControl.FORCE_NETWORK))
                }
                val tile = try {
                    file.inputStream().use { SkyTile.decodeGzip(it) }
                } catch (e: Exception) {
                    file.delete() // file corrotto: lo rimuoviamo così il prossimo tentativo lo riscarica
                    throw IOException("Tile $id corrotta", e)
                }
                check(tile.id == id) { "Tile $id contiene ${tile.id}" }
                synchronized(memory) { memory[id] = tile }
                tile
            }
        }
    }

    // ---------------------------------------------------------------------------- manutenzione

    fun cacheInfo(): SkyAtlasCacheInfo {
        val version = manifest?.version
            ?: runCatching { parseManifest(manifestFile.readText()).version }.getOrNull()
        val files = version?.let { File(rootDir, it).listFiles { f -> f.name.endsWith(".bin.gz") } }.orEmpty()
        return SkyAtlasCacheInfo(version, files.size, files.sumOf { it.length() })
    }

    suspend fun clear() = withContext(io) {
        manifestLock.withLock {
            synchronized(memory) { memory.clear() }
            manifest = null
            tileSet = emptySet()
            rootDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------- interni

    private fun tileFile(m: SkyAtlasManifest, id: String) = File(File(rootDir, m.version), "$id.bin.gz")

    private fun deleteOtherVersions(keep: String) {
        rootDir.listFiles { f -> f.isDirectory && f.name != keep }?.forEach { it.deleteRecursively() }
        synchronized(memory) { memory.clear() }
    }

    private fun get(url: String, cache: CacheControl): ByteArray {
        val request = Request.Builder().url(url).cacheControl(cache).build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, url)
            return resp.body?.bytes() ?: throw IOException("Risposta vuota da $url")
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Impossibile salvare ${target.name}")
        }
    }
}
