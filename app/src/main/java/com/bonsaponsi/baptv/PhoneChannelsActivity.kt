package com.bonsaponsi.baptv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Organising the phone's internet channels: drag a row by its handle to move it, hide it from the channel list,
 * rename it or remove it. The order is stored when a row is dropped ([PhoneIptvStore.reorder]).
 */
class PhoneChannelsActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("phone-manage")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PhoneIptvStore
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = Adapter()
    private var channels: MutableList<PhoneIptvStore.Channel> = mutableListOf()

    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(recyclerView: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            channels.add(b, channels.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        // Only by the handle: a long press anywhere would fight with scrolling and the buttons.
        override fun isLongPressDragEnabled() = false

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            viewHolder?.itemView?.alpha = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.8f else 1f
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            val order = channels.map { it.url }
            io.execute { store.reorder(order) }
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_channels)
        store = PhoneIptvStore.get(this)
        list = findViewById(R.id.manage_list)
        empty = findViewById(R.id.manage_empty)
        findViewById<TextView>(R.id.phone_header_title).setText(R.string.phone_menu_manage)
        findViewById<View>(R.id.phone_header_back).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        dragHelper.attachToRecyclerView(list)
        reload()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        io.execute {
            val all = store.load()
            handler.post {
                if (isDestroyed) return@post
                channels = all.toMutableList()
                @SuppressLint("NotifyDataSetChanged")
                adapter.notifyDataSetChanged()
                empty.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** Changes the stored list on the worker, then shows it again. */
    private fun change(block: () -> Unit) = io.execute { block(); reload() }

    private inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val handle: ImageView = view.findViewById(R.id.manage_item_handle)
        val name: TextView = view.findViewById(R.id.manage_item_name)
        val detail: TextView = view.findViewById(R.id.manage_item_detail)
        val hide: ImageButton = view.findViewById(R.id.manage_item_hide)
        val rename: ImageButton = view.findViewById(R.id.manage_item_rename)
        val remove: ImageButton = view.findViewById(R.id.manage_item_remove)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_phone_manage, parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val channel = channels[position]
            holder.name.text = channel.title
            holder.name.alpha = if (channel.hidden) 0.45f else 1f
            holder.detail.text = when {
                channel.hidden -> getString(R.string.phone_manage_hidden)
                channel.label.isNotBlank() -> getString(R.string.phone_manage_original, channel.stream.name)
                else -> channel.stream.group
            }
            holder.detail.visibility = if (holder.detail.text.isEmpty()) View.GONE else View.VISIBLE
            holder.hide.setImageResource(if (channel.hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
            holder.hide.contentDescription = getString(if (channel.hidden) R.string.phone_manage_show else R.string.phone_manage_hide)
            holder.hide.setOnClickListener { change { store.setHidden(channel.url, !channel.hidden) } }
            holder.rename.setOnClickListener { askRename(this@PhoneChannelsActivity, channel) { label -> change { store.rename(channel.url, label) } } }
            holder.remove.setOnClickListener {
                confirmRemove(this@PhoneChannelsActivity, channel) {
                    change {
                        store.remove(channel.url)
                        PhoneIptvChecks.get(this@PhoneChannelsActivity).remove(channel.url)
                    }
                }
            }
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) dragHelper.startDrag(holder)
                false
            }
        }
    }

    companion object {
        /** Asks for a new name; an empty one brings back the playlist's name. */
        fun askRename(activity: Activity, channel: PhoneIptvStore.Channel, onName: (String) -> Unit) {
            val input = EditText(activity).apply {
                setText(channel.title)
                setSelectAllOnFocus(true)
                setSingleLine()
                hint = channel.stream.name
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.phone_manage_rename)
                .setMessage(activity.getString(R.string.phone_manage_rename_message, channel.stream.name))
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = input.text.toString().trim()
                    onName(if (text == channel.stream.name) "" else text)
                }
                .setNegativeButton(R.string.phone_cancel, null)
                .show()
        }

        fun confirmRemove(activity: Activity, channel: PhoneIptvStore.Channel, onRemove: () -> Unit) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.phone_remove_title, channel.title))
                .setPositiveButton(R.string.phone_remove) { _, _ -> onRemove() }
                .setNegativeButton(R.string.phone_cancel, null)
                .show()
        }
    }
}
