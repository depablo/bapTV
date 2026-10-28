package com.bonsaponsi.baptv

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import org.json.JSONObject

/**
 * Internet channels. They live in TvContract under the app's own input ([IptvInputService]), so the player,
 * the TV guide and other TV apps see them like any other channel. The stream address and the HTTP headers
 * it needs are kept in the channel row ([Channels.COLUMN_INTERNAL_PROVIDER_DATA]).
 */
object Iptv {
    /** Browser-like User-Agent for streams that do not name their own – some servers refuse unknown clients. */
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /** Display numbers of internet channels start here, after the broadcast ones. */
    private const val FIRST_NUMBER = 1001

    /** One stream: from a playlist entry, or stored with a channel. */
    data class Stream(
        val name: String,
        val url: String,
        val group: String = "",
        val logo: String = "",
        val tvgId: String = "",
        val userAgent: String? = null,
        val referrer: String? = null,
        /** Needs DRM keys (Kodi `inputstream` properties) – not playable here. */
        val drm: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().put("name", name).put("url", url).put("group", group).put("logo", logo)
            .put("tvgId", tvgId).put("drm", drm).apply {
                userAgent?.let { put("userAgent", it) }
                referrer?.let { put("referrer", it) }
            }

        companion object {
            fun fromJson(o: JSONObject) = Stream(
                o.optString("name"), o.getString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"),
                o.optString("userAgent").ifEmpty { null }, o.optString("referrer").ifEmpty { null }, o.optBoolean("drm"),
            )
        }
    }

    class Playlist(val streams: List<Stream>, val epgUrl: String?)

    fun inputId(context: Context): String = TvContract.buildInputId(ComponentName(context, IptvInputService::class.java))

    /** Internet channels as player channels, by display number. */
    fun load(context: Context): List<Channel> = query(context).map { (channel, _) -> channel }
        .sortedWith(compareBy({ ChannelRepository.numberKey(it.number) }, { it.name }))

    /** Stored internet channels with their streams. */
    fun streams(context: Context): List<Pair<Channel, Stream>> = query(context)

    fun stream(context: Context, channelId: Long): Stream? = query(context, channelId).firstOrNull()?.second

