package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private lateinit var tvView: TvView
    private lateinit var status: TextView
    private lateinit var setupBanner: View
    private lateinit var recBadge: TextView
    private lateinit var numberEntry: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerNumber: TextView
    private lateinit var bannerName: TextView
    private lateinit var bannerClock: TextView
    private lateinit var bannerNow: TextView
    private lateinit var bannerNext: TextView
    private lateinit var bannerDetails: TextView
    private lateinit var channelPanel: View
    private lateinit var menu: MenuPanel
    private lateinit var channelList: ListView
    private lateinit var debugOverlay: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val io = BackgroundWorker("player-io")
    /** Channel AIT learning (records a few seconds) – a separate thread so it does not block channel loading. */
    private val learnExecutor = BackgroundWorker("player-learn")
    /** Publishing channels to TvContract is running ([ChannelPublisher]) – the tuner belongs to dtvinput. */
    private var publisher: ChannelPublisher? = null
    private lateinit var hbb: HbbTvBrowser
    private lateinit var hbbStore: HbbTvStore
    /** Channel change requested by the HbbTV app – the app keeps running (we do not reload it). */
    private var hbbInternalTune = false
    /** The HbbTV app stopped the broadcast video (e.g. while playing video on demand). */
    private var broadcastStopped = false
    /** Channel for which the "HbbTV unavailable" notice was shown – once per tune, not on every retry. */
    private var hbbNoticeShownFor = -1L
    /** Full channel identification (from the menu) is running – the player switches channels by itself. */
    @Volatile private var identifying = false
    // dtvinput timeshift state (experiments via /timeshift).
    /**
     * Player is on screen (between onStart and onStop). Background work results (identification, the /signal
     * page sweep) do not retune the tuner while the activity is stopped – the tuner then belongs to other apps.
     */
    @Volatile private var started = false
    /** Scheduled recording warning – dismissed in onStop (otherwise WindowLeaked). */
    private var upcomingDialog: android.app.Dialog? = null
    private var listingsAsked = false
    /**
     * A system permission dialog is open. A second request at that time comes back empty at once
     * (first launch: storage and channel list) – the empty-list handling waits for the dialog instead.
     */
    private var permissionDialogOpen = false
    private var reloadAfterPermissions = false

    /** Recording being played (instead of live TV), or null. */
    private var playingRecording: Recorder.Entry? = null
    /** Recording chosen in the recordings list – played after channels load in onStart. */
    private var pendingRecordingId: Long? = null
    private var recordingPaused = false
    /** Time left until the end of the recording (ms) – dtvinput does not report end of playback, so we count it ourselves. */
    private var recordingRemainingMs = 0L
    private var recordingResumedAt = 0L
    /** The end-of-recording countdown is running (video is up and not paused). */
    private var recordingClockRunning = false
    private val recordingEnded = Runnable {
        val entry = playingRecording ?: return@Runnable
        DebugLog.log("Rec", "end of playback ${entry.title}")
        showStatusFor(getString(R.string.recording_ended, entry.title), RECORDING_END_LIVE_MS)
        handler.postDelayed({ if (playingRecording?.id == entry.id) recordingAction("live", null) }, RECORDING_END_LIVE_MS)
    }

    private fun scheduleRecordingEnd() {
        handler.removeCallbacks(recordingEnded)
        recordingClockRunning = true
        recordingResumedAt = SystemClock.elapsedRealtime()
        handler.postDelayed(recordingEnded, recordingRemainingMs.coerceAtLeast(0))
    }

    private fun pauseRecordingEnd() {
        handler.removeCallbacks(recordingEnded)
        // Paused before the first frame – the countdown has not started yet, nothing to subtract.
        if (!recordingClockRunning) return
        recordingClockRunning = false
        recordingRemainingMs -= SystemClock.elapsedRealtime() - recordingResumedAt
    }
    @Volatile private var tsStatus = -1
    private lateinit var timeshift: TimeShiftController

    /** Tuner released for frequency probing from the /signal page – no background identification. */
    @Volatile private var probingFrequency = false
    /** After channels are discovered (e.g. after a scan) we identify them right away – names and settings come back. */
    private var identifyAfterLoad = false
    /** Channel chosen in the TV guide while the list is still loading. */
    private var pendingChannelId: Long? = null
    @Volatile private var identifyCancelled = false
    private lateinit var bannerProgress: android.widget.ProgressBar
    private lateinit var bannerDesc: TextView
    private lateinit var infoExtra: View
    private lateinit var infoMeta: TextView
    private lateinit var infoNextDesc: TextView
    private lateinit var infoOptions: View
    private lateinit var infoOptAudio: android.widget.Button
    private lateinit var infoOptSubs: android.widget.Button
    private lateinit var infoOptRecord: android.widget.Button
    private lateinit var infoOptPause: android.widget.Button
    private lateinit var infoDiag: TextView
    private lateinit var infoHint: TextView
    /**
     * Info panel level (down arrow – more, up arrow – less): 0 hidden, 1 now/next,
     * 2 full descriptions, 3 audio and subtitles, 4 technical details.
     */
    private var infoLevel = 0
    private lateinit var prefs: SharedPreferences
    private lateinit var repository: ChannelRepository
    private lateinit var signalReader: SignalReader
    private lateinit var store: ChannelStore
    private lateinit var audio: PlayerAudio
    private val adapter = ChannelAdapter()
    private var prober: Prober? = null

    private var input: TvInputInfo? = null
    private var channels: List<Channel> = emptyList()
    private var currentIndex = -1
    private var previousChannelId = -1L
    private var videoSize: Pair<Int, Int>? = null
    private var showSignal = false
    private val digits = StringBuilder()

    // State for the debug overlay.
    private var tunedAt = 0L
    private var videoState = "-"
    private var lastCallback = "-"
    @Volatile private var lastSignal: String? = null

    private val hideBanner = Runnable {
        banner.visibility = View.GONE
        showSignal = false
        infoLevel = 0
        // Focus moves from the panel buttons back to the player (OK opens the channel list).
        banner.findFocus()?.clearFocus()
    }
    private val commitDigits = Runnable { tuneByNumber(digits.toString()); digits.clear(); numberEntry.visibility = View.GONE }
    /** Signal read in progress – with a slow provider, new reads do not pile up in the queue every second. */
    private val signalReadPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val refreshSignal = object : Runnable {
        override fun run() {
            if (banner.visibility != View.VISIBLE || !showSignal) return
            if (signalReadPending.compareAndSet(false, true)) io.execute {
                val stats = try { signalReader.read() } finally { signalReadPending.set(false) }
                handler.post { if (showSignal) { updateDetails(stats); updateDiag(stats) } }
            }
            handler.postDelayed(this, SIGNAL_REFRESH_MS)
        }
    }
    private val refreshOverlay = object : Runnable {
        override fun run() {
            if (debugOverlay.visibility != View.VISIBLE) return
            if (signalReadPending.compareAndSet(false, true)) io.execute {
                lastSignal = try { signalReader.read()?.format() } finally { signalReadPending.set(false) }
                handler.post { renderOverlay() }
            }
            handler.postDelayed(this, SIGNAL_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        // Emulator / tests: `am start -n …/.MainActivity --ez enable_server true` enables the server without a remote (debug only).
        if (BuildConfig.DEBUG && intent.getBooleanExtra(DiagnosticsActivity.EXTRA_ENABLE_SERVER, false) && !DebugServerService.isEnabled(this)) {
            DebugServerService.setEnabled(this, true)
            ServerAccess.token(this)
        }
        setContentView(R.layout.activity_main)
        // TV player: no screensaver or screen sleep while watching.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tvView = findViewById(R.id.tv_view)
        status = findViewById(R.id.status)
        setupBanner = findViewById(R.id.setup_banner)
        recBadge = findViewById(R.id.rec_badge)
        numberEntry = findViewById(R.id.number_entry)
        banner = findViewById(R.id.info_banner)
        bannerNumber = findViewById(R.id.info_number)
        bannerName = findViewById(R.id.info_name)
        bannerClock = findViewById(R.id.info_clock)
        bannerNow = findViewById(R.id.info_now)
        bannerNext = findViewById(R.id.info_next)
        bannerDetails = findViewById(R.id.info_details)
        bannerProgress = findViewById(R.id.info_progress)
        bannerDesc = findViewById(R.id.info_desc)
        infoExtra = findViewById(R.id.info_extra)
        infoMeta = findViewById(R.id.info_meta)
        infoNextDesc = findViewById(R.id.info_next_desc)
        infoOptions = findViewById(R.id.info_options)
        infoOptAudio = findViewById(R.id.info_opt_audio)
        infoOptSubs = findViewById(R.id.info_opt_subs)
        infoOptRecord = findViewById(R.id.info_opt_record)
        infoOptPause = findViewById(R.id.info_opt_pause)
        infoOptPause.setOnClickListener {
            if (timeshift.active) timeshift.goLive() else startPause()
            handler.removeCallbacks(hideBanner)
            hideBanner.run()
        }
        infoDiag = findViewById(R.id.info_diag)
        infoHint = findViewById(R.id.info_hint)
        infoOptAudio.setOnClickListener { showTrackDialog(TvTrackInfo.TYPE_AUDIO) }
        infoOptSubs.setOnClickListener { showTrackDialog(TvTrackInfo.TYPE_SUBTITLE) }
        infoOptRecord.setOnClickListener {
            if (Recorder.active != null) Recorder.stop()
            else startRecordingCurrent()?.let { showStatusBriefly(getString(R.string.recording_error, it)) }
            handler.postDelayed({ updateOptionButtons() }, 500)
        }
        channelPanel = findViewById(R.id.channel_panel)
        menu = MenuPanel(this, ::menuSections) { shown -> if (::hbb.isInitialized) hbb.covered = shown }
        channelList = findViewById(R.id.channel_list)
        debugOverlay = findViewById(R.id.debug_overlay)

        prefs = getSharedPreferences("player", MODE_PRIVATE)
        repository = ChannelRepository(this)
        signalReader = SignalReader(this)
        store = ChannelStore(this)
        audio = PlayerAudio(this, tvView)

        channelList.adapter = adapter
        channelList.setOnItemLongClickListener { _, _, position, _ ->
            channels.getOrNull(position)?.let { renameChannel(it) }
            true
        }
        channelList.setOnItemClickListener { _, _, position, _ ->
            hideChannelList()
            tuneTo(position)
        }
        tvView.setCallback(PlayerCallback())
        if (prefs.getBoolean(PREF_DEBUG_OVERLAY, false)) debugOverlay.visibility = View.VISIBLE
        hbbStore = HbbTvStore(this)
        hbb = HbbTvBrowser(this, tvView.parent as android.view.ViewGroup, HbbHost())
        PlayerRemote.hbb = hbb
        SignalMonitor.player = object : SignalMonitor.Player {
            // For each multiplex pick a free-to-air TV channel (fastest to show video), otherwise the first one.
            override fun multiplexes() = channels.filter { it.frequencyMhz != null }
                .groupBy { it.frequencyMhz!! }.toSortedMap()
                .map { (f, list) ->
                    val ch = list.firstOrNull { !it.scrambled && !it.isRadio } ?: list.firstOrNull { !it.scrambled } ?: list.first()
                    SignalMonitor.MuxChannel(f, ch.id, ch.name)
                }
            override fun currentChannelId() = channels.getOrNull(currentIndex)?.id
            override fun currentFrequencyMhz() = channels.getOrNull(currentIndex)?.frequencyMhz
            override fun tune(channelId: Long): Boolean {
                val index = channels.indexOfFirst { it.id == channelId }
                if (index >= 0) tuneTo(index)
                return index >= 0
            }
            override fun releaseTuner() {
                probingFrequency = true
                hbb.start(null)
                tvView.reset()
                showStatus(getString(R.string.probe_status))
            }
            override fun restoreTuner() {
                probingFrequency = false
                showStatus(null)
                if (currentIndex in channels.indices) tuneTo(currentIndex)
            }
            override fun rediscover() {
                FrequencyProbe.clearServicesFound()
                this@MainActivity.rediscover()
            }
        }
        timeshift = TimeShiftController(this, tvView, handler,
            onGoLive = { tuneTo(currentIndex) },
            onShowBanner = { if (infoLevel == 0) showInfo(1) else scheduleInfoHide() })
        PlayerRemote.timeshift = { action, arg -> timeshiftAction(action, arg) }
        PlayerRemote.recording = { action, arg -> recordingAction(action, arg) }
        PlayerRemote.releaseTuner = {
            DebugLog.log("Player", "releasing tuner before installing update")
            timeshift.reset()
            hbb.stop()
            tvView.reset()
        }
        PlayerRemote.timeshiftState = { timeshift.json().put("status", tsStatus) }
        PlayerRemote.publish = { publishChannels(automatic = false) }
        PlayerRemote.colourKey = { event -> if (!isDestroyed) dispatchKeyEvent(event) }
        PlayerRemote.channels = { channels }
        PlayerRemote.currentChannel = { if (playingRecording == null) channels.getOrNull(currentIndex) else null }
        PlayerRemote.tune = { id ->
            val index = channels.indexOfFirst { it.id == id }
            // A channel picked on the phone should be visible at once: the menu and the list close.
            (index >= 0 && started).also { if (it) { menu.hide(); hideChannelList(); tuneTo(index) } }
        }
        PlayerRemote.channelsChanged = { reloadChannels() }
        TsProbe.tuneHook = { id ->
            val index = channels.indexOfFirst { it.id == id }
            if (index >= 0) tuneTo(index)
            index >= 0
        }
        DebugLog.log("Player", "onCreate")
    }

    override fun onResume() {
        super.onResume()
        // Returning from a screen opened from the menu (Manage channels, Diagnostics…) – menu at the same position.
        menu.returned()
    }

    override fun onStart() {
        super.onStart()
        started = true
        // Pause badge and bar (the handler was cleared in onStop).
        timeshift.startTicking()
        DebugLog.log("Player", "onStart")
        audio.start()
        Recorder.listeners += recorderListener
        RecordingScheduler.player = schedulerPlayer
        updateRecBadge()
        if (debugOverlay.visibility == View.VISIBLE) handler.post(refreshOverlay)
        // READ_EPG_DATA is enough on the set-top box (granted at install). READ_TV_LISTINGS (dangerous,
        // Android 11 without a privileged TvProvider) – only when the channel list comes out empty.
        val epg = Dtv.PERMISSION_READ_EPG
        if (checkSelfPermission(epg) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Dtv.listingsPermission(this)) != PackageManager.PERMISSION_GRANTED) {
            DebugLog.log("Player", "no permission for the channel list – requesting")
            askPermissions(arrayOf(epg, Dtv.listingsPermission(this)).distinct().toTypedArray(), REQUEST_PERMISSION)
            showStatus(getString(R.string.need_permission))
            return
        }
        loadChannelsAndPlay()
        maybeRequestStorage(intent)
        // The previous run crashed: offer a report once the player is on screen.
        handler.postDelayed({ if (started) ReportSender.offerPendingCrash(this) }, REPORT_PROMPT_DELAY_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeRequestStorage(intent)
    }

    /** Reading dtvinput recordings (HbbTV experiment) needs storage read permission – requested on demand by [TsProbe]. */
    private fun maybeRequestStorage(intent: Intent?) {
        val write = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        // Write (deleting recordings) is in the same group as read – with read granted the system does not prompt.
        if (TsProbe.hasStoragePermission(this) && checkSelfPermission(write) != PackageManager.PERMISSION_GRANTED) {
            askPermissions(arrayOf(write), REQUEST_STORAGE)
            return
        }
        if (TsProbe.hasStoragePermission(this)) return
        val requested = intent?.getBooleanExtra(TsProbe.EXTRA_REQUEST_STORAGE, false) == true
        intent?.removeExtra(TsProbe.EXTRA_REQUEST_STORAGE)
        // Without this permission channels are not identified while watching – we ask ourselves, at most once a day.
        val lastAsked = prefs.getLong(PREF_STORAGE_ASKED, 0)
        if (!requested && System.currentTimeMillis() - lastAsked < STORAGE_ASK_INTERVAL_MS) return
        prefs.edit().putLong(PREF_STORAGE_ASKED, System.currentTimeMillis()).apply()
        DebugLog.log("Player", "requesting storage access (dtvinput recordings)")
        askPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, write), REQUEST_STORAGE)
    }

    /**
     * App screen (TV guide, recordings, menu…) opened during live pause: the tuner session is kept
     * so the pause survives browsing – onStop does not release the tuner then.
     */
    private var keepSessionOnStop = false
    /** The tuner session survived onStop – onStart does not retune the channel. */
    private var sessionKept = false

    @Deprecated("Activity result API requires AndroidX – the app uses no libraries")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (timeshift.active && intent.component?.packageName == packageName) {
            DebugLog.log("Timeshift", "app screen during live pause – tuner session kept")
            keepSessionOnStop = true
        }
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onStop() {
        started = false
        val keepSession = keepSessionOnStop && timeshift.active
        keepSessionOnStop = false
        sessionKept = keepSession
        // tvView.reset() closes the tuner session together with the pause buffer.
        if (!keepSession) timeshift.reset()
        // removeCallbacksAndMessages also removes the digit commit – do not leave them on screen.
        digits.clear()
        numberEntry.visibility = View.GONE
        // Identification switches channels – it must not hold the tuner in the background.
        if (identifying) identifyCancelled = true
        TsProbe.currentChannelUri = null
        upcomingDialog?.dismiss()
        upcomingDialog = null
        Recorder.listeners -= recorderListener
        if (RecordingScheduler.player === schedulerPlayer) RecordingScheduler.player = null
        prober?.cancel("activity stopped")
        if (keepSession) {
            DebugLog.log("Player", "onStop – live pause continues, tuner kept (app screen)")
        } else {
            DebugLog.log("Player", "onStop – releasing tuner (TvView.reset)")
            // Release the tuner so other apps can use it.
            tvView.reset()
            hbb.stop()
        }
        audio.stop()
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        // Clear the static hooks only if they belong to this instance – a new one (e.g. after recreate) may have set them already.
        if (current?.get() === this) {
            current = null
            TsProbe.tuneHook = null
            SignalMonitor.player = null
            PlayerRemote.timeshift = null
            PlayerRemote.recording = null
            PlayerRemote.releaseTuner = null
            PlayerRemote.timeshiftState = null
            PlayerRemote.hbb = null
            PlayerRemote.publish = null
            PlayerRemote.colourKey = null
            PlayerRemote.channels = null
            PlayerRemote.currentChannel = null
            PlayerRemote.tune = null
            PlayerRemote.channelsChanged = null
        }
        publisher?.cancel()

        hbb.destroy()
        learnExecutor.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun askPermissions(permissions: Array<String>, requestCode: Int) {
        permissionDialogOpen = true
        requestPermissions(permissions, requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        DebugLog.log("Player", "permission result: ${permissions.zip(grantResults.toList())}")
        permissionDialogOpen = false
        // Also when refused: the empty list then leads on to the scan screen (listingsAsked prevents asking again).
        if (requestCode == REQUEST_PERMISSION || reloadAfterPermissions) {
            reloadAfterPermissions = false
            loadChannelsAndPlay()
        }
        if (requestCode == REQUEST_STORAGE && TsProbe.hasStoragePermission(this)) {
            // Permission just granted – identify the watched channel without waiting for a channel switch.
            channels.getOrNull(currentIndex)?.let { identifyInBackground(it, force = false) }
        }
    }

    private fun loadChannelsAndPlay() {
        val tuner = Dtv.findTunerInput(this)
        input = tuner
        if (tuner == null) {
            DebugLog.error("Player", "no tuner-type TV input found")
            showStatus(getString(R.string.no_input))
            ReportSender.offer(this, ReportSender.Reason.NO_TUNER, getString(R.string.no_input), automatic = true)
            return
        }
        DebugLog.log("Player", "input: ${tuner.id} (${tuner.loadLabel(this)}), tuners=${tuner.tunerCount}")
        io.execute {
            val caps = DeviceCapabilities.refresh(this)
            val loaded = repository.loadChannels(tuner.id)
            loaded.forEach { repository.programmes(it) } // load EPG into memory off the main thread
            DebugLog.log("Player", "loaded ${loaded.size} channels: " + loaded.take(40).joinToString { "${it.number} ${it.name}" })
            handler.post {
                channels = loaded
                adapter.notifyDataSetChanged()
                if (loaded.isEmpty()) {
                    if (permissionDialogOpen) {
                        DebugLog.log("Player", "no channels – waiting for the permission dialog to close")
                        reloadAfterPermissions = true
                        return@post
                    }
                    // TvProvider hides other inputs' channels without READ_TV_LISTINGS (emulator) – ask once per process.
                    val listings = Dtv.listingsPermission(this)
                    if (listings != Dtv.PERMISSION_READ_EPG && !listingsAsked && checkSelfPermission(listings) != PackageManager.PERMISSION_GRANTED) {
                        listingsAsked = true
                        DebugLog.log("Player", "no channels and no READ_TV_LISTINGS – requesting permission")
                        askPermissions(arrayOf(listings), REQUEST_PERMISSION)
                        return@post
                    }
                    // No channels: first launch – the scan screen; later on dtvinput we discover them by probing
                    // (channels from an earlier scan), elsewhere – the scan screen (TV input setup wizard).
                    // Not after a scan (setup stage 2 must follow) nor after any earlier scan in the app.
                    val firstLaunch = !SetupProgress.inPipeline(this) && !SetupProgress.scannedBefore(this) &&
                        !prefs.getBoolean(PREF_FIRST_SCAN_OPENED, false)
                    if (caps.channelDiscovery && !firstLaunch) startDiscovery(tuner) else if (!autoScanOpened) {
                        autoScanOpened = true
                        prefs.edit().putBoolean(PREF_FIRST_SCAN_OPENED, true).apply()
                        DebugLog.log("Player", "no channels${if (firstLaunch) " on first launch" else ""} – opening the scan screen")
                        showStatus(getString(R.string.no_channels_scan))
                        startActivity(Intent(this, ScanActivity::class.java))
                    } else {
                        showStatus(getString(R.string.no_channels))
                    }
                    return@post
                }
                if (identifyAfterLoad) {
                    identifyAfterLoad = false
                    if (caps.streamAnalysis && loaded.any { !it.identified && !it.iptv }) handler.postDelayed({ identifyAll() }, IDENTIFY_SETTLE_MS)
                    else { SetupProgress.endPipeline(this); hideSetupBanner() }
                }
                pendingRecordingId?.also { pendingRecordingId = null }?.let { id ->
                    val entry = Recorder.list(this).firstOrNull { it.id == id }
                    if (entry != null) {
                        currentIndex = channels.indexOfFirst { it.id == prefs.getLong(PREF_LAST_CHANNEL, -1L) }.coerceAtLeast(0)
                        playRecording(entry)
                        return@post
                    }
                }
                // Returning from an app screen during live pause: the tuner session is alive – no retune,
                // unless another channel was chosen (then tuneTo asks whether to end the pause).
                if (sessionKept) {
                    sessionKept = false
                    val chosen = pendingChannelId?.also { pendingChannelId = null }
                    currentIndex = loaded.indexOfFirst { it.id == prefs.getLong(PREF_LAST_CHANNEL, -1L) }.coerceAtLeast(0)
                    if (chosen != null && chosen != loaded.getOrNull(currentIndex)?.id) tuneTo(loaded.indexOfFirst { it.id == chosen }.coerceAtLeast(0))
                    return@post
                }
                // After publishing: the service watched before, now under a new ID.
                lastPublishedService?.also { lastPublishedService = null }?.let { (tsid, sid) ->
                    val index = loaded.indexOfFirst { it.tsid == tsid && it.sid == sid }
                    if (index >= 0) { tuneTo(index); return@post }
                }
                // While recording, the player returns to the channel being recorded (single tuner).
                val lastId = Recorder.active?.channelId ?: pendingChannelId?.also { pendingChannelId = null } ?: prefs.getLong(PREF_LAST_CHANNEL, -1L)
                tuneTo(loaded.indexOfFirst { it.id == lastId }.coerceAtLeast(0))
            }
        }
    }

    /** Index of the adjacent channel; with no channel selected (−1) always the first, not the last in the list. */
    private fun stepFrom(step: Int) = if (currentIndex < 0) 0 else currentIndex + step

    private fun tuneTo(index: Int) {
        if (!started || isDestroyed) {
            DebugLog.log("Player", "tune skipped – player is not on screen")
            return
        }
        val tuner = input ?: return
        if (channels.isEmpty()) return
        val target = Math.floorMod(index, channels.size)
        val channel = channels[target]
        if (timeshift.active && channels.getOrNull(currentIndex)?.id != channel.id) {
            confirmInterruptTimeshift(channel)
            return
        }
        if (!allowedWhileRecording(channel)) {
            confirmInterruptRecording(channel)
            return
        }
        // A new tune ends the live pause (the dtvinput buffer goes away with the session).
        timeshift.reset()
        if (currentIndex in channels.indices && channels[currentIndex].id != channel.id) {
            previousChannelId = channels[currentIndex].id
        }
        currentIndex = target
        playingRecording = null
        videoSize = null
        videoState = "tune"
        tunedAt = SystemClock.elapsedRealtime()
        prefs.edit().putLong(PREF_LAST_CHANNEL, channel.id).apply()
        DebugLog.log("Player", "tune ${channel.number} \"${channel.name}\" id=${channel.id} uri=${channel.uri}")
        hbbNoticeShownFor = -1L
        showStatus(getString(R.string.status_tuning))
        // Internet channels play through the app's own input ([IptvInputService]).
        tvView.tune(channel.inputId ?: tuner.id, channel.uri)
        TsProbe.currentChannelUri = channel.uri
        broadcastStopped = false
        onHbbTuned(channel)
        audio.apply("tune")
        adapter.notifyDataSetChanged()
        // After a channel change the panel returns to level 1 (now/next); not during identification –
        // channels switch by themselves then and the setup banner shows what is going on.
        if (!identifying) showInfo(1)
        renderOverlay()
    }

    private fun tuneByNumber(number: String) {
        if (number.isEmpty()) return
        val index = channels.indexOfFirst { it.number == number }.takeIf { it >= 0 }
            ?: channels.indexOfFirst { ChannelRepository.numberKey(it.number) / 10_000 == number.toLongOrNull() }
        DebugLog.log("Player", "number entry $number -> index $index")
        if (index >= 0) tuneTo(index)
    }

    /**
     * Banner at the top that stays for the whole channel discovery / identification, so it is clear why
     * the picture flickers and channels switch by themselves. [percent] null – progress unknown.
     */
    private fun showSetupBanner(title: String, detail: String, hint: String, percent: Int?) {
        setupBanner.findViewById<TextView>(R.id.setup_banner_title).text = title
        setupBanner.findViewById<TextView>(R.id.setup_banner_detail).text = detail
        setupBanner.findViewById<TextView>(R.id.setup_banner_hint).text = hint
        setupBanner.findViewById<android.widget.ProgressBar>(R.id.setup_banner_progress).apply {
            isIndeterminate = percent == null
            percent?.let { progress = it.coerceIn(0, 100) }
        }
        setupBanner.visibility = View.VISIBLE
    }

    private fun hideSetupBanner() {
        setupBanner.visibility = View.GONE
    }

    private fun showStatus(text: String?) {
        status.text = text
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---- Discovery of channels not visible in TvContract ----

    private fun startDiscovery(tuner: TvInputInfo) {
        if (prober != null) return
        showSetupBanner(
            getString(if (SetupProgress.inPipeline(this)) R.string.banner_setup_stage2 else R.string.banner_discovery),
            getString(R.string.banner_preparing), getString(R.string.banner_hint_discovery), null,
        )
        io.execute {
            val ceiling = repository.findChannelIdCeiling()
            val epg = repository.epgChannelIds()
            handler.post {
                val start = ceiling ?: epg.keys.maxOrNull()?.plus(BLOCK_SPAN)
                DebugLog.log(
                    "Discovery",
                    "start: id ceiling=$ceiling, EPG=${epg.keys.sorted()}, " +
                        if (start != null) "probing down from ${start - 1}" else "no starting point – searching every $COARSE_STEP from $COARSE_MAX"
                )
                Prober(tuner, start?.minus(1), epg.keys).also { prober = it }.step()
            }
        }
    }

    /**
     * Checks channel IDs via TvView.tune(). A channel exists when dtvinput reports video,
     * AUDIO_ONLY, WEAK_SIGNAL or a track list. A channel that reported tracks but stopped at a CAS
     * denial (reason 6–18, e.g. 18 = CAS_UNKNOWN) is saved as scrambled. For a nonexistent one
     * UNKNOWN(0) arrives immediately – after a short grace period we treat it as missing.
     *
     * Without a known highest id we first look for a block of channels every [COARSE_STEP] (channels from one scan
     * have consecutive ids), then check the neighborhood of the hit one by one.
     */
    private inner class Prober(private val tuner: TvInputInfo, startId: Long?, extraIds: Set<Long>) {
        private inner class Found(val id: Long, val radio: Boolean, val scrambled: Boolean, val freq: Double?)
        private val tunerSettings = TunerSettings(this@MainActivity)

        private val found = ArrayList<Found>()
        private val checkedIds = HashSet<Long>()
        private val pendingExtra = extraIds.toMutableSet()
        private var coarse = startId == null
        private var nextId = startId ?: COARSE_MAX
        private var misses = 0
        private var missLimitBeforeFirst = MAX_MISSES_BEFORE_FIRST
        private var probingId = -1L
        private var tracksVideo = false
        private var tracksAudio = false
        private var casDenied = false
        private val startedAt = SystemClock.elapsedRealtime()
        /**
         * Channel count reported by the scan (one-pass setup) – discovery ends after finding
         * that many channels. We probe from the newest IDs downward, so we do not reach
         * older entries (e.g. duplicates from a single-frequency scan).
         */
        private val expected = SetupProgress.expectedChannels(this@MainActivity)
        private val pipeline = SetupProgress.inPipeline(this@MainActivity)
        private val timeout = Runnable {
            when {
                tracksVideo || tracksAudio -> result(
                    true, radio = !tracksVideo, scrambled = casDenied,
                    reason = if (casDenied) "tracks without video, CAS denied" else "tracks without video within the wait (no CAS – treated as playable)",
                )
                else -> result(false, reason = "timeout")
            }
        }
        private val unknownGrace = Runnable { result(false, reason = "UNKNOWN") }
        /** After tracks arrive we wait less only if dtvinput has already reported a CAS denial. */
        private val tracksGrace = Runnable { if (casDenied) timeout.run() }

        fun step() {
            if (expected > 0 && found.size >= expected) {
                DebugLog.log("Discovery", "found all $expected channels reported by the scan – done")
                finish(save = true)
                return
            }
            probingId = nextCandidate() ?: run { finish(save = true); return }
            checkedIds += probingId
            tracksVideo = false
            tracksAudio = false
            casDenied = false
            showSetupBanner(
                getString(if (pipeline) R.string.banner_setup_stage2 else R.string.banner_discovery),
                progressText(), getString(R.string.banner_hint_discovery),
                if (expected > 0) found.size * 100 / expected else null,
            )
            tvView.tune(tuner.id, TvContract.buildChannelUri(probingId))
            handler.postDelayed(timeout, PROBE_TIMEOUT_MS)
        }

        private fun nextCandidate(): Long? {
            if (checkedIds.size >= MAX_PROBES) return pendingExtra.firstOrNull()?.also { pendingExtra.remove(it) }
            if (coarse) {
                while (nextId >= 1 && nextId in checkedIds) nextId -= COARSE_STEP
                if (nextId >= 1) return nextId.also { nextId -= COARSE_STEP }
            } else {
                val limit = if (found.isEmpty()) missLimitBeforeFirst else MAX_MISSES
                while (nextId >= 1 && nextId in checkedIds) nextId--
                if (misses < limit && nextId >= 1) return nextId--
            }
            return pendingExtra.firstOrNull { it !in checkedIds }?.also { pendingExtra.remove(it) }
        }

        private fun progressText(): String {
            if (expected <= 0) return getString(R.string.discovery_progress, probingId, found.size)
            val rates = SetupProgress.rates(this@MainActivity)
            val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val perProbe = if (checkedIds.size > 3) elapsed / (checkedIds.size - 1) else rates.perProbe
            val probesLeft = (expected - found.size + SetupProgress.PROBE_OVERHEAD / 2).coerceAtLeast(1)
            val left = SetupProgress.format(probesLeft * perProbe + expected * rates.perChannel)
            return if (pipeline) getString(R.string.setup_stage2, found.size, expected, left)
            else getString(R.string.discovery_progress, probingId, found.size)
        }

        /** TvView event during probing. Returns true – normal handling is skipped. */
        fun onEvent(event: String, reason: Int? = null, available: Boolean = false): Boolean {
            DebugLog.log("Discovery", "#$probingId <- $event")
            if (probingId < 0) return true
            when {
                available -> result(true, reason = event)
                reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> result(true, radio = true, reason = event)
                reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> result(true, reason = event)
                reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN -> {
                    handler.removeCallbacks(unknownGrace)
                    handler.postDelayed(unknownGrace, UNKNOWN_GRACE_MS)
                }
                reason != null && reason in CAS_REASONS -> casDenied = true
            }
            return true
        }

        fun onTracks(tracks: List<TvTrackInfo>): Boolean {
            tracksVideo = tracksVideo || tracks.any { it.type == TvTrackInfo.TYPE_VIDEO }
            tracksAudio = tracksAudio || tracks.any { it.type == TvTrackInfo.TYPE_AUDIO }
            DebugLog.log("Discovery", "#$probingId <- onTracksChanged video=$tracksVideo audio=$tracksAudio")
            if (tracksVideo || tracksAudio) {
                // The channel definitely exists; if video/audio does not start within this time – it is scrambled.
                handler.removeCallbacks(unknownGrace)
                handler.removeCallbacks(tracksGrace)
                handler.postDelayed(tracksGrace, TRACKS_GRACE_MS)
            }
            return true
        }

        private fun result(exists: Boolean, radio: Boolean = false, scrambled: Boolean = false, reason: String) {
            if (probingId < 0) return
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(unknownGrace)
            handler.removeCallbacks(tracksGrace)
            val id = probingId
            probingId = -1
            if (exists) {
                // The tuner is now tuned to the channel's multiplex – remember the frequency.
                val fe = tunerSettings.readFrontend()
                DebugLog.log("Discovery", "#$id frontend: ${fe?.describe()}")
                found += Found(id, radio, scrambled, fe?.frequencyMhz)
                misses = 0
                if (coarse) {
                    // Hit a block – now go one by one from the top of the block downward.
                    coarse = false
                    nextId = id + BLOCK_SPAN
                    missLimitBeforeFirst = BLOCK_SPAN.toInt() + 1
                    DebugLog.log("Discovery", "block hit at #$id – checking one by one from #${nextId}")
                }
            } else {
                misses++
            }
            val what = if (!exists) "missing" else "EXISTS" + (if (radio) " (radio)" else "") + (if (scrambled) " (SCRAMBLED)" else "")
            DebugLog.log("Discovery", "#$id $what ($reason), found ${found.size}, consecutive misses $misses")
            handler.postDelayed({ if (prober === this) step() }, 150)
        }

        fun cancel(why: String) {
            DebugLog.log("Discovery", "cancelled: $why (found ${found.size}, list will not be saved)")
            handler.removeCallbacks(timeout)
            handler.removeCallbacks(unknownGrace)
            handler.removeCallbacks(tracksGrace)
            finish(save = false)
        }

        private fun finish(save: Boolean) {
            prober = null
            tvView.reset()
            // Order: TV, radio, scrambled; within groups by id (scan order).
            val sorted = found.distinctBy { it.id }.sortedWith(compareBy<Found>({ it.scrambled }, { it.radio }, { it.id }))
            val list = sorted.mapIndexed { i, f ->
                Channel(
                    id = f.id, number = (i + 1).toString(), name = getString(R.string.discovered_name, i + 1),
                    isRadio = f.radio, videoFormat = null, discovered = true, scrambled = f.scrambled, frequencyMhz = f.freq,
                )
            }
            DebugLog.log(
                "Discovery",
                "done: checked ${checkedIds.size} ids, found ${list.size} " +
                    "(TV ${list.count { !it.isRadio && !it.scrambled }}, radio ${list.count { it.isRadio && !it.scrambled }}, scrambled ${list.count { it.scrambled }}): " +
                    list.joinToString { "${it.number}=#${it.id}${if (it.isRadio) "♪" else ""}${if (it.scrambled) "🔒" else ""}" }
            )
            DebugLog.log("Discovery", "multiplexes: ${ChannelStore.muxSummary(list)}")
            hideSetupBanner()
            if (!save) {
                showStatus(getString(R.string.discovery_cancelled))
                return
            }
            if (list.isEmpty()) {
                showStatus(getString(R.string.discovery_none))
                SetupProgress.endPipeline(this@MainActivity)
                return
            }
            SetupProgress.learn(this@MainActivity, "perProbe", (SystemClock.elapsedRealtime() - startedAt) / 1000.0, checkedIds.size)
            store.saveDiscovered(list)
            identifyAfterLoad = true
            // Stage 3 starts after the list loads – the banner stays meanwhile.
            if (pipeline) showSetupBanner(getString(R.string.banner_setup_stage3), getString(R.string.banner_preparing),
                getString(R.string.banner_hint_identify), 0)
            showStatusBriefly(
                getString(R.string.discovery_done_detail, list.count { !it.isRadio && !it.scrambled }, list.count { it.isRadio && !it.scrambled }, list.count { it.scrambled }) +
                    "\n" + ChannelStore.muxSummary(list)
            )
            loadChannelsAndPlay()
        }
    }

    /**
     * Publishes identified channels to TvContract through dtvinput ([ChannelPublisher]). On success they are
     * visible to every app (system launcher, other TV apps) with names and LCN numbers, and the player stops
     * relying on the discovered ID list. The tuner is released for the time of the install.
     */
    private fun publishChannels(automatic: Boolean): String {
        if (publisher != null) return "publishing already running"
        if (identifying) return "identification running – try later"
        if (Recorder.active != null) return "recording running – try later"
        val tuner = input ?: return "no tuner input"
        val toPublish = channels.filter { it.discovered && it.identified }
        if (toPublish.isEmpty()) {
            showStatusBriefly(getString(R.string.publish_nothing))
            return "nothing to publish – identify channels first"
        }
        DebugLog.log("Publish", "start (${if (automatic) "after setup" else "manual"}): ${toPublish.size} of ${channels.size} channels")
        val previousId = channels.getOrNull(currentIndex)?.id
        val previous = channels.getOrNull(currentIndex)
        timeshift.reset()
        hbb.stop()
        tvView.reset()
        showStatus(getString(R.string.publish_running, toPublish.size))
        val p = ChannelPublisher(this)
        publisher = p
        // Hidden channels are published too – hiding is a player setting kept under the DVB key.
        io.execute {
            val all = repository.loadChannels(tuner.id, includeHidden = true).filter { it.discovered && it.identified }
            handler.post {
                if (isDestroyed || publisher !== p) return@post
                p.publish(all) { result ->
                    publisher = null
                    if (result.visibleChannels > 0) {
                        store.clearDiscovered()
                        showStatusFor(getString(R.string.publish_done, result.visibleChannels), SETUP_DONE_MS)
                        // The same service under its new ID.
                        lastPublishedService = previous?.let { it.tsid to it.sid }
                    } else {
                        showStatusFor(getString(R.string.publish_failed), SETUP_DONE_MS)
                        DebugLog.log("Publish", "keeping the discovered list (last channel #$previousId)")
                    }
                    loadChannelsAndPlay()
                }
            }
        }
        return "publishing ${toPublish.size} channels started – see the log (Publish)"
    }

    /**
     * Published channels may survive a later full scan (the tuner input's scan only clears channels it found itself),
     * so the user confirms after reading the warning.
     */
    private fun confirmPublish(onClose: () -> Unit) {
        TvDialog.confirm(this, getString(R.string.menu_publish), getString(R.string.publish_warning),
            getString(R.string.publish_confirm), focusCancel = true, onClose = onClose) {
            menu.cancelReturn()
            publishChannels(automatic = false)
        }
    }

    /**
     * Shown once, the first time a channel's HbbTV app wants the colour keys while [ColourKeysService] is off:
     * on some set-top boxes those keys go to another app, and the system lets only the user switch the service on.
     * Not during setup, identification or with the menu or a dialog open – the next channel with such an app asks again.
     */
    private fun offerColourKeys() {
        if (prefs.getBoolean(PREF_COLOUR_KEYS_OFFERED, false) || ColourKeysService.isEnabled(this)) return
        if (!started || isFinishing || identifying || prober != null || SetupProgress.inPipeline(this)) return
        if (menu.visible || !hasWindowFocus() || colourKeysOfferPending) return
        val channelId = channels.getOrNull(currentIndex)?.id ?: return
        colourKeysOfferPending = true
        // A moment after the channel starts, so the offer does not cover the tune itself.
        handler.postDelayed({
            colourKeysOfferPending = false
            if (channels.getOrNull(currentIndex)?.id != channelId || !started || menu.visible || !hasWindowFocus()) return@postDelayed
            prefs.edit().putBoolean(PREF_COLOUR_KEYS_OFFERED, true).apply()
            DebugLog.log("ColourKeys", "offering to switch on the colour keys service")
            TvDialog.confirm(this, getString(R.string.colour_keys_enable_title), getString(R.string.colour_keys_offer_message),
                getString(R.string.colour_keys_open_settings), cancelLabel = getString(R.string.colour_keys_not_now)) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { DebugLog.warn("ColourKeys", "accessibility settings not available", it) }
            }
        }, COLOUR_KEYS_OFFER_DELAY_MS)
    }

    private var colourKeysOfferPending = false

    /** Service watched before publishing – after reloading, the player returns to it under its new ID. */
    private var lastPublishedService: Pair<Int?, Int?>? = null

    private fun rediscover() {
        val tuner = input ?: return
        store.clearDiscovered()
        channels = emptyList()
        currentIndex = -1
        adapter.notifyDataSetChanged()
        startDiscovery(tuner)
    }

    private fun renameChannel(channel: Channel) {
        TvDialog.input(this, getString(R.string.rename_title, channel.number), channel.name) { name ->
            store.setCustomName(channel, name)
            channels = channels.map { if (it.id == channel.id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
            adapter.notifyDataSetChanged()
            if (channels.getOrNull(currentIndex)?.id == channel.id) showBanner()
        }
    }

    // ---- Debug overlay ----

    private fun toggleOverlay() {
        val show = debugOverlay.visibility != View.VISIBLE
        debugOverlay.visibility = if (show) View.VISIBLE else View.GONE
        prefs.edit().putBoolean(PREF_DEBUG_OVERLAY, show).apply()
        DebugLog.log("Player", "debug overlay: $show")
        handler.removeCallbacks(refreshOverlay)
        if (show) handler.post(refreshOverlay)
    }

    private fun renderOverlay() {
        if (debugOverlay.visibility != View.VISIBLE) return
        val channel = channels.getOrNull(currentIndex)
        val audio = runCatching { tvView.getTracks(TvTrackInfo.TYPE_AUDIO).orEmpty() }.getOrDefault(emptyList())
        val subs = runCatching { tvView.getTracks(TvTrackInfo.TYPE_SUBTITLE).orEmpty() }.getOrDefault(emptyList())
        val video = runCatching { tvView.getTracks(TvTrackInfo.TYPE_VIDEO).orEmpty() }.getOrDefault(emptyList())
        val selectedVideo = video.firstOrNull { it.id == tvView.getSelectedTrack(TvTrackInfo.TYPE_VIDEO) }
        debugOverlay.text = buildString {
            appendLine("input   ${input?.id ?: "NONE"}")
            appendLine("channel ${channel?.let { "${it.number} ${it.name} (id ${it.id})" } ?: "-"}  [${currentIndex + 1}/${channels.size}]")
            appendLine("uri     ${channel?.uri ?: "-"}")
            appendLine("state   $videoState  since tune: ${if (tunedAt > 0) (SystemClock.elapsedRealtime() - tunedAt) / 1000 else 0} s")
            appendLine("video   ${videoSize?.let { "${it.first}x${it.second}" } ?: "-"}" +
                (selectedVideo?.let { "  ${it.videoFrameRate} fps  id=${it.id}" } ?: ""))
            appendLine("audio   ${audio.size} tracks, selected=${tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO)} " +
                audio.joinToString(" ") { "[${it.id}:${it.language}/${it.audioChannelCount}ch]" })
            appendLine("subs    ${subs.size}, selected=${tvView.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE)} " +
                subs.joinToString(" ") { "[${it.id}:${it.language}]" })
            appendLine("signal  ${lastSignal ?: "unavailable"}")
            appendLine("sound   ${this@MainActivity.audio.systemState()}")
            append("last    ${lastCallback}")
        }
    }

    private fun onPlayerEvent(event: String) {
        lastCallback = "$event @${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())}"
        DebugLog.log("Player", "<- $event")
        renderOverlay()
    }

    // ---- Info banner ----

    /** Info banner after a channel change (level 1) or technical details right away (INFO). */
    private fun showBanner(withSignal: Boolean = false) = showInfo(if (withSignal) 4 else maxOf(1, if (banner.visibility == View.VISIBLE) infoLevel else 1))

    /** Info panel at the given level (1–4), see [infoLevel]. */
    private fun showInfo(level: Int) {
        // The banner (e.g. after retuning on return from a screen) does not go under an open menu.
        if (menu.visible) return
        val channel = channels.getOrNull(currentIndex) ?: return
        infoLevel = level.coerceIn(1, 4)
        showSignal = infoLevel == 4
        bannerNumber.text = channel.number
        bannerName.text = channel.name
        bannerClock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
        // Level 4 (technical details) without long descriptions – otherwise the panel does not fit on screen.
        bannerDesc.maxLines = when (infoLevel) { 1 -> 2; 4 -> 1; else -> 10 }
        infoExtra.visibility = if (infoLevel in 2..3) View.VISIBLE else View.GONE
        infoOptions.visibility = if (infoLevel == 3) View.VISIBLE else View.GONE
        infoDiag.visibility = if (infoLevel == 4) View.VISIBLE else View.GONE
        infoHint.setText(when (infoLevel) {
            1 -> if (timeshift.active) R.string.info_hint_ts else R.string.info_hint_1
            2 -> R.string.info_hint_2; 3 -> R.string.info_hint_3; else -> R.string.info_hint_4
        })
        timeshift.render()
        if (infoLevel == 3) {
            updateOptionButtons()
            if (!infoOptSubs.hasFocus() && !infoOptRecord.hasFocus() && !infoOptPause.hasFocus()) infoOptAudio.requestFocus()
        } else {
            banner.findFocus()?.clearFocus()
        }
        if (infoLevel == 4) updateDiag(null)
        updateDetails(null)
        banner.visibility = View.VISIBLE
        // When paused / delayed, "Now" is the programme on screen, not the one airing live.
        val watchedAt = System.currentTimeMillis() - timeshift.delayMs()
        io.execute {
            val (now, next) = repository.loadNowNext(channel, watchedAt)
            handler.post {
                if (channels.getOrNull(currentIndex)?.id != channel.id) return@post
                val nowMs = watchedAt
                bannerNow.text = now?.let { getString(R.string.now, "${formatTime(it.startMs)}–${formatTime(it.endMs)}  ${it.title}") }.orEmpty()
                bannerNext.text = next?.let { getString(R.string.next, "${formatTime(it.startMs)}  ${it.title}") }.orEmpty()
                bannerProgress.visibility = if (now != null) View.VISIBLE else View.GONE
                now?.let { bannerProgress.progress = it.progress(nowMs) }
                val desc = now?.description?.let { if (infoLevel >= 2) it else it.replace('\n', ' ') }
                bannerDesc.text = desc
                bannerDesc.visibility = if (desc.isNullOrBlank()) View.GONE else View.VISIBLE
                infoMeta.text = now?.let { p ->
                    listOfNotNull(genreLabel(p.genre), "${(p.endMs - p.startMs) / 60_000} min", getString(R.string.info_left, ((p.endMs - nowMs) / 60_000).coerceAtLeast(0)))
                        .joinToString("  ·  ")
                }.orEmpty()
                val nextDesc = next?.description?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                infoNextDesc.text = nextDesc?.let { getString(R.string.info_next_desc, it) }
                infoNextDesc.visibility = if (nextDesc != null) View.VISIBLE else View.GONE
            }
        }
        scheduleInfoHide()
    }

    /** Level 1 hides after a few seconds, higher levels stay longer (refreshed by key presses). */
    private fun scheduleInfoHide() {
        handler.removeCallbacks(hideBanner)
        handler.removeCallbacks(refreshSignal)
        if (infoLevel == 4) handler.post(refreshSignal)
        handler.postDelayed(hideBanner, if (infoLevel <= 1) BANNER_MS else SIGNAL_BANNER_MS)
    }

    private fun updateOptionButtons() {
        val audio = runCatching { tvView.getTracks(TvTrackInfo.TYPE_AUDIO).orEmpty() }.getOrDefault(emptyList())
        val subs = runCatching { tvView.getTracks(TvTrackInfo.TYPE_SUBTITLE).orEmpty() }.getOrDefault(emptyList())
        val selAudio = tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO)
        val selSubs = tvView.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE)
        val audioLabel = audio.indexOfFirst { it.id == selAudio }.let { i -> if (i >= 0) trackLabel(audio[i], i) else getString(R.string.info_none) }
        val subsLabel = subs.indexOfFirst { it.id == selSubs }.let { i -> if (i >= 0) trackLabel(subs[i], i) else getString(R.string.subtitles_off) }
        infoOptAudio.text = getString(R.string.info_audio, audioLabel + if (audio.size > 1) " (${audio.size})" else "")
        infoOptSubs.text = getString(R.string.info_subs, subsLabel + if (subs.isNotEmpty()) " (${subs.size})" else "")
        // Record the watched channel (or stop the current recording) – without going into the menu.
        val current = channels.getOrNull(currentIndex)
        val rec = Recorder.active
        infoOptRecord.visibility = if (rec != null || (DeviceCapabilities.get(this).streamAnalysis && current != null &&
                recordable(current) && playingRecording == null)) View.VISIBLE else View.GONE
        infoOptRecord.text = getString(if (rec != null) R.string.info_record_stop else R.string.info_record)
        infoOptPause.visibility = if (timeshift.active || (current != null && !current.virtual && !current.iptv && playingRecording == null)) View.VISIBLE else View.GONE
        infoOptPause.setText(if (timeshift.active) R.string.info_live else R.string.info_pause)
    }

    /** Channel technical details (level 4): multiplex, identifiers, signal, video, audio, HbbTV. */
    private fun updateDiag(stats: SignalReader.Stats?) {
        val channel = channels.getOrNull(currentIndex) ?: return
        val service = ServiceCatalog.lookup(channel.onid, channel.sid)
        val audio = runCatching { tvView.getTracks(TvTrackInfo.TYPE_AUDIO).orEmpty() }.getOrDefault(emptyList())
        val video = runCatching { tvView.getTracks(TvTrackInfo.TYPE_VIDEO).orEmpty() }.getOrDefault(emptyList())
        val selAudio = audio.firstOrNull { it.id == tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO) }
        val selVideo = video.firstOrNull { it.id == tvView.getSelectedTrack(TvTrackInfo.TYPE_VIDEO) }
        fun encoding(t: TvTrackInfo?) = if (t != null && android.os.Build.VERSION.SDK_INT >= 30) t.encoding?.removePrefix("audio/")?.removePrefix("video/") else null
        fun row(label: Int, value: String) = getString(label).padEnd(12) + value
        val lines = listOf(
            row(R.string.diag_mux, "${service?.mux ?: "?"} · ${channel.frequencyMhz?.let { "%.1f MHz".format(Locale.US, it) } ?: "? MHz"}"),
            row(R.string.diag_service, "SID ${channel.sid ?: "?"} · TSID ${channel.tsid ?: "?"} · ONID ${channel.onid ?: "?"} · " +
                getString(R.string.diag_channel_id, channel.id)),
            row(R.string.diag_signal, stats?.format() ?: getString(R.string.signal_unavailable)),
            row(R.string.diag_video, ((videoSize ?: selVideo?.takeIf { it.videoWidth > 0 }?.let { it.videoWidth to it.videoHeight })?.let { "${it.first}×${it.second}" } ?: "–") +
                listOfNotNull(encoding(selVideo), selVideo?.videoFrameRate?.takeIf { it > 0 }?.let { getString(R.string.diag_fps, "%.0f".format(Locale.US, it)) }).joinToString("") { " · $it" }),
            row(R.string.diag_audio, (selAudio?.let { trackLabel(it, audio.indexOf(it)) } ?: "–") +
                listOfNotNull(encoding(selAudio), selAudio?.audioSampleRate?.takeIf { it > 0 }?.let { "${it / 1000} kHz" }).joinToString("") { " · $it" } +
                " · " + getString(R.string.diag_track_count, audio.size)),
            row(R.string.diag_kind, listOfNotNull(
                getString(if (channel.isRadio) R.string.kind_radio else R.string.kind_tv),
                getString(R.string.kind_scrambled).takeIf { channel.scrambled },
                getString(R.string.kind_virtual).takeIf { channel.virtual },
                getString(R.string.kind_iptv).takeIf { channel.iptv },
            ).joinToString(", ")),
            row(R.string.diag_hbbtv, hbb.currentUrl ?: getString(R.string.diag_no_app)),
        )
        infoDiag.text = lines.joinToString("\n")
    }

    private fun updateDetails(stats: SignalReader.Stats?) {
        val channel = channels.getOrNull(currentIndex)
        val parts = ArrayList<String>()
        if (channel?.virtual == true) parts += getString(R.string.virtual_channel_info)
        videoSize?.let { (w, h) -> parts += "${w}×${h}" }
        channel?.videoFormat?.let { parts += it.removePrefix("VIDEO_FORMAT_") }
        // At level 4 the signal is in the technical details – not repeated here.
        bannerDetails.text = parts.joinToString("   ·   ")
        bannerDetails.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatTime(ms: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    // ---- Channel list ----

    private fun showChannelList() {
        if (channels.isEmpty()) return
        banner.visibility = View.GONE
        channelPanel.visibility = View.VISIBLE
        val position = currentIndex.coerceAtLeast(0)
        channelList.setItemChecked(position, true)
        channelList.setSelection(position)
        channelList.requestFocus()
    }

    private fun hideChannelList() {
        channelPanel.visibility = View.GONE
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = channels[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val channel = channels[position]
            view.findViewById<TextView>(R.id.number).text = channel.number
            view.findViewById<TextView>(R.id.name).text = channel.name
            val nowMs = System.currentTimeMillis()
            val on = EpgSchedule.nowNext(repository.programmes(channel, cachedOnly = true), nowMs).first
            view.findViewById<TextView>(R.id.now).apply {
                text = on?.let { "${formatTime(it.startMs)}  ${it.title}" }
                visibility = if (on != null) View.VISIBLE else View.GONE
            }
            view.findViewById<android.widget.ProgressBar>(R.id.progress).apply {
                visibility = if (on != null) View.VISIBLE else View.GONE
                on?.let { progress = it.progress(nowMs) }
            }
            view.findViewById<TextView>(R.id.badge).text = when {
                position == currentIndex -> "▶"
                channel.virtual -> getString(R.string.badge_hbbtv)
                channel.scrambled -> "🔒"
                channel.isRadio -> "♪"
                else -> ""
            }
            return view
        }
    }

    // ---- Menu: audio tracks, subtitles, signal, scan, diagnostics ----

    /** UI language selection; after a change the activity is recreated with the new strings. */
    private fun chooseLanguage(onClose: (() -> Unit)? = null) {
        val current = AppLanguage.get(this)
        TvDialog.choose(this, getString(R.string.language_title),
            AppLanguage.SUPPORTED.map { TvDialog.Option(AppLanguage.label(this, it)) },
            AppLanguage.SUPPORTED.indexOf(current), onClose) { which ->
            val chosen = AppLanguage.SUPPORTED[which]
            if (chosen != current) {
                AppLanguage.set(this, chosen)
                recreate()
            }
        }
    }

    private fun showMenu() = menu.show()

    /** Menu sections; entries depending on device capabilities ([DeviceCapabilities]) are hidden when unsupported. */
    private fun menuSections(): List<MenuPanel.Section> {
        val caps = DeviceCapabilities.get(this)
        val current = channels.getOrNull(currentIndex)
        fun onOff(on: Boolean) = getString(if (on) R.string.menu_value_on else R.string.menu_value_off)
        val stay = MenuPanel.After.STAY
        val leave = MenuPanel.After.LEAVE
        val watch = listOfNotNull(
            MenuPanel.Entry(getString(R.string.menu_channels), current?.let { "${it.number}  ${it.name}" }, leave) { showChannelList() },
            MenuPanel.Entry(getString(R.string.menu_guide)) { openGuide() },
            MenuPanel.Entry(getString(R.string.menu_audio), selectedTrackLabel(TvTrackInfo.TYPE_AUDIO)) {
                showTrackDialog(TvTrackInfo.TYPE_AUDIO, menu::returned)
            },
            MenuPanel.Entry(getString(R.string.menu_subtitles), selectedTrackLabel(TvTrackInfo.TYPE_SUBTITLE)) {
                showTrackDialog(TvTrackInfo.TYPE_SUBTITLE, menu::returned)
            },
            if (timeshift.active) MenuPanel.Entry(getString(R.string.menu_live),
                getString(R.string.menu_live_value, TimeShiftModel.formatDelay(timeshift.delayMs())), leave) { timeshift.goLive() }
            else MenuPanel.Entry(getString(R.string.menu_pause), after = leave) { startPause() }
                .takeIf { current != null && !current.virtual && !current.iptv && playingRecording == null },
        )
        val recordings = if (!caps.streamAnalysis) emptyList() else listOfNotNull(
            Recorder.active.let { rec ->
                if (rec != null) MenuPanel.Entry(getString(R.string.menu_record_stop_label), rec.title, stay) { Recorder.stop() }
                else MenuPanel.Entry(getString(R.string.menu_record), current?.name, leave) {
                    startRecordingCurrent()?.let { showStatusBriefly(getString(R.string.recording_error, it)) }
                }.takeIf { current != null && recordable(current) }
            },
            MenuPanel.Entry(getString(R.string.menu_recordings), Recorder.list(this).size.toString()) { openRecordings() },
            Recorder.freeBytes().takeIf { it >= 0 }?.let { free ->
                val perHour = Recorder.bytesPerHour(Recorder.list(this)).coerceAtLeast(1)
                MenuPanel.Entry(getString(R.string.menu_free_space),
                    getString(R.string.menu_free_space_value, android.text.format.Formatter.formatShortFileSize(this, free), free / perHour),
                    stay) {}
            },
        )
        val lcn = ChannelRepository.lcnNumbering(this)
        val channelEntries = listOfNotNull(
            MenuPanel.Entry(getString(R.string.menu_scan)) { startActivity(Intent(this, ScanActivity::class.java)) },
            MenuPanel.Entry(getString(R.string.menu_manage)) { startActivity(Intent(this, ChannelsActivity::class.java)) },
            MenuPanel.Entry(getString(R.string.menu_numbering),
                getString(if (lcn) R.string.menu_numbering_value_lcn else R.string.menu_numbering_value_seq), stay) {
                ChannelRepository.setLcnNumbering(this, !lcn)
                reloadChannels()
            },
            MenuPanel.Entry(getString(R.string.menu_identify), after = leave) { identifyAll() }.takeIf { caps.streamAnalysis },
            MenuPanel.Entry(getString(R.string.menu_rediscover), after = leave) { rediscover() }.takeIf { caps.channelDiscovery },
            MenuPanel.Entry(getString(R.string.menu_publish)) { confirmPublish(menu::returned) }
                .takeIf { caps.inAppScan && channels.any { it.discovered && it.identified } },
        )
        val settings = listOfNotNull(
            MenuPanel.Entry(getString(R.string.menu_about), BuildConfig.VERSION_NAME) { showAbout(menu::returned) },
            MenuPanel.Entry(getString(R.string.menu_language_label), AppLanguage.label(this, AppLanguage.get(this))) {
                chooseLanguage(menu::returned)
            },
            // HbbTV requires stream analysis (dtvinput tuner); where it is unavailable, the entry says so explicitly.
            if (caps.hbbtv) MenuPanel.Entry(getString(R.string.menu_hbbtv), onOff(hbbStore.enabled), stay) {
                hbbStore.enabled = !hbbStore.enabled
                current?.let { onHbbTuned(it) }
            } else MenuPanel.Entry(getString(R.string.menu_hbbtv), getString(R.string.menu_value_unavailable), stay) {},
            MenuPanel.Entry(getString(R.string.menu_colour_keys), onOff(ColourKeysService.isEnabled(this))) {
                TvDialog.confirm(this, getString(R.string.colour_keys_enable_title), getString(R.string.colour_keys_enable_message),
                    getString(R.string.colour_keys_open_settings), onClose = menu::returned) {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { DebugLog.warn("ColourKeys", "accessibility settings not available", it) }
                }
            }.takeIf { caps.hbbtv },
            MenuPanel.Entry(getString(R.string.menu_phone_remote), onOff(PhoneRemote.isEnabled(this))) { showPhoneRemote(menu::returned) },
            MenuPanel.Entry(getString(R.string.menu_hbbtv_relearn), after = stay) {
                current?.let { identifyInBackground(it, force = true) }
            }.takeIf { caps.hbbtv && current != null },
        )
        val diagnostics = listOfNotNull(
            MenuPanel.Entry(getString(R.string.menu_signal), after = leave) { showBanner(withSignal = true) }.takeIf { caps.signal },
            MenuPanel.Entry(getString(R.string.menu_diagnostics)) { startActivity(Intent(this, DiagnosticsActivity::class.java)) },
            MenuPanel.Entry(getString(R.string.menu_send_report)) {
                ReportSender.offer(this, ReportSender.Reason.USER, null, automatic = false, onClose = menu::returned)
            },
            MenuPanel.Entry(getString(R.string.menu_debug_overlay), onOff(debugOverlay.visibility == View.VISIBLE), stay) { toggleOverlay() },
        )
        return listOf(
            MenuPanel.Section("watch", getString(R.string.menu_section_watch), R.drawable.ic_menu_watch, watch),
            MenuPanel.Section("recordings", getString(R.string.menu_section_recordings), R.drawable.ic_menu_record, recordings),
            MenuPanel.Section("channels", getString(R.string.menu_section_channels), R.drawable.ic_menu_channels, channelEntries),
            MenuPanel.Section("settings", getString(R.string.menu_section_settings), R.drawable.ic_menu_settings, settings),
            MenuPanel.Section("diagnostics", getString(R.string.menu_section_diagnostics), R.drawable.ic_menu_diag, diagnostics),
        ).filter { it.entries.isNotEmpty() }
    }

    /** Version, contact and project page (QR) – shown from the menu. */
    private fun showAbout(onClose: (() -> Unit)? = null) {
        TvDialog.qr(this, getString(R.string.about_title, BuildConfig.VERSION_NAME),
            getString(R.string.about_message, BuildConfig.CONTACT_EMAIL), BuildConfig.PROJECT_URL, onClose)
    }

    /** Phone remote: pairing by QR code; with phones already paired also forgetting them and turning it off. */
    private fun showPhoneRemote(onClose: (() -> Unit)? = null) {
        val paired = PhoneRemote.devices(this)
        if (!PhoneRemote.isEnabled(this) || paired.isEmpty()) {
            showPhoneRemoteQr(onClose)
            return
        }
        val options = listOf(
            TvDialog.Option(getString(R.string.phone_remote_pair)),
            TvDialog.Option(getString(R.string.phone_remote_forget), paired.joinToString(", ") { it.name }),
            TvDialog.Option(getString(R.string.phone_remote_turn_off)),
        )
        var next: (() -> Unit)? = null
        TvDialog.choose(this, getString(R.string.phone_remote_title), options, onClose = { next?.invoke() ?: onClose?.invoke() }) { pick ->
            when (pick) {
                0 -> next = { showPhoneRemoteQr(onClose) }
                1 -> { PhoneRemote.forgetAll(this); TvDialog.toast(this, getString(R.string.phone_remote_forgotten)) }
                2 -> PhoneRemote.setEnabled(this, false)
            }
        }
    }

    private fun showPhoneRemoteQr(onClose: (() -> Unit)?) {
        PhoneRemote.setEnabled(this, true)
        val link = PhoneRemote.pairingLink()
        when {
            !PhoneRemote.isRunning ->
                TvDialog.message(this, getString(R.string.phone_remote_title), getString(R.string.phone_remote_failed, PhoneRemote.lastError.orEmpty()), onClose)
            link == null -> TvDialog.message(this, getString(R.string.phone_remote_title), getString(R.string.phone_remote_no_network), onClose)
            else -> {
                lateinit var dialog: android.app.Dialog
                dialog = TvDialog.qr(this, getString(R.string.phone_remote_title), getString(R.string.phone_remote_qr_message), link) {
                    PhoneRemote.cancelPairing()
                    PhoneRemote.onPaired = null
                    onClose?.invoke()
                }
                PhoneRemote.onPaired = { name ->
                    dialog.dismiss()
                    TvDialog.toast(this, getString(R.string.phone_remote_paired, name), long = true)
                }
            }
        }
    }

    /** Selected track name for the menu value column (subtitles: "Off" when none). */
    private fun selectedTrackLabel(type: Int): String? {
        val tracks = tvView.getTracks(type).orEmpty()
        val index = tracks.indexOfFirst { it.id == tvView.getSelectedTrack(type) }
        return when {
            index >= 0 -> trackLabel(tracks[index], index)
            type == TvTrackInfo.TYPE_SUBTITLE -> getString(R.string.subtitles_off)
            else -> null
        }
    }

    private fun showTrackDialog(type: Int, onClose: (() -> Unit)? = null) {
        val tracks = tvView.getTracks(type).orEmpty()
        val subtitles = type == TvTrackInfo.TYPE_SUBTITLE
        val title = getString(if (subtitles) R.string.menu_subtitles else R.string.menu_audio)
        if (tracks.isEmpty()) {
            TvDialog.message(this, title, getString(R.string.no_tracks), onClose)
            return
        }
        val labels = ArrayList<TvDialog.Option>()
        if (subtitles) labels += TvDialog.Option(getString(R.string.subtitles_off))
        tracks.forEachIndexed { i, track -> labels += TvDialog.Option(trackLabel(track, i)) }
        val selectedId = tvView.getSelectedTrack(type)
        val checked = tracks.indexOfFirst { it.id == selectedId }.let { if (subtitles) it + 1 else it.coerceAtLeast(0) }
        TvDialog.choose(this, title, labels, checked, onClose) { which ->
            selectTrack(type, if (subtitles) tracks.getOrNull(which - 1) else tracks[which])
        }
    }

    private fun selectTrack(type: Int, track: TvTrackInfo?) {
        DebugLog.log("Player", "selectTrack type=$type id=${track?.id} lang=${track?.language}")
        if (type == TvTrackInfo.TYPE_SUBTITLE) {
            tvView.setCaptionEnabled(track != null)
            prefs.edit().putString(PREF_SUBTITLE_LANG, track?.language ?: SUBTITLES_OFF).apply()
        } else {
            prefs.edit().putString(PREF_AUDIO_LANG, track?.language).apply()
        }
        tvView.selectTrack(type, track?.id)
    }

    private fun cycleTrack(type: Int) {
        val tracks = tvView.getTracks(type).orEmpty()
        if (tracks.isEmpty()) return
        val current = tracks.indexOfFirst { it.id == tvView.getSelectedTrack(type) }
        val next = if (type == TvTrackInfo.TYPE_SUBTITLE) {
            // Order: off -> track 1 -> ... -> off.
            tracks.getOrNull(current + 1)
        } else {
            tracks[(current + 1) % tracks.size]
        }
        selectTrack(type, next)
        val label = next?.let { trackLabel(it, tracks.indexOf(it)) } ?: getString(R.string.subtitles_off)
        showStatusBriefly(label)
    }

    /** After a channel change, restores the user's preferred audio and subtitle languages. */
    private fun applyPreferredTracks() {
        val audioLang = prefs.getString(PREF_AUDIO_LANG, null)
        if (audioLang != null) {
            val selected = tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO)
            val tracks = tvView.getTracks(TvTrackInfo.TYPE_AUDIO).orEmpty()
            val preferred = tracks.firstOrNull { it.language == audioLang }
            if (preferred != null && preferred.id != selected) tvView.selectTrack(TvTrackInfo.TYPE_AUDIO, preferred.id)
        }
        when (val subtitleLang = prefs.getString(PREF_SUBTITLE_LANG, null)) {
            null, SUBTITLES_OFF -> Unit
            else -> {
                val track = tvView.getTracks(TvTrackInfo.TYPE_SUBTITLE).orEmpty().firstOrNull { it.language == subtitleLang }
                if (track != null && tvView.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE) != track.id) {
                    tvView.setCaptionEnabled(true)
                    tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, track.id)
                }
            }
        }
    }

    private fun logAudioTracks() {
        val tracks = tvView.getTracks(TvTrackInfo.TYPE_AUDIO).orEmpty()
        DebugLog.log(
            "Audio",
            "audio tracks (${tracks.size}), selected=${tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO)}: " +
                tracks.joinToString(" | ") { PlayerAudio.describeTrack(it) } + "; ${audio.systemState()}"
        )
    }

    private fun trackLabel(track: TvTrackInfo, index: Int): String {
        val parts = ArrayList<String>()
        track.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            val ui = AppLanguage.locale(this)
            parts += Locale(it).getDisplayLanguage(ui).replaceFirstChar { c -> c.titlecase(ui) }
        }
        track.description?.takeIf { it.isNotBlank() }?.let { parts += it.toString() }
        if (track.type == TvTrackInfo.TYPE_AUDIO) {
            when (track.audioChannelCount) {
                0 -> Unit
                1 -> parts += "mono"
                2 -> parts += "stereo"
                6 -> parts += "5.1"
                8 -> parts += "7.1"
                else -> parts += getString(R.string.track_channels, track.audioChannelCount)
            }
        }
        return parts.joinToString(" · ").ifEmpty { getString(R.string.track_default, index + 1) }
    }

    private val clearBriefStatus = Runnable { showStatus(null) }

    private fun showStatusFor(text: String, ms: Long) {
        showStatus(text)
        handler.removeCallbacks(clearBriefStatus)
        handler.postDelayed(clearBriefStatus, ms)
    }

    private fun showStatusBriefly(text: String) {
        showStatus(text)
        handler.removeCallbacks(clearBriefStatus)
        handler.postDelayed(clearBriefStatus, 2000)
    }

    // ---- Remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Every key press is logged here, before HbbTV or the menu take it – also keys this app has no use for,
        // with the scan code, so remotes that send unusual codes (e.g. for colour keys) can be identified.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DebugLog.log("Key", "${KeyEvent.keyCodeToString(event.keyCode)} scan=${event.scanCode} device=${event.device?.name ?: event.deviceId}")
        }
        // An open info panel handles arrows, OK and BACK itself (they do not reach HbbTV).
        if (menu.onKey(event)) return true
        val infoNav = infoLevel > 0 && event.keyCode in INFO_PANEL_KEYS
        if (infoLevel >= 2 && event.action == KeyEvent.ACTION_DOWN) scheduleInfoHide()
        // Keys in the HbbTV app's key set go to it (when the channel list is not open).
        if (channelPanel.visibility != View.VISIBLE && !infoNav && hbb.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- Recordings ----

    /** Records the watched channel: title and end from the current EPG programme (+ margin), otherwise until stopped. */
    private fun startRecordingCurrent(): String? {
        val channel = channels.getOrNull(currentIndex) ?: return "no channel"
        if (!recordable(channel)) return getString(R.string.recording_not_possible)
        val now = EpgSchedule.nowNext(repository.programmes(channel, cachedOnly = true), System.currentTimeMillis()).first
        val stopAt = now?.endMs?.plus(RECORD_MARGIN_MS)
        return Recorder.start(this, channel, now?.title ?: channel.name, now?.description.orEmpty(), stopAt)
    }

    private val recorderListener: (Recorder.Entry?) -> Unit = { entry ->
        updateRecBadge()
        if (entry == null) tuneAfterRecordingStop?.also { tuneAfterRecordingStop = null }?.let { id ->
            channels.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { tuneTo(it) }
        }
        if (entry != null) {
            val until = entry.stopAtMs?.let { java.text.SimpleDateFormat("HH:mm", AppLanguage.locale(this)).format(java.util.Date(it)) }
            showStatusBriefly(if (until != null) getString(R.string.recording_started_until, entry.title, until) else getString(R.string.recording_started, entry.title))
        } else {
            Recorder.list(this).firstOrNull()?.let { last ->
                showStatusBriefly(if (last.state == Recorder.Entry.DONE) getString(R.string.recording_saved, last.title)
                    else getString(R.string.recording_error, last.error ?: "–"))
            }
        }
    }

    private fun updateRecBadge() {
        recBadge.text = getString(R.string.rec_badge)
        recBadge.visibility = if (Recorder.active != null) View.VISIBLE else View.GONE
    }

    /** Scrambled channels record encrypted, and virtual HbbTV channels are just a slate on air – nothing to record. */
    private fun recordable(channel: Channel) = !channel.scrambled && !channel.virtual && !channel.iptv

    /** Scheduled recordings while the player is on screen: a warning a minute ahead and a channel switch. */
    private val schedulerPlayer = object : RecordingScheduler.Player {
        override fun onRecordingUpcoming(entry: Recorder.Entry) {
            val switching = channels.getOrNull(currentIndex)?.id != entry.channelId
            DebugLog.log("Rec", "recording warning ${entry.title}" + if (switching) " (channel change)" else "")
            upcomingDialog?.dismiss()
            val dialog = TvDialog.confirm(this@MainActivity, getString(R.string.schedule_upcoming_title),
                getString(if (switching) R.string.schedule_upcoming_switch else R.string.schedule_upcoming_same,
                    entry.title, "${entry.channelNumber} ${entry.channelName}"),
                getString(android.R.string.ok), getString(R.string.schedule_cancel),
                onCancel = { RecordingScheduler.cancel(this@MainActivity, entry.id) }) {}
            upcomingDialog = dialog
            handler.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, RecordingScheduler.WARN_BEFORE_MS)
        }

        override fun switchForRecording(entry: Recorder.Entry): Result<Long> {
            Recorder.active?.let { return Result.failure(IllegalStateException("another recording in progress: ${it.title}")) }
            val index = channels.indexOfFirst { it.id == entry.channelId }
            // The channel is not in the player's list (e.g. hidden) – the recorder will try to tune it anyway.
            if (index < 0 || (playingRecording == null && currentIndex == index)) return Result.success(0L)
            // First switch the player to the recording channel (frees the tuner from another multiplex), then record shortly after.
            tuneTo(index)
            return Result.success(SWITCH_BEFORE_RECORDING_MS)
        }
    }

    private fun openRecordings() {
        hideChannelList()
        startActivityForResult(Intent(this, RecordingsActivity::class.java), REQUEST_RECORDINGS)
    }

    /**
     * Single tuner: during a recording (and in future live pause) a channel change would interrupt it,
     * so only the recorded channel is available. Others – after user confirmation.
     */
    private fun allowedWhileRecording(channel: Channel): Boolean {
        val rec = Recorder.active ?: return true
        return channel.id == rec.channelId
    }

    /** Channel to switch to after the recording stops (confirmed in [confirmInterruptRecording]). */
    private var tuneAfterRecordingStop: Long? = null

    private fun confirmInterruptRecording(channel: Channel) {
        val rec = Recorder.active ?: return
        DebugLog.log("Rec", "attempt to switch to ${channel.number} ${channel.name} while recording – asking to interrupt")
        // "Cancel" is the default – an accidental OK does not interrupt the recording.
        TvDialog.confirm(this, getString(R.string.recording_interrupt_title),
            getString(R.string.recording_interrupt_message, rec.title, "${channel.number} ${channel.name}"),
            getString(R.string.recording_interrupt_confirm), getString(R.string.recording_interrupt_cancel), focusCancel = true) {
            DebugLog.log("Rec", "user interrupts recording, switching to ${channel.number} ${channel.name}")
            tuneAfterRecordingStop = channel.id
            Recorder.stop("channel change")
        }
    }

    /** Plays a recording through the tuner input session (TvView.timeShiftPlay). */
    private fun playRecording(entry: Recorder.Entry): String {
        val tuner = input ?: return "no input"
        val uri = entry.recordedUri ?: return "recording has no RecordedPrograms ID"
        DebugLog.log("Rec", "playing ${entry.title}: $uri")
        playingRecording = entry
        recordingPaused = false
        recordingRemainingMs = (entry.endMs ?: entry.startMs) - entry.startMs
        recordingClockRunning = false
        handler.removeCallbacks(recordingEnded)
        videoState = "tune"
        tunedAt = SystemClock.elapsedRealtime()
        hbb.start(null)
        tvView.timeShiftPlay(tuner.id, Uri.parse(uri))
        return "OK"
    }

    private fun recordingAction(action: String, arg: String?): String = when (action) {
        "start" -> startRecordingCurrent() ?: "OK"
        "stop" -> { Recorder.stop(); "OK" }
        "play" -> Recorder.list(this).firstOrNull { it.id == arg?.toLongOrNull() }?.let { playRecording(it) } ?: "no recording $arg"
        "live" -> { handler.removeCallbacks(recordingEnded); playingRecording = null; tuneTo(currentIndex); "OK" }
        "pause" -> { recordingPaused = true; tvView.timeShiftPause(); pauseRecordingEnd(); "OK" }
        "resume" -> { recordingPaused = false; tvView.timeShiftResume(); scheduleRecordingEnd(); "OK" }
        else -> "actions: start, stop, play&id=<id>, live"
    }

    // ---- Live pause (timeshift) ----

    /** Pauses the watched channel: video freezes, the tuner keeps buffering (up to 30 min). */
    private fun startPause() {
        val channel = channels.getOrNull(currentIndex) ?: return
        if (playingRecording != null || channel.virtual || channel.iptv) return
        // Just the "⏸" badge at the top – no banner or counter (▼ shows the banner with the pause bar).
        handler.removeCallbacks(hideBanner)
        hideBanner.run()
        // The HbbTV app relates to the live broadcast – hidden during live pause (comes back after "live").
        hbb.stop()
        timeshift.pause()
    }

    /** A channel change would end the live pause (single tuner, buffer is lost) – only after confirmation. */
    private fun confirmInterruptTimeshift(channel: Channel) {
        DebugLog.log("Timeshift", "attempt to switch to ${channel.number} ${channel.name} during live pause – asking to interrupt")
        TvDialog.confirm(this, getString(R.string.ts_interrupt_title),
            getString(R.string.ts_interrupt_message, TimeShiftModel.formatDelay(timeshift.bufferMs()), "${channel.number} ${channel.name}"),
            getString(R.string.ts_interrupt_confirm), getString(R.string.recording_interrupt_cancel), focusCancel = true) {
            timeshift.reset()
            channels.indexOfFirst { it.id == channel.id }.takeIf { it >= 0 }?.let { tuneTo(it) }
        }
    }

    /** Live pause control from the server page (remote tests). */
    private fun timeshiftAction(action: String, arg: String?): String {
        DebugLog.log("Timeshift", "remote: $action ${arg.orEmpty()}")
        when (action) {
            "pause" -> if (timeshift.active) timeshift.pause() else startPause()
            "resume" -> timeshift.resume()
            "toggle" -> if (timeshift.active) timeshift.togglePause() else startPause()
            "seek" -> timeshift.seekBy(arg?.toLongOrNull() ?: return "pass arg=<ms, negative = backward>")
            "panel" -> showInfo(1)
            "live" -> if (timeshift.active) timeshift.goLive() else return "live pause not active"
            else -> return "actions: pause, resume, toggle, seek&arg=±ms, panel, live"
        }
        return "OK $action"
    }

    // ---- HbbTV ----

    /** After a channel change: the AUTOSTART app from the stored AIT, or background learning if there is none. */
    private fun onHbbTuned(channel: Channel) {
        if (identifying) return
        // Internet channels have no broadcast stream: no HbbTV application, nothing to identify.
        if (channel.iptv) {
            hbb.start(null)
            return
        }
        if (hbbInternalTune) {
            hbbInternalTune = false
            return
        }
        hbb.start(if (hbbStore.enabled) hbbStore.get(channel.id)?.autostart?.url else null)
        identifyInBackground(channel, force = false)
    }

    /**
     * Background identification while watching: the current channel (DVB identifiers, AIT, multiplex EPG),
     * then unidentified channels on the same multiplex – recording another service from the same
     * multiplex does not require switching the video. If the tuner does not allow it, we remember that.
     */
    private fun identifyInBackground(channel: Channel, force: Boolean) {
        if (!DeviceCapabilities.get(this).streamAnalysis || channel.iptv) return
        if (!TsProbe.hasStoragePermission(this)) {
            DebugLog.log("Identify", "no permission to read recordings – identification skipped")
            if (!channel.identified) showStatusBriefly(getString(R.string.identify_no_permission))
            return
        }
        // Without a known frequency we cannot tell which channels share the multiplex – then only the current one.
        val sameMux = if (channel.frequencyMhz == null) emptyList()
        else channels.filter { it.id != channel.id && it.frequencyMhz == channel.frequencyMhz && !it.identified }
        learnExecutor.execute {
            // Give playback a moment to settle; if the channel changed in the meantime – give up.
            Thread.sleep(if (force) 500 else 3000)
            if (TsProbe.currentChannelUri != channel.uri || identifying || probingFrequency) return@execute
            val entry = hbbStore.get(channel.id)
            val epg = EpgStore.get(this)
            val epgStale = System.currentTimeMillis() - epg.updatedAt(entry?.onid, entry?.tsid) > EpgStore.REFRESH_MS
            // hasAudio == null: entry predates virtual channel detection – learn again.
            val stale = entry == null || entry.hasAudio == null || System.currentTimeMillis() - entry.learnedAt > HbbTvStore.MAX_AGE_MS
            var changed = false
            if (force || stale || epgStale) {
                // Longer recording when the EPG needs refreshing – the EIT schedule arrives more slowly than p/f.
                val learned = TsProbe.learn(applicationContext, channel.id, seconds = if (epgStale) 8 else 4)
                if (learned != null) {
                    changed = entry?.sid != learned.sid || entry?.scrambled != learned.scrambled || entry?.looksVirtual != learned.looksVirtual
                    handler.post {
                        if (channels.getOrNull(currentIndex)?.id != channel.id || !hbbStore.enabled) return@post
                        val url = learned.autostart?.url
                        if (url != hbb.currentUrl) hbb.start(url)
                    }
                }
            }
            val prefs = getSharedPreferences("identify", MODE_PRIVATE)
            if (!prefs.getBoolean(PREF_SAME_MUX_BUSY, false)) {
                for (other in sameMux) {
                    if (TsProbe.currentChannelUri != channel.uri || identifying) break
                    if (TsProbe.learn(applicationContext, other.id, seconds = 3) != null) {
                        changed = true
                    } else if (TsProbe.lastErrorWasBusy) {
                        DebugLog.log("Identify", "tuner cannot record another service on the multiplex – identifying watched channels only")
                        prefs.edit().putBoolean(PREF_SAME_MUX_BUSY, true).apply()
                        break
                    }
                }
            }
            if (changed) handler.post { reloadChannels() }
        }
    }

    /** Reloads the list (names, numbers) without switching channels. */
    private fun reloadChannels() {
        val tuner = input ?: return
        val currentId = channels.getOrNull(currentIndex)?.id
        io.execute {
            val loaded = repository.loadChannels(tuner.id)
            loaded.forEach { repository.programmes(it) } // load EPG into memory off the main thread
            handler.post {
                channels = loaded
                currentIndex = loaded.indexOfFirst { it.id == currentId }
                adapter.notifyDataSetChanged()
                if (banner.visibility == View.VISIBLE) showBanner()
            }
        }
    }

    /**
     * Full identification from the menu: for each multiplex the player switches to one of its channels,
     * and the remaining channels are recorded in the background (or, if the tuner does not allow it, switched one by one).
     * One longer recording per multiplex also yields a week of TV guide data.
     */
    private fun identifyAll() {
        if (identifying) return
        // The stage 3 banner shown after discovery must not stay when identification does not start.
        if (Recorder.active != null || !DeviceCapabilities.get(this).streamAnalysis) { hideSetupBanner(); return }
        if (!TsProbe.hasStoragePermission(this)) {
            hideSetupBanner()
            showStatusBriefly(getString(R.string.identify_no_permission))
            maybeRequestStorage(Intent().putExtra(TsProbe.EXTRA_REQUEST_STORAGE, true))
            return
        }
        val originalId = channels.getOrNull(currentIndex)?.id
        val snapshot = channels.filter { !it.iptv }
        identifying = true
        identifyCancelled = false
        hbb.start(null)
        val identifyPrefs = getSharedPreferences("identify", MODE_PRIVATE)
        val pipeline = SetupProgress.inPipeline(this)
        DebugLog.log("Identify", "full identification of ${snapshot.size} channels" + if (pipeline) " (setup stage 3/3)" else "")
        showSetupBanner(getString(if (pipeline) R.string.banner_setup_stage3 else R.string.banner_identify),
            getString(R.string.banner_preparing), getString(R.string.banner_hint_identify), 0)
        fun tuneAndWait(id: Long, settleMs: Long) {
            val latch = java.util.concurrent.CountDownLatch(1)
            handler.post {
                channels.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { tuneTo(it) }
                latch.countDown()
            }
            // The post may have been removed in onStop – without a timeout the thread would wait for the activity's lifetime.
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) identifyCancelled = true
            Thread.sleep(settleMs)
        }
        learnExecutor.execute {
            var done = 0
            var ok = 0
            val started = System.currentTimeMillis()
            // If we already know the tuner cannot record another service on the multiplex, switch channels right away.
            var busy = identifyPrefs.getBoolean(PREF_SAME_MUX_BUSY, false)
            fun progress(ch: Channel) {
                val elapsed = (System.currentTimeMillis() - started) / 1000.0
                val perChannel = if (done > 3) elapsed / (done - 1) else SetupProgress.rates(this).perChannel
                val left = SetupProgress.format((snapshot.size - done + 1) * perChannel)
                handler.post {
                    if (!identifying) return@post
                    showSetupBanner(
                        getString(if (pipeline) R.string.banner_setup_stage3 else R.string.banner_identify),
                        if (pipeline) getString(R.string.setup_stage3, done, snapshot.size, ch.name, left)
                        else getString(R.string.identify_progress_eta, done, snapshot.size, ch.name, left),
                        getString(R.string.banner_hint_identify),
                        (done - 1) * 100 / snapshot.size.coerceAtLeast(1),
                    )
                }
            }
            for ((_, group) in snapshot.groupBy { it.frequencyMhz }) {
                if (identifyCancelled) break
                tuneAndWait(group.first().id, IDENTIFY_SETTLE_MS)
                group.forEachIndexed { i, ch ->
                    if (identifyCancelled) return@forEachIndexed
                    done++
                    progress(ch)
                    // Within a multiplex the tuner is already tuned – a short settle is enough.
                    if (i > 0 && busy) tuneAndWait(ch.id, IDENTIFY_SETTLE_SAME_MUX_MS)
                    // One channel per multiplex is recorded longer – the EIT (EPG) schedule arrives more slowly.
                    var entry = TsProbe.learn(applicationContext, ch.id, seconds = if (i == 0) 8 else 3)
                    if (entry == null && TsProbe.lastErrorWasBusy && !busy) {
                        busy = true
                        identifyPrefs.edit().putBoolean(PREF_SAME_MUX_BUSY, true).apply()
                        DebugLog.log("Identify", "tuner cannot record another service on the multiplex – continuing with channel switching")
                        tuneAndWait(ch.id, IDENTIFY_SETTLE_SAME_MUX_MS)
                        entry = TsProbe.learn(applicationContext, ch.id, seconds = 3)
                    } else if (entry == null && !identifyCancelled) {
                        // A single failure (e.g. the channel was still tuning) – one retry.
                        tuneAndWait(ch.id, IDENTIFY_SETTLE_MS)
                        entry = TsProbe.learn(applicationContext, ch.id, seconds = 3)
                    }
                    if (entry != null) ok++
                }
            }
            if (!identifyCancelled && done > 0) {
                SetupProgress.learn(applicationContext, "perChannel", (System.currentTimeMillis() - started) / 1000.0, done)
            }
            val total = snapshot.size
            handler.post {
                identifying = false
                hideSetupBanner()
                DebugLog.log("Identify", "identification done: $ok/$total" + if (identifyCancelled) " (cancelled)" else "")
                if (pipeline) {
                    val whole = SetupProgress.format(SetupProgress.elapsedSinceStart(this))
                    SetupProgress.endPipeline(this)
                    SetupProgress.setLastChannelCount(this, total)
                    if (identifyCancelled) showStatusBriefly(getString(R.string.identify_cancelled))
                    else showStatusFor(getString(R.string.setup_done, ok, total, whole), SETUP_DONE_MS)
                } else {
                    showStatusBriefly(getString(if (identifyCancelled) R.string.identify_cancelled else R.string.identify_done, ok, total))
                }
                val tuner = input ?: return@post
                io.execute {
                    val loaded = repository.loadChannels(tuner.id)
                    loaded.forEach { repository.programmes(it) }
                    handler.post {
                        channels = loaded
                        adapter.notifyDataSetChanged()
                        val index = loaded.indexOfFirst { it.id == originalId }.coerceAtLeast(0)
                        tuneTo(index)
                    }
                }
            }
        }
    }

    private fun openGuide() {
        hideChannelList()
        startActivityForResult(
            Intent(this, GuideActivity::class.java).putExtra(GuideActivity.EXTRA_CHANNEL_ID, channels.getOrNull(currentIndex)?.id ?: -1L),
            REQUEST_GUIDE,
        )
    }

    @Deprecated("Activity result API requires AndroidX – the app uses no libraries")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_RECORDINGS && resultCode == RESULT_OK) {
            menu.cancelReturn()
            pendingRecordingId = data?.getLongExtra(RecordingsActivity.EXTRA_RECORDING_ID, -1L)?.takeIf { it > 0 }
            return
        }
        if (requestCode != REQUEST_GUIDE || resultCode != RESULT_OK) return
        if (data?.getBooleanExtra(GuideActivity.EXTRA_OPEN_MENU, false) == true) {
            // Open the menu once the player is back on screen.
            handler.post { showMenu() }
            return
        }
        val id = data?.getLongExtra(GuideActivity.EXTRA_CHANNEL_ID, -1L) ?: return
        menu.cancelReturn()
        // The result arrives before onStart (the player is not on screen yet) – the choice is applied
        // by channel loading in onStart; if the activity is already running, right away.
        pendingChannelId = id
        if (started) channels.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { pendingChannelId = null; tuneTo(it) }
    }

    private fun channelJson(c: Channel): org.json.JSONObject? {
        val e = hbbStore.get(c.id) ?: return null
        return org.json.JSONObject().put("ccid", "ccid:dvbt.${c.id}").put("name", c.name).put("number", c.number.toIntOrNull() ?: 0)
            .put("onid", e.onid ?: 0).put("tsid", e.tsid ?: 0).put("sid", e.sid ?: 0).put("radio", c.isRadio)
    }

    private inner class HbbHost : HbbTvBrowser.Host {
        override fun hbbChannel() = channels.getOrNull(currentIndex)?.let { channelJson(it) }
        override fun hbbChannels() = org.json.JSONArray().apply { channels.forEach { c -> channelJson(c)?.let { put(it) } } }
        override fun hbbSetChannel(onid: Int, tsid: Int, sid: Int): Boolean {
            val index = channels.indexOfFirst { c ->
                hbbStore.get(c.id)?.let { it.sid == sid && (tsid == 0 || it.tsid == tsid) } == true
            }
            DebugLog.log("HbbTV", "setChannel onid=$onid tsid=$tsid sid=$sid -> ${channels.getOrNull(index)?.number}")
            if (index < 0) return false
            hbbInternalTune = true
            tuneTo(index)
            return true
        }
        override fun hbbChannelStep(step: Int) { hbbInternalTune = true; tuneTo(stepFrom(step)) }
        override fun hbbStopBroadcast() {
            DebugLog.log("HbbTV", "app stops the broadcast video")
            broadcastStopped = true
            tvView.reset()
            // The app plays its own (internet) video now – a tuner status such as "No picture" must not stay on it.
            showStatus(null)
        }
        override fun hbbColourKeysWanted() = offerColourKeys()
        override fun hbbAppUnavailable(offline: Boolean) {
            val channel = channels.getOrNull(currentIndex)
            if (channel == null || hbbNoticeShownFor == channel.id) return
            hbbNoticeShownFor = channel.id
            // A toast, not the status line – the status line is cleared as soon as the picture appears.
            TvDialog.toast(this@MainActivity, getString(if (offline) R.string.hbbtv_offline else R.string.hbbtv_unavailable), long = true)
        }
        override fun hbbBindBroadcast() {
            if (!broadcastStopped) return
            broadcastStopped = false
            val channel = channels.getOrNull(currentIndex) ?: return
            val tuner = input ?: return
            DebugLog.log("HbbTV", "app resumes the broadcast video")
            tvView.tune(channel.inputId ?: tuner.id, channel.uri)
        }
        override fun hbbBroadcastRect(rect: String) {
            val params = tvView.layoutParams as android.widget.FrameLayout.LayoutParams
            val parent = tvView.parent as View
            val r = rect.split(',').mapNotNull { it.toFloatOrNull() }
            if (rect == "full" || r.size != 4 || r[2] >= 1270f || parent.width == 0) {
                if (params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.leftMargin == 0) return
                tvView.layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } else {
                // The HbbTV page is 1280 px wide – convert to screen pixels.
                val scale = parent.width / 1280f
                tvView.layoutParams = android.widget.FrameLayout.LayoutParams((r[2] * scale).toInt(), (r[3] * scale).toInt()).apply {
                    leftMargin = (r[0] * scale).toInt()
                    topMargin = (r[1] * scale).toInt()
                }
            }
            DebugLog.log("HbbTV", "broadcast video: $rect")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val panelVisible = channelPanel.visibility == View.VISIBLE
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            if (digits.length < 4) digits.append(keyCode - KeyEvent.KEYCODE_0)
            numberEntry.text = digits
            numberEntry.visibility = View.VISIBLE
            handler.removeCallbacks(commitDigits)
            handler.postDelayed(commitDigits, DIGIT_TIMEOUT_MS)
            return true
        }
        if (playingRecording == null && onTimeshiftKey(keyCode, event, panelVisible)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { tuneTo(stepFrom(+1)); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { tuneTo(stepFrom(-1)); return true }
            // P+/P− change channels; up/down arrows scroll the programme info panel.
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!panelVisible) { showInfo(if (infoLevel == 0) 1 else infoLevel + 1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!panelVisible) {
                if (infoLevel > 1) showInfo(infoLevel - 1) else if (infoLevel == 1) { handler.removeCallbacks(hideBanner); hideBanner.run() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!panelVisible) {
                if (digits.isNotEmpty()) {
                    handler.removeCallbacks(commitDigits)
                    commitDigits.run()
                } else if (channels.isEmpty()) {
                    startActivity(Intent(this, ScanActivity::class.java))
                } else {
                    showMenu()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (panelVisible) { hideChannelList(); return true }
            // At level 3 left/right arrows move focus between the "Audio" and "Subtitles" buttons.
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!panelVisible && infoLevel != 3) { if (infoLevel == 0) showInfo(1); return true }
            KeyEvent.KEYCODE_INFO -> if (!panelVisible) { showInfo(4); return true }
            // Some remotes have no MENU key: the menu is on REC (the guide key opens the TV guide).
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MEDIA_RECORD -> { hideChannelList(); showMenu(); return true }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_DATA_SERVICE -> { openGuide(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                val index = channels.indexOfFirst { it.id == previousChannelId }
                if (index >= 0) tuneTo(index)
                return true
            }
            KeyEvent.KEYCODE_CAPTIONS -> { cycleTrack(TvTrackInfo.TYPE_SUBTITLE); return true }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { cycleTrack(TvTrackInfo.TYPE_AUDIO); return true }
            // Developer shortcuts – in release builds colour keys not taken by an HbbTV app do nothing.
            KeyEvent.KEYCODE_PROG_RED -> if (BuildConfig.DEBUG) { toggleOverlay(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> if (BuildConfig.DEBUG) { startActivity(Intent(this, DiagnosticsActivity::class.java)); return true }
            KeyEvent.KEYCODE_MEDIA_STOP -> if (playingRecording != null) { recordingAction("live", null); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> if (playingRecording != null) {
                recordingPaused = if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) false else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) true else !recordingPaused
                if (recordingPaused) { tvView.timeShiftPause(); pauseRecordingEnd() } else { tvView.timeShiftResume(); scheduleRecordingEnd() }
                DebugLog.log("Rec", if (recordingPaused) "playback paused" else "playback resumed")
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                when {
                    identifying -> { identifyCancelled = true; showStatus(getString(R.string.identify_cancelled)); return true }
                    prober != null -> { prober?.cancel("BACK"); return true }
                    panelVisible -> { hideChannelList(); return true }
                    banner.visibility == View.VISIBLE -> { handler.removeCallbacks(hideBanner); hideBanner.run(); return true }
                    playingRecording != null -> { recordingAction("live", null); return true }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Live pause keys. ⏯/⏸ enter live pause; while paused: ◀ ▶ seek (hold – larger
     * step), ▼ slider, and with the slider visible OK – pause/play, ▲ – live, BACK – hide slider; ⏹ – live.
     */
    private fun onTimeshiftKey(keyCode: Int, event: KeyEvent, channelListVisible: Boolean): Boolean {
        if (!timeshift.active) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) { startPause(); return true }
            return false
        }
        if (channelListVisible) return false
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> timeshift.togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> timeshift.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> timeshift.resume()
            KeyEvent.KEYCODE_MEDIA_STOP -> timeshift.goLive()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> timeshift.seekBy(TimeShiftController.STEP_HELD_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> timeshift.seekBy(-TimeShiftController.STEP_HELD_MS)
            // At info panel level 3 left/right arrows move focus between buttons.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (infoLevel == 3) return false
                val step = if (event.repeatCount > 0) TimeShiftController.STEP_HELD_MS else TimeShiftController.STEP_MS
                timeshift.seekBy(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            }
            // With the banner open (level 1–2) OK pauses/resumes instead of opening the channel list.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (infoLevel in 1..2 && digits.isEmpty()) { timeshift.togglePause(); scheduleInfoHide() } else return false
            else -> return false
        }
        return true
    }

    // ---- TV input events ----

    private inner class PlayerCallback : TvView.TvInputCallback() {
        override fun onVideoAvailable(inputId: String) {
            if (prober?.onEvent("onVideoAvailable", available = true) == true) return
            videoState = "VIDEO_AVAILABLE"
            onPlayerEvent("onVideoAvailable after ${(SystemClock.elapsedRealtime() - tunedAt)} ms")
            audio.apply("video")
            logAudioTracks()
            showStatus(null)
            playingRecording?.let {
                showStatusFor(getString(R.string.recording_playing, it.title), 5000)
                // Subsequent onVideoAvailable calls (e.g. after buffering) do not restart the countdown.
                if (!recordingPaused && !recordingClockRunning) scheduleRecordingEnd()
            }
            applyPreferredTracks()
        }

        override fun onVideoUnavailable(inputId: String, reason: Int) {
            val (label, text) = when (reason) {
                TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "TUNING" to R.string.status_tuning
                TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "WEAK_SIGNAL" to R.string.status_weak_signal
                TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "BUFFERING" to R.string.status_buffering
                TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "AUDIO_ONLY" to R.string.status_audio_only
                VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED -> "NOT_CONNECTED" to R.string.status_not_connected
                in CAS_REASONS -> "CAS($reason)" to R.string.status_cas
                // dtvinput reports UNKNOWN for a moment right after a tune – that is still tuning, not "no picture".
                else -> "UNKNOWN($reason)" to when {
                    SystemClock.elapsedRealtime() - tunedAt < TUNE_UNKNOWN_GRACE_MS && channels.getOrNull(currentIndex)?.iptv != true -> R.string.status_tuning
                    channels.getOrNull(currentIndex)?.iptv == true -> R.string.status_stream_failed
                    else -> R.string.status_unknown
                }
            }
            prober?.let {
                it.onEvent("onVideoUnavailable $label", reason = reason)
                return
            }
            videoState = "UNAVAILABLE:$label"
            onPlayerEvent("onVideoUnavailable $label")
            // For an internet stream the reason helps: e.g. "HTTP 403" usually means the stream is blocked outside its country.
            val why = IptvInputService.lastError.takeIf { text == R.string.status_stream_failed }
            showStatus(getString(text) + (why?.let { " ($it)" } ?: ""))
            if (reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY) {
                applyPreferredTracks()
                audio.apply("radio")
                logAudioTracks()
            }
        }

        override fun onVideoSizeChanged(inputId: String, width: Int, height: Int) {
            if (prober?.onEvent("onVideoSizeChanged ${width}x$height") == true) return
            videoSize = width to height
            onPlayerEvent("onVideoSizeChanged ${width}x$height")
            if (banner.visibility == View.VISIBLE) updateDetails(null)
        }

        override fun onTracksChanged(inputId: String, tracks: MutableList<TvTrackInfo>?) {
            if (prober?.onTracks(tracks.orEmpty()) == true) return
            onPlayerEvent("onTracksChanged " + tracks.orEmpty().joinToString { "${trackType(it.type)}:${it.id}:${it.language}" })
        }

        override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
            onPlayerEvent("onTrackSelected ${trackType(type)} $trackId")
            if (infoLevel == 3) updateOptionButtons()
            if (menu.visible) menu.refresh()
        }

        override fun onContentBlocked(inputId: String, rating: TvContentRating) {
            onPlayerEvent("onContentBlocked ${rating.flattenToString()}")
            showStatus(getString(R.string.status_blocked))
        }

        override fun onContentAllowed(inputId: String) {
            onPlayerEvent("onContentAllowed")
            showStatus(null)
        }

        override fun onConnectionFailed(inputId: String) {
            if (prober?.onEvent("onConnectionFailed") == true) return
            videoState = "CONNECTION_FAILED"
            onPlayerEvent("onConnectionFailed $inputId")
            showStatus(getString(R.string.status_input_failed))
        }

        override fun onDisconnected(inputId: String) {
            videoState = "DISCONNECTED"
            onPlayerEvent("onDisconnected $inputId")
            showStatus(getString(R.string.status_input_disconnected))
        }

        override fun onChannelRetuned(inputId: String, channelUri: Uri?) {
            if (prober?.onEvent("onChannelRetuned $channelUri") == true) return
            onPlayerEvent("onChannelRetuned $channelUri")
            val id = channelUri?.takeIf { TvContract.isChannelUri(it) }?.lastPathSegment?.toLongOrNull() ?: return
            val index = channels.indexOfFirst { it.id == id }
            if (index >= 0 && index != currentIndex) {
                currentIndex = index
                adapter.notifyDataSetChanged()
                showBanner()
            }
        }

        override fun onTimeShiftStatusChanged(inputId: String, status: Int) {
            tsStatus = status
            onPlayerEvent("onTimeShiftStatusChanged $status")
        }
    }

    private fun trackType(type: Int) = when (type) {
        TvTrackInfo.TYPE_AUDIO -> "A"
        TvTrackInfo.TYPE_VIDEO -> "V"
        TvTrackInfo.TYPE_SUBTITLE -> "S"
        else -> type.toString()
    }

    companion object {
        private const val REPORT_PROMPT_DELAY_MS = 1500L
        private const val REQUEST_PERMISSION = 1
        private const val PREF_LAST_CHANNEL = "last_channel_id"
        private const val PREF_AUDIO_LANG = "audio_lang"
        private const val PREF_SUBTITLE_LANG = "subtitle_lang"
        private const val PREF_DEBUG_OVERLAY = "debug_overlay"
        private const val SUBTITLES_OFF = "off"
        private const val BANNER_MS = 5000L
        private val INFO_PANEL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
        )
        private const val SIGNAL_BANNER_MS = 30_000L
        private const val REQUEST_STORAGE = 2
        private const val REQUEST_GUIDE = 3
        /** The instance that set the static hooks (TsProbe, SignalMonitor, PlayerRemote). */
        private var current: java.lang.ref.WeakReference<MainActivity>? = null
        /** Scan screen opened automatically (no channels) – once per process, otherwise BACK loops back to it. */
        private var autoScanOpened = false
        private const val REQUEST_RECORDINGS = 4
        private const val PREF_STORAGE_ASKED = "storage_asked"
        private const val PREF_FIRST_SCAN_OPENED = "first_scan_opened"
        private const val PREF_COLOUR_KEYS_OFFERED = "colour_keys_offered"
        private const val COLOUR_KEYS_OFFER_DELAY_MS = 3000L
        private const val STORAGE_ASK_INTERVAL_MS = 24 * 3600_000L
        private const val PREF_SAME_MUX_BUSY = "same_mux_busy"
        /** Time for playback to settle after a channel switch before recording. */
        private const val IDENTIFY_SETTLE_MS = 3500L
        /** Switch within a multiplex – the frontend is already tuned. */
        private const val IDENTIFY_SETTLE_SAME_MUX_MS = 1500L
        /** Margin after the scheduled programme end – programmes often run late. */
        private const val RECORD_MARGIN_MS = 5 * 60_000L
        private const val RECORDING_END_LIVE_MS = 6000L
        /** Time to switch the player before a scheduled recording starts. */
        private const val SWITCH_BEFORE_RECORDING_MS = 3000L
        private const val SETUP_DONE_MS = 8000L
        private const val TUNE_UNKNOWN_GRACE_MS = 2000L
        private const val SIGNAL_REFRESH_MS = 1000L
        private const val DIGIT_TIMEOUT_MS = 1500L

        private const val PROBE_TIMEOUT_MS = 8000L
        private const val UNKNOWN_GRACE_MS = 1500L
        private const val TRACKS_GRACE_MS = 4000L

        /** CAS denial reasons (TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOLUTION..CAS_UNKNOWN, API 31). */
        private val CAS_REASONS = 6..18
        private const val MAX_MISSES = 8
        private const val MAX_MISSES_BEFORE_FIRST = 20
        private const val MAX_PROBES = 600
        private const val COARSE_MAX = 3000L
        private const val COARSE_STEP = 30L
        private const val BLOCK_SPAN = 60L

        /** TvInputManager.VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED, public since API 31. */
        private const val VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED = 5
    }
}
