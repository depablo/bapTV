package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Context

/**
 * Introduction on the first start (a few pages operated with the remote) and "What's new" after
 * an update – only major features listed in [WHATS_NEW], not fixes or small changes.
 */
object Tour {
    private const val TAG = "Tour"
    private const val PREFS = "tour"
    private const val KEY_SEEN_VERSION = "seen_version"

    /**
     * Major features per versionCode, shown once to users updating from an older version.
     * Add an entry only for features worth an announcement.
     */
    private val WHATS_NEW = mapOf(
        60 to listOf(R.string.whats_new_iptv_without_tuner, R.string.whats_new_updates, R.string.whats_new_tour),
    )

    private class Page(val title: Int, val text: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun freshInstall(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    }.getOrDefault(false)

    /**
     * On the first start of a fresh install: shows the introduction and calls [onDone] when it closes;
     * returns false (and does nothing) otherwise. Marks the running version as seen either way.
     */
    fun showIfFirstStart(activity: Activity, onDone: () -> Unit): Boolean {
        val prefs = prefs(activity)
        if (prefs.contains(KEY_SEEN_VERSION) || !freshInstall(activity)) return false
        prefs.edit().putInt(KEY_SEEN_VERSION, BuildConfig.VERSION_CODE).apply()
        DebugLog.log(TAG, "first start – introduction")
        show(activity, onDone)
        return true
    }

    /**
     * After an update: the major features added since the version seen last (without a record –
     * an update from a version before this tracking – those of the running version).
     */
    fun showWhatsNew(activity: Activity) {
        val prefs = prefs(activity)
        val seen = prefs.getInt(KEY_SEEN_VERSION, BuildConfig.VERSION_CODE - 1)
        if (seen >= BuildConfig.VERSION_CODE) return
        prefs.edit().putInt(KEY_SEEN_VERSION, BuildConfig.VERSION_CODE).apply()
        val items = WHATS_NEW.filterKeys { it in (seen + 1)..BuildConfig.VERSION_CODE }.toSortedMap().values.flatten()
        if (items.isEmpty()) return
        DebugLog.log(TAG, "what's new since versionCode $seen: ${items.size} items")
        TvDialog.confirm(activity, activity.getString(R.string.whats_new_title, BuildConfig.VERSION_NAME.substringBefore('-')),
            items.joinToString("\n\n") { "• " + activity.getString(it) },
            activity.getString(android.R.string.ok), activity.getString(R.string.tour_open)) {}
            .also { dialog ->
                dialog.findViewById<android.widget.Button>(R.id.tv_dialog_cancel)?.setOnClickListener {
                    dialog.dismiss()
                    show(activity) {}
                }
            }
    }

    /** The introduction pages; the ones about features this device lacks are left out. */
    private fun pages(activity: Activity): List<Page> {
        val caps = DeviceCapabilities.get(activity)
        val remote = activity.getString(R.string.tour_remote_text) +
            if (caps.signal) " " + activity.getString(R.string.tour_remote_signal) else ""
        return listOfNotNull(
            Page(R.string.tour_welcome_title, activity.getString(R.string.tour_welcome_text)),
            Page(R.string.tour_remote_title, remote),
            Page(R.string.tour_iptv_title, activity.getString(R.string.tour_iptv_text)),
            Page(R.string.tour_record_title, activity.getString(R.string.tour_record_text)).takeIf { caps.streamAnalysis },
            Page(R.string.tour_hbbtv_title, activity.getString(R.string.tour_hbbtv_text)).takeIf { caps.hbbtv },
            Page(R.string.tour_help_title, activity.getString(R.string.tour_help_text)),
        )
    }

    /** Shows the introduction from the first page; [onDone] once it is finished or skipped. */
    fun show(activity: Activity, onDone: () -> Unit) {
        val pages = pages(activity)
        var finished = false
        fun finish() { if (!finished) { finished = true; onDone() } }
        fun page(i: Int) {
            val p = pages[i]
            val last = i == pages.lastIndex
            var advanced = false
            TvDialog.confirm(
                activity, "${activity.getString(p.title)}  (${i + 1}/${pages.size})", p.text,
                activity.getString(if (last) R.string.tour_start else R.string.tour_next),
                if (last) null else activity.getString(R.string.tour_skip),
                onClose = { if (!advanced) finish() },
            ) {
                advanced = true
                if (last) finish() else page(i + 1)
            }
        }
        page(0)
    }
}
