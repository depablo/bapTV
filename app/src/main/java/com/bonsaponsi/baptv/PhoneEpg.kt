package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Programme guide of the phone's internet channels. The XMLTV files named by the playlists are read with the TV's
 * parser ([IptvGuide]), but the programmes go to a file of the phone app, not to TvContract: the phone has no TV
 * provider. Programmes are kept per channel address, so they follow the channel's entry in [PhoneIptvStore].
 */
class PhoneEpg(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** What the last refresh found: when, which XMLTV sources, and the programmes by channel address. */
    class Guide(val atMs: Long, val sources: List<String>, val programmes: Map<String, List<Programme>>)

    @Volatile
    private var guide: Guide = load()

    /** Now and next programme of a channel address, either may be null. */
    fun nowAndNext(url: String, nowMs: Long): Pair<Programme?, Programme?> {
        val list = guide.programmes[url].orEmpty()
        val index = list.indexOfFirst { nowMs < it.endMs }
        if (index < 0) return null to null
        val current = list[index].takeIf { it.isOn(nowMs) }
        return current to list.getOrNull(if (current != null) index + 1 else index)
    }

    /** Sources exist and the last full refresh is older than [IptvGuide.REFRESH_AFTER_MS]. */
    fun isStale(nowMs: Long): Boolean = guide.sources.isNotEmpty() && nowMs - guide.atMs >= IptvGuide.REFRESH_AFTER_MS

    /** Adds XMLTV sources named by a playlist; returns how many were new. */
    @Synchronized
    fun addSources(urls: Collection<String>): Int {
        val added = urls.map { it.trim() }.filter { it.isNotEmpty() && it !in guide.sources }.distinct()
        if (added.isEmpty()) return 0
        guide = Guide(guide.atMs, guide.sources + added, guide.programmes)
        save(guide)
        return added.size
    }

    /**
     * Downloads every source and matches its programmes to the streams. A source that fails keeps the programmes
     * it gave before. Runs on the worker; returns how many channels have a guide.
     */
    @Synchronized
    fun refresh(streams: List<Iptv.Stream>): Int {
        if (guide.sources.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val matcher = IptvGuide.Matcher(streams.mapIndexed { i, s -> IptvGuide.Matcher.Target(i.toLong(), s.name, s.tvgId) })
        val found = HashMap<String, MutableList<Programme>>()
        var failed = false
        for (source in guide.sources) {
            try {
                val byIndex = IptvGuide.download(source) { IptvGuide.parse(it, matcher, now - KEEP_PAST_MS, now + KEEP_AHEAD_MS) }
                byIndex.forEach { (index, list) ->
                    val url = streams.getOrNull(index.toInt())?.url ?: return@forEach
                    // The first source with a channel's guide wins; later ones only fill channels still missing.
                    if (found[url].isNullOrEmpty()) found[url] = list.toMutableList()
                }
                DebugLog.log("PhoneEpg", "$source: guide for ${byIndex.size} channels")
            } catch (e: Exception) {
                failed = true
                DebugLog.warn("PhoneEpg", "guide from $source failed", e)
            }
        }
        // Only a full success moves the refresh time, so a failed source is tried again at the next start.
        val previous = guide.programmes
        val programmes = if (failed) previous + found else found
        guide = Guide(if (failed) guide.atMs else now, guide.sources, programmes)
        save(guide)
        return found.size
    }

    @Synchronized
    private fun save(value: Guide) {
        val programmes = JSONObject()
        value.programmes.forEach { (url, list) ->
            programmes.put(url, JSONArray().apply {
                list.forEach { p -> put(JSONObject().put("title", p.title).put("description", p.description).put("start", p.startMs).put("end", p.endMs)) }
            })
        }
        val json = JSONObject().put("at", value.atMs)
            .put("sources", JSONArray(value.sources))
            .put("programmes", programmes)
        runCatching { file.writeText(json.toString()) }.onFailure { DebugLog.warn("PhoneEpg", "cannot save the guide", it) }
    }

    private fun load(): Guide {
        if (!file.exists()) return Guide(0, emptyList(), emptyMap())
        return runCatching {
            val json = JSONObject(file.readText())
            val sources = json.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } }
            val programmes = HashMap<String, List<Programme>>()
            json.getJSONObject("programmes").let { obj ->
                obj.keys().forEach { url ->
                    val a = obj.getJSONArray(url)
                    programmes[url] = (0 until a.length()).map { i ->
                        a.getJSONObject(i).let { p ->
                            Programme(p.getString("title"), p.optString("description"), p.getLong("start"), p.getLong("end"))
                        }
                    }
                }
            }
            Guide(json.optLong("at"), sources, programmes)
        }.getOrElse {
            DebugLog.warn("PhoneEpg", "corrupted guide – clearing", it)
            Guide(0, emptyList(), emptyMap())
        }
    }

    private companion object {
        const val FILE_NAME = "epg_phone.json"
        const val KEEP_PAST_MS = 2 * 3600_000L
        const val KEEP_AHEAD_MS = 24 * 3600_000L
    }
}
