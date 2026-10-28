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
 * Quick check whether an internet stream answers, before the user adds it: fetches the playlist (for HLS
 * also the first variant) and the start of the first segment, with the stream's own headers.
 * Does not play anything, so a stream that passes can still fail to play – but most dead, blocked
 * and DRM-protected streams are caught.
 */
object IptvProbe {
    enum class Status { OK, BLOCKED, DEAD, TIMEOUT, DRM, ERROR }

    class Result(val status: Status, val detail: String, val format: String? = null)

    private const val TIMEOUT_MS = 8_000
    private const val PLAYLIST_LIMIT = 512 * 1024
    private const val SEGMENT_PEEK = 1024
    private const val WORKERS = 12

    /** Probes [streams] in parallel; results in the same order. */
    fun probeAll(streams: List<Iptv.Stream>): List<Result> {
        if (streams.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(WORKERS.coerceAtMost(streams.size))
        return try {
            pool.invokeAll(streams.map { s -> Callable { probe(s) } }).map { f ->
                runCatching { f.get(TIMEOUT_MS * 4L, TimeUnit.MILLISECONDS) }.getOrElse { Result(Status.TIMEOUT, "timeout") }
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
        var url = playlistUrl
        var lines = text.lines().map { it.trim() }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variant = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return Result(Status.ERROR, "empty playlist")
            val (variantUrl, data) = fetch(stream, URL(url, variant).toString(), PLAYLIST_LIMIT)
            url = variantUrl
            lines = String(data, Charsets.UTF_8).lines().map { it.trim() }
        }
        if (lines.any { it.startsWith("#EXT-X-KEY") && "SAMPLE-AES" in it }) return Result(Status.DRM, "DRM", "hls")
        val segment = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return Result(Status.ERROR, "empty playlist")
        fetch(stream, URL(url, segment).toString(), SEGMENT_PEEK)
        return Result(Status.OK, "HLS", "hls")
    }

    /** GET with the stream's headers; returns the final address (after redirects) and the first [limit] bytes. */
    private fun fetch(stream: Iptv.Stream, address: String, limit: Int): Pair<URL, ByteArray> {
        var url = URL(address)
        // HttpURLConnection does not follow http <-> https redirects by itself.
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", stream.userAgent ?: Iptv.USER_AGENT)
                stream.referrer?.let { c.setRequestProperty("Referer", it) }
                val code = c.responseCode
                if (code in 300..399) {
                    url = URL(url, c.getHeaderField("Location") ?: throw IOException("redirect without location"))
                    return@repeat
                }
                if (code !in 200..299) throw HttpStatus(code)
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
                return url to bytes
            } finally {
                c.disconnect()
            }
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
