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
 * Screenshots for the introduction and the "Did you know?" tips, kept in the GitHub repository
 * (docs/tour/<language>/<name>.webp) instead of the APK. Only an illustration: without a network,
 * on a slow connection or when the file is missing the view simply stays hidden.
 */
object Illustrations {
    private const val BASE_URL = "https://raw.githubusercontent.com/depablo/bapTV/main/docs/tour"
    private const val TIMEOUT_MS = 4000
    private const val MAX_BYTES = 1024 * 1024
    /** Cached files are fetched again after a week – screenshots follow the current interface. */
    private const val MAX_AGE_MS = 7 * 24 * 3600_000L

    private val handler = Handler(Looper.getMainLooper())

    /** Screenshots exist in Polish and English; other languages see the English ones. */
    private fun language(context: Context) =
        if (context.resources.configuration.locales[0].language == "pl") "pl" else "en"

    /** Shows illustration [name] in [view] once it is available; [view] stays GONE otherwise. */
    fun show(view: ImageView, name: String) {
        view.visibility = View.GONE
        view.tag = name
        val context = view.context
        val lang = language(context)
        val file = File(File(context.cacheDir, "illustrations").apply { mkdirs() }, "$lang-$name.webp")
        BackgroundWorker.start("illustration") {
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
            if (!fresh) runCatching { download("$BASE_URL/$lang/$name.webp", file) }
                .onFailure { DebugLog.log("Tour", "illustration $lang/$name not available: ${it.message}") }
            // A stale copy is better than none when the download failed.
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            if (bitmap != null) handler.post {
                // The view may show another illustration by now (next page of the same dialog).
                if (view.tag == name) {
                    view.setImageBitmap(bitmap)
                    view.visibility = View.VISIBLE
                }
            }
        }
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
            val tmp = File(target.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) error("cannot store ${target.name}")
        } finally {
            connection.disconnect()
        }
    }
}
