package com.astroscout.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.astroscout.domain.model.Spot
import com.astroscout.domain.model.WeatherCondition
import com.astroscout.domain.scoring.Bortle

/**
 * Badge disegnati come Bitmap e registrati nello style (`style.addImage`).
 *
 * Perché bitmap invece di `text-field` in un SymbolLayer: il testo nei layer richiede un endpoint
 * `glyphs` (font PBF) nello style; con una base raster pura non ce l'abbiamo. Un badge
 * "☀ 82" è al massimo 4 icone × 101 punteggi, generate pigramente e cache-ate dallo style.
 */
object MapBadges {

    fun weatherEmoji(c: WeatherCondition?): String = when (c) {
        WeatherCondition.CLEAR -> "☀"            // nessuna nube significativa
        WeatherCondition.PARTLY_CLOUDY -> "⛅"
        WeatherCondition.CLOUDY -> "☁"
        WeatherCondition.PRECIPITATION -> "🌧"
        null -> "…"
    }

    fun badgeId(spot: Spot): String =
        "badge_${spot.condition?.name ?: "NA"}_${spot.score?.total ?: -1}"

    fun colorHex(spot: Spot): String = when (spot.sky?.bortle?.let(Bortle::band)) {
        Bortle.Band.DARK -> "#2E7D32"
        Bortle.Band.MODERATE -> "#F9A825"
        Bortle.Band.BRIGHT -> "#C62828"
        null -> "#9E9E9E"
    }

    fun render(spot: Spot, density: Float): Bitmap {
        val label = buildString {
            append(weatherEmoji(spot.condition))
            spot.score?.let { append(' ').append(it.total) }
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val padH = 6f * density
        val padV = 3f * density
        val fm = textPaint.fontMetrics
        val w = (textPaint.measureText(label) + 2 * padH).toInt().coerceAtLeast(1)
        val h = ((fm.descent - fm.ascent) + 2 * padV).toInt().coerceAtLeast(1)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 11, 16, 38) }
        val r = h / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, bg)
        canvas.drawText(label, padH, padV - fm.ascent, textPaint)
        return bmp
    }
}
