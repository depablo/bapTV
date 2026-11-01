package com.bonsaponsi.baptv

import android.content.ContentProviderOperation
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * TV guide for internet channels from XMLTV files: the playlist's `x-tvg-url` / `url-tvg` or an address added in
 * the phone remote. Programmes are matched to channels by `tvg-id`, otherwise by name, and written to
 * [TvContract.Programs] of the app's own input – so the TV guide, the info panel, the phone remote and other TV
 * apps show them like a broadcast guide. Refreshed after a source is added, from the phone remote and when the
 * data is older than [REFRESH_AFTER_MS] (player start).
 */
object IptvGuide {

    /** Result of the last refresh, for the phone remote. */
    class Status(val atMs: Long, val channels: Int, val matched: Int, val programmes: Int, val error: String?) {
        fun toJson(): JSONObject = JSONObject().put("at", atMs).put("channels", channels).put("matched", matched)
            .put("programmes", programmes).put("error", error ?: JSONObject.NULL)
    }

    private val running = AtomicBoolean(false)
    private val worker by lazy { BackgroundWorker("iptv-guide") }

    val isRefreshing get() = running.get()

    fun sources(context: Context): List<String> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_SOURCES, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    /** Adds guide addresses (http/https only); returns true when the list changed. */
    fun addSources(context: Context, urls: Collection<String>): Boolean {
        val current = sources(context)
        val new = urls.map { it.trim() }.filter { (it.startsWith("http://") || it.startsWith("https://")) && it !in current }.distinct()
        if (new.isEmpty()) return false
        setSources(context, (current + new).take(MAX_SOURCES))
        DebugLog.log("Guide", "guide sources added: ${new.joinToString()}")
        return true
    }

    fun removeSource(context: Context, url: String) {
        setSources(context, sources(context).filter { it != url })
        DebugLog.log("Guide", "guide source removed: $url")
    }

