package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvContract.Channels
import android.os.Build
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
    private var onDone: ((Result) -> Unit)? = null
    private var failedMuxes = 0
    private var startedAt = 0L

    data class Result(val muxes: Int, val failedMuxes: Int, val visibleChannels: Int)

    private val timeout = Runnable {
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
    }

    private fun next() {
        handler.removeCallbacks(timeout)
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
        handler.postDelayed(timeout, MUX_TIMEOUT_MS)
    }

    private fun onEvent(intent: Intent) {
        val status = intent.getStringExtra(Dtv.EXTRA_STATUS).orEmpty().lowercase(Locale.ROOT)
        DebugLog.log(TAG, "<- status=$status nb=${intent.getIntExtra(Dtv.EXTRA_CHANNEL_COUNT, -1)} progress=${intent.getIntExtra(Dtv.EXTRA_PROGRESS, -1)}")
        when {
            "complete" in status -> handler.postDelayed({ next() }, BETWEEN_MUXES_MS)
            "fail" in status || "cancel" in status -> {
                failedMuxes++
                handler.postDelayed({ next() }, BETWEEN_MUXES_MS)
            }
        }
        if ("complete" in status || "fail" in status || "cancel" in status) handler.removeCallbacks(timeout)
    }

    private fun finish() {
        unregister()
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
        val filter = IntentFilter(Dtv.ACTION_INSTALL_EVENT)
        val permission = Dtv.eventSenderPermission(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, permission, handler, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(r, filter, permission, handler)
        }
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
                .put("country", countryName(Region.country()))
                .put("services", services)
        }

        /** dtvinput takes the country as an English lower-case name ("poland"); "" – unknown. */
        private fun countryName(code: String): String =
            if (code.isEmpty()) "" else Locale("", code).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
    }
}
