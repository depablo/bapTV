package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Results of the phone's own check of internet channels ([IptvProbe]), kept per channel address with the time of
 * the check. The check runs over the phone's connection, not the set-top box's, so the screen says so. Kept in a
 * file of its own: the channel records stay as they are. Call from a worker.
 */
class PhoneIptvChecks(context: Context) {

    class Entry(val status: IptvProbe.Status, val detail: String, val speed: Double?, val checkedAt: Long)

    private val file = File(context.filesDir, FILE_NAME)
    private var cached: Map<String, Entry>? = null

    @Synchronized
    fun load(): Map<String, Entry> = cached ?: read().also { cached = it }

    private fun read(): Map<String, Entry> = StoredFile.readList(file, "PhoneIptvChecks") { o ->
        o.getString("url") to Entry(
            IptvProbe.Status.valueOf(o.getString("status")), o.optString("detail"),
            if (o.has("speed")) o.getDouble("speed") else null, o.getLong("checkedAt"),
        )
    }.toMap()

    /** Stores the results of one batch; [results] pairs each channel address with its result. */
    @Synchronized
    fun put(results: List<Pair<String, IptvProbe.Result>>, now: Long) {
        val current = load().toMutableMap()
        results.forEach { (url, r) -> current[url] = Entry(r.status, r.detail, r.speed, now) }
        save(current)
    }

    @Synchronized
    fun remove(url: String) {
        val current = load()
        if (url in current) save(current - url)
    }

    private fun save(checks: Map<String, Entry>) {
        val array = JSONArray()
        checks.forEach { (url, e) ->
            array.put(JSONObject().put("url", url).put("status", e.status.name).put("detail", e.detail)
                .put("checkedAt", e.checkedAt).apply { e.speed?.let { put("speed", it) } })
        }
        StoredFile.write(file, array.toString())
        cached = checks
    }

    private companion object {
        const val FILE_NAME = "iptv_phone_checks.json"
    }
}
