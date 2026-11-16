package com.astroscout.domain.scoring

import com.astroscout.domain.model.AstroScore
import com.astroscout.domain.model.NightForecast
import com.astroscout.domain.model.ScoreWeights
import com.astroscout.domain.model.SkyQuality
import kotlin.math.roundToInt

/**
 * Astro Score 0..100: somma pesata di sotto-punteggi normalizzati in [0, 1].
 *
 * ```
 * score = 100 · Σ wᵢ·sᵢ / Σ wᵢ        (solo sulle componenti disponibili)
 * ```
 *
 * Sotto-punteggi:
 * - **Inquinamento luminoso**: lineare sull'SQM tra 17.5 (cielo cittadino, 0) e 22.0
 *   (cielo naturale, 1). L'SQM è logaritmico (mag/arcsec²), quindi una scala lineare in SQM è già
 *   "percettiva": +1 mag/arcsec² ≈ cielo 2.5× più scuro. Se c'è solo il Bortle: (9 − B) / 8.
 * - **Nuvolosità**: 1 − copertura media nella finestra notturna; 0 se è prevista precipitazione.
 * - **Seeing** / **Trasparenza**: (8 − indice medio 7Timer!) / 7, perché 1 è il valore migliore.
 *
 * Componenti mancanti (es. forecast non disponibile offline) vengono escluse e i pesi
 * rinormalizzati: lo score resta confrontabile ma è marcato [AstroScore.isPartial].
 *
 * [overcastCap]: una somma pesata pura darebbe ~50 a un sito Bortle 1 sotto un cielo coperto,
 * che però è inutilizzabile. Se la copertura media ≥ [overcastThresholdPct] o piove, il totale
 * viene limitato a [overcastCapValue]. Disattivabile per avere la formula "da specifica".
 */
class AstroScoreCalculator(
    private val overcastCap: Boolean = true,
    private val overcastThresholdPct: Double = 80.0,
    private val overcastCapValue: Int = 20,
) {

    fun compute(sky: SkyQuality?, forecast: NightForecast?, weights: ScoreWeights): AstroScore? {
        val lp = sky?.let { lightPollutionScore(it) }
        val cloud = forecast?.let { cloudScore(it) }
        val seeing = forecast?.avgSeeingIndex?.let { indexScore(it) }
        val transp = forecast?.avgTransparencyIndex?.let { indexScore(it) }

        val parts = listOf(
            weights.lightPollution to lp,
            weights.cloudCover to cloud,
            weights.seeing to seeing,
            weights.transparency to transp,
        ).filter { (w, _) -> w > 0.0 }

        val available = parts.filter { it.second != null }
        val weightSum = available.sumOf { it.first }
        if (available.isEmpty() || weightSum <= 0.0) return null

        var total = (100.0 * available.sumOf { (w, s) -> w * s!! } / weightSum).roundToInt().coerceIn(0, 100)
        if (overcastCap && forecast != null &&
            (forecast.hasPrecipitation || forecast.avgCloudCoverPct >= overcastThresholdPct)
        ) {
            total = minOf(total, overcastCapValue)
        }

        return AstroScore(
            total = total,
            lightPollution = lp,
            cloudCover = cloud,
            seeing = seeing,
            transparency = transp,
            isPartial = available.size < parts.size,
        )
    }

    companion object {
        const val SQM_WORST = 17.5
        const val SQM_BEST = 22.0

        fun lightPollutionScore(sky: SkyQuality): Double =
            sky.sqm?.let { ((it - SQM_WORST) / (SQM_BEST - SQM_WORST)).coerceIn(0.0, 1.0) }
                ?: ((9 - sky.bortle.coerceIn(1, 9)) / 8.0)

        fun cloudScore(f: NightForecast): Double =
            if (f.hasPrecipitation) 0.0 else (1.0 - f.avgCloudCoverPct / 100.0).coerceIn(0.0, 1.0)

        /** Indici 7Timer! seeing/trasparenza: 1 = ottimo → 1.0, 8 = pessimo → 0.0. */
        fun indexScore(avgIndex: Double): Double = ((8.0 - avgIndex) / 7.0).coerceIn(0.0, 1.0)
    }
}
