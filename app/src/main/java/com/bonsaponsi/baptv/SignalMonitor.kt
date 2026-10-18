package com.bonsaponsi.baptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Signal measurement for aiming the antenna, driven entirely from the web server's /signal page.
 *
 * There is a single tuner, so live measurement covers the multiplex the player is tuned to. Sampling
 * (every 0.5 s) runs as long as someone polls the page. A cycle switches the player in turn to a channel
 * on each known multiplex, collects statistics and returns to the original channel.
 */
object SignalMonitor {
    private const val TAG = "Signal"
    private const val SAMPLE_MS = 500L
    private const val HISTORY = 240 // 2 minutes
    private const val IDLE_STOP_MS = 15_000L
    private const val LOG_EVERY_MS = 2_000L
    const val SETTLE_MS = 2_500L
    const val MEASURE_MS = 5_000L
    val METRICS = listOf("rssi", "snr", "ber")

    /** Channel used to measure a multiplex. */
    data class MuxChannel(val frequencyMhz: Double, val channelId: Long, val name: String)

    /** Access to the player (set by [MainActivity]). */
    interface Player {
        fun multiplexes(): List<MuxChannel>
        fun currentChannelId(): Long?
        fun currentFrequencyMhz(): Double?
        /** Switches the player to a channel (called on the main thread). */
        fun tune(channelId: Long): Boolean
        /** Releases the tuner for a single-frequency scan ([FrequencyProbe]). */
        fun releaseTuner()
        /** Returns to the watched channel after the scan. */
        fun restoreTuner()
        /** Rediscovers channels (after a new multiplex is found); automatic identification follows. */
        fun rediscover()
    }

    @Volatile var player: Player? = null

    private class Sample(val time: Long, val frequencyMhz: Double?, val values: Map<String, Double>)

    private val history = ArrayDeque<Sample>()
    @Volatile private var lastPoll = 0L
    @Volatile private var sampler: Thread? = null
    @Volatile private var lastTuner: SignalReader.Tuner? = null
    @Volatile private var lastLog = 0L

    @Volatile var cycleRunning = false; private set
    @Volatile private var cycleCancelled = false
    @Volatile private var cycleStatus: String? = null
    /** Results of the last cycle: frequency -> statistics. */
    private val cycleResults = LinkedHashMap<Double, JSONObject>()

    private val main = Handler(Looper.getMainLooper())

