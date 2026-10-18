package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A programme in the TV guide (UTC times in milliseconds). */
data class Programme(
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
    /** General content_descriptor category (EN 300 468, table 29), -1 – none; name: `Context.genreLabel`. */
    val genre: Int = -1,
) {
    fun isOn(nowMs: Long) = nowMs in startMs until endMs

    /** Programme progress 0..1000 (for the progress bar). */
    fun progress(nowMs: Long): Int =
        if (endMs <= startMs) 0 else (((nowMs - startMs) * 1000) / (endMs - startMs)).coerceIn(0, 1000).toInt()
}

/** Operations on programme lists – no Android dependencies (unit tests). */
object EpgSchedule {
    /** How much of the past we keep (e.g. for "earlier today" in the guide). */
    const val KEEP_PAST_MS = 6 * 3600_000L

    /** Current and next programme. */
    fun nowNext(list: List<Programme>, nowMs: Long): Pair<Programme?, Programme?> {
        val index = list.indexOfFirst { it.endMs > nowMs }
        if (index < 0) return null to null
        val first = list[index]
        return if (first.startMs <= nowMs) first to list.getOrNull(index + 1) else null to first
    }

    /**
     * Merges the stored guide with a freshly read one: new programmes replace old ones that
     * overlap them (schedule changes), old ones outside the new range stay. Removes long-finished programmes.
     */
    fun merge(old: List<Programme>, fresh: List<Programme>, nowMs: Long): List<Programme> {
        val sortedFresh = fresh.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val kept = old.filter { o -> sortedFresh.none { n -> o.startMs < n.endMs && n.startMs < o.endMs } }
        return (kept + sortedFresh)
            .filter { it.endMs > nowMs - KEEP_PAST_MS }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
    }

    fun fromEvents(events: Collection<TsAnalyzer.Event>): List<Programme> =
        events.map { Programme(it.title, it.description, it.startMs, it.endMs, it.genre) }.sortedBy { it.startMs }
}

/**
 * TV guide read from recording EIT ([TsProbe]). One recording contains the EPG of the whole multiplex
 * for about 7 days, so data is kept per multiplex (ONID, TSID), and within it per service (SID).
 * JSON files in the app directory, with an in-memory cached copy.
 */
class EpgStore private constructor(private val dir: File) {

    private class Mux(val updatedAt: Long, val services: MutableMap<Int, List<Programme>>)

    private val cache = HashMap<String, Mux>()
    /** Multiplexes with a corrupted EPG file – we don't read them on every query. */
    private val broken = HashSet<String>()

    private fun key(onid: Int, tsid: Int) = "${onid}_$tsid"
    private fun file(key: String) = File(dir, "epg_$key.json")

    @Synchronized
    private fun mux(onid: Int, tsid: Int): Mux? {
        val key = key(onid, tsid)
        cache[key]?.let { return it }
        val f = file(key)
        if (key in broken) return null
        val text = runCatching { StoredFile.read(f) }.getOrNull() ?: return null
        return runCatching { parse(JSONObject(text)) }
            // A corrupted file is read once – until the next EPG update of the multiplex.
            .onFailure { DebugLog.warn(TAG, "corrupted EPG file $f – skipping", it); broken += key }
            .getOrNull()?.also { cache[key] = it }
    }

    fun programmes(onid: Int?, tsid: Int?, sid: Int?): List<Programme> {
        if (onid == null || tsid == null || sid == null) return emptyList()
        return mux(onid, tsid)?.services?.get(sid).orEmpty()
    }

    /** Time of the last EPG update of the multiplex (0 – no data). */
    fun updatedAt(onid: Int?, tsid: Int?): Long =
        if (onid == null || tsid == null) 0 else mux(onid, tsid)?.updatedAt ?: 0

    /** Saves the multiplex EPG from a recording. */
    @Synchronized
    fun update(onid: Int, tsid: Int, events: Map<Int, Collection<TsAnalyzer.Event>>, nowMs: Long = System.currentTimeMillis()) {
        if (events.values.all { it.isEmpty() }) return
        val old = mux(onid, tsid)
        val services = HashMap(old?.services.orEmpty())
        events.forEach { (sid, list) ->
            services[sid] = EpgSchedule.merge(services[sid].orEmpty(), EpgSchedule.fromEvents(list), nowMs)
        }
        val mux = Mux(nowMs, services)
        cache[key(onid, tsid)] = mux
        broken -= key(onid, tsid)
        runCatching { StoredFile.write(file(key(onid, tsid)), toJson(mux).toString()) }
            .onFailure { DebugLog.warn(TAG, "saving EPG failed", it) }
        DebugLog.log(TAG, "EPG of multiplex $onid/$tsid: " + services.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value.size}" })
    }

    private fun toJson(m: Mux) = JSONObject().put("updatedAt", m.updatedAt).put("services", JSONObject().apply {
        m.services.forEach { (sid, list) ->
            put(sid.toString(), JSONArray().apply {
                list.forEach { p -> put(JSONArray().put(p.startMs).put(p.endMs).put(p.title).put(p.description).put(p.genre)) }
            })
        }
    })

    private fun parse(o: JSONObject): Mux {
        val services = HashMap<Int, List<Programme>>()
        val s = o.getJSONObject("services")
        s.keys().forEach { sid ->
            val a = s.getJSONArray(sid)
            services[sid.toInt()] = (0 until a.length()).map { i ->
                val e = a.getJSONArray(i)
                Programme(e.getString(2), e.optString(3), e.getLong(0), e.getLong(1), e.optInt(4, -1))
            }
        }
        return Mux(o.optLong("updatedAt"), services)
    }

    companion object {
        private const val TAG = "EPG"
        /** EPG older than this is refreshed with a longer recording while watching the channel. */
        const val REFRESH_MS = 6 * 3600_000L

        @Volatile private var instance: EpgStore? = null

        fun get(context: Context): EpgStore = instance ?: synchronized(this) {
            instance ?: EpgStore(File(context.applicationContext.filesDir, "epg").apply { mkdirs() }).also { instance = it }
        }
    }
}
