package com.bonsaponsi.baptv

import android.content.Context

/**
 * Single-run channel setup: (1) dtvinput scan, (2) discovery of channel
 * IDs, (3) identification (recording each channel). Estimates time from stored averages
 * of previous runs; the averages are updated after each stage.
 */
object SetupProgress {
    private const val PREFS = "setup_progress"
    private const val KEY_PIPELINE = "pipeline"
    private const val KEY_EXPECTED = "expected"
    private const val KEY_STARTED = "started"
    private const val KEY_LAST_COUNT = "last_count"

    /** Per-unit times in seconds – pure functions for tests. */
    data class Rates(val perFrequency: Double, val perProbe: Double, val perChannel: Double)

    /** Initial values measured on the reference set-top box (10.2026). */
    val DEFAULT_RATES = Rates(perFrequency = 3.0, perProbe = 2.7, perChannel = 7.8)

    /** Probing usually checks a few more IDs than there are channels (nonexistent ones, termination). */
    const val PROBE_OVERHEAD = 6

    data class Estimate(val scanSec: Double, val discoverySec: Double, val identifySec: Double) {
        val totalSec get() = scanSec + discoverySec + identifySec
    }

    fun estimate(rates: Rates, frequencies: Int, channels: Int) = Estimate(
        scanSec = frequencies * rates.perFrequency,
        discoverySec = (channels + PROBE_OVERHEAD) * rates.perProbe,
        identifySec = channels * rates.perChannel,
    )

    /** Exponential average: a new measurement weighs half (times depend on the set-top box and channel count). */
    fun blend(old: Double, measured: Double): Double = if (measured <= 0 || measured.isNaN()) old else old * 0.5 + measured * 0.5

    /** Remaining stage time from progress in percent (0–100); null when it is too early to extrapolate. */
    fun remainingFromProgress(elapsedSec: Double, percent: Int): Double? =
        if (percent < 5 || percent >= 100) null else elapsedSec * (100 - percent) / percent

    /** "~45 s", "~3 min", "~1 h 10 min". */
    fun format(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        return when {
            s < 60 -> "~${(Math.ceil(s / 5) * 5).toInt().coerceAtLeast(5)} s"
            s < 3600 -> "~${Math.ceil(s / 60).toInt()} min"
            else -> {
                // Round up to a full minute before splitting – otherwise 7199 s gave "1 h 60 min".
                val minutes = Math.ceil(s / 60).toInt()
                "~${minutes / 60} h ${minutes % 60} min"
            }
        }
    }

    // ---- State in SharedPreferences ----

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rates(c: Context): Rates = prefs(c).let { p ->
        Rates(
            p.getFloat("perFrequency", DEFAULT_RATES.perFrequency.toFloat()).toDouble(),
            p.getFloat("perProbe", DEFAULT_RATES.perProbe.toFloat()).toDouble(),
            p.getFloat("perChannel", DEFAULT_RATES.perChannel.toFloat()).toDouble(),
        )
    }

    /** Stores the measured stage time ([key]: perFrequency / perProbe / perChannel). */
    fun learn(c: Context, key: String, totalSec: Double, units: Int) {
        if (units <= 0) return
        val current = rates(c)
        val old = when (key) { "perFrequency" -> current.perFrequency; "perProbe" -> current.perProbe; else -> current.perChannel }
        val updated = blend(old, totalSec / units)
        prefs(c).edit().putFloat(key, updated.toFloat()).apply()
        DebugLog.log("Setup", "per-unit time $key: %.2f s (measured %.2f s × %d)".format(java.util.Locale.US, updated, totalSec / units, units))
    }

    /** Channel count for the pre-scan estimate: from the last setup, otherwise 40. */
    fun lastChannelCount(c: Context) = prefs(c).getInt(KEY_LAST_COUNT, 40)

    fun setLastChannelCount(c: Context, n: Int) { if (n > 0) prefs(c).edit().putInt(KEY_LAST_COUNT, n).apply() }

    /** Scan finished – the player should immediately discover and identify [expected] channels. */
    fun startPipeline(c: Context, expected: Int, scanStartedAt: Long) {
        prefs(c).edit().putBoolean(KEY_PIPELINE, true).putInt(KEY_EXPECTED, expected).putLong(KEY_STARTED, scanStartedAt).apply()
        setLastChannelCount(c, expected)
    }

    fun inPipeline(c: Context) = prefs(c).getBoolean(KEY_PIPELINE, false)

    /** Channel count reported by the scan (0 – unknown). */
    fun expectedChannels(c: Context) = if (inPipeline(c)) prefs(c).getInt(KEY_EXPECTED, 0) else 0

    /** Time since the scan started (for the whole-run summary). */
    fun elapsedSinceStart(c: Context): Double = (System.currentTimeMillis() - prefs(c).getLong(KEY_STARTED, System.currentTimeMillis())) / 1000.0

    fun endPipeline(c: Context) { prefs(c).edit().putBoolean(KEY_PIPELINE, false).apply() }
}
