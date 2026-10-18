package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Channel HbbTV data read from a stream recording ([TsProbe]): the service's DVB identifiers
 * and the apps from the AIT. Stored because learning a channel takes several seconds of recording.
 */
class HbbTvStore(context: Context) {

    data class App(val orgId: Long, val appId: Int, val control: String, val name: String, val url: String)

    data class Entry(
        val channelId: Long,
        val onid: Int?,
        val tsid: Int?,
        val sid: Int?,
        val scrambled: Boolean,
        val hasVideo: Boolean,
        val apps: List<App>,
        val learnedAt: Long,
        /** Null in data from before 2.8 – the channel then has to be learned again. */
        val hasAudio: Boolean? = null,
    ) {
        val autostart get() = apps.firstOrNull { it.control == "AUTOSTART" }

        /** Video without audio plus an HbbTV app: a static card on air, the programme comes over the internet (virtual channel). */
        val looksVirtual get() = hasVideo && hasAudio == false && autostart != null

        fun toJson(): JSONObject = JSONObject()
            .put("channelId", channelId).put("onid", onid).put("tsid", tsid).put("sid", sid)
            .put("scrambled", scrambled).put("hasVideo", hasVideo).put("learnedAt", learnedAt)
            .put("hasAudio", hasAudio ?: JSONObject.NULL)
            .put("apps", JSONArray().apply {
                apps.forEach { put(JSONObject().put("orgId", it.orgId).put("appId", it.appId).put("control", it.control).put("name", it.name).put("url", it.url)) }
            })

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.getLong("channelId"),
                o.optInt("onid", -1).takeIf { it >= 0 },
                o.optInt("tsid", -1).takeIf { it >= 0 },
                o.optInt("sid", -1).takeIf { it >= 0 },
                o.optBoolean("scrambled"),
                o.optBoolean("hasVideo", true),
                o.optJSONArray("apps")?.let { a ->
                    (0 until a.length()).map { i ->
                        a.getJSONObject(i).let { App(it.getLong("orgId"), it.getInt("appId"), it.getString("control"), it.optString("name"), it.getString("url")) }
                    }
                }.orEmpty(),
                o.optLong("learnedAt"),
                if (o.has("hasAudio") && !o.isNull("hasAudio")) o.getBoolean("hasAudio") else null,
            )
        }
    }

    private val prefs = context.getSharedPreferences("hbbtv", Context.MODE_PRIVATE)

    fun get(channelId: Long): Entry? =
        prefs.getString("ch_$channelId", null)?.let { runCatching { Entry.fromJson(JSONObject(it)) }.getOrNull() }

    fun put(entry: Entry) {
        prefs.edit().putString("ch_${entry.channelId}", entry.toJson().toString()).apply()
    }

    fun all(): List<Entry> = prefs.all.keys.filter { it.startsWith("ch_") }
        .mapNotNull { get(it.removePrefix("ch_").toLongOrNull() ?: return@mapNotNull null) }

    /**
     * Channels re-created under new IDs (published to TvContract, [ChannelPublisher]) take over the learned data
     * of the same service (TSID + SID), so the radio/scrambled/virtual marks and HbbTV apps stay.
     */
    fun carryOver(channels: List<Channel>) {
        val missing = channels.filter { it.tsid != null && it.sid != null && !prefs.contains("ch_${it.id}") }
        if (missing.isEmpty()) return
        val bySid = all().filter { it.tsid != null && it.sid != null }.groupBy { it.tsid to it.sid }
            .mapValues { (_, list) -> list.maxBy { it.learnedAt } }
        var copied = 0
        missing.forEach { ch -> bySid[ch.tsid to ch.sid]?.let { put(it.copy(channelId = ch.id)); copied++ } }
        if (copied > 0) DebugLog.log("HbbTV", "learned data carried over to $copied channels with new IDs")
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }

    companion object {
        /** Data older than a week is refreshed while the channel is watched (the AIT may change). */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
    }
}
