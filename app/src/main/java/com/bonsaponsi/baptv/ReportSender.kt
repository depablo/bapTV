package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream

/**
 * Sends the diagnostics report to the report service ([BuildConfig.REPORTS_URL]), which stores it
 * privately and opens a GitHub issue with a short summary. Nothing is sent without the user's consent:
 * [offer] always asks first.
 */
object ReportSender {
    private const val TAG = "Report"
    private const val PREFS = "reports"
    private const val KEY_PENDING_CRASH = "pending_crash"
    /** Automatic prompts for the same reason at most once a day. */
    private const val PROMPT_INTERVAL_MS = 24 * 3600_000L

    enum class Reason(val wire: String) { USER("user"), SCAN_FAILED("scan_failed"), NO_TUNER("no_tuner"), CRASH("crash") }

    class Result(val id: String, val issueNumber: Int?, val issueUrl: String?)

    /**
     * Asks whether to send a report and sends it. [automatic] prompts (after an error) are throttled
     * per reason; the user can always send one from Diagnostics or the menu.
     */
    fun offer(activity: Activity, reason: Reason, error: String?, automatic: Boolean, onClose: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        if (automatic) {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "prompted_${reason.wire}"
            if (System.currentTimeMillis() - prefs.getLong(key, 0) < PROMPT_INTERVAL_MS) return
            prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        }
        DebugLog.log(TAG, "offering a report (${reason.wire}${if (automatic) ", automatic" else ""}): ${error.orEmpty()}")
        val title = activity.getString(if (automatic) R.string.report_title_problem else R.string.report_title)
        val message = (error?.let { activity.getString(R.string.report_problem, it) + "\n\n" } ?: "") +
            activity.getString(R.string.report_consent)
        var sending = false
        TvDialog.confirm(activity, title, message, activity.getString(R.string.report_send), activity.getString(R.string.report_not_now),
            onClose = { if (!sending) onClose?.invoke() }) {
            sending = true
            send(activity, reason, error, onClose)
        }
    }

    private fun send(activity: Activity, reason: Reason, error: String?, onClose: (() -> Unit)?) {
        TvDialog.toast(activity, activity.getString(R.string.report_sending))
        val app = activity.applicationContext
        Thread({
            val outcome = runCatching { post(app, reason, error) }
            Handler(Looper.getMainLooper()).post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                outcome.onSuccess { result ->
                    DebugLog.log(TAG, "report sent: ${result.id}, issue ${result.issueNumber ?: "-"}")
                    val link = result.issueUrl
                    if (link != null) {
                        TvDialog.qr(activity, activity.getString(R.string.report_sent_title),
                            activity.getString(R.string.report_sent_issue, result.issueNumber ?: 0), link, onClose)
                    } else {
                        TvDialog.message(activity, activity.getString(R.string.report_sent_title),
                            activity.getString(R.string.report_sent_id, result.id), onClose)
                    }
                }.onFailure { e ->
                    DebugLog.warn(TAG, "sending the report failed", e)
                    TvDialog.message(activity, activity.getString(R.string.report_failed_title),
                        activity.getString(R.string.report_failed, e.message ?: e.javaClass.simpleName), onClose)
                }
            }
        }, "report-send").start()
    }

    private fun post(context: Context, reason: Reason, error: String?): Result {
        val caps = DeviceCapabilities.get(context)
        val summary = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE.toString())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT.toString())
            .put("tuner", listOfNotNull(caps.inputLabel, caps.inputId).joinToString(" – ").ifEmpty { "none" })
            .put("reason", reason.wire)
            .put("region", Region.country())
            .put("language", AppLanguage.get(context).ifEmpty { "system" })
        error?.let { summary.put("error", it) }
        val body = JSONObject().put("summary", summary).put("report", Diagnostics(context).build()).toString()
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body.toByteArray()) } }.toByteArray()

        val connection = URL(BuildConfig.REPORTS_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Content-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "bapTV/${BuildConfig.VERSION_NAME}")
            connection.setFixedLengthStreamingMode(gzipped.size)
            connection.outputStream.use { it.write(gzipped) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code !in 200..299) error(json?.optString("error")?.ifEmpty { null } ?: "HTTP $code")
            val issue = json?.optJSONObject("issue")
            return Result(
                id = json?.optString("id").orEmpty(),
                issueNumber = issue?.optInt("number")?.takeIf { it > 0 },
                issueUrl = issue?.optString("url")?.takeIf { it.startsWith("https://") },
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Called from the uncaught exception handler: remembers the crash so the next start can offer a report. */
    fun rememberCrash(context: Context, error: Throwable) {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith(BuildConfig.APPLICATION_ID) } ?: error.stackTrace.firstOrNull()
        val text = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(200) + (frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "")
        // commit(): the process is about to die.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PENDING_CRASH, text).commit()
    }

    /** After a crash in the previous run: offers a report once. */
    fun offerPendingCrash(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val crash = prefs.getString(KEY_PENDING_CRASH, null) ?: return
        prefs.edit().remove(KEY_PENDING_CRASH).apply()
        offer(activity, Reason.CRASH, crash, automatic = true)
    }
}
