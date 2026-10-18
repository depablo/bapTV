package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Recordings list ([Recorder]): OK – play (the result goes back to the player), right arrow – delete.
 * The recording in progress is at the top of the list; OK on it asks whether to stop it.
 */
class RecordingsActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var space: TextView
    private var items: List<Recorder.Entry> = emptyList()
    private val dateFormat by lazy { SimpleDateFormat("EEE d.MM, HH:mm", AppLanguage.locale(this)) }

    private val onRecorderChange: (Recorder.Entry?) -> Unit = { runOnUiThread { reload() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)
        list = findViewById(R.id.recordings_list)
        empty = findViewById(R.id.recordings_empty)
        space = findViewById(R.id.recordings_space)
        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, position, _ -> open(position) }
        reload()
        list.requestFocus()
        DebugLog.log("Rec", "opened recordings list (${items.size})")
    }

    override fun onStart() {
        super.onStart()
        Recorder.listeners += onRecorderChange
    }

    override fun onStop() {
        Recorder.listeners -= onRecorderChange
        super.onStop()
    }

    private fun reload() {
        items = RecordingScheduler.list(this) + Recorder.list(this)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val free = Recorder.freeBytes()
        space.text = if (free >= 0) getString(R.string.recordings_free, Formatter.formatShortFileSize(this, free), free / Recorder.bytesPerHour(items)) else ""
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && list.hasFocus() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_PROG_RED)) {
            items.getOrNull(list.selectedItemPosition)?.let { confirmDelete(it) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun open(position: Int) {
        val entry = items.getOrNull(position) ?: return
        when {
            entry.state == Recorder.Entry.SCHEDULED -> TvDialog.choose(this, entry.title,
                listOf(TvDialog.Option(getString(R.string.schedule_cancel)))) { RecordingScheduler.cancel(this, entry.id); reload() }
            Recorder.active?.id == entry.id -> TvDialog.choose(this, entry.title,
                listOf(TvDialog.Option(getString(R.string.recording_stop)))) { Recorder.stop() }
            entry.recordedUri == null || entry.file?.exists() != true -> TvDialog.confirm(this, entry.title,
                getString(R.string.recording_unavailable, entry.error ?: "–"), getString(R.string.recording_delete)) { delete(entry) }
            else -> {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RECORDING_ID, entry.id))
                finish()
            }
        }
    }

    private fun confirmDelete(entry: Recorder.Entry) {
        if (Recorder.active?.id == entry.id) return
        if (entry.state == Recorder.Entry.SCHEDULED) { open(items.indexOf(entry)); return }
        TvDialog.confirm(this, getString(R.string.recording_delete_title, entry.title), null,
            getString(R.string.recording_delete), focusCancel = true) { delete(entry) }
    }

    private val io = BackgroundWorker("recordings-io")

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Deletes the multi-gigabyte file in the background; the list refreshes when done. */
    private fun delete(entry: Recorder.Entry) {
        val position = list.selectedItemPosition
        io.execute {
            Recorder.delete(this, entry.id)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                reload()
                list.setSelection(position.coerceAtMost(items.size - 1).coerceAtLeast(0))
            }
        }
    }

    private fun describe(e: Recorder.Entry): String {
        if (e.state == Recorder.Entry.SCHEDULED) {
            val end = e.endMs?.let { " – " + SimpleDateFormat("HH:mm", AppLanguage.locale(this)).format(Date(it)) }.orEmpty()
            return "${e.channelNumber} ${e.channelName} · " + getString(R.string.recording_scheduled_at, dateFormat.format(Date(e.startMs)) + end)
        }
        val parts = mutableListOf("${e.channelNumber} ${e.channelName}", dateFormat.format(Date(e.startMs)))
        val end = e.endMs ?: System.currentTimeMillis()
        parts += getString(R.string.recording_minutes, ((end - e.startMs) / 60_000).coerceAtLeast(0))
        if (e.sizeBytes > 0) parts += Formatter.formatShortFileSize(this, e.sizeBytes)
        when {
            Recorder.active?.id == e.id -> parts += getString(R.string.recording_in_progress)
            e.state == Recorder.Entry.FAILED -> parts += getString(R.string.recording_failed, e.error ?: "–")
        }
        return parts.joinToString(" · ")
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_recording, parent, false)
            val e = items[position]
            view.findViewById<TextView>(R.id.rec_title).text = when {
                Recorder.active?.id == e.id -> "● " + e.title
                e.state == Recorder.Entry.SCHEDULED -> "◷ " + e.title
                else -> e.title
            }
            view.findViewById<TextView>(R.id.rec_meta).text = describe(e)
            view.alpha = if (e.state == Recorder.Entry.FAILED) 0.5f else 1f
            return view
        }
    }

    companion object {
        const val EXTRA_RECORDING_ID = "recording_id"
    }
}
