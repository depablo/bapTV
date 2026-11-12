package com.bonsaponsi.baptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * The phone's own screen for internet channels (IPTV): a touch list with search and a player above it, which the
 * player's button turns into full screen (landscape, no system bars). Plays the same streams as the TV
 * ([IptvInputService]), with the same headers, but straight into a [PlayerView] – there is no TV input on a phone.
 * Adding channels ([PhoneImportActivity]) and organising them ([PhoneChannelsActivity]) have screens of their own;
 * the menu also has checks, updates, language and the set-top box remote. Files are read and written on workers:
 * a playlist can have tens of thousands of channels.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PhoneActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("phone-iptv")
    /** The guide download takes minutes – adding or removing channels must not wait for it. */
    private val guideIo = BackgroundWorker("phone-epg")
    /** Checks take seconds per channel – they run apart from the list and the guide. */
    private val checkIo = BackgroundWorker("phone-check")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PhoneIptvStore
    private lateinit var checks: PhoneIptvChecks
    private lateinit var epg: PhoneEpg
    private lateinit var updates: PhoneUpdates
    private lateinit var header: View
    private lateinit var body: View
    private lateinit var playerBox: View
    private lateinit var playerHint: View
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private var player: ExoPlayer? = null
    private var playing: PhoneIptvStore.Channel? = null
    private var fullScreen = false

    /** Channels on the list – the hidden ones are left out. */
    private var channels: List<PhoneIptvStore.Channel> = emptyList()
    /** The channels the list shows – all of them, or the ones matching the search. */
    private var shown: List<PhoneIptvStore.Channel> = emptyList()
    /** Started from a TV launcher: sent on to the TV player, the views below are never created. */
    private var redirected = false
    private var guideRunning = false
    /** A refresh was asked for while one was running (new playlist) – run again after it. */
    private var guideAgain = false
    private var checking = false

    /** Redraws the "now" lines when the first programme on screen ends. */
    private val redrawNow = Runnable { applyFilter(); scheduleRedraw() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launchers that list LAUNCHER activities (also on TV boxes) start this one: a TV gets its own player.
        // The phone build has no TV player.
        if (BuildConfig.TV && DeviceCapabilities.isTelevision(this)) {
            redirected = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_phone)
        header = findViewById(R.id.phone_header)
        body = findViewById(R.id.phone_body)
        playerBox = findViewById(R.id.phone_player_box)
        playerHint = findViewById(R.id.phone_player_hint)
        playerView = findViewById(R.id.phone_player)
        title = findViewById(R.id.phone_title)
        status = findViewById(R.id.phone_status)
        search = findViewById(R.id.phone_search)
        list = findViewById(R.id.phone_list)
        store = PhoneIptvStore.get(this)
        checks = PhoneIptvChecks.get(this)
        epg = PhoneEpg(this)
        updates = PhoneUpdates(this, io)

        list.adapter = Adapter()
        list.emptyView = findViewById(R.id.phone_empty)
        list.setOnItemClickListener { _, _, position, _ -> play(shown[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> channelMenu(shown[position]); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        findViewById<View>(R.id.phone_add).setOnClickListener { askForPlaylist() }
        findViewById<View>(R.id.phone_header_remote).apply {
            visibility = View.VISIBLE
            setOnClickListener { startActivity(Intent(this@PhoneActivity, RemoteActivity::class.java)) }
        }
        findViewById<View>(R.id.phone_header_menu).apply {
            visibility = View.VISIBLE
            setOnClickListener { showMenu(it) }
        }
        // The player's own full-screen button; its state is the source of truth (see leaveFullScreen).
        playerView.setFullscreenButtonClickListener { on -> setFullScreen(on) }
        sizePlayer()
    }

    override fun onStart() {
        super.onStart()
        if (redirected) return
        // Back from the import or management screen: the list may have changed.
        reloadList()
        // Refreshes the guide when it is stale (see PhoneEpg.isStale); otherwise only redraws the "now" lines.
        guideIo.execute {
            epg.load()
            val stale = epg.isStale(System.currentTimeMillis())
            handler.post {
                if (isDestroyed) return@post
                if (stale) refreshGuide()
                applyFilter()
                scheduleRedraw()
            }
        }
        updates.check(manual = false)
    }

    override fun onStop() {
        if (!redirected) {
            handler.removeCallbacks(redrawNow)
            releasePlayer()
        }
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        guideIo.shutdownNow()
        checkIo.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!redirected) sizePlayer()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullScreen) leaveFullScreen() else super.onBackPressed()
    }

    /** The player box: 16:9 under the bar, or the whole screen. */
    private fun sizePlayer() {
        playerBox.layoutParams = playerBox.layoutParams.apply {
            height = if (fullScreen) ViewGroup.LayoutParams.MATCH_PARENT else resources.displayMetrics.widthPixels * 9 / 16
        }
    }

    private fun setFullScreen(on: Boolean) {
        DebugLog.log("Phone", "full screen: $on")
        fullScreen = on
        header.visibility = if (on) View.GONE else View.VISIBLE
        body.visibility = if (on) View.GONE else View.VISIBLE
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (on) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Otherwise the place of the hidden navigation bar stays empty.
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.let {
                if (on) {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.systemBars())
                } else {
                    it.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else 0
        }
        sizePlayer()
    }

    /**
     * Back in full screen: goes through the player's button, which keeps its own on/off state (media3 1.4 cannot
     * set it from outside) and then calls [setFullScreen].
     */
    private fun leaveFullScreen() {
        val button = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)
        if (button != null) button.performClick() else setFullScreen(false)
    }

    /** Reads the channel list on the worker and shows it. */
    private fun reloadList(message: String? = null, refreshGuide: Boolean = false) {
        io.execute {
            val all = store.load()
            // Read here, not in the list's getView on the main thread.
            checks.load()
            handler.post {
                if (isDestroyed) return@post
                message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                channels = all.filter { !it.hidden }
                applyFilter()
                if (refreshGuide) refreshGuide()
            }
        }
    }

    private fun scheduleRedraw() {
        handler.removeCallbacks(redrawNow)
        val now = System.currentTimeMillis()
        val next = epg.nextChangeMs(now) ?: return
        handler.postDelayed(redrawNow, (next - now + 1_000).coerceIn(REDRAW_MIN_MS, REDRAW_MAX_MS))
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        shown = if (query.isEmpty()) channels else channels.filter {
            it.title.contains(query, ignoreCase = true) || it.stream.group.contains(query, ignoreCase = true)
        }
        // Nothing found is not the same as nothing added.
        (list.emptyView as TextView).text = if (channels.isNotEmpty() && query.isNotEmpty()) getString(R.string.phone_no_match, query)
            else getString(R.string.phone_empty)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        showPlaying()
    }

    /** The channel playing and its programme on now, under the player. */
    private fun showPlaying() {
        val channel = playing ?: return
        title.text = channel.title
        title.visibility = View.VISIBLE
        val now = epg.now(channel.url, System.currentTimeMillis())
        if (now != null && status.tag == null) showStatus(getString(R.string.phone_now, now.title))
    }

    /** A line under the player; [sticky] keeps it from being replaced by the programme on now (errors, checks). */
    private fun showStatus(text: String, sticky: Boolean = false) {
        status.text = text
        status.tag = if (sticky) text else null
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun play(channel: PhoneIptvStore.Channel) {
        val stream = channel.stream
        playing = channel
        showStatus("")
        if (stream.drm) {
            showPlaying()
            showStatus(getString(R.string.phone_drm, channel.title), sticky = true)
            return
        }
        releasePlayer()
        playerHint.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(IptvInputService.httpFactory(stream)))
            // A phone has less memory than the TV, so the buffer is smaller.
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS).build())
            .build().also { p ->
                playerView.player = p
                playerView.keepScreenOn = true
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        DebugLog.warn("PhoneIptv", "play \"${stream.name}\" failed", error)
                        showStatus(getString(R.string.phone_play_error, channel.title), sticky = true)
                    }
                })
                p.setMediaItem(MediaItem.Builder().setUri(stream.url)
                    .setMimeType(IptvInputService.mimeType(stream.url)).build())
                p.playWhenReady = true
                p.prepare()
            }
        showPlaying()
    }

    private fun releasePlayer() {
        playerView.player = null
        playerView.keepScreenOn = false
        player?.release()
        player = null
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        menu.add(Menu.NONE, MENU_MANAGE, 0, R.string.phone_menu_manage)
        menu.add(Menu.NONE, MENU_ADD, 1, R.string.phone_add)
        menu.add(Menu.NONE, MENU_CHECK, 2, R.string.phone_menu_check)
        menu.add(Menu.NONE, MENU_REMOTE, 3, R.string.phone_menu_remote)
        if (UpdateChecker.supported(this)) {
            menu.add(Menu.NONE, MENU_UPDATE, 4, R.string.menu_update_check)
            menu.add(Menu.NONE, MENU_UPDATE_AUTO, 5, R.string.menu_update_auto).apply {
                isCheckable = true
                isChecked = UpdateChecker.autoEnabled(this@PhoneActivity)
            }
        }
        menu.add(Menu.NONE, MENU_LANGUAGE, 6, getString(R.string.phone_menu_language, AppLanguage.label(this, AppLanguage.get(this))))
        menu.add(Menu.NONE, MENU_ABOUT, 7, R.string.menu_about)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MANAGE -> startActivity(Intent(this, PhoneChannelsActivity::class.java))
                MENU_ADD -> askForPlaylist()
                MENU_CHECK -> checkShown()
                MENU_REMOTE -> startActivity(Intent(this, RemoteActivity::class.java))
                MENU_UPDATE -> updates.check(manual = true)
                MENU_UPDATE_AUTO -> UpdateChecker.setAutoEnabled(this, !UpdateChecker.autoEnabled(this))
                MENU_LANGUAGE -> chooseLanguage()
                MENU_ABOUT -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun chooseLanguage() {
        val codes = AppLanguage.SUPPORTED
        val current = codes.indexOf(AppLanguage.get(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(codes.map { AppLanguage.label(this, it) }.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which != current) {
                    AppLanguage.set(this, codes[which])
                    recreate()
                }
            }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title, BuildConfig.VERSION_NAME))
            .setMessage(getString(R.string.phone_about_message, BuildConfig.CONTACT_EMAIL, BuildConfig.PROJECT_URL, BuildConfig.TARGET_SDK.toString()))
            .setPositiveButton(R.string.phone_about_open) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PROJECT_URL))) }
                    .onFailure { DebugLog.warn("Phone", "no browser for the project page", it) }
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun askForPlaylist() {
        val input = EditText(this).apply {
            hint = getString(R.string.phone_add_hint)
            setSingleLine()
            // An address: no autocorrection, the keyboard with "/" and ".".
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.phone_add_title)
            .setMessage(R.string.phone_add_message)
            .setView(input)
            .setPositiveButton(R.string.phone_add_ok) { _, _ -> openImport(input.text.toString().trim()) }
            .setNeutralButton(R.string.phone_add_file) { _, _ -> pickFile() }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    private fun openImport(address: String) {
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            Toast.makeText(this, R.string.phone_add_bad_address, Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(Intent(this, PhoneImportActivity::class.java).putExtra(PhoneImportActivity.EXTRA_URL, address), REQUEST_IMPORT)
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_FILE -> data.data?.let { uri ->
                startActivityForResult(Intent(this, PhoneImportActivity::class.java).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_IMPORT)
            }
            REQUEST_IMPORT -> {
                val added = data.getIntExtra(PhoneImportActivity.RESULT_ADDED, 0)
                val guides = data.getStringArrayListExtra(PhoneImportActivity.RESULT_GUIDES).orEmpty()
                // The playlist's guide addresses are fetched now, so the new channels get their programmes at once.
                guideIo.execute {
                    epg.addSources(guides)
                    handler.post { if (!isDestroyed) reloadList(getString(R.string.phone_added, added), refreshGuide = added > 0) }
                }
            }
        }
    }

    /** Downloads the XMLTV guide in the background and redraws the list when it is ready. Main thread. */
    private fun refreshGuide() {
        if (guideRunning) { guideAgain = true; return }
        guideRunning = true
        guideIo.execute {
            try {
                epg.refresh(store.streams())
            } finally {
                handler.post {
                    guideRunning = false
                    if (isDestroyed) return@post
                    applyFilter()
                    scheduleRedraw()
                    if (guideAgain) { guideAgain = false; refreshGuide() }
                }
            }
        }
    }

    /** Long press on a channel: check, rename, hide or remove it. */
    private fun channelMenu(channel: PhoneIptvStore.Channel) {
        val actions = listOf(
            getString(R.string.phone_check_one) to { checkShown(listOf(channel)) },
            getString(R.string.phone_manage_rename) to { PhoneChannelsActivity.askRename(this, channel) { label -> change { store.rename(channel.url, label) } } },
            getString(R.string.phone_manage_hide) to { change { store.setHidden(channel.url, true) } },
            getString(R.string.phone_remove) to { confirmRemove(channel) },
            getString(R.string.phone_menu_manage) to { startActivity(Intent(this, PhoneChannelsActivity::class.java)) },
        )
        AlertDialog.Builder(this)
            .setTitle(channel.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    /** Changes the stored list on the worker, then shows it again. */
    private fun change(block: () -> Unit) = io.execute { block(); reloadList() }

    private fun confirmRemove(channel: PhoneIptvStore.Channel) {
        PhoneChannelsActivity.confirmRemove(this, channel) {
            change {
                store.remove(channel.url)
                checks.remove(channel.url)
            }
        }
    }

    /** Checks the channels on screen (the first [CHECK_LIMIT] when there are more); one check at a time. */
    private fun checkShown(channels: List<PhoneIptvStore.Channel> = shown) {
        if (checking || channels.isEmpty()) return
        val batch = channels.take(CHECK_LIMIT).map { it.stream }
        checking = true
        showStatus(getString(R.string.phone_check_running, batch.size), sticky = true)
        checkIo.execute {
            var results: List<IptvProbe.Result> = emptyList()
            try {
                results = IptvProbe.probeAll(batch)
                checks.put(batch.map { it.url }.zip(results), System.currentTimeMillis())
            } finally {
                handler.post {
                    checking = false
                    if (isDestroyed) return@post
                    showStatus(sticky = true, text = when {
                        results.isEmpty() -> getString(R.string.phone_check_failed)
                        else -> {
                            val ok = results.count { it.status == IptvProbe.Status.OK }
                            val done = getString(R.string.phone_check_done, results.size, ok, results.size - ok)
                            if (channels.size > batch.size) done + " " + getString(R.string.phone_check_limit, batch.size, channels.size) else done
                        }
                    })
                    applyFilter()
                }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_phone_channel, parent, false)
            val channel = shown[position]
            val stream = channel.stream
            view.findViewById<TextView>(R.id.phone_item_avatar).text = initial(channel.title)
            view.findViewById<TextView>(R.id.phone_item_name).apply {
                text = channel.title
                setTextColor(getColor(if (channel.url == playing?.url) R.color.brand_amber else R.color.text_primary))
            }
            view.findViewById<TextView>(R.id.phone_item_now).apply {
                val now = epg.now(stream.url, System.currentTimeMillis())
                text = now?.let { getString(R.string.phone_now, it.title) }.orEmpty()
                visibility = if (now == null) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.phone_item_group).apply {
                text = stream.group
                visibility = if (stream.group.isEmpty()) View.GONE else View.VISIBLE
            }
            showCheck(view.findViewById(R.id.phone_item_check), checks.load()[stream.url])
            return view
        }
    }

    private companion object {
        const val REQUEST_FILE = 1
        const val REQUEST_IMPORT = 2
        const val MENU_MANAGE = 1
        const val MENU_ADD = 2
        const val MENU_CHECK = 3
        const val MENU_REMOTE = 4
        const val MENU_UPDATE = 5
        const val MENU_UPDATE_AUTO = 6
        const val MENU_LANGUAGE = 7
        const val MENU_ABOUT = 8
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 2_500
        const val REBUFFER_MS = 6_000
        const val REDRAW_MIN_MS = 30_000L
        const val REDRAW_MAX_MS = 15 * 60_000L
        /** Each check can take up to several seconds; more channels than this would keep the phone busy for minutes. */
        const val CHECK_LIMIT = 50
    }
}

/** First letter or digit of a channel name, for the round badge in phone lists. */
internal fun initial(name: String): String =
    name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"

/** A check result as a coloured badge with its date ([PhoneIptvChecks]); hidden when the channel was never checked. */
internal fun Context.showCheck(view: TextView, entry: PhoneIptvChecks.Entry?) {
    if (entry == null) {
        view.visibility = View.GONE
        return
    }
    val (label, color) = checkLabel(entry.status)
    val date = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.checkedAt))
    view.text = getString(R.string.phone_check_row, label, date)
    view.setTextColor(color)
    view.visibility = View.VISIBLE
}

/** Label and colour of a check result. */
internal fun Context.checkLabel(status: IptvProbe.Status): Pair<String, Int> {
    val (text, color) = when (status) {
        IptvProbe.Status.OK -> R.string.phone_check_ok to R.color.status_ok
        IptvProbe.Status.SLOW -> R.string.phone_check_slow to R.color.status_warn
        IptvProbe.Status.BLOCKED -> R.string.phone_check_blocked to R.color.status_bad
        IptvProbe.Status.DEAD -> R.string.phone_check_dead to R.color.status_bad
        IptvProbe.Status.TIMEOUT -> R.string.phone_check_timeout to R.color.status_bad
        IptvProbe.Status.DRM -> R.string.phone_check_drm to R.color.status_warn
        IptvProbe.Status.ERROR -> R.string.phone_check_error to R.color.status_bad
    }
    return getString(text) to getColor(color)
}
