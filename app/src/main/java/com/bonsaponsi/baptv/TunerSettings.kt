package com.bonsaponsi.baptv

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** Access to tuner settings and frontend state in DtvProvider (tvsettings and streamers tables). */
class TunerSettings(private val context: Context) {

    data class Frontend(val type: String?, val state: String?, val signal: String?, val stats: String?, val parameters: String?) {
        /** Frequency in MHz from the parameters column ({"frequency":850000000,...}). */
        val frequencyMhz: Double?
            get() = runCatching { JSONObject(parameters ?: return null).getLong("frequency") / 1_000_000.0 }.getOrNull()

        fun describe(): String =
            "f=${frequencyMhz?.let { "%.1f MHz".format(java.util.Locale.US, it) } ?: "?"} type=$type state=$state signal=$signal stats=$stats"
    }

    fun getValue(key: String): String? = try {
        context.contentResolver.query(Uri.parse(Dtv.TVSETTINGS_URI), arrayOf("_id", "value"), "_id=?", arrayOf(key), null)
            ?.use { c -> if (c.moveToFirst()) c.getString(1) else null }
    } catch (e: Exception) {
        DebugLog.warn("Tuner", "reading tvsettings/$key failed", e)
        null
    }

    /** Writes like DtvSetup Demo does: update by _id, and insert if the row does not exist. */
    fun setValue(key: String, value: String): Boolean = try {
        val uri = Uri.parse(Dtv.TVSETTINGS_URI)
        val updated = context.contentResolver.update(uri, ContentValues().apply { put("value", value) }, "_id=?", arrayOf(key))
        val ok = if (updated == 0) {
            context.contentResolver.insert(uri, ContentValues().apply { put("_id", key); put("value", value) }) != null
        } else {
            true
        }
        DebugLog.log("Tuner", "tvsettings $key=$value (update=$updated, ok=$ok), read back=${getValue(key)}")
        ok
    } catch (e: Exception) {
        DebugLog.error("Tuner", "writing tvsettings $key=$value failed", e)
        false
    }

    /** State of the first frontend (RF tuner) – changes live during the scan. */
    fun readFrontend(): Frontend? = try {
        context.contentResolver.query(Uri.parse(Dtv.STREAMERS_URI), arrayOf("name", "type", "state", "signal", "stats", "parameters"), null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == "frontend" || c.getString(1) != "IP") {
                        return@use Frontend(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5))
                    }
                }
                null
            }
    } catch (e: Exception) {
        null
    }
}
