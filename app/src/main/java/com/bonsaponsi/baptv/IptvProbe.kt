package com.bonsaponsi.baptv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Check whether an internet stream can play, before the user adds it: fetches the playlist (for HLS also
 * a variant) with the stream's own headers and, for HLS, measures how fast a recent segment downloads
 * compared with its duration. A server slower than real time answers every request but cannot be
 * watched – its segments leave the live window before they arrive. Nothing is played, so a stream
 * that passes can still fail, but dead, blocked, DRM-protected and too slow streams are caught.
 */
object IptvProbe {
    enum class Status { OK, SLOW, BLOCKED, DEAD, TIMEOUT, DRM, ERROR }

    /** [speed] – download speed as a multiple of real time (HLS only), e.g. 0.4 = too slow, 5.0 = plenty. */
    class Result(val status: Status, val detail: String, val format: String? = null, val speed: Double? = null)

    private const val TIMEOUT_MS = 8_000
    private const val PLAYLIST_LIMIT = 512 * 1024
    private const val SEGMENT_LIMIT = 16 * 1024 * 1024
    private const val WORKERS = 12
    /** How long one speed test downloads; enough to see the rate, short enough for long lists. */
    private const val SPEED_BUDGET_MS = 2_500L
    /** Below this a stream keeps buffering: data must arrive faster than it plays, with some margin. */
    const val MIN_SPEED = 1.2
    /** Speed tests run a few at a time – many in parallel would share the line and look slower than they are. */
    private val speedSlots = java.util.concurrent.Semaphore(4)

