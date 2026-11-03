package com.bonsaponsi.baptv

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Screenshots for the introduction and the "Did you know?" tips, kept on the repository's "tour" branch
 * (<language>/<name>.webp, no shared history with main) instead of the APK, so they can be replaced without
 * a release. Only an illustration: without a network, on a slow connection or when the file is missing
 * the view simply stays hidden.
 */
object Illustrations {
    private const val BASE_URL = "https://raw.githubusercontent.com/depablo/bapTV/tour"
    private const val TIMEOUT_MS = 4000
    private const val MAX_BYTES = 1024 * 1024
    /** Cached files are fetched again after a week – screenshots follow the current interface. */
    private const val MAX_AGE_MS = 7 * 24 * 3600_000L

    /** Downloads are not tried again sooner after a failure – offline, every tip would wait for the timeout. */
    private const val RETRY_AFTER_MS = 30 * 60_000L
    /** Shown at most this wide (the files are 960×540) – a bigger picture would only cost memory. */
    private const val MAX_WIDTH = 960

    private val handler = Handler(Looper.getMainLooper())
    /** Failed downloads by file name, with their time. */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Screenshots exist in Polish and English; other languages see the English ones. */
    private fun language(context: Context) =
        if (context.resources.configuration.locales[0].language == "pl") "pl" else "en"

    /** Shows illustration [name] in [view] once it is available; [view] stays GONE otherwise. */
    fun show(view: ImageView, name: String) {
        view.visibility = View.GONE
        view.tag = name
        val context = view.context
        val lang = language(context)
        val dir = File(context.cacheDir, "illustrations")
        val file = File(dir, "$lang-$name.webp")
        BackgroundWorker.start("illustration") {
            dir.mkdirs()
            val now = System.currentTimeMillis()
            val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
            val recentlyFailed = failedAt[file.name]?.let { now - it < RETRY_AFTER_MS } == true
            if (!fresh && !recentlyFailed) runCatching { download("$BASE_URL/$lang/$name.webp", file) }
                .onSuccess { failedAt.remove(file.name) }
                .onFailure {
                    failedAt[file.name] = now
                    DebugLog.log("Tour", "illustration $lang/$name not available: ${it.message}")
                }
            // A stale copy is better than none when the download failed.
            val bitmap = if (file.exists()) decode(file) else null
            if (bitmap != null) handler.post {
                // The view may show another illustration by now (next page of the same dialog).
                if (view.tag == name) {
                    view.setImageBitmap(bitmap)
                    view.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Decodes the file scaled down to at most [MAX_WIDTH] pixels wide. */
    private fun decode(file: File): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) error("larger than $MAX_BYTES B")
                }
                out.toByteArray()
            }
            // A file of its own: the same illustration may be fetched by two views at once.
            val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
            try {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) error("cannot store ${target.name}")
            } finally {
                tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }
}