    private fun query(context: Context, channelId: Long? = null): List<Pair<Channel, Stream>> {
        val inputId = inputId(context)
        val uri = if (channelId != null) TvContract.buildChannelUri(channelId) else TvContract.buildChannelsUriForInput(inputId)
        val projection = arrayOf(Channels._ID, Channels.COLUMN_DISPLAY_NUMBER, Channels.COLUMN_DISPLAY_NAME,
            Channels.COLUMN_SERVICE_TYPE, Channels.COLUMN_INTERNAL_PROVIDER_DATA, Channels.COLUMN_INPUT_ID)
        val result = ArrayList<Pair<Channel, Stream>>()
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(5) != inputId) continue
                    val stream = c.getBlob(4)?.let { runCatching { Stream.fromJson(JSONObject(String(it))) }.getOrNull() } ?: continue
                    result += Channel(
                        id = c.getLong(0),
                        number = c.getString(1).orEmpty(),
                        name = c.getString(2).orEmpty(),
                        isRadio = c.getString(3) == Channels.SERVICE_TYPE_AUDIO,
                        videoFormat = null,
                        inputId = inputId,
                    ) to stream
                }
            }
        } catch (e: Exception) {
            DebugLog.error("Iptv", "cannot read internet channels", e)
        }
        return result
    }

    /** Adds channels (streams already stored are skipped); returns how many were added. */
    fun add(context: Context, streams: List<Stream>): Int {
        val existing = query(context)
        val known = existing.map { it.second.url }.toSet()
        var number = (existing.mapNotNull { it.first.number.toIntOrNull() }.maxOrNull() ?: (FIRST_NUMBER - 1)) + 1
        val inputId = inputId(context)
        var added = 0
        streams.filter { it.url !in known }.distinctBy { it.url }.forEach { s ->
            val values = ContentValues().apply {
                put(Channels.COLUMN_INPUT_ID, inputId)
                put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER)
                put(Channels.COLUMN_SERVICE_TYPE, Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(Channels.COLUMN_DISPLAY_NUMBER, (number++).toString())
                put(Channels.COLUMN_DISPLAY_NAME, s.name.ifBlank { s.url })
                put(Channels.COLUMN_DESCRIPTION, s.group)
                put(Channels.COLUMN_INTERNAL_PROVIDER_DATA, s.toJson().toString().toByteArray())
            }
            if (context.contentResolver.insert(Channels.CONTENT_URI, values) != null) added++
        }
        DebugLog.log("Iptv", "added $added of ${streams.size} internet channels")
        return added
    }

    fun remove(context: Context, channelIds: Collection<Long>): Int {
        val own = query(context).map { it.first.id }.toSet()
        val removed = channelIds.filter { it in own }.sumOf { context.contentResolver.delete(TvContract.buildChannelUri(it), null, null) }
        DebugLog.log("Iptv", "removed $removed internet channels")
        return removed
    }

    /** Downloads a playlist (up to [limit] bytes), following redirects. */
    fun download(address: String, limit: Int): String {
        var url = java.net.URL(address)
        repeat(5) {
            val c = url.openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", USER_AGENT)
                val code = c.responseCode
                if (code in 300..399) {
                    url = java.net.URL(url, c.getHeaderField("Location") ?: throw java.io.IOException("redirect without location"))
                    return@repeat
                }
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                val bytes = c.inputStream.use { it.readNBytesCompat(limit + 1) }
                if (bytes.size > limit) throw java.io.IOException("playlist too large")
                return String(bytes, Charsets.UTF_8)
            } finally {
                c.disconnect()
            }
        }
        throw java.io.IOException("too many redirects")
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() < max) {
            val n = read(chunk, 0, minOf(chunk.size, max - out.size()))
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    /** Playlist addresses used before, most recent first. */
    fun sources(context: Context): List<String> = runCatching {
        val array = org.json.JSONArray(prefs(context).getString(KEY_SOURCES, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    fun rememberSource(context: Context, url: String) {
        val list = (listOf(url) + sources(context).filter { it != url }).take(MAX_SOURCES)
        prefs(context).edit().putString(KEY_SOURCES, org.json.JSONArray(list).toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private const val KEY_SOURCES = "sources"
    private const val MAX_SOURCES = 8

    /**
     * M3U / M3U8 playlist (the IPTV "extended M3U" dialect): `#EXTINF` attributes (tvg-id, tvg-logo, group-title,
     * http-user-agent, http-referrer), `#EXTVLCOPT` headers, `#EXTGRP` groups and `#KODIPROP` DRM properties.
     */
    fun parseM3u(text: String): Playlist {
        val streams = ArrayList<Stream>()
        var epgUrl: String? = null
        var info: Map<String, String> = emptyMap()
        var name = ""
        var group = ""
        var userAgent: String? = null
        var referrer: String? = null
        var drm = false
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> epgUrl = attributes(line).let { it["x-tvg-url"] ?: it["url-tvg"] }?.split(',')?.firstOrNull()?.trim()
                line.startsWith("#EXTINF") -> {
                    name = commaTitle(line)
                    info = attributes(line.removeSuffix(name))
                    group = info["group-title"].orEmpty()
                    userAgent = info["http-user-agent"]
                    referrer = info["http-referrer"]
                    drm = false
                }
                line.startsWith("#EXTGRP:") -> if (group.isEmpty()) group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val (key, value) = line.substringAfter(':').split('=', limit = 2).let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                    when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer" -> referrer = value
                    }
                }
                line.startsWith("#KODIPROP:") -> if ("license" in line || "drm" in line) drm = true
                line.startsWith("#") -> Unit
                else -> {
                    streams += Stream(name.ifBlank { line }, line, group, info["tvg-logo"].orEmpty(), info["tvg-id"].orEmpty(),
                        userAgent, referrer, drm)
                    info = emptyMap(); name = ""; group = ""; userAgent = null; referrer = null; drm = false
                }
            }
        }
        return Playlist(streams, epgUrl)
    }

    private val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    /** The title after the comma that ends the attributes (a comma inside a quoted attribute does not count). */
    private fun commaTitle(line: String): String {
        var quoted = false
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return line.substring(i + 1).trim()
        }
        return ""
    }
}
