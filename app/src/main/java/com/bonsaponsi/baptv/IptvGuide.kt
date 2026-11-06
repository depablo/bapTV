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
    @Synchronized
    fun addSources(context: Context, urls: Collection<String>): Boolean {
        val current = sources(context)
        val new = urls.map { it.trim() }.filter { (it.startsWith("http://") || it.startsWith("https://")) && it !in current }.distinct()
        if (new.isEmpty()) return false
        setSources(context, (current + new).take(MAX_SOURCES))
        DebugLog.log("Guide", "guide sources added: ${new.joinToString()}")
        return true
    }

    @Synchronized
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

    /**
     * Refresh in the background when there are sources and the last full refresh is older than [REFRESH_AFTER_MS].
     * After a failed attempt the next one waits [RETRY_AFTER_MS]: one dead source must not download all the others
     * again at every player start.
     */
    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (sources(app).isEmpty() || Iptv.streams(app).isEmpty()) return@execute
            val now = System.currentTimeMillis()
            if (isStale(now, status(app)?.atMs ?: 0, prefs(app).getLong(KEY_TRIED, 0))) refresh(app)
        }
    }

    /** The last full success is old and the last attempt (of any result) is not too recent. */
    fun isStale(nowMs: Long, successMs: Long, triedMs: Long): Boolean =
        nowMs - successMs >= REFRESH_AFTER_MS && nowMs - triedMs >= RETRY_AFTER_MS

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
            prefs(context).edit().putLong(KEY_TRIED, now).apply()
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
            // Channels removed during the (minutes long) download are left out – their rows would fail the batch.
            val existing = Iptv.streams(context).mapTo(HashSet()) { it.first.id }
            val written = write(context, rewrite.filter { it in existing }, programmes, errors)
            ChannelRepository.clearProgrammeCache()
            val at = if (errors.isEmpty()) now else status(context)?.atMs ?: 0
            // After a partial failure the older guide of the other channels stays, so it is counted from the provider.
            val (matched, total) = if (errors.isEmpty()) programmes.count { it.value.isNotEmpty() } to written
            else stored(context, existing)
            val status = Status(at, channels.size, matched, total, errors.joinToString("; ").ifEmpty { null })
            prefs(context).edit().putString(KEY_STATUS, status.toJson().toString()).apply()
            DebugLog.log("Guide", "internet channels with a guide: ${status.matched}/${status.channels}, programmes written $written")
            return status
        } finally {
            running.set(false)
        }
    }

    /**
     * Replaces the programmes of the given channels (those without a guide are cleared too). A batch that fails is
     * logged and added to [errors]; the following ones are still written. Returns how many programmes were written.
     */
    private fun write(context: Context, channelIds: List<Long>, programmes: Map<Long, List<Programme>>, errors: MutableList<String>): Int {
        val ops = ArrayList<ContentProviderOperation>()
        var pending = 0
        var bytes = 0L
        var count = 0
        fun flush() {
            if (ops.isEmpty()) return
            try {
                context.contentResolver.applyBatch(TvContract.AUTHORITY, ops)
                count += pending
            } catch (e: Exception) {
                DebugLog.warn("Guide", "writing ${ops.size} guide rows failed", e)
                errors += "write: ${e.message ?: e.javaClass.simpleName}"
            }
            ops.clear()
            pending = 0
            bytes = 0
        }
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
                pending++
                // Strings travel as UTF-16; the description goes in up to two columns.
                bytes += ROW_OVERHEAD + 2L * (p.title.length + 2 * p.description.length)
                // Flushed per batch – one transaction for thousands of rows would hold the provider for long, and
                // a Binder transaction is limited to about 1 MB.
                if (ops.size >= BATCH || bytes >= MAX_BATCH_BYTES) flush()
            }
        }
        flush()
        return count
    }

    /** Channels with a guide and their programmes, as stored in the provider. */
    private fun stored(context: Context, channelIds: Collection<Long>): Pair<Int, Int> {
        var channels = 0
        var programmes = 0
        for (id in channelIds) {
            val n = runCatching {
                context.contentResolver.query(TvContract.buildProgramsUriForChannel(id), arrayOf(Programs._ID), null, null, null)
                    ?.use { it.count } ?: 0
            }.getOrDefault(0)
            if (n > 0) channels++
            programmes += n
        }
        return channels to programmes
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
    internal fun <T> download(address: String, read: (InputStream) -> T): T {
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
                // Both the download and the unpacked data are limited, in size and time: a gzip bomb or a server
                // sending a byte now and then must not keep the worker (and further refreshes) busy for hours.
                val deadline = android.os.SystemClock.elapsedRealtime() + MAX_DOWNLOAD_TIME_MS
                return c.inputStream.use {
                    read(LimitedInputStream(unpack(LimitedInputStream(it, MAX_DOWNLOAD, deadline)), MAX_UNPACKED, deadline))
                }
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
        // By XMLTV channel first: which one an app channel takes is decided at the end ([Matcher.resolve]).
        val result = LinkedHashMap<String, MutableList<Programme>>()
        val handler = object : DefaultHandler() {
            private val text = StringBuilder()
            private var collecting = false
            // <channel>
            private var channelId: String? = null
            private val names = ArrayList<String>()
            // <programme>
            private var key: String? = null
            private var start = 0L
            private var stop = 0L
            private var title: String? = null
            private var description: String? = null
            private var category = ChannelCategory.NONE

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                when (qName) {
                    "channel" -> { channelId = attributes.getValue("id"); names.clear() }
                    "programme" -> {
                        key = matcher.keyFor(attributes.getValue("channel"))
                        // Times only for the matched channels: a country's file has 100k+ programmes, mostly not ours.
                        start = if (key == null) 0 else attributes.getValue("start")?.let(::parseTime) ?: 0
                        // "stop" is optional in XMLTV (0 – taken from the next programme later).
                        stop = if (key == null) 0 else attributes.getValue("stop")?.let(::parseTime)?.takeIf { it > start } ?: 0
                        title = null; description = null; category = ChannelCategory.NONE
                    }
                    "display-name", "title", "desc", "category" -> if (channelId != null || key != null) { text.setLength(0); collecting = true }
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
                        val k = key
                        // A start that did not parse would put the programme in 1970, covering "now" for ever.
                        if (k != null && t != null && start > 0 && start < toMs && (stop == 0L || stop > fromMs)) {
                            result.getOrPut(k) { ArrayList() } += Programme(t, description.orEmpty(), start, stop, category)
                        }
                        key = null
                    }
                }
            }
        }
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        // Limits entity expansion ("billion laughs" in an internal DTD) where the parser supports it – Android's
        // parser does not, so such a DTD is also refused before parsing ([refuseEntities]).
        runCatching { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        factory.newSAXParser().parse(refuseEntities(input), handler)
        return matcher.resolve(result.mapValues { (_, list) -> complete(list, fromMs) }.filterValues { it.isNotEmpty() })
    }

    /** Sorted programmes of one channel; a missing end is the next programme's start ([DEFAULT_LENGTH_MS] for the last). */
    private fun complete(list: List<Programme>, fromMs: Long): List<Programme> {
        val sorted = list.sortedBy { it.startMs }.distinctBy { it.startMs }
        return sorted.mapIndexed { i, p ->
            if (p.endMs > 0) p else p.copy(endMs = sorted.getOrNull(i + 1)?.startMs ?: (p.startMs + DEFAULT_LENGTH_MS))
        }.filter { it.endMs > fromMs }
    }

    /**
     * App channels by XMLTV channel: `tvg-id` equal to the XMLTV id (case-insensitive), otherwise a `<display-name>`
     * equal to the channel name (normalised as for categories, without "HD"). Several app channels may share one
     * guide, but an app channel takes only one XMLTV channel: the one its `tvg-id` names, otherwise the first one
     * with its name – so variants with the same name (SD, "+1") do not mix into its guide.
     */
    class Matcher(targets: List<Target>) {
        class Target(val channelId: Long, val name: String, val tvgId: String)

        private val byTvgId = targets.filter { it.tvgId.isNotBlank() }.groupBy({ it.tvgId.trim().lowercase() }, { it.channelId })
        private val byName = targets.groupBy({ ChannelCategory.normalize(it.name) }, { it.channelId })
        private val declaredByName = HashMap<String, List<Long>>()

        /** A `<channel>` element: its id and display names. */
        fun declare(xmltvId: String, displayNames: List<String>) {
            val key = xmltvId.trim().lowercase()
            val ids = displayNames.flatMap { byName[ChannelCategory.normalize(it)].orEmpty() }.distinct()
            if (ids.isNotEmpty()) declaredByName[key] = ids
        }

        /**
         * The key under which the programmes of a `<programme channel="…">` are collected, null when no app channel
         * can take them – also matched by `tvg-id` when the file has no `<channel>` element for it.
         */
        fun keyFor(xmltvId: String?): String? {
            val key = xmltvId?.trim()?.lowercase() ?: return null
            return key.takeIf { byTvgId.containsKey(it) || declaredByName.containsKey(it) }
        }

        /** Programmes by XMLTV channel (document order) → programmes by app channel. */
        fun resolve(byKey: Map<String, List<Programme>>): Map<Long, List<Programme>> {
            val result = HashMap<Long, List<Programme>>()
            byKey.forEach { (key, list) -> byTvgId[key]?.forEach { result[it] = list } }
            byKey.forEach { (key, list) -> declaredByName[key]?.forEach { id -> result.putIfAbsent(id, list) } }
            return result
        }
    }

    /** XMLTV time: `20261031203000 +0100`; without an offset UTC. Read by hand – called for every matched programme. */
    /**
     * The stream unchanged, after a look at its start: entities can be declared only in the DTD before the root
     * element, and XMLTV never needs them – a file declaring any, or with no `<tv` root in the first
     * [PROLOG_BYTES], is refused.
     */
    fun refuseEntities(input: InputStream): InputStream {
        val head = ByteArray(PROLOG_BYTES)
        var n = 0
        while (n < head.size) {
            val read = input.read(head, n, head.size - n)
            if (read < 0) break
            n += read
        }
        val start = String(head, 0, n, Charsets.ISO_8859_1)
        val root = start.indexOf("<tv")
        if (root < 0 && n == head.size) throw IOException("no <tv> element in the first ${PROLOG_BYTES / 1024} KB – not XMLTV")
        if (start.substring(0, if (root < 0) n else root).contains("<!ENTITY")) throw IOException("XMLTV declares entities – refused")
        return java.io.SequenceInputStream(java.io.ByteArrayInputStream(head, 0, n), input)
    }

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

    /**
     * Stops reading after [limit] bytes or at [deadlineMs] (elapsed realtime) – a misbehaving server must not fill
     * the memory or run forever.
     */
    internal class LimitedInputStream(
        private val input: InputStream,
        private val limit: Long,
        private val deadlineMs: Long = Long.MAX_VALUE,
        private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    ) : InputStream() {
        private var count = 0L
        private fun check() {
            if (count >= limit) throw IOException("guide too large")
            if (deadlineMs != Long.MAX_VALUE && clock() > deadlineMs) throw IOException("guide download too slow")
        }
        override fun read(): Int { check(); return input.read().also { if (it >= 0) count++ } }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            check()
            return input.read(b, off, minOf(len.toLong(), limit - count).toInt()).also { if (it > 0) count += it }
        }
        override fun close() = input.close()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("iptv_guide", Context.MODE_PRIVATE)

    private const val KEY_SOURCES = "sources"
    private const val KEY_STATUS = "status"
    /** Time of the last refresh attempt, successful or not. */
    private const val KEY_TRIED = "tried"
    private const val MAX_SOURCES = 5
    const val REFRESH_AFTER_MS = 12 * 3600_000L
    const val RETRY_AFTER_MS = 3600_000L
    private const val KEEP_PAST_MS = 6 * 3600_000L
    private const val KEEP_AHEAD_MS = 4 * 24 * 3600_000L
    private const val MAX_DOWNLOAD = 300L * 1024 * 1024
    private const val MAX_UNPACKED = 1024L * 1024 * 1024
    private const val MAX_DOWNLOAD_TIME_MS = 20 * 60_000L
    /** Length of the last programme of a channel when the file gives no end. */
    private const val DEFAULT_LENGTH_MS = 3600_000L
    private const val MAX_TEXT = 4000
    private const val PROLOG_BYTES = 64 * 1024
    private const val MAX_SHORT_DESCRIPTION = 250
    private const val BATCH = 400
    private const val MAX_BATCH_BYTES = 256 * 1024L
    private const val ROW_OVERHEAD = 300L
}
