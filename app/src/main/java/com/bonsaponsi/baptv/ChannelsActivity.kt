package com.bonsaponsi.baptv

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.util.Collections

/**
 * Channel management with the remote: OK – channel options (move: up/down, OK – drop; rename; hide),
 * quick hiding of single channels (right arrow) and of all scrambled ones at once.
 * Hidden channels disappear from the list, zapping and the TV guide, but stay here.
 */
class ChannelsActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("channels-io")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: ListView
    private lateinit var hint: TextView
    private lateinit var hideScrambledButton: Button
    private lateinit var store: ChannelStore
    private lateinit var repository: ChannelRepository

    private var items: MutableList<Channel> = mutableListOf()
    /** Index of the channel being moved, or -1. */
    private var moving = -1
    private var orderChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        list = findViewById(R.id.manage_list)
        hint = findViewById(R.id.manage_hint)
        hideScrambledButton = findViewById(R.id.manage_hide_scrambled)
        store = ChannelStore(this)
        repository = ChannelRepository(this)

        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, position, _ -> showActions(position) }
        hideScrambledButton.setOnClickListener {
            store.hideScrambled = !store.hideScrambled
            reload(keepSelection = true, focusList = false)
        }
        findViewById<Button>(R.id.manage_reset).setOnClickListener {
            store.resetOrganisation()
            orderChanged = false
            reload(keepSelection = false, focusList = false)
        }
        updateHideScrambledButton()
        reload(keepSelection = false)
        DebugLog.log("Manage", "opened channel management")
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** @param focusList false after the top buttons – focus stays on the button. */
    private fun reload(keepSelection: Boolean, focusList: Boolean = true) {
        val selectedId = items.getOrNull(list.selectedItemPosition)?.id
        val selectedTop = list.selectedView?.top ?: 0
        updateHideScrambledButton()
        io.execute {
            val input = Dtv.findTunerInput(this) ?: return@execute
            val loaded = repository.loadChannels(input.id, includeHidden = true)
            handler.post {
                items = loaded.toMutableList()
                (list.adapter as BaseAdapter).notifyDataSetChanged()
                val index = if (keepSelection) items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0) else 0
                if (keepSelection) list.setSelectionFromTop(index, selectedTop) else list.setSelection(index)
                if (focusList) list.requestFocus()
            }
        }
    }

    private fun updateHideScrambledButton() {
        hideScrambledButton.setText(if (store.hideScrambled) R.string.manage_hide_scrambled_on else R.string.manage_hide_scrambled_off)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        if (moving >= 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { move(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { move(+1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { drop(); return true }
            }
            return true
        }
        if (list.hasFocus() && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            toggleHidden(list.selectedItemPosition)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Channel options in a dialog styled like the player menu. */
    private fun showActions(position: Int) {
        val channel = items.getOrNull(position) ?: return
        val hideLabel = getString(if (channel.hidden) R.string.manage_action_show else R.string.manage_action_hide)
        TvDialog.choose(this, "${channel.number}  ${channel.name}", listOf(
            TvDialog.Option(getString(R.string.manage_action_move)),
            TvDialog.Option(getString(R.string.manage_action_rename), channel.name),
            TvDialog.Option(hideLabel),
        )) { which ->
            when (which) {
                0 -> startMoving(position)
                1 -> rename(channel)
                2 -> toggleHidden(position)
            }
        }
    }

    private fun rename(channel: Channel) {
        TvDialog.input(this, getString(R.string.rename_title, channel.number), channel.name,
            getString(R.string.manage_rename_reset)) { name ->
            store.setCustomName(channel, name)
            DebugLog.log("Manage", "name ${channel.number}: ${name.trim().ifEmpty { "(broadcaster's)" }}")
            reload(keepSelection = true)
        }
    }

    private fun startMoving(position: Int) {
        val channel = items.getOrNull(position) ?: return
        moving = position
        hint.text = getString(R.string.manage_moving_hint, channel.name)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun move(step: Int) {
        val target = moving + step
        if (target !in items.indices) return
        // The list stays in place, only the channel moves (unless it goes off screen).
        val rowTop = list.getChildAt(target - list.firstVisiblePosition)?.top
        Collections.swap(items, moving, target)
        moving = target
        orderChanged = true
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        if (rowTop != null && rowTop >= 0 && rowTop < list.height - (list.getChildAt(0)?.height ?: 0)) {
            list.setSelectionFromTop(target, rowTop)
        } else {
            list.setSelection(target)
        }
    }

    private fun drop() {
        moving = -1
        hint.setText(R.string.manage_hint)
        if (orderChanged) {
            store.setCustomOrder(items.map { store.settingsKey(it) })
            orderChanged = false
            // Sequential numbers depend on the order – refresh.
            reload(keepSelection = true)
        } else {
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }
    }

    private fun toggleHidden(position: Int) {
        val channel = items.getOrNull(position) ?: return
        val hideAllScrambled = store.hideScrambled && channel.scrambled
        if (hideAllScrambled) {
            // The channel is hidden by the "Scrambled: hidden" toggle – it can't be shown individually.
            hint.text = getString(R.string.manage_hide_scrambled_on)
            return
        }
        store.setHidden(channel, !store.isHidden(channel))
        items[position] = channel.copy(hidden = store.isHidden(channel))
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_manage_channel, parent, false)
            val channel = items[position]
            view.findViewById<TextView>(R.id.number).text = channel.number
            view.findViewById<TextView>(R.id.name).text = channel.name
            view.findViewById<TextView>(R.id.badge).text = listOfNotNull(
                getString(R.string.badge_hbbtv).takeIf { channel.virtual },
                "🔒".takeIf { channel.scrambled },
                "♪".takeIf { channel.isRadio },
            ).joinToString("  ")
            view.findViewById<TextView>(R.id.state).text = when {
                position == moving -> getString(R.string.manage_moving)
                channel.hidden -> getString(R.string.manage_hidden)
                else -> ""
            }
            view.findViewById<View>(R.id.marker).visibility = if (position == moving) View.VISIBLE else View.INVISIBLE
            view.alpha = if (channel.hidden && position != moving) 0.45f else 1f
            return view
        }
    }
}
