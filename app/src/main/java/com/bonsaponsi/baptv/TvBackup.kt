package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup of the TV's channel organisation: the order of all channels, hidden ones, names and categories the user
 * gave, the "hide scrambled" switch and the internet-channel blacklist.
 *
 * Broadcast channels are identified by their DVB identity (multiplex and service number), so the backup survives a
 * rescan. Internet channels are identified by their playlist address and carry the stream itself, so a new set-top
 * box can add them. A broadcast channel that is not identified yet has no stable identity and is left out ("skipped").
 *
 * Import: the file's entries win for the channels it lists (name, hidden flag, category), and the file's order
 * places them; channels it does not list keep their settings in merge mode and get defaults in replace mode.
 * Internet channels missing on this TV are added; nothing is deleted.
 */
object TvBackup {

    const val FORMAT = "baptv-tv-settings"
    const val VERSION = 2

    fun export(context: Context): JSONObject {
        val repository = ChannelRepository(context)
        val store = ChannelStore(context)
        val streams = Iptv.streams(context).associate { (channel, stream) -> channel.id to stream }
        var skipped = 0
        val channels = JSONArray()
        repository.loadChannels(Dtv.findTunerInput(context)?.id, includeHidden = true).forEach { ch ->
            val item = JSONObject()
            if (ch.iptv) {
                val stream = streams[ch.id] ?: return@forEach
                item.put("stream", stream.toJson())
            } else {
                val key = store.settingsKey(ch)
                if (!key.startsWith(DVB_PREFIX)) { skipped++; return@forEach }
                item.put("dvb", key)
            }
            store.customName(ch)?.let { item.put("name", it) }
            if (store.isHidden(ch)) item.put("hidden", true)
            store.customCategory(ch)?.let { item.put("category", it) }
            channels.put(item)
        }
        DebugLog.log("Backup", "exported ${channels.length()} channels, skipped $skipped")
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", BuildConfig.VERSION_NAME)
            .put("hideScrambled", store.hideScrambled)
            .put("channels", channels)
            .put("blacklist", JSONArray(Iptv.blacklist(context)))
            .put("skipped", skipped)
    }

    /** Result of an import: internet channels added, channel settings applied, entries that matched nothing. */
    class Result(val added: Int, val applied: Int, val skipped: Int) {
        fun toJson(): JSONObject = JSONObject().put("added", added).put("applied", applied).put("skipped", skipped)
    }

    /** Throws [IllegalArgumentException] when the text is not a backup of this app. */
    fun import(context: Context, text: String, replace: Boolean): Result {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("not JSON", it) }
        require(root.optString("format") == FORMAT) { "not a bapTV TV settings file" }
        require(root.optInt("version", -1) in 1..VERSION) { "settings file from another version" }
        val entries = root.optJSONArray("channels") ?: throw IllegalArgumentException("no channels in the file")
        val items = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
        val store = ChannelStore(context)

        if (replace) store.clearOrganisation()
        val blacklist = root.optJSONArray("blacklist")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        if (blacklist != null) {
            if (replace) Iptv.blacklistSet(context, blacklist) else blacklist.forEach { Iptv.blacklistAdd(context, it) }
        }
        // Internet channels this TV does not have yet (the blacklist is applied first, so it is respected).
        val present = Iptv.streams(context).map { it.second.url }.toHashSet()
        val streams = items.mapNotNull { it.optJSONObject("stream") }
            .mapNotNull { runCatching { Iptv.Stream.fromJson(it) }.getOrNull() }
            .filter { it.url !in present }
            .distinctBy { it.url }
        val added = Iptv.add(context, streams)

        val idByUrl = Iptv.streams(context).associate { (channel, stream) -> stream.url to channel.id }
        val order = mutableListOf<String>()
        var skipped = 0
        items.forEach { item ->
            val key = item.optString("dvb").takeIf { it.startsWith(DVB_PREFIX) }
                ?: item.optJSONObject("stream")?.optString("url")?.let { idByUrl[it] }?.let { "id_$it" }
            if (key == null) { skipped++; return@forEach }
            order += key
            val category = if (item.has("category")) item.optInt("category", -1).takeIf { it == ChannelCategory.NONE || it in ChannelCategory.ALL } else null
            store.putSettings(key, name = item.optString("name").ifBlank { null }, hidden = item.optBoolean("hidden"), category = category)
        }
        store.setCustomOrder(order.distinct())
        if (root.has("hideScrambled")) store.hideScrambled = root.optBoolean("hideScrambled")
        DebugLog.log("Backup", "imported ${items.size} entries (replace=$replace): added $added, skipped $skipped")
        return Result(added, items.size - skipped, skipped)
    }

    private const val DVB_PREFIX = "dvb_"
}
