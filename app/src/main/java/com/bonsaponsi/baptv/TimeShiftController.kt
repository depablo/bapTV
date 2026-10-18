package com.bonsaponsi.baptv

import android.app.Activity
import android.media.tv.TvView
import android.os.Handler
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Live pause (timeshift) via dtvinput's built-in buffer: `TvView.timeShiftPause/Resume/SeekTo`.
 * [TimeShiftModel] does the timing; this class drives the tuner, the "⏸" badge (only while paused) and the buffer
 * bar row in the program info banner (second line, under the channel name).
 *
 * Seeking (◀ ▶) is collected for a moment and only then sent to the tuner – repeated
 * presses move the target on the bar without overloading dtvinput.
 */
class TimeShiftController(
    private val activity: Activity,
    private val tvView: TvView,
    private val handler: Handler,
    /** Back to live: the player retunes the channel (the buffer is discarded). */
    private val onGoLive: () -> Unit,
    /** Shows the info banner (with the pause row) – while seeking. */
    private val onShowBanner: () -> Unit,
) {
    private val model = TimeShiftModel()
    private val badge: TextView = activity.findViewById(R.id.ts_badge)
    private val row: View = activity.findViewById(R.id.ts_row)
    private val bar: ProgressBar = activity.findViewById(R.id.ts_bar)
    private val delayText: TextView = activity.findViewById(R.id.ts_delay)
    private val clock by lazy { SimpleDateFormat("HH:mm:ss", AppLanguage.locale(activity)) }

    /** Seek target (delay) collected from repeated presses, not yet sent to the tuner. */
    private var pendingDelayMs: Long? = null

    val active get() = model.active
    val paused get() = model.paused

    fun delayMs() = model.delayMs(now())

    /** Length of the buffered segment. */
    fun bufferMs() = model.maxDelayMs(now())

    private fun now() = System.currentTimeMillis()

    fun pause() {
        model.pause(now())
        tvView.timeShiftPause()
        DebugLog.log(TAG, "pause (delay ${TimeShiftModel.formatDelay(delayMs())})")
        startTicking()
    }

    fun resume() {
        if (!model.active) return
        model.resume(now())
        tvView.timeShiftResume()
        DebugLog.log(TAG, "resume with delay ${TimeShiftModel.formatDelay(delayMs())}")
        render()
    }

    fun togglePause() = if (model.paused) resume() else pause()

    /** Seeks by [deltaMs] (negative – backward). Forward to the end of the buffer = back to live. */
    fun seekBy(deltaMs: Long) {
        if (!model.active) return
        val now = now()
        val from = pendingDelayMs ?: model.delayMs(now)
        pendingDelayMs = model.targetDelayAfterSeek(now, from, deltaMs)
        handler.removeCallbacks(applySeek)
        handler.postDelayed(applySeek, SEEK_SETTLE_MS)
        onShowBanner()
        render()
    }

    private val applySeek = Runnable {
        val target = pendingDelayMs ?: return@Runnable
        pendingDelayMs = null
        if (target < TimeShiftModel.LIVE_THRESHOLD_MS) {
            DebugLog.log(TAG, "seeked to the end of the buffer – back to live")
            goLive()
            return@Runnable
        }
        val now = now()
        tvView.timeShiftSeekTo(now - target)
        model.seekApplied(now, target)
        DebugLog.log(TAG, "seek: delay ${TimeShiftModel.formatDelay(target)}" + if (model.paused) " (paused)" else "")
        render()
    }

    fun goLive() {
        DebugLog.log(TAG, "back to live")
        reset()
        onGoLive()
    }

    /** A new tune or player stop – dtvinput's buffer goes away with the session. */
    fun reset() {
        if (model.active) DebugLog.log(TAG, "live pause ended")
        model.reset()
        pendingDelayMs = null
        handler.removeCallbacks(applySeek)
        handler.removeCallbacks(tick)
        badge.visibility = View.GONE
        row.visibility = View.GONE
    }

    /** Refreshes the badge and bar (the handler is cleared after returning from another app screen). */
    fun startTicking() {
        handler.removeCallbacks(tick)
        if (model.active) handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!model.active) return
            render()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The "⏸" badge only while paused (no counter – it should not suggest the program is rewound); the bar is in the banner. */
    fun render() {
        badge.text = activity.getString(R.string.ts_badge_paused)
        badge.visibility = if (model.active && model.paused) View.VISIBLE else View.GONE
        row.visibility = if (model.active) View.VISIBLE else View.GONE
        if (!model.active) return
        val now = now()
        val delay = model.delayMs(now)
        val shown = pendingDelayMs ?: delay
        val start = model.bufferStartMs(now)
        val span = (now - start).coerceAtLeast(1)
        bar.progress = (((now - delay - start) * 1000) / span).toInt().coerceIn(0, 1000)
        bar.secondaryProgress = pendingDelayMs?.let { (((now - it - start) * 1000) / span).toInt().coerceIn(0, 1000) } ?: 0
        delayText.text = activity.getString(if (model.paused) R.string.ts_row_paused else R.string.ts_row_delayed,
            TimeShiftModel.formatDelay(shown), clock.format(Date(now - shown)))
    }

    /** State for remote tests (`/timeshift`). */
    fun json(): JSONObject {
        val now = now()
        return JSONObject().put("active", model.active).put("paused", model.paused)
            .put("delayMs", model.delayMs(now)).put("bufferMs", model.maxDelayMs(now))
            .put("pendingDelayMs", pendingDelayMs ?: JSONObject.NULL).put("badge", badge.visibility == View.VISIBLE)
            .put("row", row.visibility == View.VISIBLE)
    }

    companion object {
        private const val TAG = "Timeshift"
        /** Seek step: single press and key held down. */
        const val STEP_MS = 10_000L
        const val STEP_HELD_MS = 30_000L
        private const val SEEK_SETTLE_MS = 700L
        private const val TICK_MS = 500L
    }
}
