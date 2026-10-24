package com.bonsaponsi.baptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * DVB-T/T2 channel scan. Available methods:
 *  - the dtvinput input's InstallerService (exported without permissions) – the default,
 *  - the ACTION_START_INSTALL broadcast – only with the INSTALL_TV_CHANNELS permission (priv-app),
 *  - the system input setup wizard (on set-top boxes with Google DTV it leads to DtvSetup Demo),
 * Progress arrives via the INSTALL_EVENT broadcast, sent by dtvinput without permissions.
 */
class ScanActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private enum class Method { SERVICE, BROADCAST }

    private lateinit var modeText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var methods: View
    private lateinit var cancelButton: Button
    private lateinit var vhfCheck: CheckBox
    private lateinit var t2Check: CheckBox
    private lateinit var lnaCheck: CheckBox
    private lateinit var options: View
    private lateinit var frontendText: TextView
    private lateinit var logView: TextView
    private lateinit var tunerSettings: TunerSettings

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var scanning: Method? = null
    private var lastEventAt = 0L
    private var startedAt = 0L

    // Reception statistics collected from the streamers table during the scan.
    private var lastFrontend: String? = null
    private val visitedFrequencies = HashSet<Double>()
    private val lockedFrequencies = HashSet<Double>()

    private val logListener: (String) -> Unit = { line -> handler.post { appendLog(line) } }

    /** Tuner reads off the main thread – one queue instead of a new thread every second. */
    private val io = BackgroundWorker("scan-io")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DebugLog.log("Scan", "<- ${intent.action?.substringAfterLast('.')} ${describeExtras(intent)}")
            lastEventAt = System.currentTimeMillis()
            when (intent.action) {
                Dtv.ACTION_INSTALL_EVENT -> onInstallEvent(intent)
                Dtv.ACTION_SETUP_READY -> if (scanning != null) statusText.setText(R.string.scan_tuner_ready)
            }
        }
    }

    /** Warning when dtvinput goes silent – e.g. it rejected the parameters ("Setup not found" is not broadcast). */
    private val watchdog = object : Runnable {
        override fun run() {
            if (scanning == null) return
            val silentFor = (System.currentTimeMillis() - lastEventAt) / 1000
            if (silentFor >= WATCHDOG_SECONDS) {
                DebugLog.warn("Scan", "no events from dtvinput for $silentFor s (started ${(System.currentTimeMillis() - startedAt) / 1000} s ago)")
                statusText.text = getString(R.string.scan_no_response, silentFor)
            }
            handler.postDelayed(this, WATCHDOG_SECONDS * 1000L)
        }
    }

    /** Reads the frontend state every second: frequency, state, signal. Changes go to the log. */
    private val frontendPoll = object : Runnable {
        override fun run() {
            io.execute {
                val fe = tunerSettings.readFrontend()
                handler.post { if (!isDestroyed) onFrontend(fe) }
            }
            if (scanning != null) handler.postDelayed(this, 1000)
        }
    }

    private val hasInstallPermission
        get() = checkSelfPermission(Dtv.PERMISSION_INSTALL_TV_CHANNELS) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        modeText = findViewById(R.id.scan_mode)
        progressBar = findViewById(R.id.scan_progress)
        statusText = findViewById(R.id.scan_status)
        methods = findViewById(R.id.scan_methods)
        cancelButton = findViewById(R.id.scan_cancel)
        vhfCheck = findViewById(R.id.scan_vhf)
        t2Check = findViewById(R.id.scan_t2)
        lnaCheck = findViewById(R.id.scan_lna)
        options = findViewById(R.id.scan_options)
        frontendText = findViewById(R.id.scan_frontend)
        logView = findViewById(R.id.scan_log)
        tunerSettings = TunerSettings(this)

        // Reading and writing the LNA setting are provider queries – off the main thread.
        io.execute {
            val lna = tunerSettings.getValue(Dtv.KEY_LNA)
            DebugLog.log("Scan", "tvsettings ${Dtv.KEY_LNA}=$lna")
            handler.post {
                if (isDestroyed) return@post
                lnaCheck.isChecked = lna.equals("true", ignoreCase = true)
                if (lna == null) lnaCheck.text = getString(R.string.scan_lna) + " (" + getString(R.string.scan_lna_unknown) + ")"
                lnaCheck.setOnCheckedChangeListener { _, checked ->
                    lnaCheck.setText(R.string.scan_lna)
                    io.execute { tunerSettings.setValue(Dtv.KEY_LNA, checked.toString()) }
                }
            }
        }
        handler.post(frontendPoll)

        val directButton = findViewById<Button>(R.id.scan_direct)
        directButton.isEnabled = hasInstallPermission
        // Without the system permission the button would always be disabled – so it is hidden.
        if (!hasInstallPermission) directButton.visibility = View.GONE
        modeText.text = getString(if (hasInstallPermission) R.string.scan_mode_direct else R.string.scan_mode_service)

        findViewById<Button>(R.id.scan_service).setOnClickListener { startScan(Method.SERVICE) }
        directButton.setOnClickListener { startScan(Method.BROADCAST) }
        findViewById<Button>(R.id.scan_setup).setOnClickListener { openSystemSetup() }
        findViewById<Button>(R.id.scan_diag).setOnClickListener { startActivity(Intent(this, DiagnosticsActivity::class.java)) }
        findViewById<Button>(R.id.scan_close).setOnClickListener { finish() }
        cancelButton.setOnClickListener { cancelScan() }

        DebugLog.snapshot().filter { "/Scan:" in it }.takeLast(LOG_LINES).forEach { appendLog(it) }
        DebugLog.addListener(logListener)
        registerEvents()
        DebugLog.log(
            "Scan",
            "scan screen: INSTALL_TV_CHANNELS=${hasInstallPermission}, input=${Dtv.findTunerInput(this)?.id}, " +
                "wizard=${Dtv.findTunerInput(this)?.createSetupIntent()?.component}"
        )
        vhfCheck.setOnCheckedChangeListener { _, _ -> if (scanning == null) showEstimate() }
        applyCapabilities()
    }

    /** Without dtvinput only the input setup wizard can scan; buttons and options unavailable on the device are hidden. */
    private fun applyCapabilities() {
        val caps = DeviceCapabilities.get(this)
        if (!caps.systemSetup) findViewById<View>(R.id.scan_setup).visibility = View.GONE
        if (!caps.tunerStats) {
            lnaCheck.visibility = View.GONE
            frontendText.visibility = View.GONE
        }
        if (Region.plan().vhf == null) vhfCheck.visibility = View.GONE
        if (caps.inAppScan) {
            showEstimate()
            findViewById<Button>(R.id.scan_service).requestFocus()
        } else {
            findViewById<View>(R.id.scan_service).visibility = View.GONE
            findViewById<View>(R.id.scan_direct).visibility = View.GONE
            options.visibility = View.GONE
            modeText.text = getString(if (caps.systemSetup) R.string.scan_mode_wizard else R.string.scan_mode_none)
            statusText.text = ""
            findViewById<Button>(if (caps.systemSetup) R.id.scan_setup else R.id.scan_close).requestFocus()
        }
    }

    /** Number of scan frequencies according to the country plan (in Poland 48 UHF and optionally 8 VHF). */
    private fun frequencyCount() = Region.plan().frequencyCount(vhfCheck.isChecked)

    /** Estimated time of the whole run (scan + discovery + identification) before starting. */
    private fun showEstimate() {
        val channels = SetupProgress.lastChannelCount(this)
        val e = SetupProgress.estimate(SetupProgress.rates(this), frequencyCount(), channels)
        statusText.text = getString(
            R.string.setup_estimate, SetupProgress.format(e.totalSec), SetupProgress.format(e.scanSec),
            SetupProgress.format(e.discoverySec), SetupProgress.format(e.identifySec), channels,
        )
    }

    /** Remaining time of the whole run during the scan (stage 1/3). */
    private fun remainingTotal(progress: Int, channelsSoFar: Int): String {
        val rates = SetupProgress.rates(this)
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
        val scanLeft = SetupProgress.remainingFromProgress(elapsed, progress)
            ?: (frequencyCount() * rates.perFrequency - elapsed).coerceAtLeast(0.0)
        val channels = maxOf(channelsSoFar, SetupProgress.lastChannelCount(this))
        val rest = SetupProgress.estimate(rates, 0, channels)
        return SetupProgress.format(scanLeft + rest.discoverySec + rest.identifySec)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        DebugLog.removeListener(logListener)
        if (scanning != null) DebugLog.warn("Scan", "screen closed during the scan – the scan continues in the background")
        if (receiverRegistered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        DebugLog.log("Scan", "returned from setup wizard: requestCode=$requestCode resultCode=$resultCode data=$data")
        statusText.text = getString(R.string.scan_setup_returned, resultCode)
        DeviceCapabilities.refresh(this)
    }

    private fun registerEvents() {
        val filter = IntentFilter().apply {
            addAction(Dtv.ACTION_INSTALL_EVENT)
            addAction(Dtv.ACTION_UNINSTALL_EVENT)
            addAction(Dtv.ACTION_SETUP_READY)
            addAction(Dtv.ACTION_START_WATCHING_UPDATE)
            addAction(Dtv.ACTION_END_WATCHING_UPDATE)
        }
        val permission = Dtv.eventSenderPermission(this)
        DebugLog.log("Scan", "scan events only from a sender with permission: ${permission ?: "no restriction"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, permission, null, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter, permission, null)
        }
        receiverRegistered = true
    }

    private fun startScan(method: Method) {
        val params = scanParams(vhfCheck.isChecked, t2Check.isChecked)
        DebugLog.log("Scan", "start with method $method, params=$params")
        try {
            when (method) {
                Method.SERVICE -> startInstallerService(params)
                Method.BROADCAST -> {
                    val intent = Intent(Dtv.ACTION_START_INSTALL).setPackage(Dtv.DTV_INPUT_PACKAGE).putExtra(Dtv.EXTRA_PARAMS, params)
                    sendBroadcast(intent, Dtv.PERMISSION_INSTALL_TV_CHANNELS)
                }
            }
        } catch (e: Exception) {
            DebugLog.error("Scan", "failed to request the scan", e)
            statusText.text = getString(R.string.scan_send_failed, e.toString())
            ReportSender.offer(this, ReportSender.Reason.SCAN_FAILED, getString(R.string.scan_send_failed, e.toString()), automatic = true)
            return
        }
        scanning = method
        SetupProgress.markScanned(this)
        visitedFrequencies.clear()
        lockedFrequencies.clear()
        startedAt = System.currentTimeMillis()
        lastEventAt = startedAt
        progressBar.progress = 0
        statusText.setText(R.string.scan_waiting)
        methods.visibility = View.GONE
        options.visibility = View.GONE
        cancelButton.visibility = View.VISIBLE
        cancelButton.requestFocus()
        handler.removeCallbacks(frontendPoll)
        handler.post(frontendPoll)
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_SECONDS * 1000L)
    }

    /**
     * InstallerService stores the extras and starts the scan once the tuner is ready.
     * Plain startService – with startForegroundService a service that is already running would not call
     * startForeground() and the system would kill it.
     */
    private fun startInstallerService(params: String) {
        val intent = Intent().setComponent(Dtv.INSTALLER_SERVICE).putExtra(Dtv.EXTRA_PARAMS, params)
        val result = try {
            startService(intent)
        } catch (e: IllegalStateException) {
            DebugLog.warn("Scan", "startService rejected (${e.message}), trying startForegroundService")
            startForegroundService(intent)
        }
        DebugLog.log("Scan", "-> startService($intent) = $result")
        if (result == null) error("service ${Dtv.INSTALLER_SERVICE.flattenToShortString()} does not exist")
    }

    private fun onInstallEvent(intent: Intent) {
        val progress = intent.getIntExtra(Dtv.EXTRA_PROGRESS, 0)
        val count = intent.getIntExtra(Dtv.EXTRA_CHANNEL_COUNT, -1)
        val status = intent.getStringExtra(Dtv.EXTRA_STATUS).orEmpty().lowercase()
        progressBar.progress = progress
        val shown = count.coerceAtLeast(0)
        when {
            "complete" in status -> {
                // A new scan changes channel IDs – the discovered list must be refreshed.
                ChannelStore(this).clearDiscovered()
                finishScan(getString(R.string.scan_complete, shown), 100)
                if (shown == 0) ReportSender.offer(this, ReportSender.Reason.SCAN_FAILED, getString(R.string.scan_complete, 0), automatic = true)
                if (shown > 0) continueInPlayer(shown)
            }
            "fail" in status -> {
                finishScan(getString(R.string.scan_failed), null)
                ReportSender.offer(this, ReportSender.Reason.SCAN_FAILED, getString(R.string.scan_failed), automatic = true)
            }
            "cancel" in status -> finishScan(getString(R.string.scan_cancelled), null)
            else -> statusText.text = getString(R.string.scan_progress, progress, shown, status) + "\n" +
                getString(R.string.setup_stage1_remaining, remainingTotal(progress, shown))
        }
    }

    /**
     * Single run: after the scan the player discovers and identifies channels by itself (stages 2 and 3),
     * so we close the scan screen and pass the channel count on for the time estimate.
     */
    private fun continueInPlayer(channels: Int) {
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
        SetupProgress.learn(this, "perFrequency", elapsed, frequencyCount())
        SetupProgress.startPipeline(this, channels, startedAt)
        val rest = SetupProgress.estimate(SetupProgress.rates(this), 0, channels)
        statusText.text = getString(R.string.setup_stage1_done, channels, SetupProgress.format(rest.discoverySec + rest.identifySec))
        DebugLog.log("Scan", "single run: moving on to discovering and identifying $channels channels")
        handler.postDelayed({ if (!isFinishing) finish() }, CONTINUE_DELAY_MS)
    }

    private fun cancelScan() {
        DebugLog.log("Scan", "cancelling scan (method $scanning)")
        // The broadcast only gets through with INSTALL_TV_CHANNELS; without it we stop the installer service.
        sendBroadcast(Intent(Dtv.ACTION_CANCEL_INSTALL).setPackage(Dtv.DTV_INPUT_PACKAGE), Dtv.PERMISSION_INSTALL_TV_CHANNELS)
        if (!hasInstallPermission) {
            val stopped = runCatching { stopService(Intent().setComponent(Dtv.INSTALLER_SERVICE)) }
            DebugLog.log("Scan", "-> stopService(InstallerService) = $stopped")
        }
        finishScan(getString(R.string.scan_cancelled), null)
    }

    private fun finishScan(message: String, progress: Int?) {
        DebugLog.log("Scan", "finished: $message (after ${(System.currentTimeMillis() - startedAt) / 1000} s)")
        val wasScanning = scanning != null
        scanning = null
        handler.removeCallbacks(watchdog)
        progress?.let { progressBar.progress = it }
        if (wasScanning) {
            DebugLog.log(
                "Scan",
                "frontend: visited frequencies=${visitedFrequencies.size}, with signal=${lockedFrequencies.sorted().joinToString { "%.1f".format(java.util.Locale.US, it) }.ifEmpty { "none" }}"
            )
        }
        statusText.text = if (wasScanning && lockedFrequencies.isEmpty() && visitedFrequencies.size > 3 && progress == null) {
            message + "\n" + getString(R.string.scan_no_lock, visitedFrequencies.size)
        } else {
            message
        }
        cancelButton.visibility = View.GONE
        methods.visibility = View.VISIBLE
        options.visibility = View.VISIBLE
        findViewById<Button>(R.id.scan_close).requestFocus()
    }

    private fun onFrontend(fe: TunerSettings.Frontend?) {
        val text = fe?.describe() ?: getString(R.string.value_unavailable)
        frontendText.text = getString(R.string.scan_frontend, text)
        if (scanning == null) return
        fe?.frequencyMhz?.let { f ->
            visitedFrequencies += f
            val locked = fe.signal.equals("true", ignoreCase = true) || (fe.stats != null && fe.stats != "{}")
            if (locked) lockedFrequencies += f
        }
        if (text != lastFrontend) {
            lastFrontend = text
            DebugLog.log("Scan", "frontend $text")
        }
    }

    @Suppress("DEPRECATION")
    private fun openSystemSetup() {
        val input = Dtv.findTunerInput(this)
        val setupIntent = input?.createSetupIntent()
        DebugLog.log("Scan", "system setup wizard: input=${input?.id}, intent=$setupIntent")
        if (setupIntent == null) {
            statusText.setText(R.string.scan_no_setup)
            return
        }
        try {
            startActivityForResult(setupIntent, REQUEST_SETUP)
        } catch (e: ActivityNotFoundException) {
            DebugLog.error("Scan", "setup wizard activity not found", e)
            statusText.setText(R.string.scan_no_setup)
        }
    }

    private fun appendLog(line: String) {
        if ("/Scan:" !in line && "/Crash:" !in line) return
        val lines = (logView.text.toString().lines() + line.substringAfter(' ')).filter { it.isNotBlank() }.takeLast(LOG_LINES)
        logView.text = lines.joinToString("\n")
    }

    private fun describeExtras(intent: Intent): String =
        intent.extras?.keySet()?.joinToString(", ", "{", "}") { "$it=${intent.extras?.get(it)}" } ?: "{}"

    /**
     * Full DVB-T/T2 scan in dtvinput JSON format (frequencies in kHz) according to the country plan
     * ([Region]): in Poland optionally VHF III with 7 MHz channels and UHF 474–850 MHz with 8 MHz channels.
     */
    private fun scanParams(includeVhf: Boolean, dvbT2: Boolean): String {
        val slots = JSONArray()
        Region.plan().bands(includeVhf).forEach { slots.put(band(it)) }
        return JSONObject()
            .put("type", if (dvbT2) "dvb-t2" else "dvb-t")
            .put("scan-mode", "full")
            .put("country", "")
            .put("custom-scan", false)
            .put("slots", slots)
            .toString()
    }

    private fun band(b: Region.Band) = JSONObject()
        .put("frequency-min", b.minKhz)
        .put("frequency-max", b.maxKhz)
        .put("frequency-step", b.stepKhz)
        .put("bandwidth", b.bandwidthKhz)

    companion object {
        private const val REQUEST_SETUP = 1
        private const val WATCHDOG_SECONDS = 20
        private const val LOG_LINES = 14
        private const val CONTINUE_DELAY_MS = 3000L
    }
}
