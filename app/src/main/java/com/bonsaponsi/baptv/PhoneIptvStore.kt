package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Internet channels of the phone app, kept in a file of their own. The TV keeps its channels in TvContract,
 * which phones do not provide, so the phone stores the same [Iptv.Stream] records as JSON. The file is read once
 * and kept in memory; call from a worker – a playlist can have tens of thousands of entries.
 */
class PhoneIptvStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private var cached: List<Iptv.Stream>? = null

    @Synchronized
    fun load(): List<Iptv.Stream> = cached ?: read().also { cached = it }

    // A single bad entry is skipped – the rest of the list stays.
    private fun read(): List<Iptv.Stream> = StoredFile.readList(file, "PhoneIptv") { Iptv.Stream.fromJson(it) }

    /** Adds streams whose addresses are not stored yet; returns how many were added. */
    @Synchronized
    fun add(streams: List<Iptv.Stream>): Int {
        val current = load()
        val known = current.mapTo(HashSet()) { it.url }
        val added = streams.filter { known.add(it.url) }
        save(current + added)
        DebugLog.log("PhoneIptv", "added ${added.size} of ${streams.size} internet channels")
        return added.size
    }

    @Synchronized
    fun remove(url: String) = save(load().filter { it.url != url })

    private fun save(streams: List<Iptv.Stream>) {
        val array = JSONArray()
        streams.forEach { array.put(it.toJson()) }
        StoredFile.write(file, array.toString())
        cached = streams
    }

    private companion object {
        const val FILE_NAME = "iptv_phone.json"
    }
}
