package com.bonsaponsi.baptv

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * Reads reception parameters from DtvProvider (streamers table = tuners). The "stats" column holds
 * JSON with the tuner's rssi, snr and ber fields (dtvprovider reads them from the tuner on every query),
 * "parameters" – tuning parameters (including frequency), "state" – IDLE/STREAMING/FILTERING.
 */
class SignalReader(private val context: Context) {

    data class Stats(val values: Map<String, String>) {
        fun format(): String = values.entries.joinToString("   ") { (k, v) -> "${k.uppercase()}: $v" }
        /** Finite numbers only – "NaN"/"Infinity" from the driver would break the JSON (JSONObject.put throws). */
        fun number(key: String): Double? = values[key]?.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Full tuner state: statistics and raw columns (for unit calibration). */
    data class Tuner(val stats: Stats?, val state: String?, val parameters: String?, val signal: String?) {
        /** Frequency from the tuning parameters in MHz, if it can be read. */
        val frequencyMhz: Double? get() = runCatching {
            val f = JSONObject(parameters ?: return null).optDouble(PARAM_FREQUENCY)
            when {
                !f.isFinite() || f <= 0 -> null
                f > 1e6 -> f / 1e6 // Hz
                f > 1e3 -> f / 1e3 // kHz
                else -> f
            }
        }.getOrNull()
    }

    fun read(): Stats? = readTuner()?.stats

    fun readTuner(): Tuner? = try {
        context.contentResolver.query(Uri.parse(Dtv.STREAMERS_URI), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
            Tuner(col("stats")?.let { parseStats(it) }, col("state"), col("parameters"), col("signal"))
        }
    } catch (e: Exception) {
        // No provider on other hardware or no permission – statistics are optional.
        Log.d("SignalReader", "Signal stats unavailable: ${e.message}")
        null
    }

    private fun parseStats(raw: String): Stats? {
        val json = JSONObject(raw)
        val values = linkedMapOf<String, String>()
        for (key in json.keys()) {
            val value = json.opt(key)
            if (value != null && value !is JSONObject && value != JSONObject.NULL) values[key] = value.toString()
        }
        return Stats(values).takeIf { values.isNotEmpty() }
    }

    companion object {
        private const val PARAM_FREQUENCY = "frequency"
    }
}
