package com.bonsaponsi.baptv

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Player menu: a translucent panel like the channel list, sections with icons on the left, the selected
 * section's entries with their current value (track, language, on/off) on the right. We track the selection ourselves
 * (no ListView focus), so keys do not reach TvView or the HbbTV app.
 */
class MenuPanel(
    private val activity: Activity,
    private val build: () -> List<Section>,
    /** Menu shown / hidden (e.g. to hide the HbbTV overlay under the panel). */
    private val onShown: (Boolean) -> Unit = {},
) {
    class Section(val id: String, val title: String, val icon: Int, val entries: List<Entry>)

    /** What happens to the menu after an entry is selected. */
    enum class After {
        /** Toggle: the menu stays, values refresh. */
        STAY,
        /** Screen or dialog: the menu hides and comes back at the same spot after it closes ([returned]). */
        RETURN,
        /** Goes to watching (channel list, TV guide, pause): the menu closes. */
        LEAVE,
    }

    class Entry(val label: String, val value: String? = null, val after: After = After.RETURN, val action: () -> Unit)

    private val panel: View = activity.findViewById(R.id.menu_panel)
    private val sectionList: ListView = activity.findViewById(R.id.menu_sections)
    private val entryList: ListView = activity.findViewById(R.id.menu_entries)
    private val header: TextView = activity.findViewById(R.id.menu_section_header)

    private var sections: List<Section> = emptyList()
    private var sectionIndex = 0
    private var entryIndex = 0
    private var inEntries = false
    /** Menu hidden while a screen/dialog from an [After.RETURN] entry is open – comes back on [returned]. */
    private var awaitingReturn = false

    val visible get() = panel.visibility == View.VISIBLE

    private val sectionAdapter = object : BaseAdapter() {
        override fun getCount() = sections.size
        override fun getItem(position: Int) = sections[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_menu_section, parent, false)
            val section = sections[position]
            view.findViewById<ImageView>(R.id.menu_icon).setImageResource(section.icon)
            view.findViewById<TextView>(R.id.menu_section_title).text = section.title
            highlight(view, focused = position == sectionIndex && !inEntries, current = position == sectionIndex && inEntries)
            return view
        }
    }

    private val entryAdapter = object : BaseAdapter() {
        private val entries get() = sections.getOrNull(sectionIndex)?.entries.orEmpty()
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_menu_entry, parent, false)
            val entry = entries[position]
            view.findViewById<TextView>(R.id.menu_entry_label).text = entry.label
            view.findViewById<TextView>(R.id.menu_entry_value).apply {
                text = entry.value.orEmpty()
                visibility = if (entry.value.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            view.findViewById<View>(R.id.menu_entry_chevron).visibility = if (entry.after == After.STAY) View.INVISIBLE else View.VISIBLE
            val focused = position == entryIndex && inEntries
            highlight(view, focused, current = false)
            // Brighter value on the blue highlight, otherwise it gets lost.
            val valueColor = if (focused) 0xE6FFFFFF.toInt() else activity.getColor(R.color.text_secondary)
            view.findViewById<TextView>(R.id.menu_entry_value).setTextColor(valueColor)
            view.findViewById<TextView>(R.id.menu_entry_chevron).setTextColor(valueColor)
            return view
        }
    }

    init {
        sectionList.adapter = sectionAdapter
        entryList.adapter = entryAdapter
        sectionList.isFocusable = false
        entryList.isFocusable = false
        // Touch / mouse (emulator): section selection and entry activation.
        sectionList.setOnItemClickListener { _, _, position, _ -> sectionIndex = position; entryIndex = 0; inEntries = false; render() }
        entryList.setOnItemClickListener { _, _, position, _ -> entryIndex = position; inEntries = true; activate() }
    }

    /** We set the row background ourselves – an unfocused ListView overrides the selected/activated states. */
    private fun highlight(view: View, focused: Boolean, current: Boolean) = view.setBackgroundResource(when {
        focused -> R.drawable.menu_item_focused
        current -> R.drawable.menu_item_current
        else -> 0
    })

    fun show() {
        awaitingReturn = false
        sections = build()
        sectionIndex = sectionIndex.coerceIn(0, (sections.size - 1).coerceAtLeast(0))
        entryIndex = 0
        inEntries = false
        panel.visibility = View.VISIBLE
        onShown(true)
        render()
    }

    fun hide() {
        if (!visible) return
        panel.visibility = View.GONE
        onShown(false)
    }

    /** A screen or dialog opened from the menu was closed: the menu comes back in the same section at the same entry. */
    fun returned() {
        if (!awaitingReturn) return
        awaitingReturn = false
        panel.visibility = View.VISIBLE
        onShown(true)
        refresh()
    }

    /** A screen opened from the menu went on to watching (e.g. a channel picked in the TV guide) – no return to the menu. */
    fun cancelReturn() {
        awaitingReturn = false
    }

    /** Rebuilds the entries (after toggling an option), keeping the selected section and row. */
    fun refresh() {
        val currentId = sections.getOrNull(sectionIndex)?.id
        sections = build()
        sectionIndex = sections.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        entryIndex = entryIndex.coerceIn(0, (currentEntries().size - 1).coerceAtLeast(0))
        if (currentEntries().isEmpty()) inEntries = false
        render()
    }

    /** Keys while the menu is open; true – handled (also the key-up event). */
    fun onKey(event: KeyEvent): Boolean {
        if (!visible || event.keyCode !in KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(+1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> enterEntries()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inEntries) { inEntries = false; render() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (inEntries) activate() else enterEntries()
            KeyEvent.KEYCODE_BACK -> if (inEntries) { inEntries = false; render() } else hide()
            else -> hide() // MENU / REC / SETTINGS close the menu
        }
        return true
    }

    private fun currentEntries() = sections.getOrNull(sectionIndex)?.entries.orEmpty()

    private fun move(delta: Int) {
        if (inEntries) {
            val size = currentEntries().size
            if (size > 0) entryIndex = (entryIndex + delta).coerceIn(0, size - 1)
        } else if (sections.isNotEmpty()) {
            val next = (sectionIndex + delta).coerceIn(0, sections.size - 1)
            if (next != sectionIndex) { sectionIndex = next; entryIndex = 0 }
        }
        render()
    }

    private fun enterEntries() {
        if (currentEntries().isEmpty()) return
        inEntries = true
        render()
    }

    private fun activate() {
        val entry = currentEntries().getOrNull(entryIndex) ?: return
        DebugLog.log("Player", "menu: ${entry.label}")
        when (entry.after) {
            After.STAY -> {
                entry.action()
                if (visible) refresh()
            }
            After.RETURN -> {
                hide()
                awaitingReturn = true
                entry.action()
            }
            After.LEAVE -> {
                hide()
                entry.action()
            }
        }
    }

    private fun render() {
        header.text = sections.getOrNull(sectionIndex)?.title.orEmpty()
        sectionAdapter.notifyDataSetChanged()
        entryAdapter.notifyDataSetChanged()
        sectionList.setSelection(sectionIndex)
        entryList.setSelection(if (inEntries) entryIndex else 0)
    }

    companion object {
        private val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_MEDIA_RECORD,
        )
    }
}
