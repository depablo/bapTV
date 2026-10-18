package com.bonsaponsi.baptv.testtuner

import android.content.ContentUris
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.media.PlaybackParams
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TV input with a drawn "picture": channel color, name, content clock and a seconds bar. The content clock
 * shows which moment of the "broadcast" is on screen, so the delay is visible after pausing and seeking.
 * Live pause as in dtvinput: positions and seeking in device clock time, buffer up to 30 min.
 */
class TestTunerService : TvInputService() {
    override fun onCreateSession(inputId: String): Session = TestSession(this)

    private class TestSession(context: Context) : TvInputService.Session(context) {
        private val ctx = context
        private val thread = HandlerThread("test-tuner-draw").apply { start() }
        private val drawHandler = Handler(thread.looper)
        @Volatile private var surface: Surface? = null
        @Volatile private var channel: TestChannels.Def? = null

        // Live pause: buffer starts at tune time, delay is relative to live.
        @Volatile private var bufferStartMs = 0L
        @Volatile private var delayMs = 0L
        @Volatile private var pausedAtMs = 0L

        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        private val big = Paint().apply { color = Color.WHITE; textSize = 110f; isAntiAlias = true; isFakeBoldText = true }
        private val mid = Paint().apply { color = Color.WHITE; textSize = 64f; isAntiAlias = true }
        private val small = Paint().apply { color = Color.LTGRAY; textSize = 36f; isAntiAlias = true }
        private val barPaint = Paint().apply { color = Color.WHITE }

        private fun now() = System.currentTimeMillis()
        private fun position() = if (pausedAtMs > 0) pausedAtMs - delayMs else now() - delayMs

        private val draw = object : Runnable {
            override fun run() {
                render()
                drawHandler.postDelayed(this, 200)
            }
        }

        override fun onSetSurface(s: Surface?): Boolean {
            surface = s
            return true
        }

        override fun onTune(channelUri: Uri): Boolean {
            val def = TestChannels.byChannelId(ctx, ContentUris.parseId(channelUri))
            Log.i(TAG, "onTune $channelUri -> ${def?.name}")
            channel = def
            bufferStartMs = now()
            delayMs = 0
            pausedAtMs = 0
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            drawHandler.removeCallbacks(draw)
            drawHandler.postDelayed({
                if (def == null) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                    return@postDelayed
                }
                notifyTracksChanged(listOf(
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, "v").setVideoWidth(1920).setVideoHeight(1080).setVideoFrameRate(25f).build(),
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, "a-pol").setLanguage("pol").setAudioChannelCount(2).build(),
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, "a-qaa").setLanguage("qaa").setAudioChannelCount(2).build(),
                    TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, "s-pol").setLanguage("pol").build(),
                ))
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, "v")
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, "a-pol")
                notifyVideoAvailable()
                notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
                drawHandler.post(draw)
            }, 800)
            return true
        }

        override fun onSelectTrack(type: Int, trackId: String?): Boolean {
            notifyTrackSelected(type, trackId)
            return true
        }

        override fun onTimeShiftPause() {
            if (pausedAtMs == 0L) pausedAtMs = now()
            Log.i(TAG, "pause")
        }

        override fun onTimeShiftResume() {
            if (pausedAtMs > 0) delayMs += now() - pausedAtMs
            pausedAtMs = 0
            Log.i(TAG, "resume, delay ${delayMs / 1000} s")
        }

        override fun onTimeShiftSeekTo(timeMs: Long) {
            val reference = if (pausedAtMs > 0) pausedAtMs else now()
            delayMs = (reference - timeMs).coerceIn(0, reference - bufferStartMs)
            Log.i(TAG, "seek to ${clock.format(Date(timeMs))}, delay ${delayMs / 1000} s")
        }

        override fun onTimeShiftSetPlaybackParams(params: PlaybackParams) = Unit
        override fun onTimeShiftGetStartPosition() = maxOf(bufferStartMs, now() - 30 * 60_000L)
        override fun onTimeShiftGetCurrentPosition() = position()

        override fun onSetStreamVolume(volume: Float) = Unit
        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onRelease() {
            drawHandler.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }

        private fun render() {
            val s = surface ?: return
            val def = channel ?: return
            val canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return
            try {
                val w = canvas.width.toFloat()
                val h = canvas.height.toFloat()
                canvas.drawColor(def.color)
                canvas.drawText("${def.number}  ${def.name}", w * 0.08f, h * 0.30f, big)
                val pos = position()
                canvas.drawText("broadcast time: ${clock.format(Date(pos))}", w * 0.08f, h * 0.48f, mid)
                val behind = now() - pos
                val state = when {
                    pausedAtMs > 0 -> "PAUSED (picture frozen)"
                    behind > 1500 -> "delay ${behind / 1000} s"
                    else -> "live"
                }
                canvas.drawText(state, w * 0.08f, h * 0.58f, mid)
                val sec = (pos / 1000) % 60
                canvas.drawRect(w * 0.08f, h * 0.70f, w * 0.08f + (w * 0.84f) * (sec + 1) / 60f, h * 0.73f, barPaint)
                canvas.drawText("Test tuner - drawn picture, no audio", w * 0.08f, h * 0.85f, small)
            } finally {
                runCatching { s.unlockCanvasAndPost(canvas) }
            }
        }
    }

    companion object {
        private const val TAG = "TestTuner"

        fun inputId(context: Context) =
            TvContract.buildInputId(android.content.ComponentName(context, TestTunerService::class.java))
    }
}
