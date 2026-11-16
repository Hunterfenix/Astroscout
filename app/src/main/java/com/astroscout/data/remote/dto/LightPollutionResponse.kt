package com.astroscout.data.remote.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Risposta "canonica" di DarkSkySites per un punto lat/lon.
 *
 * Lo schema reale dell'API non è documentato pubblicamente, quindi NON lo deserializziamo con un
 * serializer rigido: Retrofit restituisce un [JsonElement] grezzo e [fromJson] estrae i campi in modo
 * tollerante, cercando i nomi più probabili (anche annidati sotto `data` / `result`).
 * Quando avrai lo schema definitivo basta modificare [SQM_KEYS] / [BORTLE_KEYS] (o sostituire il parser).
 *
 * @property sqm   brillanza del cielo allo zenit in mag/arcsec² (≈17 città … ≈22 cielo incontaminato)
 * @property bortle classe Bortle 1..9 (1 = cielo perfettamente buio)
 */
@Serializable
data class LightPollutionResponse(
    val sqm: Double? = null,
    val bortle: Int? = null,
) {
    val isEmpty: Boolean get() = sqm == null && bortle == null

    companion object {
        internal val SQM_KEYS = listOf("sqm", "SQM", "sqm_zenith", "zenith_sqm", "sqmZenith", "mpsas")
        internal val BORTLE_KEYS = listOf("bortle", "Bortle", "bortle_class", "bortleClass", "bortle_scale")
        private val WRAPPER_KEYS = listOf("data", "result", "results", "sky", "skyQuality")

        /** Campi ammessi: SQM 10..23 mag/arcsec² e Bortle 1..9. Valori fuori range = dato non valido. */
        private val SQM_RANGE = 10.0..23.0
        private val BORTLE_RANGE = 1..9

        fun fromJson(element: JsonElement): LightPollutionResponse {
            val obj = element as? JsonObject ?: return LightPollutionResponse()
            val direct = LightPollutionResponse(
                sqm = obj.firstNumber(SQM_KEYS)?.takeIf { it in SQM_RANGE },
                bortle = obj.firstNumber(BORTLE_KEYS)?.let { Math.round(it).toInt() }?.takeIf { it in BORTLE_RANGE },
            )
            if (!direct.isEmpty) return direct
            // Cerca un livello sotto (es. {"data": {"sqm": 21.3}}), o nel primo elemento di un array.
            for (key in WRAPPER_KEYS) {
                val nested = when (val child = obj[key]) {
                    is JsonObject -> child
                    is kotlinx.serialization.json.JsonArray -> child.firstOrNull() as? JsonObject
                    else -> null
                } ?: continue
                val r = fromJson(nested)
                if (!r.isEmpty) return r
            }
            return direct
        }

        /** Accetta sia numeri JSON che stringhe numeriche ("21.4"). */
        private fun JsonObject.firstNumber(keys: List<String>): Double? =
            keys.firstNotNullOfOrNull { k ->
                (this[k] as? JsonPrimitive)?.let { p -> p.doubleOrNull ?: p.intOrNull?.toDouble() }
            }
    }
}