    /** Page poll: starts sampling and returns the state to display. */
    fun poll(context: Context): JSONObject {
        lastPoll = System.currentTimeMillis()
        ensureSampler(context)
        val p = player
        val tuner = lastTuner
        return JSONObject()
            .put("time", System.currentTimeMillis())
            .put("capabilities", DeviceCapabilities.get(context).toJson())
            .put("player", p != null)
            .put("channel", p?.let { onMain { it.currentChannelId() } } ?: JSONObject.NULL)
            .put("frequency", currentFrequency() ?: JSONObject.NULL)
            .put("state", tuner?.state ?: JSONObject.NULL)
            .put("raw", JSONObject().put("stats", tuner?.stats?.values?.let { JSONObject(it) } ?: JSONObject.NULL)
                .put("parameters", tuner?.parameters ?: JSONObject.NULL).put("signal", tuner?.signal ?: JSONObject.NULL))
            .put("history", JSONArray().apply {
                synchronized(history) {
                    history.forEach { s ->
                        put(JSONObject().put("t", s.time).put("f", s.frequencyMhz ?: JSONObject.NULL).apply {
                            s.values.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }
            })
            .put("multiplexes", JSONArray().apply {
                (p?.let { onMain { it.multiplexes() } } ?: emptyList()).forEach { m ->
                    put(JSONObject().put("frequency", m.frequencyMhz).put("channel", m.channelId).put("name", m.name))
                }
            })
            .put("probe", FrequencyProbe.json())
            .put("lna", TunerSettings(context).getValue(Dtv.KEY_LNA) ?: JSONObject.NULL)
            .put("cycle", JSONObject().put("running", cycleRunning).put("status", cycleStatus ?: JSONObject.NULL)
                .put("results", JSONArray().apply { synchronized(cycleResults) { cycleResults.values.forEach { put(it) } } }))
    }

    private fun currentFrequency(): Double? = lastTuner?.frequencyMhz ?: player?.let { onMain { it.currentFrequencyMhz() } }

    /** Synchronized: the server handles each connection on its own thread (two /signal tabs at once). */
    @Synchronized
    private fun ensureSampler(context: Context) {
        if (sampler?.isAlive == true) return
        val reader = SignalReader(context.applicationContext)
        DebugLog.log(TAG, "signal measurement started")
        sampler = BackgroundWorker.start("signal-monitor") {
            while (System.currentTimeMillis() - lastPoll < IDLE_STOP_MS || cycleRunning) {
                sample(reader)
                Thread.sleep(SAMPLE_MS)
            }
            DebugLog.log(TAG, "signal measurement stopped (nobody is viewing the page)")
        }
    }

    private fun sample(reader: SignalReader) {
        val tuner = reader.readTuner()
        lastTuner = tuner
        val values = METRICS.mapNotNull { k -> tuner?.stats?.number(k)?.let { k to it } }.toMap()
        val s = Sample(System.currentTimeMillis(), currentFrequency(), values)
        synchronized(history) {
            history.addLast(s)
            while (history.size > HISTORY) history.removeFirst()
        }
        if (s.time - lastLog >= LOG_EVERY_MS) {
            lastLog = s.time
            val f = s.frequencyMhz?.let { "%.0f MHz".format(java.util.Locale.US, it) } ?: "? MHz"
            DebugLog.log(TAG, "$f  " + (tuner?.stats?.format() ?: "no statistics") + "  state=${tuner?.state}")
        }
    }

    /** Switches the player to a multiplex channel (from the page). */
    fun tuneMultiplex(context: Context, frequencyMhz: Double): String {
        if (cycleRunning) return "A cycle is running – stop it first."
        val p = ensurePlayer(context) ?: return "The player is not running and could not be started."
        val mux = onMain { p.multiplexes() }?.firstOrNull { it.frequencyMhz == frequencyMhz }
            ?: return "No channel on $frequencyMhz MHz."
        val ok = onMain { p.tune(mux.channelId) } == true
        DebugLog.log(TAG, "tuning to multiplex $frequencyMhz MHz (${mux.name}): $ok")
        return if (ok) "Tuner on $frequencyMhz MHz (${mux.name})." else "Could not switch."
    }

    /** Cycle through all known multiplexes in the background; result in [poll]. */
    @Synchronized
    fun startCycle(context: Context): String {
        if (cycleRunning) return "A cycle is already running."
        if (FrequencyProbe.running) return "A frequency check is running – stop it first."
        val p = ensurePlayer(context) ?: return "The player is not running and could not be started."
        val muxes = onMain { p.multiplexes() }.orEmpty()
        if (muxes.isEmpty()) return "No known multiplexes (is the channel list empty?)."
        val original = onMain { p.currentChannelId() }
        cycleRunning = true
        cycleCancelled = false
        synchronized(cycleResults) { cycleResults.clear() }
        lastPoll = System.currentTimeMillis()
        ensureSampler(context)
        DebugLog.log(TAG, "multiplex cycle: ${muxes.joinToString { "${it.frequencyMhz} MHz" }}")
        BackgroundWorker.start("signal-cycle") {
            try {
                muxes.forEachIndexed { i, mux ->
                    if (cycleCancelled) return@forEachIndexed
                    cycleStatus = "${i + 1}/${muxes.size}: %.0f MHz (%s) – tuning".format(java.util.Locale.US, mux.frequencyMhz, mux.name)
                    onMain { p.tune(mux.channelId) }
                    Thread.sleep(SETTLE_MS)
                    cycleStatus = "${i + 1}/${muxes.size}: %.0f MHz (%s) – measuring".format(java.util.Locale.US, mux.frequencyMhz, mux.name)
                    val from = System.currentTimeMillis()
                    Thread.sleep(MEASURE_MS)
                    val samples = synchronized(history) { history.filter { it.time >= from } }
                    val result = JSONObject().put("frequency", mux.frequencyMhz).put("name", mux.name).put("samples", samples.size)
                    METRICS.forEach { k ->
                        val v = samples.mapNotNull { it.values[k] }
                        if (v.isNotEmpty()) result.put(k, JSONObject().put("min", v.min()).put("avg", v.average()).put("max", v.max()))
                    }
                    synchronized(cycleResults) { cycleResults[mux.frequencyMhz] = result }
                    DebugLog.log(TAG, "cycle: $result")
                }
            } finally {
                original?.let { id -> onMain { p.tune(id) } }
                cycleStatus = if (cycleCancelled) "Cycle cancelled" else "Cycle finished"
                cycleRunning = false
            }
        }
        return "Cycle started: ${muxes.size} multiplexes, approx. ${muxes.size * (SETTLE_MS + MEASURE_MS) / 1000} s."
    }

    fun stopCycle(): String {
        if (!cycleRunning) return "No cycle is running."
        cycleCancelled = true
        return "Stopping the cycle…"
    }

    /** The player is needed for tuning; if it is not running, we start it (no remote needed). */
    private fun ensurePlayer(context: Context): Player? {
        player?.let { return it }
        DebugLog.log(TAG, "player not running – starting it")
        SelfUpdater.launch(context)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            player?.let { p ->
                // The player still has to load the channel list.
                if (onMain { p.multiplexes() }.orEmpty().isNotEmpty()) return p
            }
            Thread.sleep(300)
        }
        return player
    }

    fun <T> onMainBlocking(block: () -> T): T? = onMain(block)

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        main.post { try { value = block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
        return value
    }
}
