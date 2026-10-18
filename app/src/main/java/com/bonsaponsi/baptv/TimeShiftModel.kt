package com.bonsaponsi.baptv

/**
 * Live pause (timeshift) timing model, computed by the app: dtvinput reports the UNAVAILABLE status
 * and stale positions, but pause, resume and seeking work (verified 2026-10-17).
 *
 * All times are device clock milliseconds ([System.currentTimeMillis]) – dtvinput computes positions in
 * `onTimeShiftSeekTo` with the same clock, so the seek position is "now minus delay".
 */
class TimeShiftModel {
    /** Pause has been used since the last tune (a buffer exists, the picture may be delayed). */
    var active = false; private set
    var paused = false; private set

    /** Earliest content moment in the buffer (dtvinput starts buffering on pause, with a ~3 s lead). */
    private var bufferStartMs = 0L
    /** Delay behind the live broadcast at [refMs]; while paused it grows with time. */
    private var refDelayMs = 0L
    private var refMs = 0L

    /** Delay behind the live broadcast at [nowMs]. */
    fun delayMs(nowMs: Long): Long {
        if (!active) return 0
        val d = if (paused) refDelayMs + (nowMs - refMs) else refDelayMs
        return d.coerceIn(0, maxDelayMs(nowMs))
    }

    /** How much content is in the buffer (from its start to "now"), at most [MAX_BUFFER_MS]. */
    fun maxDelayMs(nowMs: Long): Long = if (!active) 0 else (nowMs - effectiveBufferStart(nowMs)).coerceAtLeast(0)

    private fun effectiveBufferStart(nowMs: Long) = maxOf(bufferStartMs, nowMs - MAX_BUFFER_MS)

    /** Moment of the content shown on screen (device clock) – for seeking and the slider label. */
    fun positionMs(nowMs: Long) = nowMs - delayMs(nowMs)

    fun bufferStartMs(nowMs: Long) = if (active) effectiveBufferStart(nowMs) else nowMs

    fun pause(nowMs: Long) {
        if (!active) {
            active = true
            bufferStartMs = nowMs - BUFFER_LEAD_MS
            refDelayMs = 0
        } else {
            refDelayMs = delayMs(nowMs)
        }
        refMs = nowMs
        paused = true
    }

    fun resume(nowMs: Long) {
        if (!active) return
        refDelayMs = delayMs(nowMs)
        refMs = nowMs
        paused = false
    }

    /**
     * New delay after seeking by [deltaMs] (negative – backward, positive – forward, toward "live"),
     * clamped to the buffer contents.
     */
    fun targetDelayAfterSeek(nowMs: Long, fromDelayMs: Long, deltaMs: Long): Long =
        (fromDelayMs - deltaMs).coerceIn(0, maxDelayMs(nowMs))

    /** Seek applied in dtvinput: from now on we show content from [delayMs] ago. */
    fun seekApplied(nowMs: Long, delayMs: Long) {
        if (!active) return
        refDelayMs = delayMs.coerceIn(0, maxDelayMs(nowMs))
        refMs = nowMs
    }

    /** Back to live / new tune – the buffer is discarded. */
    fun reset() {
        active = false
        paused = false
        refDelayMs = 0
        bufferStartMs = 0
    }

    companion object {
        /** dtvinput's buffer (`PlayerStartSettings.timeshiftBufferMaxSizeInS`). */
        const val MAX_BUFFER_MS = 30 * 60_000L
        /** dtvinput keeps ~3 s of content before the moment of pause. */
        const val BUFFER_LEAD_MS = 3_000L
        /** Closer to the live broadcast than this – treated as "live". */
        const val LIVE_THRESHOLD_MS = 3_000L

        /** "1 min 30 s", "45 s", "1 h 5 min". */
        fun formatDelay(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return when {
                s < 60 -> "$s s"
                s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
                else -> "${s / 3600} h ${(s % 3600) / 60} min"
            }
        }
    }
}
