package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Checks a single frequency (a multiplex with no channels in the list), e.g. when aiming
 * the antenna at MUX-2. The tuner is retuned by a dtvinput scan limited to one frequency in
 * "unmanaged_manual" mode, which according to the dtvinput code clears only the scanned multiplex – the other
 * channels stay. Without a signal the tuner reports no values, so the result is "present / absent" plus
 * statistics once locked. Driven from the /signal page.
 */
object FrequencyProbe {
    private const val TAG = "Probe"
    private const val SCAN_TIMEOUT_MS = 25_000L
    private const val SAMPLE_MS = 300L
    private const val HISTORY = 30

    @Volatile var running = false; private set
    @Volatile private var cancelled = false
    @Volatile private var status: String? = null
    @Volatile var servicesFound = false; private set
    private val results = ArrayDeque<JSONObject>()

    @Synchronized
    fun start(context: Context, frequencyMhz: Double, dvbT2: Boolean, repeat: Boolean): String {
        if (running) return "A check is already running."
        if (SignalMonitor.cycleRunning) return "A multiplex cycle is running – stop it first."
        if (frequencyMhz !in 170.0..870.0) return "Frequency outside the 170–870 MHz range."
        val player = SignalMonitor.player ?: return "The player is not running – open it (e.g. with a multiplex button)."
        running = true
        cancelled = false
        val app = context.applicationContext
        val label = "%.1f MHz %s".format(java.util.Locale.US, frequencyMhz, if (dvbT2) "DVB-T2" else "DVB-T")
        DebugLog.log(TAG, "checking $label, repeat=$repeat")
        BackgroundWorker.start("frequency-probe") {
            try {
                SignalMonitor.onMainBlocking { player.releaseTuner() }
                Thread.sleep(800)
                do {
                    status = "Checking $label…"
                    val result = probeOnce(app, frequencyMhz, dvbT2)
                    synchronized(results) {
                        results.addFirst(result)
                        while (results.size > HISTORY) results.removeLast()
                    }
                    DebugLog.log(TAG, "result: $result")
                    if (result.optInt("services") > 0) servicesFound = true
                    if (repeat && !cancelled) Thread.sleep(1000)
                } while (repeat && !cancelled)
            } catch (e: Exception) {
                DebugLog.error(TAG, "check aborted", e)
            } finally {
                SignalMonitor.onMainBlocking { player.restoreTuner() }
                status = if (cancelled) "Stopped" else "Done"
                running = false
            }
        }
        return "Checking $label" + if (repeat) " (repeating – stop it when you are done)" else ""
    }

    fun stop(): String {
        if (!running) return "Nothing is being checked."
        cancelled = true
        return "Stopping after the current measurement…"
    }

    fun json(): JSONObject = JSONObject()
        .put("running", running).put("status", status ?: JSONObject.NULL).put("servicesFound", servicesFound)
        .put("results", JSONArray().apply { synchronized(results) { results.forEach { put(it) } } })

    /** After the found channels have been added to the list. */
    fun clearServicesFound() { servicesFound = false }

    private fun probeOnce(context: Context, frequencyMhz: Double, dvbT2: Boolean): JSONObject {
        val khz = Math.round(frequencyMhz * 1000).toInt()
        val bandwidth = if (frequencyMhz < 300) 7_000 else 8_000
        val params = JSONObject()
            .put("type", if (dvbT2) "dvb-t2" else "dvb-t")
            .put("scan-mode", "unmanaged_manual")
            .put("country", "")
            .put("custom-scan", false)
            .put("slots", JSONArray().put(JSONObject()
                .put("frequency-min", khz).put("frequency-max", khz)
                .put("frequency-step", bandwidth).put("bandwidth", bandwidth)))
            .toString()

        val done = CountDownLatch(1)
        var finalStatus = "no response"
        var services = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val st = intent.getStringExtra(Dtv.EXTRA_STATUS).orEmpty().lowercase()
                services = intent.getIntExtra(Dtv.EXTRA_CHANNEL_COUNT, services)
                if ("complete" in st || "fail" in st || "cancel" in st) {
                    finalStatus = st
                    done.countDown()
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Dtv.ACTION_INSTALL_EVENT), Dtv.eventSenderPermission(context), null)
        val reader = SignalReader(context)
        var locked = false
        val values = HashMap<String, MutableList<Double>>()
        val started = System.currentTimeMillis()
        try {
            context.startService(Intent().setComponent(Dtv.INSTALLER_SERVICE).putExtra(Dtv.EXTRA_PARAMS, params))
            while (done.count > 0 && System.currentTimeMillis() - started < SCAN_TIMEOUT_MS && !cancelled) {
                val t = reader.readTuner()
                val f = t?.frequencyMhz
                if (f != null && Math.abs(f - frequencyMhz) < 1.0) {
                    if (t.signal.equals("true", true) || t.stats != null) locked = locked || t.stats != null || t.signal.equals("true", true)
                    t.stats?.let { s -> SignalMonitor.METRICS.forEach { k -> s.number(k)?.let { values.getOrPut(k) { mutableListOf() } += it } } }
                }
                done.await(SAMPLE_MS, TimeUnit.MILLISECONDS)
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            if (done.count > 0) {
                // Timeout or stop – end the installer scan.
                runCatching { context.stopService(Intent().setComponent(Dtv.INSTALLER_SERVICE)) }
            }
        }
        return JSONObject()
            .put("time", System.currentTimeMillis())
            .put("frequency", frequencyMhz)
            .put("standard", if (dvbT2) "DVB-T2" else "DVB-T")
            .put("locked", locked || services > 0)
            .put("services", services)
            .put("status", finalStatus)
            .put("seconds", (System.currentTimeMillis() - started) / 1000.0)
            .apply { values.forEach { (k, v) -> put(k, JSONObject().put("max", v.max()).put("avg", v.average())) } }
    }
}
