package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

/**
 * TV guide: channel list on the left, programmes of the selected channel (split into days) on the right
 * and the description of the selected programme at the bottom. OK on a channel or programme switches the player to that channel.
 */
class GuideActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private sealed class Row {
        class Day(val label: String) : Row()
        class Item(val programme: Programme) : Row()
        class Message(val text: String) : Row()
    }

    private val io = BackgroundWorker("guide-io")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var channelList: ListView
    private lateinit var programmeList: ListView
    private lateinit var clock: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailDesc: TextView
    private lateinit var repository: ChannelRepository

    private var channels: List<Channel> = emptyList()
    private var rows: List<Row> = emptyList()
    private var shownChannel: Channel? = null

    private val timeFormat by lazy { SimpleDateFormat("HH:mm", AppLanguage.locale(this)) }
    private val dayFormat by lazy { SimpleDateFormat("EEEE, d MMMM", AppLanguage.locale(this)) }

    private val tick = object : Runnable {
        override fun run() {
            clock.text = timeFormat.format(Date())
            (programmeList.adapter as? BaseAdapter)?.notifyDataSetChanged()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        channelList = findViewById(R.id.guide_channels)
        programmeList = findViewById(R.id.guide_programmes)
        clock = findViewById(R.id.guide_clock)
        detailTitle = findViewById(R.id.guide_detail_title)
        detailMeta = findViewById(R.id.guide_detail_meta)
        detailDesc = findViewById(R.id.guide_detail_desc)
        repository = ChannelRepository(this)

        channelList.adapter = ChannelAdapter()
        programmeList.adapter = ProgrammeAdapter()
        channelList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                channels.getOrNull(position)?.let { showChannel(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        channelList.setOnItemClickListener { _, _, position, _ -> channels.getOrNull(position)?.let { watch(it) } }
        programmeList.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (rows.getOrNull(position) as? Row.Item)?.let { showDetails(it.programme) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        programmeList.setOnItemClickListener { _, _, position, _ ->
            val channel = shownChannel ?: return@setOnItemClickListener
            programmeAction(channel, (rows.getOrNull(position) as? Row.Item)?.programme)
        }
        findViewById<View>(R.id.guide_menu).setOnClickListener { openPlayerMenu() }
        findViewById<View>(R.id.guide_diagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        val currentId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1)
        io.execute {
            val input = Dtv.findTunerInput(this) ?: return@execute
            val loaded = repository.loadChannels(input.id)
            handler.post {
                channels = loaded
                (channelList.adapter as BaseAdapter).notifyDataSetChanged()
                val index = loaded.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                channelList.setSelection(index)
                channelList.requestFocus()
                loaded.getOrNull(index)?.let { showChannel(it) }
            }
        }
        DebugLog.log("Guide", "opened TV guide")
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (channelList.hasFocus() && rows.any { it is Row.Item }) {
                programmeList.requestFocus()
                programmeList.setSelection(currentRowIndex())
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (programmeList.hasFocus()) { channelList.requestFocus(); return true }
            KeyEvent.KEYCODE_GUIDE -> { finish(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_RECORD -> { openPlayerMenu(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Player menu (scanning, diagnostics, HbbTV…) – the player opens it after the guide closes. */
    private fun openPlayerMenu() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_MENU, true))
        finish()
    }

    /** Start times of programmes of the channel shown in the guide that are scheduled for recording. */
    private var scheduledStarts: Set<Long> = emptySet()

    private fun refreshScheduled() {
        val id = shownChannel?.id
        scheduledStarts = RecordingScheduler.list(this).filter { it.channelId == id }.map { it.startMs }.toSet()
    }

    /**
     * OK on a programme: a current or future one on a recordable channel – choice of "Watch" / "Record"
     * (or "Cancel recording"); others – switch to the channel.
     */
    private fun programmeAction(channel: Channel, p: Programme?) {
        val canRecord = p != null && p.endMs > System.currentTimeMillis() && !channel.scrambled && !channel.virtual &&
            DeviceCapabilities.get(this).streamAnalysis
        if (!canRecord || p == null) { watch(channel); return }
        val planned = RecordingScheduler.find(this, channel.id, p)
        val options = listOf(getString(R.string.guide_watch), getString(if (planned != null) R.string.schedule_cancel else R.string.guide_record))
        TvDialog.choose(this, p.title, options.map { TvDialog.Option(it) }) { which ->
            when {
                which == 0 -> watch(channel)
                planned != null -> {
                    RecordingScheduler.cancel(this, planned.id)
                    TvDialog.toast(this, getString(R.string.schedule_cancelled, p.title))
                }
                else -> RecordingScheduler.schedule(this, channel, p)
                    .onSuccess { TvDialog.toast(this, getString(R.string.schedule_added, p.title, timeFormat.format(Date(p.startMs))), long = true) }
                    .onFailure { TvDialog.toast(this, getString(R.string.recording_error, it.message), long = true) }
            }
            refreshScheduled()
            (programmeList.adapter as BaseAdapter).notifyDataSetChanged()
        }
    }

    private fun watch(channel: Channel) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANNEL_ID, channel.id))
        finish()
    }

    /** Channel guide loaded in the background (EPG from file or TvContract) – the D-pad doesn't wait for the provider. */
    private fun showChannel(channel: Channel) {
        shownChannel = channel
        io.execute {
            val programmes = repository.programmes(channel).filter { it.endMs > startOfToday() }
            handler.post { if (!isDestroyed && shownChannel?.id == channel.id) showProgrammes(channel, programmes) }
        }
    }

    private fun showProgrammes(channel: Channel, programmes: List<Programme>) {
        refreshScheduled()
        val now = System.currentTimeMillis()
        rows = if (programmes.isEmpty()) {
            listOf(Row.Message(getString(if (channel.identified || !channel.discovered) R.string.guide_no_epg else R.string.guide_unidentified)))
        } else {
            buildList {
                var lastDay = -1L
                programmes.forEach { p ->
                    val day = startOfDay(p.startMs)
                    if (day != lastDay) { add(Row.Day(dayLabel(day))); lastDay = day }
                    add(Row.Item(p))
                }
            }
        }
        (programmeList.adapter as BaseAdapter).notifyDataSetChanged()
        val current = currentRowIndex()
        programmeList.setSelection(maxOf(0, current - 1))
        val (on, next) = EpgSchedule.nowNext(programmes, now)
        (on ?: next)?.let { showDetails(it) } ?: run {
            detailTitle.text = channel.name
            detailMeta.text = ""
            detailDesc.text = ""
        }
    }

    private fun currentRowIndex(): Int {
        val now = System.currentTimeMillis()
        val i = rows.indexOfFirst { it is Row.Item && it.programme.endMs > now }
        return if (i >= 0) i else rows.indexOfFirst { it is Row.Item }.coerceAtLeast(0)
    }

    private fun showDetails(p: Programme) {
        detailTitle.text = p.title
        val parts = listOfNotNull(
            "${dayLabel(startOfDay(p.startMs))}, ${timeFormat.format(Date(p.startMs))}–${timeFormat.format(Date(p.endMs))}",
            "${(p.endMs - p.startMs) / 60_000} min",
            genreLabel(p.genre),
        )
        detailMeta.text = parts.joinToString("  ·  ")
        detailDesc.text = p.description
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfToday() = startOfDay(System.currentTimeMillis())

    private fun dayLabel(day: Long): String {
        val today = startOfToday()
        val name = dayFormat.format(Date(day))
        return when (((day - today) / 86_400_000L).toInt()) {
            0 -> "${getString(R.string.guide_today)} · $name"
            1 -> "${getString(R.string.guide_tomorrow)} · $name"
            else -> name
        }
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = channels.size
        override fun getItem(position: Int) = channels[position]
        override fun getItemId(position: Int) = channels[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_guide_channel, parent, false)
            val channel = channels[position]
            view.findViewById<TextView>(R.id.number).text = channel.number
            view.findViewById<TextView>(R.id.name).text = channel.name
            view.findViewById<TextView>(R.id.badge).text = when {
                channel.virtual -> getString(R.string.badge_hbbtv)
                channel.scrambled -> "🔒"
                channel.isRadio -> "♪"
                else -> ""
            }
            return view
        }
    }

    private inner class ProgrammeAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Item -> 0
            is Row.Day -> 1
            is Row.Message -> 2
        }
        override fun isEnabled(position: Int) = rows[position] is Row.Item
        override fun areAllItemsEnabled() = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Row.Day -> (convertView ?: layoutInflater.inflate(R.layout.item_guide_day, parent, false)).also {
                (it as TextView).text = row.label
            }
            is Row.Message -> (convertView as? TextView ?: TextView(this@GuideActivity).apply {
                val dp = resources.displayMetrics.density
                setPadding((28 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt(), (24 * dp).toInt())
                textSize = 18f
                setTextColor(getColor(R.color.text_secondary))
            }).also { it.text = row.text }
            is Row.Item -> (convertView ?: layoutInflater.inflate(R.layout.item_guide_programme, parent, false)).also { view ->
                val p = row.programme
                val now = System.currentTimeMillis()
                val on = p.isOn(now)
                val past = p.endMs <= now
                view.findViewById<TextView>(R.id.time).text = timeFormat.format(Date(p.startMs))
                view.findViewById<TextView>(R.id.title).apply {
                    text = if (p.startMs in scheduledStarts) android.text.SpannableString("● " + p.title).apply {
                        setSpan(android.text.style.ForegroundColorSpan(0xFFFF5252.toInt()), 0, 1, 0)
                    } else p.title
                    setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
                }
                view.findViewById<View>(R.id.marker).visibility = if (on) View.VISIBLE else View.INVISIBLE
                view.findViewById<View>(R.id.live).visibility = if (on) View.VISIBLE else View.GONE
                view.findViewById<ProgressBar>(R.id.progress).apply {
                    visibility = if (on) View.VISIBLE else View.GONE
                    progress = p.progress(now)
                }
                view.alpha = if (past) 0.5f else 1f
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_OPEN_MENU = "open_menu"
    }
}
