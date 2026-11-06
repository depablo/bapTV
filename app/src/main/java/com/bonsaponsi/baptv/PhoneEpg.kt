package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Programme guide of the phone's internet channels. The XMLTV files named by the playlists are read with the TV's
 * parser ([IptvGuide]), but the programmes go to a file of the phone app, not to TvContract: the phone has no TV
 * provider. Programmes are kept per channel address, so they follow the channel's entry in [PhoneIptvStore].
 * The file is read lazily ([load] on the worker); until then the guide is empty.
 */
class PhoneEpg(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /**
     * What the refreshes found: the last full success ([atMs]), the last attempt of any result ([triedMs]), the
     * XMLTV sources, and the programmes by channel address.
     */
    class Guide(val atMs: Long, val triedMs: Long, val sources: List<String>, val programmes: Map<String, List<Programme>>)

    @Volatile
    private var guide: Guide = Guide(0, 0, emptyList(), emptyMap())
    @Volatile
    private var loaded = false
    /** Held for a whole refresh (minutes) – the short changes above only take the object's own lock. */
    private val refreshLock = Any()

    /** The programme on now on a channel address, or null. */
    fun now(url: String, nowMs: Long): Programme? = guide.programmes[url].orEmpty().firstOrNull { it.isOn(nowMs) }

    /** When the programme on now ends first – the time the "now" lines need redrawing; null – no guide. */
    fun nextChangeMs(nowMs: Long): Long? =
        guide.programmes.values.mapNotNull { list -> list.firstOrNull { nowMs < it.endMs }?.let { if (it.isOn(nowMs)) it.endMs else it.startMs } }
            .minOrNull()

    /**
     * Sources exist, the last full refresh is older than [IptvGuide.REFRESH_AFTER_MS], and the last attempt is
     * older than [IptvGuide.RETRY_AFTER_MS] – a dead source must not download the others at every app start.
     */
    fun isStale(nowMs: Long): Boolean = guide.sources.isNotEmpty() && IptvGuide.isStale(nowMs, guide.atMs, guide.triedMs)

    /** Reads the stored guide, once. Runs on the worker. */
    @Synchronized
    fun load() {
        if (loaded) return
        guide = read()
        loaded = true
    }

    /** Adds XMLTV sources (http/https only) named by a playlist; returns how many were new. */
    @Synchronized
    fun addSources(urls: Collection<String>): Int {
        load()
        val added = urls.map { it.trim() }
            .filter { (it.startsWith("http://") || it.startsWith("https://")) && it !in guide.sources }.distinct()
        if (added.isEmpty()) return 0
        // The newest sources are kept: an old playlist's guide is the one most likely gone.
        guide = Guide(guide.atMs, 0, (guide.sources + added).takeLast(MAX_SOURCES), guide.programmes)
        save(guide)
        return added.size
    }

    /**
     * Downloads every source and matches its programmes to the streams. A source that fails keeps the programmes
     * it gave before; one that matches no channel is dropped. Without channels the sources are dropped too. Runs on the worker; returns how many channels
     * have a guide.
     */
    fun refresh(streams: List<Iptv.Stream>): Int = synchronized(refreshLock) {
        load()
        if (streams.isEmpty()) {
            update { if (it.sources.isEmpty() && it.programmes.isEmpty()) null else Guide(0, 0, emptyList(), emptyMap()) }
            return 0
        }
        val sources = guide.sources
        if (sources.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val matcher = IptvGuide.Matcher(streams.mapIndexed { i, s -> IptvGuide.Matcher.Target(i.toLong(), s.name, s.tvgId) })
        val found = HashMap<String, List<Programme>>()
        var failed = false
        // Read fine but matched none of the channels: its playlist's channels are gone – the source goes too.
        val unused = HashSet<String>()
        for (source in sources) {
            try {
                val byIndex = IptvGuide.download(source) { IptvGuide.parse(it, matcher, now - KEEP_PAST_MS, now + KEEP_AHEAD_MS) }
                byIndex.forEach { (index, list) ->
                    val url = streams.getOrNull(index.toInt())?.url ?: return@forEach
                    // The first source with a channel's guide wins; later ones only fill channels still missing.
                    // Descriptions are not shown on the phone, so they are not kept.
                    if (found[url].isNullOrEmpty()) found[url] = list.map { it.copy(description = "") }
                }
                DebugLog.log("PhoneEpg", "$source: guide for ${byIndex.size} channels")
                if (byIndex.isEmpty()) unused += source
            } catch (e: Exception) {
                failed = true
                DebugLog.warn("PhoneEpg", "guide from $source failed", e)
            }
        }
        // Only a full success moves the refresh time; a failed source is tried again after [IptvGuide.RETRY_AFTER_MS].
        val urls = streams.mapTo(HashSet()) { it.url }
        // Sources added during the download are kept (they are read at the next refresh).
        if (unused.isNotEmpty()) DebugLog.log("PhoneEpg", "dropping sources with no matching channel: ${unused.joinToString()}")
        update { g -> Guide(if (failed) g.atMs else now, now, g.sources - unused, (if (failed) g.programmes + found else found).filterKeys { it in urls }) }
        return found.size
    }

    /** Replaces the guide with [change] of the current one (null – no change) and saves it. */
    @Synchronized
    private fun update(change: (Guide) -> Guide?) {
        guide = change(guide)?.also { save(it) } ?: return
    }

    private fun save(value: Guide) {
        val programmes = JSONObject()
        value.programmes.forEach { (url, list) ->
            programmes.put(url, JSONArray().apply {
                list.forEach { p -> put(JSONObject().put("title", p.title).put("start", p.startMs).put("end", p.endMs)) }
            })
        }
        val json = JSONObject().put("at", value.atMs).put("tried", value.triedMs)
            .put("sources", JSONArray(value.sources))
            .put("programmes", programmes)
        runCatching { StoredFile.write(file, json.toString()) }.onFailure { DebugLog.warn("PhoneEpg", "cannot save the guide", it) }
    }

    private fun read(): Guide {
        val empty = Guide(0, 0, emptyList(), emptyMap())
        return runCatching {
            val json = JSONObject(StoredFile.read(file) ?: return empty)
            val sources = json.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } }
            val programmes = HashMap<String, List<Programme>>()
            json.getJSONObject("programmes").let { obj ->
                obj.keys().forEach { url ->
                    val a = obj.getJSONArray(url)
                    programmes[url] = (0 until a.length()).map { i ->
                        a.getJSONObject(i).let { p -> Programme(p.getString("title"), "", p.getLong("start"), p.getLong("end")) }
                    }
                }
            }
            Guide(json.optLong("at"), json.optLong("tried"), sources, programmes)
        }.getOrElse {
            DebugLog.warn("PhoneEpg", "corrupted guide – clearing", it)
            empty
        }
    }

    private companion object {
        const val FILE_NAME = "epg_phone.json"
        const val KEEP_PAST_MS = 2 * 3600_000L
        const val KEEP_AHEAD_MS = 24 * 3600_000L
        const val MAX_SOURCES = 10
    }
}
