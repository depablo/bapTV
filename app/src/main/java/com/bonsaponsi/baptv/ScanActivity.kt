package com.bonsaponsi.baptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView

/**
 * DVB-T/T2 channel scan screen. Available methods:
 *  - the in-app scan ([ScanSession]: dtvinput's InstallerService or, with INSTALL_TV_CHANNELS, the broadcast) –
 *    it runs in the process, so the screen can be left while it scans,
 *  - the system input setup wizard (on set-top boxes with Google DTV it leads to DtvSetup Demo).
 */
class ScanActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private lateinit var modeText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var methods: View
    private lateinit var runningButtons: View
    private lateinit var vhfCheck: CheckBox
    private lateinit var t2Check: CheckBox
    private lateinit var lnaCheck: CheckBox
    private lateinit var options: View
    private lateinit var advanced: View
    private lateinit var advancedToggle: Button
    private lateinit var tunerSettings: TunerSettings

    private val handler = Handler(Looper.getMainLooper())

    /** Tuner settings off the main thread – one queue instead of a new thread for each read. */
    private val io = BackgroundWorker("scan-io")

    private val tips by lazy { Tips.Rotator(findViewById(R.id.scan_tip)) }

    private val sessionListener: (ScanSession.State) -> Unit = { handler.post { if (!isDestroyed) render(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        modeText = findViewById(R.id.scan_mode)
        progressBar = findViewById(R.id.scan_progress)
        statusText = findViewById(R.id.scan_status)
        methods = findViewById(R.id.scan_methods)
        runningButtons = findViewById(R.id.scan_running)
        vhfCheck = findViewById(R.id.scan_vhf)
        t2Check = findViewById(R.id.scan_t2)
        lnaCheck = findViewById(R.id.scan_lna)
        options = findViewById(R.id.scan_options)
        advanced = findViewById(R.id.scan_advanced)
        advancedToggle = findViewById(R.id.scan_advanced_toggle)
        advancedToggle.setOnClickListener { showAdvanced(advanced.visibility != View.VISIBLE) }
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

        val hasInstallPermission = ScanSession.hasInstallPermission(this)
        val directButton = findViewById<Button>(R.id.scan_direct)
        directButton.isEnabled = hasInstallPermission
        // Without the system permission the button would always be disabled – so it is hidden.
        if (!hasInstallPermission) directButton.visibility = View.GONE

        findViewById<Button>(R.id.scan_service).setOnClickListener { startScan(ScanSession.Method.SERVICE) }
        directButton.setOnClickListener { startScan(ScanSession.Method.BROADCAST) }
        findViewById<Button>(R.id.scan_setup).setOnClickListener { openSystemSetup() }
        findViewById<Button>(R.id.scan_diag).setOnClickListener {
            handler.removeCallbacks(openWizard)
            wizardPending = false
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.scan_close).setOnClickListener { finish() }
        // The scan goes on without the screen – back to the player (menu, internet channels) with a progress banner.
        findViewById<Button>(R.id.scan_background).setOnClickListener { finish() }
        findViewById<Button>(R.id.scan_cancel).setOnClickListener { ScanSession.cancel(this) }

        DebugLog.log(
            "Scan",
            "scan screen: INSTALL_TV_CHANNELS=$hasInstallPermission, input=${Dtv.findTunerInput(this)?.id}, " +
                "wizard=${Dtv.findTunerInput(this)?.createSetupIntent()?.component}, scan running=${ScanSession.running}"
        )
        vhfCheck.setOnCheckedChangeListener { _, _ -> if (!ScanSession.running) showEstimate() }
        applyCapabilities()
        ScanSession.addListener(sessionListener)
        // A scan running in the background, or one that ended while the screen was closed (e.g. rejected – the wizard is next).
        ScanSession.state.let { if (it.running || it.outcome != null) render(it) }
    }

    /** Without dtvinput only the input setup wizard can scan; buttons and options unavailable on the device are hidden. */
    private fun applyCapabilities() {
        val caps = DeviceCapabilities.get(this)
        if (!caps.systemSetup) findViewById<View>(R.id.scan_setup).visibility = View.GONE
        if (!caps.tunerStats) lnaCheck.visibility = View.GONE
        if (Region.plan().vhf == null) vhfCheck.visibility = View.GONE
        if (caps.inAppScan) {
            showEstimate()
            findViewById<Button>(R.id.scan_service).requestFocus()
        } else {
            findViewById<View>(R.id.scan_service).visibility = View.GONE
            findViewById<View>(R.id.scan_direct).visibility = View.GONE
            options.visibility = View.GONE
            // The system wizard is the only way here – shown directly instead of under "Advanced settings".
            showAdvanced(true)
            advancedToggle.visibility = View.GONE
            modeText.text = getString(
                when {
                    !caps.systemSetup -> R.string.scan_mode_none
                    caps.scanRejected -> R.string.scan_mode_wizard_rejected
                    else -> R.string.scan_mode_wizard
                }
            )
            statusText.text = if (caps.systemSetup) "" else getString(R.string.scan_iptv_hint)
            findViewById<Button>(if (caps.systemSetup) R.id.scan_setup else R.id.scan_close).requestFocus()
        }
    }

    /** One sentence with the estimated time of the whole run (scan + discovery + identification). */
    private fun showEstimate() {
        val channels = SetupProgress.lastChannelCount(this)
        val e = SetupProgress.estimate(SetupProgress.rates(this), Region.plan().frequencyCount(vhfCheck.isChecked), channels)
        DebugLog.log("Scan", "estimate: total ${SetupProgress.format(e.totalSec)} (scan ${SetupProgress.format(e.scanSec)}, " +
            "discovery ${SetupProgress.format(e.discoverySec)}, identification ${SetupProgress.format(e.identifySec)}, ~$channels channels)")
        modeText.text = getString(R.string.scan_intro, SetupProgress.format(e.totalSec))
    }

    private fun showAdvanced(show: Boolean) {
        advanced.visibility = if (show) View.VISIBLE else View.GONE
        advancedToggle.setText(if (show) R.string.scan_advanced_hide else R.string.scan_advanced_show)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        tips.stop()
        ScanSession.removeListener(sessionListener)
        if (ScanSession.running) DebugLog.log("Scan", "scan screen closed – the scan continues in the background")
        super.onDestroy()
    }

    private fun startScan(method: ScanSession.Method) {
        handler.removeCallbacks(openWizard)
        wizardPending = false
        ScanSession.start(this, method, vhfCheck.isChecked, t2Check.isChecked)
    }

    /** Shows the scan from [ScanSession]: progress while it runs, then the result and what comes next. */
    private fun render(s: ScanSession.State) {
        progressBar.progress = s.progress
        statusText.text = if (s.running) s.status + "\n" + getString(R.string.scan_background_hint) else s.status
        if (s.running) {
            tips.start()
            progressBar.visibility = View.VISIBLE
            if (runningButtons.visibility != View.VISIBLE) {
                methods.visibility = View.GONE
                advanced.visibility = View.GONE
                runningButtons.visibility = View.VISIBLE
                findViewById<Button>(R.id.scan_background).requestFocus()
            }
            return
        }
        tips.stop()
        val outcome = s.outcome ?: return
        runningButtons.visibility = View.GONE
        methods.visibility = View.VISIBLE
        showAdvanced(false)
        findViewById<Button>(R.id.scan_close).requestFocus()
        ScanSession.takeReport()?.let { ReportSender.offer(this, ReportSender.Reason.SCAN_FAILED, it, automatic = true) }
        when {
            outcome == ScanSession.Outcome.COMPLETE && s.channels > 0 ->
                handler.postDelayed({ if (!isFinishing) finish() }, CONTINUE_DELAY_MS)
            outcome == ScanSession.Outcome.REJECTED -> {
                if (s.serviceRejected) {
                    findViewById<View>(R.id.scan_service).visibility = View.GONE
                    options.visibility = View.GONE
                }
                if (s.openWizard) {
                    // The device's own wizard can still scan (it does on dtvinput CBS.U14) – opened for the user;
                    // a report is offered only if the wizard brings no channels either (onActivityResult).
                    showAdvanced(true)
                    findViewById<Button>(R.id.scan_setup).requestFocus()
                    handler.postDelayed(openWizard, ScanSession.WIZARD_DELAY_MS)
                }
            }
        }
        ScanSession.acknowledge()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Queries to dtvinput's and the TV provider (slow on a busy box) stay off the main thread.
        io.execute {
            val caps = DeviceCapabilities.refresh(this)
            val channels = caps.inputId?.let { DeviceCapabilities.countChannels(this, it) } ?: 0
            handler.post { if (!isDestroyed) onSetupResult(requestCode, resultCode, data, caps, channels) }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_LISTINGS) return
        DebugLog.log("Scan", "permission result: ${permissions.zip(grantResults.toList())}")
        // Also when refused: the wizard can still scan, only its channels stay invisible to the app.
        openSystemSetup()
    }

    private fun onSetupResult(requestCode: Int, resultCode: Int, data: Intent?, caps: DeviceCapabilities, channels: Int) {
        val listings = Dtv.listingsPermission(this)
        val listingsGranted = checkSelfPermission(listings) == PackageManager.PERMISSION_GRANTED
        DebugLog.log("Scan", "returned from setup wizard: requestCode=$requestCode resultCode=$resultCode data=$data, input channels=$channels, $listings=$listingsGranted")
        if (channels > 0) {
            statusText.text = getString(R.string.scan_setup_channels, channels)
            findViewById<Button>(R.id.scan_close).requestFocus()
            handler.postDelayed({ if (!isFinishing) finish() }, CONTINUE_DELAY_MS)
            return
        }
        val message = getString(R.string.scan_setup_no_channels, resultCode) +
            if (listingsGranted) "" else "\n" + getString(R.string.scan_listings_denied)
        // dtvinput may hide its channels from TvContract (searchable=0, CBS.R11) – then the player finds them by probing.
        // On a dtvinput that rejects in-app scans (CBS.U14) channels from the wizard were visible, so 0 really means none.
        if (caps.channelDiscovery && !caps.scanRejected) {
            DebugLog.log("Scan", "no channels visible after the wizard – the player will discover them by probing")
            statusText.setText(R.string.scan_setup_discover)
            handler.postDelayed({ if (!isFinishing) finish() }, CONTINUE_DELAY_MS)
            return
        }
        statusText.text = message + "\n" + getString(R.string.scan_iptv_hint)
        // Only now a report is worth it: neither the in-app scan nor the device's own wizard gave channels.
        ReportSender.offer(this, ReportSender.Reason.SCAN_FAILED, message, automatic = true)
    }

    @Suppress("DEPRECATION")
    private fun openSystemSetup() {
        handler.removeCallbacks(openWizard)
        wizardPending = false
        // Without READ_TV_LISTINGS (dangerous) TvProvider shows only the app's own channels – the wizard's channels
        // would be counted as 0 afterwards (Android 9 boxes without dtvinput). Asked for once, before the wizard;
        // dtvinput's own channels are visible with READ_EPG_DATA.
        val input = Dtv.findTunerInput(this)
        val listings = Dtv.listingsPermission(this)
        if (input != null && input.serviceInfo.packageName != Dtv.DTV_INPUT_PACKAGE && listings != Dtv.PERMISSION_READ_EPG &&
            !listingsAsked && checkSelfPermission(listings) != PackageManager.PERMISSION_GRANTED) {
            listingsAsked = true
            DebugLog.log("Scan", "requesting $listings before the setup wizard")
            requestPermissions(arrayOf(listings), REQUEST_LISTINGS)
            return
        }
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

    /**
     * Under HOME the screen cannot start the wizard (Android 10+ blocks activity starts from the background), and
     * the rejected state is already acknowledged – so the wizard waits for the screen to come back.
     */
    private val openWizard = Runnable {
        if (isFinishing) return@Runnable
        if (isResumedNow) openSystemSetup() else wizardPending = true
    }
    private var isResumedNow = false
    private var wizardPending = false

    /** READ_TV_LISTINGS asked for on this screen – not again when the wizard is opened a second time. */
    private var listingsAsked = false

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        if (wizardPending) {
            wizardPending = false
            handler.postDelayed(openWizard, ScanSession.WIZARD_DELAY_MS)
        }
    }

    override fun onPause() {
        isResumedNow = false
        super.onPause()
    }

    companion object {
        private const val REQUEST_SETUP = 1
        private const val REQUEST_LISTINGS = 2
        private const val CONTINUE_DELAY_MS = 3000L
        const val PREFS = "scan"

        /**
         * dtvinput rejected every in-app scan request (seen with CBS.U14 on Android 14). Kept per dtvinput
         * version – a firmware update gives the in-app scan another chance.
         */
        fun rejected(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(rejectedKey(context), false)

        fun setRejected(context: Context, rejected: Boolean) {
            if (rejected == rejected(context)) return
            context.applicationContext.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(rejectedKey(context), rejected).apply()
            DebugLog.log("Scan", if (rejected) "this dtvinput rejects in-app scans – the system wizard from now on" else "in-app scan works again")
            // Queries the TV provider and the packages – off the main thread, which calls this from the scan.
            val app = context.applicationContext
            capabilitiesWorker.execute { DeviceCapabilities.refresh(app) }
        }

        private val capabilitiesWorker by lazy { BackgroundWorker("capabilities") }

        private fun rejectedKey(context: Context): String {
            val version = runCatching { context.packageManager.getPackageInfo(Dtv.DTV_INPUT_PACKAGE, 0).versionName }.getOrNull()
            return "rejected_$version"
        }
    }
}
