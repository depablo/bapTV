package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The in-app DVB-T/T2 scan, kept by the process instead of a screen: [ScanActivity] only shows it,
 * so the user can leave the scan screen (menu, introduction, internet channels) while dtvinput scans.
 * Requests go to dtvinput's InstallerService (exported without permissions) or, with INSTALL_TV_CHANNELS,
 * as the ACTION_START_INSTALL broadcast; progress arrives via the INSTALL_EVENT broadcast.
 * All calls on the main thread.
 */
@android.annotation.SuppressLint("StaticFieldLeak")
object ScanSession {
    enum class Method { SERVICE, BROADCAST }

    enum class Outcome { COMPLETE, FAILED, CANCELLED, REJECTED }

    /**
     * What the scan screen and the player banner show. [outcome] is set once the scan ended;
     * [openWizard] – the request was rejected and the system setup wizard should open.
     */
    class State(
        val running: Boolean = false,
        val progress: Int = 0,
        val channels: Int = 0,
        val status: String = "",
        val outcome: Outcome? = null,
        val openWizard: Boolean = false,
        /** The InstallerService method was rejected – its button and options make no sense any more. */
        val serviceRejected: Boolean = false,
    )

    @Volatile var state = State()
        private set
    val running get() = state.running

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    fun addListener(listener: (State) -> Unit) { listeners += listener }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    /** Message for a scan failure report, taken by the first screen able to offer it ([takeReport]). */
    private var pendingReport: String? = null
    fun takeReport(): String? = pendingReport.also { pendingReport = null }

    private val handler = Handler(Looper.getMainLooper())
    /** Application context only – it lives as long as the process, so no leak. */
    @android.annotation.SuppressLint("StaticFieldLeak")
    private lateinit var app: Context
    private var receiverRegistered = false
    private var method: Method? = null
    private var vhf = false
    private var t2 = true
    private var startedAt = 0L
    private var lastEventAt = 0L
    /** Index in [paramsVariants] sent with the current request. */
    private var paramsVariant = 0
    /** One retry per scan when it was rejected while another client held the tuner. */
    private var busyRetried = false

    // Reception statistics collected from the streamers table during the scan.
    private var lastFrontend: String? = null
    private val visitedFrequencies = HashSet<Double>()
    private val lockedFrequencies = HashSet<Double>()
    /** Tuner reads off the main thread; created per scan, shut down when it ends. */
    private var io: BackgroundWorker? = null

