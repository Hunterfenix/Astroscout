package com.astroscout.data.sky.atlas

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/**
 * Trasforma una [SkyTile] (griglia lat/lon regolare) in pixel ARGB da sovrapporre alla mappa.
 *
 * **Riproiezione**: la mappa è in Web Mercator, dove y = ln(tan(π/4 + φ/2)) NON è lineare in
 * latitudine. Un'immagine posata su un quadrilatero lat/lon verrebbe stirata linearmente in y:
 * su una tile da 5° a 45–50° N l'errore a metà altezza è ~2 % (≈ 11 km). Per questo ogni riga
 * dell'immagine di output corrisponde a una y Mercator equispaziata, e la riga sorgente si ricava
 * invertendo la proiezione: φ = 2·atan(e^y) − π/2. In longitudine Mercator è lineare: nessuna
 * correzione.
 *
 * **Palette** (stile lightpollutionmap.info): trasparente al cielo naturale, poi blu → verde →
 * giallo → arancio → rosso → magenta → bianco man mano che il cielo si schiarisce. L'alpha cresce
 * con l'inquinamento così le zone buie lasciano vedere la mappa sotto.
 */
object SkyOverlayRenderer {

    /** (SQM, A, R, G, B) — ordinati per SQM decrescente. */
    private val STOPS = arrayOf(
        doubleArrayOf(22.00, 0.0, 10.0, 10.0, 40.0),
        doubleArrayOf(21.75, 90.0, 20.0, 30.0, 120.0),
        doubleArrayOf(21.50, 140.0, 30.0, 80.0, 200.0),
        doubleArrayOf(21.25, 150.0, 40.0, 160.0, 220.0),
        doubleArrayOf(21.00, 160.0, 60.0, 180.0, 75.0),
        doubleArrayOf(20.50, 170.0, 170.0, 210.0, 50.0),
        doubleArrayOf(20.00, 180.0, 250.0, 220.0, 40.0),
        doubleArrayOf(19.50, 190.0, 250.0, 150.0, 30.0),
        doubleArrayOf(19.00, 200.0, 230.0, 50.0, 30.0),
        doubleArrayOf(18.50, 210.0, 200.0, 40.0, 160.0),
        doubleArrayOf(18.00, 220.0, 240.0, 240.0, 240.0),
    )

    /** Colore ARGB per un valore SQM (interpolazione lineare tra gli stop). */
    fun colorFor(sqm: Double): Int {
        if (sqm >= STOPS.first()[0]) return argb(STOPS.first())
        if (sqm <= STOPS.last()[0]) return argb(STOPS.last())
        for (i in 0 until STOPS.size - 1) {
            val a = STOPS[i]
            val b = STOPS[i + 1]
            if (sqm <= a[0] && sqm >= b[0]) {
                val f = (a[0] - sqm) / (a[0] - b[0])
                return argb(DoubleArray(5) { k -> a[k] + (b[k] - a[k]) * f })
            }
        }
        return 0
    }

    private fun argb(v: DoubleArray): Int =
        (v[1].toInt() shl 24) or (v[2].toInt() shl 16) or (v[3].toInt() shl 8) or v[4].toInt()

    fun mercatorY(latDeg: Double): Double = ln(tan(PI / 4 + Math.toRadians(latDeg) / 2))
    fun latFromMercatorY(y: Double): Double = Math.toDegrees(2 * atan(exp(y)) - PI / 2)

    /**
     * Pixel ARGB (row-major, riga 0 = Nord) di dimensione `tile.width × outHeight`, già in Mercator.
     * Le latitudini sono limitate a ±85° (limite di Web Mercator).
     */
    fun render(tile: SkyTile, outHeight: Int = tile.height): IntArray {
        val latN = (tile.latSouth + tile.tileDeg).toDouble().coerceAtMost(85.0)
        val latS = tile.latSouth.toDouble().coerceAtLeast(-85.0)
        val yN = mercatorY(latN)
        val yS = mercatorY(latS)
        val w = tile.width
        val out = IntArray(w * outHeight)
        val colorCache = HashMap<Int, Int>(256)
        for (row in 0 until outHeight) {
            val y = yN - (row + 0.5) / outHeight * (yN - yS)
            val lat = latFromMercatorY(y)
            val srcRow = ((tile.latSouth + tile.tileDeg - lat) / tile.tileDeg * tile.height).toInt().coerceIn(0, tile.height - 1)
            for (col in 0 until w) {
                val q = tile.rawAt(srcRow, col)
                out[row * w + col] = if (q == SkyTile.NODATA) 0 else colorCache.getOrPut(q) {
                    colorFor(tile.sqmMin + q * tile.sqmStep.toDouble())
                }
            }
        }
        return out
    }

    /** Legenda: (SQM, colore) per disegnare la scala nella UI. */
    fun legend(): List<Pair<Double, Int>> = STOPS.map { it[0] to (argb(it) or (0xFF shl 24)) }
}
