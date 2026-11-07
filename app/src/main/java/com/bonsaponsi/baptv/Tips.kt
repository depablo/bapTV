package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * "Did you know?" tips about features that are easy to miss, each with an optional screenshot ([Illustrations]).
 * They rotate on screen while the user waits for the channel setup ([Rotator]) and one appears now and then
 * at start ([showDaily]) until the user turns that off.
 */
object Tips {
    private const val TAG = "Tips"
    private const val PREFS = "tips"
    private const val KEY_NEXT = "next"
    private const val KEY_DAILY_DAY = "daily_day"
    private const val KEY_DAILY = "daily"
    private const val ROTATE_MS = 20_000L

    /**
     * [image] – screenshot name in [Illustrations]; [githubOnly] – the tip describes a feature of the GitHub build only
     * (see BuildConfig.DISTRIBUTION); [available] – false on devices without the feature.
     */
    private class Tip(
        val text: Int,
        val image: String?,
        val githubOnly: Boolean = false,
        val available: (DeviceCapabilities) -> Boolean = { true },
    )

    private val ALL = listOf(
        Tip(R.string.tip_iptv_check, "phone-iptv-check"),
        Tip(R.string.tip_info, "player-info"),
        Tip(R.string.tip_categories, "channel-list-categories"),
        Tip(R.string.tip_pause, "pause") { it.streamAnalysis },
        Tip(R.string.tip_signal, "phone-signal") { it.signal },
        Tip(R.string.tip_guide, "guide"),
        Tip(R.string.tip_iptv_cleanup, "phone-iptv-mine"),
        Tip(R.string.tip_record, "menu-record", githubOnly = true) { it.streamAnalysis },
        Tip(R.string.tip_numbering, "menu-numbering"),
        Tip(R.string.tip_remote_home, "phone-home"),
        Tip(R.string.tip_hbbtv, "hbbtv") { it.hbbtv },
        Tip(R.string.tip_report, "menu-report"),
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The next tip in order; the position is kept, so each wait and each day shows a different one. */
    private fun next(context: Context): Tip? {
        val caps = DeviceCapabilities.get(context)
        val tips = ALL.filter { it.available(caps) && (!it.githubOnly || BuildConfig.DISTRIBUTION == "github") }
        if (tips.isEmpty()) return null
        val prefs = prefs(context)
        val index = prefs.getInt(KEY_NEXT, 0).mod(tips.size)
        prefs.edit().putInt(KEY_NEXT, index + 1).apply()
        return tips[index]
    }

    /** A tip at start, at most once a day; not on the install day (the introduction is enough then). */
    fun dailyEnabled(context: Context) = prefs(context).getBoolean(KEY_DAILY, true)

    fun setDailyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DAILY, enabled).apply()
        DebugLog.log(TAG, "tip at start ${if (enabled) "on" else "off"}")
    }

    /** Shows today's tip when due; returns whether it did. */
    fun showDaily(activity: Activity): Boolean {
        if (!dailyEnabled(activity)) return false
        val today = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
        val prefs = prefs(activity)
        val installDay = runCatching {
            val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime
            (installed + java.util.TimeZone.getDefault().getOffset(installed)) / 86_400_000L
        }.getOrDefault(-1L)
        if (prefs.getLong(KEY_DAILY_DAY, -1L) == today || installDay == today) return false
        val tip = next(activity) ?: return false
        prefs.edit().putLong(KEY_DAILY_DAY, today).apply()
        DebugLog.log(TAG, "tip at start: ${activity.resources.getResourceEntryName(tip.text)}")
        TvDialog.confirm(activity, activity.getString(R.string.tip_title), activity.getString(tip.text),
            activity.getString(android.R.string.ok), activity.getString(R.string.tip_daily_off),
            onCancel = { setDailyEnabled(activity, false) }, image = tip.image) {}
        return true
    }

    /**
     * Rotating tip in a view inflated from include_tip: shown while [start]ed, a new tip every [ROTATE_MS].
     * Used during the channel scan, discovery and identification.
     */
    class Rotator(private val root: View) {
        private val handler = Handler(Looper.getMainLooper())
        private val text = root.findViewById<TextView>(R.id.tip_text)
        private val image = root.findViewById<ImageView>(R.id.tip_image)
        var running = false
            private set

        private val rotate = object : Runnable {
            override fun run() {
                val tip = next(root.context) ?: return stop()
                text.setText(tip.text)
                tip.image?.let { Illustrations.show(image, it) } ?: run { image.visibility = View.GONE }
                root.visibility = View.VISIBLE
                handler.postDelayed(this, ROTATE_MS)
            }
        }

        fun start() {
            if (running) return
            running = true
            handler.post(rotate)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(rotate)
            root.visibility = View.GONE
        }
    }
}