    /** Probes [streams] in parallel; results in the same order. */
    fun probeAll(streams: List<Iptv.Stream>): List<Result> {
        if (streams.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(WORKERS.coerceAtMost(streams.size))
        return try {
            pool.invokeAll(streams.map { s -> Callable { probe(s) } }).map { f ->
                runCatching { f.get(TIMEOUT_MS * 8L, TimeUnit.MILLISECONDS) }.getOrElse { Result(Status.TIMEOUT, "timeout") }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    fun probe(stream: Iptv.Stream): Result {
        if (stream.drm) return Result(Status.DRM, "DRM")
        if (!stream.url.startsWith("http://") && !stream.url.startsWith("https://")) {
            return Result(Status.ERROR, stream.url.substringBefore(':') + " not supported")
        }
        return try {
            val (url, data) = fetch(stream, stream.url, PLAYLIST_LIMIT)
            val head = String(data, 0, minOf(data.size, 2048), Charsets.UTF_8).trimStart('﻿', ' ', '\r', '\n', '\t')
            when {
                head.startsWith("#EXTM3U") -> probeHls(stream, url, String(data, Charsets.UTF_8))
                "<MPD" in head -> if ("ContentProtection" in String(data, Charsets.UTF_8)) Result(Status.DRM, "DRM", "dash")
                    else Result(Status.OK, "DASH", "dash")
                isTs(data) -> Result(Status.OK, "MPEG-TS", "ts")
                else -> Result(Status.ERROR, "not a stream")
            }
        } catch (e: Exception) {
            failure(e)
        }
    }

    private fun probeHls(stream: Iptv.Stream, playlistUrl: URL, text: String): Result {
        var lines = text.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variants = variants(lines)
            if (variants.isEmpty()) return Result(Status.ERROR, "empty playlist")
            // The best quality first; when it is too slow, the lowest one shows whether the stream is usable at all.
            val best = measureVariant(stream, URL(playlistUrl, variants.last()))
            if (best.status != Status.SLOW || variants.size == 1) return best
            val lowest = measureVariant(stream, URL(playlistUrl, variants.first()))
            return if (lowest.status == Status.OK) Result(Status.OK, "HLS, lower quality", "hls", lowest.speed) else best
        }
        return measureMedia(stream, playlistUrl, lines)
    }

    /** Variant addresses from the lowest to the highest BANDWIDTH. */
    private fun variants(lines: List<String>): List<String> {
        val result = ArrayList<Pair<Long, String>>()
        lines.forEachIndexed { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = Regex("""[:,]BANDWIDTH=(\d+)""").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { result += bandwidth to it }
        }
        return result.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun measureVariant(stream: Iptv.Stream, url: URL): Result {
        val (variantUrl, data) = fetch(stream, url.toString(), PLAYLIST_LIMIT)
        return measureMedia(stream, variantUrl, String(data, Charsets.UTF_8).lines().map { it.trim() })
    }

    /** Media playlist: DRM check, then the speed of a recent segment. */
    private fun measureMedia(stream: Iptv.Stream, url: URL, lines: List<String>): Result {
        if (lines.any { it.startsWith("#EXT-X-KEY") && "SAMPLE-AES" in it }) return Result(Status.DRM, "DRM", "hls")
        // Segments with their durations; the one before the newest is complete on every server.
        val segments = ArrayList<Pair<Double, String>>()
        var duration = 0.0
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> duration = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> segments += duration to line
            }
        }
        val (seconds, segment) = segments.getOrNull(segments.size - 2) ?: segments.lastOrNull() ?: return Result(Status.ERROR, "empty playlist")
        val speed = speedSlots.withPermit { downloadSpeed(stream, URL(url, segment).toString(), seconds) }
        return when {
            speed == null -> Result(Status.OK, "HLS", "hls")
            speed < MIN_SPEED -> Result(Status.SLOW, "%.1f×".format(java.util.Locale.US, speed), "hls", speed)
            else -> Result(Status.OK, "HLS", "hls", speed)
        }
    }

    private inline fun <T> java.util.concurrent.Semaphore.withPermit(block: () -> T): T {
        acquire()
        try { return block() } finally { release() }
    }

    /**
     * Downloads a segment for up to [SPEED_BUDGET_MS] and returns the speed as a multiple of real time:
     * segment duration / download time when it finished, otherwise measured rate / needed rate (from its size).
     * Null when it cannot be told (no duration, or unfinished without a size).
     */
    private fun downloadSpeed(stream: Iptv.Stream, address: String, seconds: Double): Double? {
        if (seconds <= 0) return null
        val started = System.nanoTime()
        val c = connect(stream, address)
        try {
            val size = c.contentLengthLong
            val deadline = started + SPEED_BUDGET_MS * 1_000_000
            var read = 0L
            var finished = false
            c.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                while (System.nanoTime() < deadline && read < SEGMENT_LIMIT) {
                    val n = input.read(chunk)
                    if (n < 0) { finished = true; break }
                    read += n
                }
            }
            val elapsed = (System.nanoTime() - started) / 1e9
            return when {
                finished -> seconds / elapsed
                size > 0 && read > 0 -> (read / elapsed) / (size / seconds)
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }

    /** GET with the stream's headers; returns the final address (after redirects) and the first [limit] bytes. */
    private fun fetch(stream: Iptv.Stream, address: String, limit: Int): Pair<URL, ByteArray> {
        val c = connect(stream, address)
        try {
            val bytes = c.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (buffer.size() < limit) {
                    val n = input.read(chunk, 0, minOf(chunk.size, limit - buffer.size()))
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            return c.url to bytes
        } finally {
            c.disconnect()
        }
    }

    /** Opened connection with a 2xx answer, after redirects (HttpURLConnection does not follow http <-> https itself). */
    private fun connect(stream: Iptv.Stream, address: String): HttpURLConnection {
        var url = URL(address)
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", stream.userAgent ?: Iptv.USER_AGENT)
            stream.referrer?.let { c.setRequestProperty("Referer", it) }
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }
            if (code in 200..299) return c
            c.disconnect()
            if (code !in 300..399) throw HttpStatus(code)
            url = URL(url, c.getHeaderField("Location") ?: throw IOException("redirect without location"))
        }
        throw IOException("too many redirects")
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    private fun failure(e: Exception): Result = when (e) {
        is HttpStatus -> when (e.code) {
            401, 403, 451 -> Result(Status.BLOCKED, "HTTP ${e.code}")
            404, 410 -> Result(Status.DEAD, "HTTP ${e.code}")
            else -> Result(Status.ERROR, "HTTP ${e.code}")
        }
        is SocketTimeoutException -> Result(Status.TIMEOUT, "timeout")
        is UnknownHostException -> Result(Status.DEAD, "unknown host")
        is java.net.ConnectException -> Result(Status.DEAD, "connection refused")
        is javax.net.ssl.SSLException -> Result(Status.ERROR, "TLS error")
        else -> Result(Status.ERROR, e.javaClass.simpleName)
    }

    private fun isTs(data: ByteArray) = data.size > 188 && data[0] == 0x47.toByte() && data[188] == 0x47.toByte()

}
