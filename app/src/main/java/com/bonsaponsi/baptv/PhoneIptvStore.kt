package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Internet channels of the phone app, kept in a file of their own. The TV keeps its channels in TvContract,
 * which phones do not provide, so the phone stores the same [Iptv.Stream] records as JSON.
 */
class PhoneIptvStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(): List<Iptv.Stream> {
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrElse {
            DebugLog.warn("PhoneIptv", "corrupted channel list – clearing", it)
            return emptyList()
        }
        // A single bad entry is skipped – the rest of the list stays.
        return (0 until array.length()).mapNotNull { i ->
            runCatching { Iptv.Stream.fromJson(array.getJSONObject(i)) }.getOrNull()
        }
    }

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
        file.writeText(array.toString())
    }

    private companion object {
        const val FILE_NAME = "iptv_phone.json"
    }
}
