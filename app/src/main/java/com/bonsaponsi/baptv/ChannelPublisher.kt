package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvContract.Channels
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Publishes identified channels to the system through dtvinput's InstallerService in "json" mode:
 * for each multiplex an explicit service list with name, LCN number and DVB identifiers, searchable=1.
 * dtvinput then writes the channels to TvContract visible to every app – with the broadcaster's numbering
 * and without the ID probing the player otherwise needs ([ChannelRepository.loadChannels] prefers them).
 *
 * The "json" install type is not documented, so the result is checked afterwards (channels visible in TvContract).
 * Published channels may not be removed by a later full scan – publishing runs only on the user's request.
 */
class ChannelPublisher(private val context: Context) {

    /** One install request = one multiplex. */
    data class Mux(val frequencyMhz: Double, val services: List<Channel>)

    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var queue: List<Mux> = emptyList()
    private var index = 0
    /** Which end events belong to the current multiplex – see [MuxEventFilter]. */
    private val events = MuxEventFilter(SETTLE_MS)
    private var onDone: ((Result) -> Unit)? = null
    private var failedMuxes = 0
    private var startedAt = 0L

    data class Result(val muxes: Int, val failedMuxes: Int, val visibleChannels: Int)

    private val timeout = Runnable {
        events.timedOut()
        DebugLog.warn(TAG, "no completion event for multiplex ${queue.getOrNull(index)?.frequencyMhz} MHz within ${MUX_TIMEOUT_MS / 1000} s")
        failedMuxes++
        next()
    }

    /** Starts publishing [channels] (identified ones with a known frequency); [done] on the main thread. */
    fun publish(channels: List<Channel>, done: (Result) -> Unit) {
        queue = muxes(channels)
        if (queue.isEmpty()) {
            DebugLog.warn(TAG, "nothing to publish – no identified channels with a known multiplex frequency")
            done(Result(0, 0, visibleChannels()))
            return
        }
        onDone = done
        active = true
        index = -1
        failedMuxes = 0
        startedAt = System.currentTimeMillis()
        DebugLog.log(TAG, "publishing ${queue.sumOf { it.services.size }} services on ${queue.size} multiplexes: " +
            queue.joinToString { "${fmt(it.frequencyMhz)} MHz×${it.services.size}" })
        register()
        // The installer waits for the tuner – the player has just released it.
        handler.postDelayed({ next() }, TUNER_RELEASE_DELAY_MS)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        unregister()
        onDone = null
        active = false
    }

    /** A deferred end event stands: nothing showed that the multiplex is still being installed. */
    private val settle = Runnable { events.settle()?.let { ended(it) } }

