package com.bonsaponsi.baptv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why earlier app processes ended (Android 11+): a kill by the system, a native crash, an ANR…
 * Logged once per exit at process start, so a report shows what ended the previous process
 * even when the system log did not survive (e.g. a restart of the Android framework).
 */
object ProcessExits {
    private const val PREFS = "process_exits"
    private const val KEY_LAST = "last_timestamp"
    private const val MAX_EXITS = 5

    fun logNew(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching { logNewR(context) }.onFailure { DebugLog.warn("App", "reading process exit reasons", it) }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun logNewR(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        // Newest first; the current process is not on the list yet.
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
            .filter { it.timestamp > last }
        if (exits.isEmpty()) return
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.US)
        exits.asReversed().forEach { e ->
            val details = listOfNotNull(
                e.processName.takeIf { it != context.packageName },
                "status ${e.status}".takeIf { e.status != 0 },
                "importance ${e.importance}",
                "rss ${e.rss / 1024} MB".takeIf { e.rss > 0 },
                e.description?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
            ).joinToString(", ")
            DebugLog.log("App", "previous process ${e.pid} ended ${fmt.format(Date(e.timestamp))}: ${reasonName(e.reason)} ($details)")
        }
        prefs.edit().putLong(KEY_LAST, exits.maxOf { it.timestamp }).apply()
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit by itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested (force stop, update)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other (killed by the system)"
        else -> "unknown ($reason)"
    }
}
