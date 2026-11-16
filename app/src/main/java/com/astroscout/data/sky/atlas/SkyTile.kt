package com.astroscout.data.sky.atlas

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor
import kotlin.math.log10

/**
 * Una tile del World Atlas 2015 già convertita in SQM e quantizzata a 1 byte/pixel.
 *
 * ## Formato file `*.bin.gz` (big-endian, poi gzip) — identico a `tools/build_sky_tiles.py`
 * ```
 * offset  size  campo
 *  0      4     magic "ASQT"
 *  4      1     formatVersion (= 1)
 *  5      2     width  (u16)   es. 600
 *  7      2     height (u16)   es. 600
 *  9      2     latSouth (i16, gradi interi)
 * 11      2     lonWest  (i16, gradi interi)
 * 13      2     tileDeg  (u16) es. 5
 * 15      4     sqmMin   (f32) es. 16.0
 * 19      4     sqmStep  (f32) es. 0.025
 * 23      w*h   dati u8, riga 0 = bordo NORD, colonna 0 = bordo OVEST; 255 = nodata
 * ```
 * Quantizzazione: `q = round((sqm − sqmMin)/sqmStep)`, 0..254 → 16.00…22.35 mag/arcsec².
 * Errore massimo ±0.0125 mag: ben sotto l'incertezza del modello (~0.1–0.2 mag).
 *
 * Il centro del pixel (r, c) è a
 * `lat = latSouth + tileDeg − (r + 0.5)·tileDeg/height`, `lon = lonWest + (c + 0.5)·tileDeg/width`.
 */
class SkyTile(
    val latSouth: Int,
    val lonWest: Int,
    val tileDeg: Int,
    val width: Int,
    val height: Int,
    val sqmMin: Float,
    val sqmStep: Float,
    private val data: ByteArray,
) {
    init {
        require(data.size == width * height) { "dati tile: attesi ${width * height} byte, trovati ${data.size}" }
    }

    val id: String get() = tileId(latSouth, lonWest)

    private fun sqmAt(row: Int, col: Int): Float? {
        val q = data[row * width + col].toInt() and 0xFF
        return if (q == NODATA) null else sqmMin + q * sqmStep
    }

    /**
     * SQM in (lat, lon) con **interpolazione bilineare** tra i 4 centri pixel più vicini.
     * Ai bordi della tile si fa clamp (il pixel di bordo si ripete): a ~1 km di risoluzione
     * l'errore è trascurabile e si evita di dover leggere la tile adiacente.
     * Pixel nodata vengono esclusi e i pesi rinormalizzati.
     */
    fun sample(lat: Double, lon: Double): Double? {
        // coordinate continue in "spazio pixel": 0.0 = centro del primo pixel
        val y = (latSouth + tileDeg - lat) / tileDeg * height - 0.5
        val x = (lon - lonWest) / tileDeg * width - 0.5
        val r0 = floor(y).toInt()
        val c0 = floor(x).toInt()
        val fy = y - r0
        val fx = x - c0

        var acc = 0.0
        var wSum = 0.0
        for ((dr, wr) in listOf(0 to 1 - fy, 1 to fy)) for ((dc, wc) in listOf(0 to 1 - fx, 1 to fx)) {
            val w = wr * wc
            if (w <= 0.0) continue
            val v = sqmAt((r0 + dr).coerceIn(0, height - 1), (c0 + dc).coerceIn(0, width - 1)) ?: continue
            acc += w * v
            wSum += w
        }
        return if (wSum > 0.0) acc / wSum else null
    }

    fun contains(lat: Double, lon: Double): Boolean =
        lat >= latSouth && lat <= latSouth + tileDeg && lon >= lonWest && lon <= lonWest + tileDeg

    companion object {
        const val MAGIC = "ASQT"
        const val FORMAT_VERSION = 1
        const val NODATA = 255

        /** Brillanza naturale del cielo usata dal World Atlas / lightpollutionmap.info (mcd/m²). */
        const val NATURAL_MCD = 0.171168

        /**
         * Brillanza artificiale (mcd/m²) → SQM (mag/arcsec²).
         * L[cd/m²] = 108 000 · 10^(−0.4·m)  ⇒  m = −2.5 · log10(L / 108 000)
         * e con L in mcd/m²: m = −2.5 · log10((art + nat) / 1.08e8).
         * Verifica: art = 0 → m = 22.00 (cielo naturale).
         */
        fun artificialToSqm(artificialMcd: Double): Double =
            -2.5 * log10((artificialMcd.coerceAtLeast(0.0) + NATURAL_MCD) / 1.08e8)

        /** Id della tile che contiene il punto: es. N45E005, S35W070 (angolo sud-ovest). */
        fun tileIdFor(lat: Double, lon: Double, tileDeg: Int): String {
            val latS = (floor(lat / tileDeg) * tileDeg).toInt()
            val lonW = (floor(lon / tileDeg) * tileDeg).toInt().let { if (it >= 180) it - 360 else it }
            return tileId(latS, lonW)
        }

        fun tileId(latSouth: Int, lonWest: Int): String = String.format(
            Locale.ROOT, "%s%02d%s%03d",
            if (latSouth >= 0) "N" else "S", kotlin.math.abs(latSouth),
            if (lonWest >= 0) "E" else "W", kotlin.math.abs(lonWest),
        )

        fun decodeGzip(input: InputStream): SkyTile = decode(GZIPInputStream(input))

        fun decode(input: InputStream): SkyTile = DataInputStream(input.buffered()).use { d ->
            val magic = ByteArray(4).also { d.readFully(it) }.toString(Charsets.US_ASCII)
            require(magic == MAGIC) { "tile non valida: magic '$magic'" }
            val version = d.readUnsignedByte()
            require(version == FORMAT_VERSION) { "formato tile $version non supportato" }
            val w = d.readUnsignedShort()
            val h = d.readUnsignedShort()
            val latS = d.readShort().toInt()
            val lonW = d.readShort().toInt()
            val deg = d.readUnsignedShort()
            val min = d.readFloat()
            val step = d.readFloat()
            val data = ByteArray(w * h).also { d.readFully(it) }
            SkyTile(latS, lonW, deg, w, h, min, step, data)
        }

        /** Encoder (usato dai test; lo script Python produce byte identici). */
        fun encodeGzip(t: SkyTile, rawData: ByteArray): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(bos)).use { o ->
                o.write(MAGIC.toByteArray(Charsets.US_ASCII))
                o.writeByte(FORMAT_VERSION)
                o.writeShort(t.width); o.writeShort(t.height)
                o.writeShort(t.latSouth); o.writeShort(t.lonWest)
                o.writeShort(t.tileDeg)
                o.writeFloat(t.sqmMin); o.writeFloat(t.sqmStep)
                o.write(rawData)
            }
            return bos.toByteArray()
        }
    }
}