    private fun next() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(settle)
        index++
        val mux = queue.getOrNull(index)
        if (mux == null) {
            finish()
            return
        }
        val params = json(mux, Region.plan()).toString()
        DebugLog.log(TAG, "-> multiplex ${index + 1}/${queue.size} ${fmt(mux.frequencyMhz)} MHz: $params")
        val started = runCatching {
            val intent = Intent().setComponent(Dtv.INSTALLER_SERVICE).putExtra(Dtv.EXTRA_PARAMS, params)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                context.startForegroundService(intent)
            }
        }.onFailure { DebugLog.error(TAG, "starting InstallerService failed", it) }.getOrNull()
        if (started == null) {
            failedMuxes++
            handler.post { next() }
            return
        }
        events.started(System.currentTimeMillis())
        handler.postDelayed(timeout, MUX_TIMEOUT_MS)
    }

    private fun onEvent(intent: Intent) {
        val status = intent.getStringExtra(Dtv.EXTRA_STATUS).orEmpty().lowercase(Locale.ROOT)
        DebugLog.log(TAG, "<- status=$status nb=${intent.getIntExtra(Dtv.EXTRA_CHANNEL_COUNT, -1)} progress=${intent.getIntExtra(Dtv.EXTRA_PROGRESS, -1)}")
        val now = System.currentTimeMillis()
        when (events.onEvent(status, now)) {
            MuxEventFilter.Verdict.END -> ended(status)
            MuxEventFilter.Verdict.DEFER -> {
                DebugLog.log(TAG, "end event right after the request – waiting to see whether it belongs to this multiplex")
                handler.removeCallbacks(settle)
                handler.postDelayed(settle, (events.settleAtMs - now).coerceAtLeast(0))
            }
            MuxEventFilter.Verdict.IGNORE -> Unit
        }
    }

    /** The current multiplex ended with [status]; the next one follows after a pause. */
    private fun ended(status: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(settle)
        if (!MuxEventFilter.isComplete(status)) failedMuxes++
        handler.postDelayed({ next() }, BETWEEN_MUXES_MS)
    }

    private fun finish() {
        unregister()
        active = false
        val visible = visibleChannels()
        val result = Result(queue.size, failedMuxes, visible)
        val ok = visible > 0
        DebugLog.log(TAG, "publishing done in ${(System.currentTimeMillis() - startedAt) / 1000} s: multiplexes ${queue.size}, " +
            "failed $failedMuxes, channels visible in TvContract: $visible" + if (ok) "" else " – dtvinput ignored searchable=1 or the json install")
        val callback = onDone
        onDone = null
        callback?.invoke(result)
    }

    /** Channels of the tuner input that a regular app can see (searchable=1). */
    private fun visibleChannels(): Int = runCatching {
        val input = Dtv.findTunerInput(context) ?: return 0
        context.contentResolver.query(android.media.tv.TvContract.buildChannelsUriForInput(input.id), arrayOf(Channels._ID), null, null, null)
            ?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun register() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onEvent(intent)
        }
        Dtv.registerEvents(context, r, IntentFilter(Dtv.ACTION_INSTALL_EVENT), handler)
        receiver = r
    }

    private fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    companion object {
        private const val TAG = "Publish"
        private const val TUNER_RELEASE_DELAY_MS = 3000L
        private const val BETWEEN_MUXES_MS = 1500L
        private const val MUX_TIMEOUT_MS = 120_000L
        /** An end event sooner than this after a request, with no progress before it, is probably a late one. */
        private const val SETTLE_MS = 5000L

        /** Publishing is running – a channel scan must not start meanwhile (both use InstallerService). */
        @Volatile var active = false
            private set

        private fun fmt(mhz: Double) = if (mhz % 1.0 == 0.0) mhz.toLong().toString() else String.format(Locale.US, "%.1f", mhz)

        /** Identified channels grouped by multiplex frequency (and ONID/TSID, in case a frequency carries two). */
        fun muxes(channels: List<Channel>): List<Mux> =
            channels.filter { it.identified && it.onid != null && it.tsid != null && it.frequencyMhz != null }
                .groupBy { Triple(it.frequencyMhz!!, it.onid, it.tsid) }
                .map { (key, list) -> Mux(key.first, list.distinctBy { it.sid }) }
                .sortedBy { it.frequencyMhz }

        /** dtvinput "json" install request for one multiplex. */
        fun json(mux: Mux, plan: Region.Plan): JSONObject {
            val khz = (mux.frequencyMhz * 1000).toLong()
            val bandwidthMhz = plan.bands(true).firstOrNull { khz in it.minKhz..it.maxKhz }?.bandwidthKhz?.div(1000) ?: 8
            val services = JSONArray()
            mux.services.forEach { ch ->
                services.put(JSONObject()
                    .put("display_name", ch.name)
                    .put("display_number", ch.number)
                    .put("service_id", ch.sid)
                    .put("service_type", if (ch.isRadio) Channels.SERVICE_TYPE_AUDIO else Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    .put("type", Channels.TYPE_DVB_T2)
                    .put("original_network_id", ch.onid)
                    .put("transport_stream_id", ch.tsid)
                    .put("frequency", fmt(mux.frequencyMhz) + "mhz")
                    .put("bandwidth", "${bandwidthMhz}mhz")
                    .put("searchable", 1)
                    .put("browsable", 1))
            }
            return JSONObject()
                .put("type", "json")
                .put("scan-mode", "full")
                .put("country", Region.countryName(Region.country()))
                .put("services", services)
        }
    }
}

/**
 * Decides which install events end the current multiplex. dtvinput may send several end events for one
 * multiplex (progress=100, completed), and one may come only after the timeout moved on: counted for the next
 * multiplex, it would skip that one, and each skipped one would push the error further. So an end event counts
 * once per multiplex, and one right after the request ([settleMs], with no progress event before it) is
 * deferred: it stands only if no progress event follows in that time. No Android dependencies (unit tests).
 */
internal class MuxEventFilter(private val settleMs: Long) {
    enum class Verdict { IGNORE, END, DEFER }

    private var startedMs = 0L
    private var progressSeen = false
    private var ended = true
    private var deferred: String? = null

    /** When a [Verdict.DEFER]red event is decided ([settle]). */
    val settleAtMs get() = startedMs + settleMs

    fun started(nowMs: Long) {
        startedMs = nowMs
        progressSeen = false
        ended = false
        deferred = null
    }

    fun timedOut() {
        ended = true
        deferred = null
    }

    fun onEvent(status: String, nowMs: Long): Verdict {
        if (ended) return Verdict.IGNORE
        if (!isTerminal(status)) {
            progressSeen = true
            deferred = null
            return Verdict.IGNORE
        }
        if (progressSeen || nowMs - startedMs >= settleMs) {
            ended = true
            deferred = null
            return Verdict.END
        }
        deferred = status
        return Verdict.DEFER
    }

    /** The deferred end event's status when it stands, otherwise null. */
    fun settle(): String? {
        val status = deferred ?: return null
        if (ended) return null
        ended = true
        deferred = null
        return status
    }

    companion object {
        fun isTerminal(status: String) = isComplete(status) || "fail" in status || "cancel" in status
        fun isComplete(status: String) = "complete" in status
    }
}
