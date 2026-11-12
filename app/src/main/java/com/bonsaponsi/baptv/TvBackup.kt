package com.bonsaponsi.baptv

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Backup of the TV's channel organisation: the order of all channels, hidden ones, names and categories the user
 * gave, the "hide scrambled" switch and the internet-channel blacklist.
 *
 * Broadcast channels are identified by their DVB identity (multiplex and service number), so the backup survives a
 * rescan. Internet channels are identified by their playlist address and carry the stream itself, so a new set-top
 * box can add them. A broadcast channel that is not identified yet has no stable identity and is left out ("skipped").
 *
 * Also kept: the playlist addresses (the player's list of sources) and the TV guide addresses (XMLTV), and the
 * blacklist.
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
            .put("sources", JSONArray(Iptv.sources(context)))
            .put("guideSources", JSONArray(IptvGuide.sources(context)))
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
        val blacklist = stringList(root, "blacklist")
        if (blacklist != null) {
            if (replace) Iptv.blacklistSet(context, blacklist) else blacklist.forEach { Iptv.blacklistAdd(context, it) }
        }
        stringList(root, "sources")?.let { playlists ->
            Iptv.setSources(context, if (replace) playlists else playlists + Iptv.sources(context))
        }
        stringList(root, "guideSources")?.let { guides ->
            if (replace) IptvGuide.sources(context).forEach { IptvGuide.removeSource(context, it) }
            if (IptvGuide.addSources(context, guides)) IptvGuide.refreshInBackground(context)
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

    /** Writes the backup to the Downloads folder (a bapTV subfolder) and returns where it is. */
    @Throws(IOException::class)
    fun save(context: Context): String {
        val bytes = export(context).toString(2).toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // The previous backup is replaced, not numbered.
            resolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(FILE_NAME, RELATIVE_PATH))
            val uri = resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            }) ?: throw IOException("cannot create the file")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("cannot write the file")
            return RELATIVE_PATH + FILE_NAME
        }
        // Before Android 10 the app's own folder needs no permission.
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "bapTV").apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun stringList(root: JSONObject, key: String): List<String>? =
        root.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }

    private const val DVB_PREFIX = "dvb_"
    private const val FILE_NAME = "baptv-settings.json"
    private const val RELATIVE_PATH = "Download/bapTV/"
}