    private fun setSources(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY_SOURCES, JSONArray(list).toString()).apply()
    }

    fun status(context: Context): Status? = runCatching {
        val o = JSONObject(prefs(context).getString(KEY_STATUS, null) ?: return null)
        val error = if (o.isNull("error")) null else o.optString("error").ifEmpty { null }
        Status(o.getLong("at"), o.optInt("channels"), o.optInt("matched"), o.optInt("programmes"), error)
    }.getOrNull()

    /** Refresh in the background when there are sources and the last refresh is older than [REFRESH_AFTER_MS]. */
    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (sources(app).isEmpty() || Iptv.streams(app).isEmpty()) return@execute
            val last = status(app)?.atMs ?: 0
            if (System.currentTimeMillis() - last >= REFRESH_AFTER_MS) refresh(app)
        }
    }

    fun refreshInBackground(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            refresh(app)
            onDone?.invoke()
        }
    }

    /** Downloads all sources and replaces the guide of the matched internet channels. Returns null when already running. */
    fun refresh(context: Context): Status? {
        if (!running.compareAndSet(false, true)) return null
        try {
            val channels = Iptv.streams(context)
            val now = System.currentTimeMillis()
            val matcher = Matcher(channels.map { (c, s) -> Matcher.Target(c.id, c.name, s.tvgId) })
            val programmes = HashMap<Long, MutableList<Programme>>()
            val errors = ArrayList<String>()
            for (url in sources(context)) {
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val found = download(url) { parse(it, matcher, now - KEEP_PAST_MS, now + KEEP_AHEAD_MS) }
                    val seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000
                    // The first source with a channel's guide wins; later ones only fill channels still missing.
                    found.forEach { (id, list) -> if (programmes[id].isNullOrEmpty()) programmes[id] = list.toMutableList() }
                    DebugLog.log("Guide", "$url: guide for ${found.size} channels, ${found.values.sumOf { it.size }} programmes in $seconds s")
                } catch (e: Exception) {
                    DebugLog.warn("Guide", "guide from $url failed", e)
                    errors += "${url.substringAfter("://").substringBefore('/')}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // A failed source must not wipe the guide it provided before: then only channels with fresh data are
            // rewritten, and the time of the last full success stays, so the next player start tries again.
            val rewrite = if (errors.isEmpty()) channels.map { it.first.id } else programmes.keys.toList()
            val written = write(context, rewrite, programmes)
            ChannelRepository.clearProgrammeCache()
            val at = if (errors.isEmpty()) now else status(context)?.atMs ?: 0
            val status = Status(at, channels.size, programmes.count { it.value.isNotEmpty() }, written, errors.joinToString("; ").ifEmpty { null })
            prefs(context).edit().putString(KEY_STATUS, status.toJson().toString()).apply()
            DebugLog.log("Guide", "internet channels with a guide: ${status.matched}/${status.channels}, programmes $written")
            return status
        } finally {
            running.set(false)
        }
    }

    /** Replaces the programmes of all internet channels (those without a guide are cleared too). */
    private fun write(context: Context, channelIds: List<Long>, programmes: Map<Long, List<Programme>>): Int {
        val ops = ArrayList<ContentProviderOperation>()
        var count = 0
        for (id in channelIds) {
            ops += ContentProviderOperation.newDelete(TvContract.buildProgramsUriForChannel(id)).build()
            programmes[id].orEmpty().forEach { p ->
                ops += ContentProviderOperation.newInsert(Programs.CONTENT_URI)
                    .withValue(Programs.COLUMN_CHANNEL_ID, id)
                    .withValue(Programs.COLUMN_TITLE, p.title)
                    .withValue(Programs.COLUMN_SHORT_DESCRIPTION, p.description.take(MAX_SHORT_DESCRIPTION))
                    .withValue(Programs.COLUMN_LONG_DESCRIPTION, p.description.takeIf { it.length > MAX_SHORT_DESCRIPTION })
                    .withValue(Programs.COLUMN_START_TIME_UTC_MILLIS, p.startMs)
                    .withValue(Programs.COLUMN_END_TIME_UTC_MILLIS, p.endMs)
                    .withValue(Programs.COLUMN_CANONICAL_GENRE, canonicalGenre(p.genre))
                    .build()
                count++
            }
            // Flushed per batch – one transaction for thousands of rows would hold the provider for long.
            if (ops.size >= BATCH) { context.contentResolver.applyBatch(TvContract.AUTHORITY, ops); ops.clear() }
        }
        if (ops.isNotEmpty()) context.contentResolver.applyBatch(TvContract.AUTHORITY, ops)
        return count
    }

    /** General category ([ChannelCategory], the same as DVB genres) → TvContract genre. */
    private fun canonicalGenre(genre: Int): String? = when (genre) {
        ChannelCategory.FILM -> Programs.Genres.MOVIES
        ChannelCategory.NEWS -> Programs.Genres.NEWS
        ChannelCategory.ENTERTAINMENT -> Programs.Genres.ENTERTAINMENT
        ChannelCategory.SPORT -> Programs.Genres.SPORTS
        ChannelCategory.KIDS -> Programs.Genres.FAMILY_KIDS
        ChannelCategory.MUSIC -> Programs.Genres.MUSIC
        ChannelCategory.ARTS -> Programs.Genres.ARTS
        ChannelCategory.EDUCATION -> Programs.Genres.EDUCATION
        ChannelCategory.LEISURE -> Programs.Genres.LIFE_STYLE
        else -> null
    }?.let { Programs.Genres.encode(it) }

    /** Opens the address (following redirects), unpacks gzip (by the magic bytes) and hands the stream over. */
    private fun <T> download(address: String, read: (InputStream) -> T): T {
        var url = java.net.URL(address)
        repeat(5) {
            val c = url.openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", Iptv.USER_AGENT)
                c.setRequestProperty("Accept-Encoding", "gzip")
                val code = c.responseCode
                if (code in 300..399) {
                    url = java.net.URL(url, c.getHeaderField("Location") ?: throw IOException("redirect without location"))
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return c.inputStream.use { read(unpack(LimitedInputStream(it, MAX_DOWNLOAD))) }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /** gzip (also double: a .gz file served with Content-Encoding: gzip) is recognised by its first two bytes. */
    fun unpack(input: InputStream): InputStream {
        var stream: InputStream = BufferedInputStream(input, 64 * 1024)
        repeat(2) {
            stream.mark(2)
            val gzip = stream.read() == 0x1f && stream.read() == 0x8b
            stream.reset()
            if (!gzip) return stream
            stream = BufferedInputStream(GZIPInputStream(stream, 64 * 1024), 64 * 1024)
        }
        return stream
    }

    /**
     * Programmes of the matched channels from an XMLTV document, between [fromMs] and [toMs], by app channel ID.
     * Streamed (SAX): guide files of a whole country are tens of megabytes.
     */
    fun parse(input: InputStream, matcher: Matcher, fromMs: Long, toMs: Long): Map<Long, List<Programme>> {
        val result = HashMap<Long, MutableList<Programme>>()
        val handler = object : DefaultHandler() {
            private val text = StringBuilder()
            private var collecting = false
            // <channel>
            private var channelId: String? = null
            private val names = ArrayList<String>()
            // <programme>
            private var targets: List<Long> = emptyList()
            private var start = 0L
            private var stop = 0L
            private var title: String? = null
            private var description: String? = null
            private var category = ChannelCategory.NONE

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "channel" -> { channelId = attributes.getValue("id"); names.clear() }
                    "programme" -> {
                        targets = matcher.channelsFor(attributes.getValue("channel"))
                        // Times only for the matched channels: a country's file has 100k+ programmes, mostly not ours.
                        start = if (targets.isEmpty()) 0 else attributes.getValue("start")?.let(::parseTime) ?: 0
                        stop = if (targets.isEmpty()) 0 else attributes.getValue("stop")?.let(::parseTime) ?: 0
                        title = null; description = null; category = ChannelCategory.NONE
                    }
                    "display-name", "title", "desc", "category" -> if (channelId != null || targets.isNotEmpty()) { text.setLength(0); collecting = true }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (collecting && text.length < MAX_TEXT) text.append(ch, start, minOf(length, MAX_TEXT - text.length))
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val value = if (collecting) text.toString().trim() else ""
                collecting = false
                when (qName) {
                    "display-name" -> if (channelId != null && value.isNotEmpty()) names += value
                    "channel" -> { channelId?.let { matcher.declare(it, names) }; channelId = null }
                    // The first title/description is the main language one; later ones are translations.
                    "title" -> if (title == null && value.isNotEmpty()) title = value
                    "desc" -> if (description == null && value.isNotEmpty()) description = value
                    "category" -> if (category == ChannelCategory.NONE) category = ChannelCategory.guess(value)
                    "programme" -> {
                        val t = title
                        if (targets.isNotEmpty() && t != null && stop > start && stop > fromMs && start < toMs) {
                            val p = Programme(t, description.orEmpty(), start, stop, category)
                            targets.forEach { result.getOrPut(it) { ArrayList() } += p }
                        }
                        targets = emptyList()
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.newSAXParser().parse(input, handler)
        return result.mapValues { (_, list) -> list.sortedBy { it.startMs }.distinctBy { it.startMs } }
    }

    /**
     * App channels by XMLTV channel: `tvg-id` equal to the XMLTV id (case-insensitive), otherwise a `<display-name>`
     * equal to the channel name (normalised as for categories, without "HD"). Several app channels may share one guide.
     */
    class Matcher(targets: List<Target>) {
        class Target(val channelId: Long, val name: String, val tvgId: String)

        private val byTvgId = targets.filter { it.tvgId.isNotBlank() }.groupBy({ it.tvgId.trim().lowercase() }, { it.channelId })
        private val byName = targets.groupBy({ ChannelCategory.normalize(it.name) }, { it.channelId })
        private val declared = HashMap<String, List<Long>>()

        /** A `<channel>` element: its id and display names. */
        fun declare(xmltvId: String, displayNames: List<String>) {
            val key = xmltvId.trim().lowercase()
            val ids = (byTvgId[key].orEmpty() + displayNames.flatMap { byName[ChannelCategory.normalize(it)].orEmpty() }).distinct()
            if (ids.isNotEmpty()) declared[key] = ids
        }

        /** App channels for a `<programme channel="…">` – also when the file has no `<channel>` element for it. */
        fun channelsFor(xmltvId: String?): List<Long> {
            val key = xmltvId?.trim()?.lowercase() ?: return emptyList()
            return declared[key] ?: byTvgId[key].orEmpty()
        }
    }

    /** XMLTV time: `20261031203000 +0100`; without an offset UTC. Read by hand – called for every matched programme. */
    fun parseTime(value: String): Long? {
        val v = value.trim()
        val length = v.indexOfFirst { !it.isDigit() }.let { if (it < 0) v.length else it }
        if (length < 12) return null
        val digits = v.substring(0, length).padEnd(14, '0')
        fun field(from: Int, to: Int) = digits.substring(from, to).toInt()
        val offset = v.substring(length).trim()
        val offsetSeconds = if (TIME_OFFSET.matches(offset)) {
            (if (offset[0] == '-') -1 else 1) * (offset.substring(1, 3).toInt() * 3600 + offset.substring(3, 5).toInt() * 60)
        } else 0
        return try {
            LocalDateTime.of(field(0, 4), field(4, 6), field(6, 8), field(8, 10), field(10, 12), field(12, 14))
                .toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSeconds)) * 1000
        } catch (e: DateTimeException) {
            null
        }
    }

    private val TIME_OFFSET = Regex("[+-]\\d{4}")

    /** Stops reading after [limit] bytes – a misbehaving server must not fill the memory or run forever. */
    private class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        override fun read(): Int = if (count >= limit) throw IOException("guide too large") else input.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= limit) throw IOException("guide too large")
            return input.read(b, off, minOf(len.toLong(), limit - count).toInt()).also { if (it > 0) count += it }
        }
        override fun close() = input.close()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("iptv_guide", Context.MODE_PRIVATE)

    private const val KEY_SOURCES = "sources"
    private const val KEY_STATUS = "status"
    private const val MAX_SOURCES = 5
    const val REFRESH_AFTER_MS = 12 * 3600_000L
    private const val KEEP_PAST_MS = 6 * 3600_000L
    private const val KEEP_AHEAD_MS = 4 * 24 * 3600_000L
    private const val MAX_DOWNLOAD = 300L * 1024 * 1024
    private const val MAX_TEXT = 4000
    private const val MAX_SHORT_DESCRIPTION = 250
    private const val BATCH = 400
}