    /** Strings in the app language, not the system one. */
    private fun res() = AppLanguage.wrap(app)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.log("Scan", "<- ${intent.action?.substringAfterLast('.')} ${Dtv.describeExtras(intent)}")
            lastEventAt = System.currentTimeMillis()
            when (intent.action) {
                Dtv.ACTION_INSTALL_EVENT -> onInstallEvent(intent)
                Dtv.ACTION_SETUP_READY -> if (running) update(status = res().getString(R.string.scan_tuner_ready))
            }
        }
    }

    /**
     * Warning when dtvinput goes silent – e.g. it rejected the parameters ("Setup not found" is not broadcast) or
     * its InstallerService died. After [SILENCE_FAIL_SECONDS] the scan ends as failed: a session that never ends
     * would keep the tuner from the player, discovery and identification until the process restarts.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            val silentFor = (System.currentTimeMillis() - lastEventAt) / 1000
            if (silentFor >= SILENCE_FAIL_SECONDS) {
                DebugLog.warn("Scan", "no events from dtvinput for $silentFor s – giving up")
                val message = res().getString(R.string.scan_no_response, silentFor)
                pendingReport = message
                end(Outcome.FAILED, message)
                return
            }
            if (silentFor >= WATCHDOG_SECONDS) {
                DebugLog.warn("Scan", "no events from dtvinput for $silentFor s (started ${(System.currentTimeMillis() - startedAt) / 1000} s ago)")
                update(status = res().getString(R.string.scan_no_response, silentFor))
            }
            handler.postDelayed(this, WATCHDOG_SECONDS * 1000L)
        }
    }

    /** The retry after a rejection with a busy tuner ([onRejected]) – removed when the scan ends. */
    private val busyRetry = Runnable {
        if (running && method == Method.SERVICE) {
            // A new request from the remembered variant, as from the button.
            val variants = paramsVariants()
            paramsVariant = prefs().getInt(PREF_PARAMS_VARIANT, 0).takeIf { it in variants.indices } ?: 0
            sendRequest(variants[paramsVariant])
        }
    }

    /** Frontend state every second during the scan – which frequencies were visited and which had a signal. */
    private val frontendPoll = object : Runnable {
        override fun run() {
            if (!running) return
            val tuner = TunerSettings(app)
            io?.execute {
                val fe = tuner.readFrontend()
                handler.post { if (running) onFrontend(fe) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun hasInstallPermission(context: Context) =
        context.checkSelfPermission(Dtv.PERMISSION_INSTALL_TV_CHANNELS) == PackageManager.PERMISSION_GRANTED

    /** Starts a scan with the remembered request variant; false when the request could not be sent. */
    fun start(context: Context, method: Method, includeVhf: Boolean, dvbT2: Boolean): Boolean {
        // A second OK (or another screen) while scanning must not send a second request.
        if (running) return true
        app = context.applicationContext
        // A report of an earlier scan must not show up after this one.
        pendingReport = null
        if (Recorder.active != null) {
            // Retuning for the scan would cut the recording short.
            end(Outcome.FAILED, res().getString(R.string.scan_recording))
            return false
        }
        if (ChannelPublisher.active) {
            // Publishing sends its own requests to InstallerService – a scan would break both.
            end(Outcome.FAILED, res().getString(R.string.scan_publishing))
            return false
        }
        vhf = includeVhf
        t2 = dvbT2
        val variants = paramsVariants()
        paramsVariant = prefs().getInt(PREF_PARAMS_VARIANT, 0).takeIf { it in variants.indices } ?: 0
        val params = variants[paramsVariant]
        DebugLog.log("Scan", "start with method $method, params=$params")
        registerEvents()
        try {
            when (method) {
                Method.SERVICE -> startInstallerService(params)
                Method.BROADCAST -> {
                    val intent = Intent(Dtv.ACTION_START_INSTALL).setPackage(Dtv.DTV_INPUT_PACKAGE).putExtra(Dtv.EXTRA_PARAMS, params)
                    app.sendBroadcast(intent, Dtv.PERMISSION_INSTALL_TV_CHANNELS)
                }
            }
        } catch (e: Exception) {
            DebugLog.error("Scan", "failed to request the scan", e)
            val message = res().getString(R.string.scan_send_failed, e.toString())
            pendingReport = message
            end(Outcome.FAILED, message)
            return false
        }
        this.method = method
        // Kept over a process death: the next start clears the discovered list the new scan made stale.
        prefs().edit().putBoolean(PREF_INTERRUPTED, true).apply()
        SetupProgress.markScanned(app)
        visitedFrequencies.clear()
        lockedFrequencies.clear()
        lastFrontend = null
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        io = io ?: BackgroundWorker("scan-io")
        publish(State(running = true, status = res().getString(R.string.scan_waiting)))
        handler.removeCallbacks(frontendPoll)
        handler.post(frontendPoll)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_SECONDS * 1000L)
        return true
    }

    fun cancel(context: Context) {
        if (!running) return
        DebugLog.log("Scan", "cancelling scan (method $method)")
        // The broadcast only gets through with INSTALL_TV_CHANNELS; without it we stop the installer service.
        app.sendBroadcast(Intent(Dtv.ACTION_CANCEL_INSTALL).setPackage(Dtv.DTV_INPUT_PACKAGE), Dtv.PERMISSION_INSTALL_TV_CHANNELS)
        if (!hasInstallPermission(context)) {
            val stopped = runCatching { app.stopService(Intent().setComponent(Dtv.INSTALLER_SERVICE)) }
            DebugLog.log("Scan", "-> stopService(InstallerService) = $stopped")
        }
        end(Outcome.CANCELLED, res().getString(R.string.scan_cancelled))
    }

    /**
     * The process died during a scan (state is kept only in memory): channel IDs may have changed, so the
     * discovered list is cleared as after a finished scan. Called at process start.
     */
    fun recoverInterrupted(context: Context) {
        val prefs = context.getSharedPreferences(ScanActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_INTERRUPTED, false)) return
        DebugLog.warn("Scan", "the process ended during a scan – clearing the discovered channels")
        ChannelStore(context).clearDiscovered()
        prefs.edit().remove(PREF_INTERRUPTED).apply()
    }

    /** The finished scan was shown – the next screen starts from a clean state. */
    fun acknowledge() {
        if (!running) state = State()
    }

    private fun registerEvents() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Dtv.ACTION_INSTALL_EVENT)
            addAction(Dtv.ACTION_UNINSTALL_EVENT)
            addAction(Dtv.ACTION_SETUP_READY)
            addAction(Dtv.ACTION_START_WATCHING_UPDATE)
            addAction(Dtv.ACTION_END_WATCHING_UPDATE)
        }
        DebugLog.log("Scan", "scan events only from a sender with permission: ${Dtv.eventSenderPermission(app) ?: "no restriction"}")
        Dtv.registerEvents(app, receiver, filter)
        receiverRegistered = true
    }

    /** InstallerService stores the extras and starts the scan once the tuner is ready. */
    private fun startInstallerService(params: String) {
        val result = Dtv.startInstaller(app, params)
        DebugLog.log("Scan", "-> startService(${Dtv.INSTALLER_SERVICE.flattenToShortString()}, $params) = $result")
        if (result == null) error("service ${Dtv.INSTALLER_SERVICE.flattenToShortString()} does not exist")
    }

    private fun onInstallEvent(intent: Intent) {
        if (!running) return
        val progress = intent.getIntExtra(Dtv.EXTRA_PROGRESS, 0)
        val count = intent.getIntExtra(Dtv.EXTRA_CHANNEL_COUNT, -1)
        val status = intent.getStringExtra(Dtv.EXTRA_STATUS).orEmpty().lowercase()
        val shown = count.coerceAtLeast(0)
        val r = res()
        when {
            "complete" in status -> {
                // A new scan changes channel IDs – the discovered list must be refreshed.
                ChannelStore(app).clearDiscovered()
                if (shown == 0) {
                    val message = r.getString(R.string.scan_complete, 0)
                    pendingReport = message
                    end(Outcome.COMPLETE, message, progress = 100)
                    return
                }
                prefs().edit().putInt(PREF_PARAMS_VARIANT, paramsVariant).apply()
                ScanActivity.setRejected(app, false)
                // Single run: the player discovers and identifies the channels by itself (stages 2 and 3).
                val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
                SetupProgress.learn(app, "perFrequency", elapsed, frequencyCount())
                SetupProgress.startPipeline(app, shown, startedAt)
                val rest = SetupProgress.estimate(SetupProgress.rates(app), 0, shown)
                DebugLog.log("Scan", "single run: moving on to discovering and identifying $shown channels")
                end(Outcome.COMPLETE, r.getString(R.string.setup_stage1_done, shown, SetupProgress.format(rest.discoverySec + rest.identifySec)),
                    progress = 100, channels = shown)
            }
            "fail" in status && System.currentTimeMillis() - startedAt < REJECTED_MS && visitedFrequencies.size <= 1 -> onRejected()
            "fail" in status -> {
                pendingReport = r.getString(R.string.scan_failed)
                end(Outcome.FAILED, r.getString(R.string.scan_failed) + "\n" + r.getString(R.string.scan_iptv_hint))
            }
            "cancel" in status -> end(Outcome.CANCELLED, r.getString(R.string.scan_cancelled))
            else -> publish(State(running = true, progress = progress, channels = shown,
                status = r.getString(R.string.scan_progress, progress, shown) + "\n" +
                    r.getString(R.string.setup_stage1_remaining, remainingTotal(progress, shown))))
        }
    }

    /**
     * dtvinput failed the request within moments without tuning (seen with CBS.U14 on Android 14): the
     * parameters were not accepted. Next request variant; when none is left – the system wizard.
     */
    private fun onRejected() {
        val variants = paramsVariants()
        if (method == Method.SERVICE && paramsVariant + 1 < variants.size) {
            paramsVariant++
            DebugLog.warn("Scan", "dtvinput rejected the request at once – trying params ${variants[paramsVariant]}")
            update(progress = 0)
            sendRequest(variants[paramsVariant])
            return
        }
        val wasService = method == Method.SERVICE
        val tuner = TunerSettings(app)
        io?.execute {
            // Our request was refused, so a working frontend belongs to another client (e.g. a home screen preview):
            // a busy tuner, not unaccepted parameters, may be the reason.
            val frontend = tuner.readFrontend()
            val busy = frontend?.state?.uppercase(java.util.Locale.ROOT) in BUSY_FRONTEND_STATES
            val caps = DeviceCapabilities.refresh(app)
            handler.post { if (running) onRejected(wasService, caps, busy, frontend) }
        }
    }

    private fun onRejected(wasService: Boolean, caps: DeviceCapabilities, busy: Boolean, frontend: TunerSettings.Frontend?) {
        val r = res()
        if (busy && wasService && !busyRetried) {
            busyRetried = true
            DebugLog.warn("Scan", "rejected while the frontend is busy (${frontend?.describe()}) – retrying in ${BUSY_RETRY_MS / 1000} s")
            update(status = r.getString(R.string.scan_tuner_busy_retry, BUSY_RETRY_MS / 1000))
            handler.postDelayed(busyRetry, BUSY_RETRY_MS)
            return
        }
        prefs().edit().remove(PREF_PARAMS_VARIANT).apply()
        // Remembered only when the tuner was free: a busy one may accept the in-app scan another time.
        if (wasService && !busy) ScanActivity.setRejected(app, true)
        if (busy) DebugLog.warn("Scan", "rejected again with a busy frontend – not remembered as a rejecting tuner")
        val message = r.getString(R.string.scan_rejected, r.getString(R.string.scan_method_setup)) +
            if (busy) "\n" + r.getString(R.string.scan_tuner_busy) else ""
        if (!caps.systemSetup) {
            pendingReport = message
            end(Outcome.REJECTED, message + "\n" + r.getString(R.string.scan_iptv_hint), serviceRejected = wasService)
            return
        }
        // The device's own wizard can still scan (it does on dtvinput CBS.U14) – the scan screen opens it;
        // a report is offered only if the wizard brings no channels either.
        end(Outcome.REJECTED, message + "\n" + r.getString(R.string.scan_opening_setup, WIZARD_DELAY_MS / 1000),
            openWizard = true, serviceRejected = wasService)
    }

    /** A further request of the running scan (another variant, a retry): timing restarts with it. */
    private fun sendRequest(params: String) {
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        runCatching { startInstallerService(params) }.onFailure {
            DebugLog.error("Scan", "failed to request the scan", it)
            end(Outcome.FAILED, res().getString(R.string.scan_send_failed, it.toString()))
        }
    }

    private fun onFrontend(fe: TunerSettings.Frontend?) {
        val text = fe?.describe() ?: return
        fe.frequencyMhz?.let { f ->
            visitedFrequencies += f
            val locked = fe.signal.equals("true", ignoreCase = true) || (fe.stats != null && fe.stats != "{}")
            if (locked) lockedFrequencies += f
        }
        if (text != lastFrontend) {
            lastFrontend = text
            DebugLog.log("Scan", "frontend $text")
        }
    }

    private fun end(
        outcome: Outcome, message: String, progress: Int? = null, channels: Int = 0,
        openWizard: Boolean = false, serviceRejected: Boolean = false,
    ) {
        DebugLog.log("Scan", "finished: $message (after ${(System.currentTimeMillis() - startedAt) / 1000} s)")
        val wasScanning = running
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(frontendPoll)
        handler.removeCallbacks(busyRetry)
        if (wasScanning) prefs().edit().remove(PREF_INTERRUPTED).apply()
        io?.shutdownNow()
        io = null
        method = null
        busyRetried = false
        if (wasScanning) {
            DebugLog.log(
                "Scan",
                "frontend: visited frequencies=${visitedFrequencies.size}, with signal=${lockedFrequencies.sorted().joinToString { "%.1f".format(java.util.Locale.US, it) }.ifEmpty { "none" }}"
            )
        }
        val noLock = wasScanning && lockedFrequencies.isEmpty() && visitedFrequencies.size > 3 && progress == null
        val status = if (noLock) message + "\n" + res().resources.getQuantityString(R.plurals.scan_no_lock, visitedFrequencies.size, visitedFrequencies.size) else message
        publish(State(false, progress ?: state.progress, channels, status, outcome, openWizard, serviceRejected))
    }

    private fun update(progress: Int = state.progress, status: String = state.status) {
        val s = state
        publish(State(s.running, progress, s.channels, status, s.outcome, s.openWizard, s.serviceRejected))
    }

    private fun publish(s: State) {
        state = s
        listeners.forEach { it(s) }
    }

    private fun prefs() = app.getSharedPreferences(ScanActivity.PREFS, Context.MODE_PRIVATE)

    /** Number of scan frequencies according to the country plan (in Poland 48 UHF and optionally 8 VHF). */
    private fun frequencyCount() = Region.plan().frequencyCount(vhf)

    /** Remaining time of the whole run during the scan (stage 1/3). */
    private fun remainingTotal(progress: Int, channelsSoFar: Int): String {
        val rates = SetupProgress.rates(app)
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
        val scanLeft = SetupProgress.remainingFromProgress(elapsed, progress)
            ?: (frequencyCount() * rates.perFrequency - elapsed).coerceAtLeast(0.0)
        val channels = maxOf(channelsSoFar, SetupProgress.lastChannelCount(app))
        val rest = SetupProgress.estimate(rates, 0, channels)
        return SetupProgress.format(scanLeft + rest.discoverySec + rest.identifySec)
    }

    /**
     * Scan requests in dtvinput JSON format, tried in order when dtvinput rejects one at once:
     *  - full DVB-T/T2 scan with frequencies (kHz) from the country plan ([Region]): in Poland optionally
     *    VHF III with 7 MHz channels and UHF 474–850 MHz with 8 MHz channels,
     *  - dtvinput's own frequency plan for the country,
     *  - the same as DVB-T only.
     */
    private fun paramsVariants(): List<String> {
        val type = if (t2) "dvb-t2" else "dvb-t"
        val slots = JSONArray()
        Region.plan().bands(vhf).forEach { slots.put(band(it)) }
        val country = Region.countryName(Region.country())
        return listOf(
            JSONObject().put("type", type).put("scan-mode", "full").put("country", "").put("custom-scan", false).put("slots", slots),
            JSONObject().put("type", type).put("scan-mode", "full").put("country", country),
            JSONObject().put("type", "dvb-t").put("scan-mode", "full").put("country", country),
        ).map { it.toString() }.distinct()
    }

    private fun band(b: Region.Band) = JSONObject()
        .put("frequency-min", b.minKhz)
        .put("frequency-max", b.maxKhz)
        .put("frequency-step", b.stepKhz)
        .put("bandwidth", b.bandwidthKhz)

    private const val WATCHDOG_SECONDS = 20
    /** Silence after which the scan is given up – dtvinput reports every frequency it tunes. */
    private const val SILENCE_FAIL_SECONDS = 150
    /** A failure this soon after the request means rejected parameters, not an empty band. */
    private const val REJECTED_MS = 3000L
    private const val PREF_PARAMS_VARIANT = "params_variant"
    private const val PREF_INTERRUPTED = "scan_in_progress"
    const val WIZARD_DELAY_MS = 3000L
    /** Wait before retrying a scan rejected while another client held the tuner. */
    private const val BUSY_RETRY_MS = 5000L
    /** Frontend states (dtvprovider streamers) of a tuner in use. */
    private val BUSY_FRONTEND_STATES = setOf("FILTERING", "STREAMING")
}
