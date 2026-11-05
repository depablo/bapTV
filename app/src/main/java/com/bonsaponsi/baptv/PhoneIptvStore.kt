package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Internet channels of the phone app, kept in a file of their own. The TV keeps its channels in TvContract,
 * which phones do not provide, so the phone stores the same [Iptv.Stream] records as JSON, in the user's order,
 * with the user's own name and the hidden flag next to each. The file is read once and kept in memory; call from
 * a worker – a playlist can have tens of thousands of entries.
 */
class PhoneIptvStore private constructor(context: Context) {

    /**
     * One channel on the phone. [label] – name given by the user (empty: the playlist's name, which also stays the
     * key for the guide); [hidden] – kept, but left out of the channel list.
     */
    data class Channel(val stream: Iptv.Stream, val label: String = "", val hidden: Boolean = false) {
        val url get() = stream.url
        val title get() = label.ifBlank { stream.name.ifBlank { stream.url } }

        fun toJson(): JSONObject = stream.toJson().apply {
            if (label.isNotBlank()) put(KEY_LABEL, label)
            if (hidden) put(KEY_HIDDEN, true)
        }

        companion object {
            fun fromJson(o: JSONObject) = Channel(Iptv.Stream.fromJson(o), o.optString(KEY_LABEL), o.optBoolean(KEY_HIDDEN))
        }
    }

    private val file = File(context.filesDir, FILE_NAME)
    private var cached: List<Channel>? = null

    @Synchronized
    fun load(): List<Channel> = cached ?: read().also { cached = it }

    /** The streams of all channels, hidden ones too (the guide is fetched for them all). */
    fun streams(): List<Iptv.Stream> = load().map { it.stream }

    // A single bad entry is skipped – the rest of the list stays.
    private fun read(): List<Channel> = StoredFile.readList(file, "PhoneIptv") { Channel.fromJson(it) }

    /** Adds streams whose addresses are not stored yet, at the end; returns how many were added. */
    @Synchronized
    fun add(streams: List<Iptv.Stream>): Int {
        val current = load()
        val known = current.mapTo(HashSet()) { it.url }
        val added = streams.filter { known.add(it.url) }
        save(current + added.map { Channel(it) })
        DebugLog.log("PhoneIptv", "added ${added.size} of ${streams.size} internet channels")
        return added.size
    }

    /** Addresses already on the list – the import screen marks those channels. */
    fun urls(): Set<String> = load().mapTo(HashSet()) { it.url }

    @Synchronized
    fun remove(url: String) = save(load().filter { it.url != url })

    @Synchronized
    fun rename(url: String, label: String) = update(url) { it.copy(label = label.trim()) }

    @Synchronized
    fun setHidden(url: String, hidden: Boolean) = update(url) { it.copy(hidden = hidden) }

    /** Stores the list in the given order (the management screen after a move); unknown addresses are ignored. */
    @Synchronized
    fun reorder(urls: List<String>) {
        val byUrl = load().associateBy { it.url }
        val ordered = urls.distinct().mapNotNull { byUrl[it] }
        // Channels added meanwhile (not in [urls]) stay at the end.
        val listed = urls.toHashSet()
        val rest = load().filter { it.url !in listed }
        save(ordered + rest)
    }

    private fun update(url: String, change: (Channel) -> Channel) = save(load().map { if (it.url == url) change(it) else it })

    private fun save(channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        StoredFile.write(file, array.toString())
        cached = channels
    }

    companion object {
        @Volatile private var instance: PhoneIptvStore? = null

        /** One copy for the process: the phone screens share the cached list. */
        fun get(context: Context): PhoneIptvStore =
            instance ?: synchronized(this) { instance ?: PhoneIptvStore(context.applicationContext).also { instance = it } }

        private const val FILE_NAME = "iptv_phone.json"
        private const val KEY_LABEL = "label"
        private const val KEY_HIDDEN = "hidden"
    }
}
