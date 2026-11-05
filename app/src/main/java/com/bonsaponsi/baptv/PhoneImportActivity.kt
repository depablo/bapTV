package com.bonsaponsi.baptv

import android.app.Activity
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.io.IOException

/**
 * Adding channels from an M3U playlist on the phone (address in [EXTRA_URL], or a file as the intent data).
 * The playlist is shown first: the user can check the channels ([IptvProbe], over the phone's connection), select
 * the ones that work and add only those. Channels already on the phone's list are marked and cannot be selected
 * again. Returns [RESULT_ADDED] and the playlist's guide addresses ([RESULT_GUIDES]) to [PhoneActivity].
 */
class PhoneImportActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    /** One playlist entry: [known] – already on the list; [result] – of the check, when done. */
    private class Entry(val stream: Iptv.Stream, val known: Boolean) {
        var selected = false
        /** Written by the check worker, read by the list. */
        @Volatile var result: IptvProbe.Result? = null
    }

    private val io = BackgroundWorker("phone-import")
    private val checkIo = BackgroundWorker("phone-import-check")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PhoneIptvStore
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var checkButton: Button
    private lateinit var addButton: Button

    private var entries: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private var guides: List<String> = emptyList()
    /** A check in progress; set to false by "Stop" – the worker ends after the current batch. */
    @Volatile private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_import)
        store = PhoneIptvStore.get(this)
        status = findViewById(R.id.import_status)
        progress = findViewById(R.id.import_progress)
        search = findViewById(R.id.import_search)
        list = findViewById(R.id.import_list)
        checkButton = findViewById(R.id.import_check)
        addButton = findViewById(R.id.import_add)
        findViewById<TextView>(R.id.phone_header_title).setText(R.string.import_title)
        findViewById<View>(R.id.phone_header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }

        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, position, _ ->
            val entry = shown[position]
            if (!entry.known) {
                entry.selected = !entry.selected
                refresh()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        checkButton.setOnClickListener { if (checking) checking = false else check() }
        findViewById<Button>(R.id.import_select_working).setOnClickListener {
            shown.forEach { if (!it.known && it.result != null) it.selected = it.result?.status == IptvProbe.Status.OK }
            refresh()
        }
        findViewById<Button>(R.id.import_select_all).setOnClickListener { shown.forEach { if (!it.known) it.selected = true }; refresh() }
        findViewById<Button>(R.id.import_select_none).setOnClickListener { shown.forEach { it.selected = false }; refresh() }
        addButton.setOnClickListener { add() }
        setControlsEnabled(false)
        load()
    }

    override fun onDestroy() {
        checking = false
        io.shutdownNow()
        checkIo.shutdownNow()
        super.onDestroy()
    }

    /** Reads the playlist (address or file) on the worker. */
    private fun load() {
        val url = intent.getStringExtra(EXTRA_URL)
        val uri = intent.data
        io.execute {
            val outcome = runCatching {
                val (text, source) = when {
                    url != null -> Iptv.download(url, MAX_PLAYLIST) to url
                    uri != null -> readFile(uri) to displayName(uri)
                    else -> throw IOException("no playlist")
                }
                val playlist = Iptv.parseM3u(text)
                val known = store.urls()
                // Kept with each channel, as the TV keeps the address or file it was added from.
                playlist.streams.distinctBy { it.url }.map { Entry(it.copy(source = source), it.url in known) } to playlist.epgUrls
            }
            handler.post {
                if (isDestroyed) return@post
                progress.visibility = View.GONE
                outcome.onSuccess { (loaded, epg) ->
                    entries = loaded
                    guides = epg
                    if (loaded.isEmpty()) {
                        status.setText(R.string.phone_add_empty)
                    } else {
                        setControlsEnabled(true)
                        applyFilter()
                    }
                }.onFailure {
                    DebugLog.warn("PhoneIptv", "cannot load playlist", it)
                    status.text = getString(R.string.phone_add_error, it.message ?: it.javaClass.simpleName)
                }
            }
        }
    }

    // Not only IOException: a document provider may also refuse the address (SecurityException).
    private fun readFile(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { with(Iptv) { it.readNBytesCompat(MAX_PLAYLIST + 1) } }
            ?: throw IOException("cannot open the file")
        // A bigger file is refused rather than cut.
        if (bytes.size > MAX_PLAYLIST) throw IOException("playlist too large")
        return String(bytes, Charsets.UTF_8)
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    private fun setControlsEnabled(on: Boolean) {
        listOf(R.id.import_check, R.id.import_select_working, R.id.import_select_all, R.id.import_select_none)
            .forEach { findViewById<View>(it).isEnabled = on }
        search.isEnabled = on
    }

    private fun applyFilter() {
        val query = search.text.toString().trim()
        shown = if (query.isEmpty()) entries else entries.filter {
            it.stream.name.contains(query, ignoreCase = true) || it.stream.group.contains(query, ignoreCase = true)
        }
        refresh()
    }

    /** Redraws the list, the summary line and the Add button. */
    private fun refresh() {
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        val selected = entries.count { it.selected }
        addButton.isEnabled = selected > 0 && !checking
        addButton.text = if (selected > 0) getString(R.string.import_add, selected) else getString(R.string.import_add_none)
        if (!checking) status.text = summary()
    }

    private fun summary(): String {
        val checked = entries.filter { it.result != null }
        val known = entries.count { it.known }
        return buildString {
            append(getString(R.string.import_summary, entries.size, known))
            if (checked.isNotEmpty()) {
                append(" ")
                append(getString(R.string.import_checked, checked.size, checked.count { it.result?.status == IptvProbe.Status.OK }))
            }
        }
    }

    /**
     * Checks the channels on screen that were not checked yet, a batch at a time, so the progress shows and
     * "Stop" ends it after the current batch.
     */
    private fun check() {
        val todo = shown.filter { it.result == null && !it.known }
        if (todo.isEmpty()) return
        checking = true
        checkButton.setText(R.string.import_stop)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.max = todo.size
        progress.progress = 0
        status.text = getString(R.string.import_checking, 0, todo.size)
        addButton.isEnabled = false
        checkIo.execute {
            var done = 0
            try {
                for (batch in todo.chunked(BATCH)) {
                    if (!checking) break
                    val results = IptvProbe.probeAll(batch.map { it.stream })
                    batch.zip(results).forEach { (entry, result) -> entry.result = result }
                    done += batch.size
                    val count = done
                    handler.post {
                        if (isDestroyed) return@post
                        progress.progress = count
                        status.text = getString(R.string.import_checking, count, todo.size)
                        (list.adapter as BaseAdapter).notifyDataSetChanged()
                    }
                }
            } finally {
                handler.post {
                    checking = false
                    if (isDestroyed) return@post
                    checkButton.setText(R.string.import_check)
                    progress.visibility = View.GONE
                    refresh()
                }
            }
        }
    }

    private fun add() {
        val chosen = entries.filter { it.selected && !it.known }
        if (chosen.isEmpty()) return
        addButton.isEnabled = false
        io.execute {
            val added = store.add(chosen.map { it.stream })
            // The check results come along, so the list shows them at once.
            val checked = chosen.mapNotNull { e -> e.result?.let { e.stream.url to it } }
            if (checked.isNotEmpty()) PhoneIptvChecks.get(this).put(checked, System.currentTimeMillis())
            handler.post {
                if (isDestroyed) return@post
                setResult(RESULT_OK, Intent().putExtra(RESULT_ADDED, added).putStringArrayListExtra(RESULT_GUIDES, ArrayList(guides)))
                finish()
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_phone_import, parent, false)
            val entry = shown[position]
            view.findViewById<CheckBox>(R.id.import_item_tick).apply {
                isChecked = entry.selected || entry.known
                isEnabled = !entry.known
            }
            view.findViewById<TextView>(R.id.import_item_name).apply {
                text = entry.stream.name.ifBlank { entry.stream.url }
                alpha = if (entry.known) 0.5f else 1f
            }
            view.findViewById<TextView>(R.id.import_item_group).apply {
                text = entry.stream.group
                visibility = if (entry.stream.group.isEmpty()) View.GONE else View.VISIBLE
            }
            view.findViewById<TextView>(R.id.import_item_result).apply {
                val result = entry.result
                when {
                    entry.known -> {
                        setText(R.string.import_known)
                        setTextColor(getColor(R.color.text_secondary))
                        visibility = View.VISIBLE
                    }
                    result != null -> {
                        val (label, color) = checkLabel(result.status)
                        text = label
                        setTextColor(color)
                        visibility = View.VISIBLE
                    }
                    entry.stream.drm -> {
                        setText(R.string.phone_check_drm)
                        setTextColor(getColor(R.color.status_warn))
                        visibility = View.VISIBLE
                    }
                    else -> visibility = View.GONE
                }
            }
            return view
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        const val RESULT_ADDED = "added"
        const val RESULT_GUIDES = "guides"
        private const val MAX_PLAYLIST = 30 * 1024 * 1024
        /** Channels checked at once ([IptvProbe] runs them in parallel); between batches the progress shows. */
        private const val BATCH = 12
    }
}
