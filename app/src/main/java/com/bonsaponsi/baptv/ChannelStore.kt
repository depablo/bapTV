package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * List of channels discovered by probing IDs, plus user-assigned names.
 *
 * Needed because dtvinput stores channels with searchable=0, and TvProvider shows such rows
 * only to the owner and to apps with ACCESS_ALL_EPG_DATA (signature|privileged).
 */
class ChannelStore(context: Context) {

    private val prefs = context.getSharedPreferences("channels", Context.MODE_PRIVATE)

    fun loadDiscovered(): List<Channel> {
        val json = prefs.getString(KEY_DISCOVERED, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrElse {
            DebugLog.warn("Store", "corrupted channel list – clearing", it)
            clearDiscovered()
            return emptyList()
        }
        // A single bad entry is skipped – the rest of the list stays.
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                Channel(
                    id = o.getLong("id"),
                    number = o.getString("number"),
                    name = o.optString("name"),
                    isRadio = o.optBoolean("radio"),
                    videoFormat = null,
                    discovered = true,
                    scrambled = o.optBoolean("scrambled"),
                    frequencyMhz = if (o.has("freq")) o.getDouble("freq") else null,
                )
            }.onFailure { DebugLog.warn("Store", "skipped bad channel $i", it) }.getOrNull()
        }
    }

    fun saveDiscovered(channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach { array.put(JSONObject().put("id", it.id).put("number", it.number).put("name", it.name).put("radio", it.isRadio).put("scrambled", it.scrambled).apply { it.frequencyMhz?.let { f -> put("freq", f) } }) }
        prefs.edit().putString(KEY_DISCOVERED, array.toString()).apply()
        DebugLog.log("Store", "saved ${channels.size} discovered channels: ${channels.joinToString { "${it.number}=#${it.id}" }}")
    }

    /** After a new scan channel IDs change – the list must be discovered again. */
    fun clearDiscovered() {
        prefs.edit().remove(KEY_DISCOVERED).apply()
    }

    // ---- User settings: name, hiding, order ----
    //
    // The key is the service identity in the signal (network, multiplex, service number), so settings
    // survive a rescan, after which dtvinput assigns new IDs to channels.
    // A not-yet-identified channel has a temporary key based on its ID; once identified,
    // [migrate] moves its settings to the DVB key. The "name_<id>" / "hidden_<id>" keys
    // are the format from before 2.9.

    // Multiplex (TSID) and service number are enough in the Polish DVB-T network; ONID may be unknown (service without EIT).
    fun settingsKey(ch: Channel): String = settingsKey(ch.id, ch.tsid, ch.sid)

    fun settingsKey(channelId: Long, tsid: Int?, sid: Int?): String =
        if (tsid != null && sid != null) "dvb_${tsid}_$sid" else "id_$channelId"

    private fun legacyKeys(prefix: String, ch: Channel) = listOf("$prefix${ch.id}", "${prefix}id_${ch.id}")

    fun customName(ch: Channel): String? =
        prefs.getString("name_${settingsKey(ch)}", null) ?: legacyKeys("name_", ch).firstNotNullOfOrNull { prefs.getString(it, null) }

    fun setCustomName(ch: Channel, name: String?) {
        val key = "name_${settingsKey(ch)}"
        prefs.edit().apply {
            legacyKeys("name_", ch).forEach { remove(it) }
            if (name.isNullOrBlank()) remove(key) else putString(key, name.trim())
        }.apply()
        DebugLog.log("Store", "channel name ${settingsKey(ch)} = $name")
    }

    fun isHidden(ch: Channel): Boolean =
        prefs.getBoolean("hidden_${settingsKey(ch)}", false) || legacyKeys("hidden_", ch).any { prefs.getBoolean(it, false) }

    fun setHidden(ch: Channel, hidden: Boolean) {
        val key = "hidden_${settingsKey(ch)}"
        prefs.edit().apply {
            legacyKeys("hidden_", ch).forEach { remove(it) }
            if (hidden) putBoolean(key, true) else remove(key)
        }.apply()
        DebugLog.log("Store", "channel ${settingsKey(ch)} hidden=$hidden")
    }

    /** User-defined order ([settingsKey] keys); empty = default. */
    fun customOrder(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(KEY_ORDER, "[]"))
        (0 until a.length()).map { i -> a.get(i).let { if (it is Number) "id_${it.toLong()}" else it.toString() } }
    }.getOrDefault(emptyList())

    fun setCustomOrder(keys: List<String>?) {
        prefs.edit().apply { if (keys == null) remove(KEY_ORDER) else putString(KEY_ORDER, JSONArray(keys).toString()) }.apply()
        DebugLog.log("Store", if (keys == null) "default order" else "user order: ${keys.size} channels")
    }

    fun migrate(ch: Channel) = migrate(ch.id, ch.tsid, ch.sid)

    /**
     * Channel identified: settings stored under its ID move to the DVB key.
     * Also works for IDs from before a rescan (from saved identifications).
     */
    fun migrate(channelId: Long, tsid: Int?, sid: Int?) = synchronized(LOCK) { migrateLocked(channelId, tsid, sid) }

    /** Read-modify-write of order and settings – called from several threads, so under a shared lock. */
    private fun migrateLocked(channelId: Long, tsid: Int?, sid: Int?) {
        val key = settingsKey(channelId, tsid, sid)
        if (!key.startsWith("dvb_")) return
        val ch = Channel(id = channelId, number = "", name = "", isRadio = false, videoFormat = null)
        val editor = prefs.edit()
        var moved = false
        legacyKeys("name_", ch).forEach { old ->
            prefs.getString(old, null)?.let { if (!prefs.contains("name_$key")) editor.putString("name_$key", it); editor.remove(old); moved = true }
        }
        legacyKeys("hidden_", ch).forEach { old ->
            if (prefs.getBoolean(old, false)) { editor.putBoolean("hidden_$key", true); editor.remove(old); moved = true }
        }
        val order = customOrder()
        if ("id_${ch.id}" in order) {
            editor.putString(KEY_ORDER, JSONArray(order.map { if (it == "id_${ch.id}") key else it }).toString())
            moved = true
        }
        if (moved) {
            editor.apply()
            DebugLog.log("Store", "settings of channel #${ch.id} moved to $key")
        }
    }

    /** Hide all scrambled channels at once. */
    var hideScrambled: Boolean
        get() = prefs.getBoolean(KEY_HIDE_SCRAMBLED, false)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_SCRAMBLED, value).apply(); DebugLog.log("Store", "hide scrambled=$value") }

    /** Restores the default order and shows all channels. */
    fun resetOrganisation() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("hidden_") }.forEach { editor.remove(it) }
        editor.remove(KEY_ORDER).remove(KEY_HIDE_SCRAMBLED).apply()
        DebugLog.log("Store", "order and hiding reset to defaults")
    }

    companion object {
        private val LOCK = Any()
        /** Summary by multiplex, e.g. "482 MHz: TV 13 · 506 MHz: TV 2, radio 17, scrambled 10". */
        fun muxSummary(channels: List<Channel>): String =
            channels.groupBy { it.frequencyMhz }.toSortedMap(nullsLast()).entries.joinToString(" · ") { (f, list) ->
                val parts = listOfNotNull(
                    list.count { !it.isRadio && !it.scrambled }.takeIf { it > 0 }?.let { "TV $it" },
                    list.count { it.isRadio && !it.scrambled }.takeIf { it > 0 }?.let { "radio $it" },
                    list.count { it.scrambled }.takeIf { it > 0 }?.let { "scrambled $it" },
                )
                "${f?.let { "%.0f MHz".format(java.util.Locale.US, it) } ?: "? MHz"}: ${parts.joinToString(", ")}"
            }

        private const val KEY_DISCOVERED = "discovered"
        private const val KEY_ORDER = "order"
        private const val KEY_HIDE_SCRAMBLED = "hide_scrambled"
    }
}
