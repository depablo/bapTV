package com.bonsaponsi.baptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
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
import java.io.IOException

/**
 * The phone's own screen for internet channels (IPTV): a touch list with search, a player under it and adding of
 * M3U playlists by address. Plays the same streams as the TV ([IptvInputService]), with the same headers, but
 * straight into a [PlayerView] – there is no TV input on a phone. Files are read and written on workers: a
 * playlist can have tens of thousands of channels.
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
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private var player: ExoPlayer? = null

    private var streams: List<Iptv.Stream> = emptyList()
    /** The streams the list shows – all of them, or the ones matching the search. */
    private var shown: List<Iptv.Stream> = emptyList()
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
        if (DeviceCapabilities.isTelevision(this)) {
            redirected = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_phone)
        playerView = findViewById(R.id.phone_player)
        status = findViewById(R.id.phone_status)
        search = findViewById(R.id.phone_search)
        list = findViewById(R.id.phone_list)
        store = PhoneIptvStore(this)
        checks = PhoneIptvChecks(this)
        epg = PhoneEpg(this)

        list.adapter = Adapter()
        list.emptyView = findViewById(R.id.phone_empty)
        list.setOnItemClickListener { _, _, position, _ -> play(shown[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> channelMenu(shown[position]); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        findViewById<Button>(R.id.phone_add).setOnClickListener { askForPlaylist() }
        findViewById<Button>(R.id.phone_check_all).setOnClickListener { checkShown() }
        findViewById<Button>(R.id.phone_remote).setOnClickListener { startActivity(Intent(this, RemoteActivity::class.java)) }

        reloadList()
    }

    override fun onStart() {
        super.onStart()
        if (redirected) return
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

    /** Reads the channel list on the worker and shows it. */
    private fun reloadList(message: String? = null, refreshGuide: Boolean = false) {
        io.execute {
            val list = store.load()
            // Read here, not in the list's getView on the main thread.
            checks.load()
            handler.post {
                if (isDestroyed) return@post
                message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                streams = list
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
        shown = if (query.isEmpty()) streams else streams.filter {
            it.name.contains(query, ignoreCase = true) || it.group.contains(query, ignoreCase = true)
        }
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun play(stream: Iptv.Stream) {
        if (stream.drm) {
            status.text = getString(R.string.phone_drm, stream.name)
            return
        }
        releasePlayer()
        status.text = stream.name
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
                        status.text = getString(R.string.phone_play_error, stream.name)
                    }
                })
                p.setMediaItem(MediaItem.Builder().setUri(stream.url)
                    .setMimeType(IptvInputService.mimeType(stream.url)).build())
                p.playWhenReady = true
                p.prepare()
            }
    }

    private fun releasePlayer() {
        playerView.player = null
        playerView.keepScreenOn = false
        player?.release()
        player = null
    }

    private fun askForPlaylist() {
        val input = EditText(this).apply {
            hint = getString(R.string.phone_add_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.phone_add_title)
            .setView(input)
            .setPositiveButton(R.string.phone_add_ok) { _, _ -> addPlaylist(input.text.toString().trim()) }
            .setNeutralButton(R.string.phone_add_file) { _, _ -> pickFile() }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    private fun addPlaylist(address: String) {
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            Toast.makeText(this, R.string.phone_add_bad_address, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.phone_add_loading, Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                addText(Iptv.download(address, MAX_PLAYLIST), source = address)
            } catch (e: Exception) {
                DebugLog.warn("PhoneIptv", "cannot load playlist", e)
                reloadList(getString(R.string.phone_add_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun pickFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK && uri != null) addFile(uri)
    }

    private fun addFile(uri: Uri) {
        Toast.makeText(this, R.string.phone_add_loading, Toast.LENGTH_SHORT).show()
        io.execute {
            // Not only IOException: a document provider may also refuse the address (SecurityException).
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { with(Iptv) { it.readNBytesCompat(MAX_PLAYLIST + 1) } }
                    ?: throw IOException("cannot open the file")
                // A bigger file is refused rather than cut.
                if (bytes.size > MAX_PLAYLIST) throw IOException("playlist too large")
                addText(String(bytes, Charsets.UTF_8), source = displayName(uri))
            } catch (e: Exception) {
                DebugLog.warn("PhoneIptv", "cannot read playlist file", e)
                reloadList(getString(R.string.phone_add_error, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Stores the channels of a playlist text and shows the result. Runs on the worker. */
    private fun addText(text: String, source: String) {
        val playlist = Iptv.parseM3u(text)
        if (playlist.streams.isEmpty()) return reloadList(getString(R.string.phone_add_empty))
        // Kept with each channel, as the TV keeps the address or file it was added from.
        val added = store.add(playlist.streams.map { it.copy(source = source) })
        // The playlist's guide addresses are fetched now, so the new channels get their programmes at once.
        epg.addSources(playlist.epgUrls)
        reloadList(getString(R.string.phone_added, added), refreshGuide = added > 0)
    }

    /** Downloads the XMLTV guide in the background and redraws the list when it is ready. Main thread. */
    private fun refreshGuide() {
        if (guideRunning) { guideAgain = true; return }
        guideRunning = true
        guideIo.execute {
            try {
                epg.refresh(store.load())
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

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Long press on a channel: check it, or remove it. */
    private fun channelMenu(stream: Iptv.Stream) {
        AlertDialog.Builder(this)
            .setTitle(stream.name.ifBlank { stream.url })
            .setItems(arrayOf(getString(R.string.phone_check_one), getString(R.string.phone_remove))) { _, which ->
                if (which == 0) checkShown(listOf(stream)) else confirmRemove(stream)
            }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    private fun confirmRemove(stream: Iptv.Stream) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.phone_remove_title, stream.name))
            .setPositiveButton(R.string.phone_remove) { _, _ ->
                io.execute {
                    store.remove(stream.url)
                    checks.remove(stream.url)
                    reloadList()
                }
            }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    /** Checks the channels on screen (the first [CHECK_LIMIT] when there are more); one check at a time. */
    private fun checkShown(channels: List<Iptv.Stream> = shown) {
        if (checking || channels.isEmpty()) return
        val batch = channels.take(CHECK_LIMIT)
        checking = true
        status.text = getString(R.string.phone_check_running, batch.size)
        checkIo.execute {
            var results: List<IptvProbe.Result> = emptyList()
            try {
                results = IptvProbe.probeAll(batch)
                checks.put(batch.map { it.url }.zip(results), System.currentTimeMillis())
            } finally {
                handler.post {
                    checking = false
                    if (isDestroyed) return@post
                    status.text = when {
                        results.isEmpty() -> getString(R.string.phone_check_failed)
                        else -> {
                            val ok = results.count { it.status == IptvProbe.Status.OK }
                            val done = getString(R.string.phone_check_done, results.size, ok, results.size - ok)
                            if (channels.size > batch.size) done + " " + getString(R.string.phone_check_limit, batch.size, channels.size) else done
                        }
                    }
                    applyFilter()
                }
            }
        }
    }

    /** Label and colour of a check result, for the row. */
    private fun checkLabel(entry: PhoneIptvChecks.Entry): Pair<String, Int> {
        val (text, color) = when (entry.status) {
            IptvProbe.Status.OK -> R.string.phone_check_ok to R.color.status_ok
            IptvProbe.Status.SLOW -> R.string.phone_check_slow to R.color.status_warn
            IptvProbe.Status.BLOCKED -> R.string.phone_check_blocked to R.color.status_bad
            IptvProbe.Status.DEAD -> R.string.phone_check_dead to R.color.status_bad
            IptvProbe.Status.TIMEOUT -> R.string.phone_check_timeout to R.color.status_bad
            IptvProbe.Status.DRM -> R.string.phone_check_drm to R.color.status_warn
            IptvProbe.Status.ERROR -> R.string.phone_check_error to R.color.status_bad
        }
        val date = java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.checkedAt))
        return getString(R.string.phone_check_row, getString(text), date) to getColor(color)
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_phone_channel, parent, false)
            val stream = shown[position]
            view.findViewById<TextView>(R.id.phone_item_name).text = stream.name.ifBlank { stream.url }
            view.findViewById<TextView>(R.id.phone_item_now).apply {
                val now = epg.now(stream.url, System.currentTimeMillis())
                text = now?.let { getString(R.string.phone_now, it.title) }.orEmpty()
                visibility = if (now == null) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.phone_item_group).apply {
                text = stream.group
                visibility = if (stream.group.isEmpty()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.phone_item_check).apply {
                val entry = checks.load()[stream.url]
                if (entry == null) {
                    visibility = View.GONE
                } else {
                    val (label, color) = checkLabel(entry)
                    text = label
                    setTextColor(color)
                    visibility = View.VISIBLE
                }
            }
            return view
        }
    }

    private companion object {
        const val REQUEST_FILE = 1
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 2_500
        const val REBUFFER_MS = 6_000
        const val MAX_PLAYLIST = 30 * 1024 * 1024
        const val REDRAW_MIN_MS = 30_000L
        const val REDRAW_MAX_MS = 15 * 60_000L
        /** Each check can take up to several seconds; more channels than this would keep the phone busy for minutes. */
        const val CHECK_LIMIT = 50
    }
}
