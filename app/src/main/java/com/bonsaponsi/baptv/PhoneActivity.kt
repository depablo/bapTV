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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The phone's own screen for internet channels (IPTV): a touch list with search, a player under it and adding of
 * M3U playlists by address. Plays the same streams as the TV ([IptvInputService]), with the same headers, but
 * straight into a [PlayerView] – there is no TV input on a phone.
 */
class PhoneActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("phone-iptv")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PhoneIptvStore
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var list: ListView
    private var player: ExoPlayer? = null

    private var streams: List<Iptv.Stream> = emptyList()
    /** The streams the list shows – all of them, or the ones matching the search. */
    private var shown: List<Iptv.Stream> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone)
        playerView = findViewById(R.id.phone_player)
        status = findViewById(R.id.phone_status)
        search = findViewById(R.id.phone_search)
        list = findViewById(R.id.phone_list)
        store = PhoneIptvStore(this)

        list.adapter = Adapter()
        list.emptyView = findViewById(R.id.phone_empty)
        list.setOnItemClickListener { _, _, position, _ -> play(shown[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> confirmRemove(shown[position]); true }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        findViewById<Button>(R.id.phone_add).setOnClickListener { askForPlaylist() }

        streams = store.load()
        applyFilter()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent ?: Iptv.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        stream.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
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
            showResult(try {
                addText(Iptv.download(address, MAX_PLAYLIST), source = address)
            } catch (e: IOException) {
                DebugLog.warn("PhoneIptv", "cannot load playlist", e)
                getString(R.string.phone_add_error, e.message ?: e.javaClass.simpleName)
            })
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
            showResult(try {
                val text = contentResolver.openInputStream(uri)?.use { readLimited(it, MAX_PLAYLIST) }
                    ?: throw IOException("cannot open the file")
                addText(text, source = displayName(uri))
            } catch (e: IOException) {
                DebugLog.warn("PhoneIptv", "cannot read playlist file", e)
                getString(R.string.phone_add_error, e.message ?: e.javaClass.simpleName)
            })
        }
    }

    /** Stores the channels of a playlist text; returns the message for the user. Runs on the worker. */
    private fun addText(text: String, source: String): String {
        val playlist = Iptv.parseM3u(text)
        if (playlist.streams.isEmpty()) return getString(R.string.phone_add_empty)
        // Kept with each channel, as the TV keeps the address or file it was added from.
        val added = store.add(playlist.streams.map { it.copy(source = source) })
        return getString(R.string.phone_added, added)
    }

    private fun showResult(message: String) {
        handler.post {
            if (isDestroyed) return@post
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            streams = store.load()
            applyFilter()
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Reads at most [max] bytes as UTF-8 text; a bigger file is refused rather than cut. */
    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (out.size() <= max) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        if (out.size() > max) throw IOException("playlist too large")
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun confirmRemove(stream: Iptv.Stream) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.phone_remove_title, stream.name))
            .setPositiveButton(R.string.phone_remove) { _, _ ->
                store.remove(stream.url)
                streams = store.load()
                applyFilter()
            }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_phone_channel, parent, false)
            val stream = shown[position]
            view.findViewById<TextView>(R.id.phone_item_name).text = stream.name.ifBlank { stream.url }
            view.findViewById<TextView>(R.id.phone_item_group).apply {
                text = stream.group
                visibility = if (stream.group.isEmpty()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    private companion object {
        const val REQUEST_FILE = 1
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 2_500
        const val REBUFFER_MS = 6_000
        const val MAX_PLAYLIST = 30 * 1024 * 1024
    }
}
