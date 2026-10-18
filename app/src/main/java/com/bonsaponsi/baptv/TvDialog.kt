package com.bonsaponsi.baptv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView

/**
 * Dialogs styled like the player menu (translucent panel, rows highlighted in the accent color)
 * instead of system AlertDialogs: pick from a list, enter text, message.
 * [onClose] is called on every close (pick, OK, BACK) – after a pick, only after [onPick] / [onOk].
 */
object TvDialog {
    class Option(val label: String, val value: String? = null)

    fun choose(
        activity: Activity, title: String, options: List<Option>, checked: Int = -1,
        onClose: (() -> Unit)? = null, onPick: (Int) -> Unit,
    ): Dialog {
        val (dialog, root) = create(activity, title, onClose)
        val list = root.findViewById<ListView>(R.id.tv_dialog_list)
        list.visibility = View.VISIBLE
        list.adapter = object : BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_menu_entry, parent, false)
                val option = options[position]
                view.findViewById<TextView>(R.id.menu_entry_label).text = option.label
                val value = if (position == checked) "●" else option.value
                view.findViewById<TextView>(R.id.menu_entry_value).apply {
                    text = value.orEmpty()
                    visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
                view.findViewById<View>(R.id.menu_entry_chevron).visibility = View.GONE
                return view
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            onPick(position)
            dialog.dismiss()
        }
        dialog.show()
        list.requestFocus()
        list.setSelection(checked.coerceAtLeast(0))
        return dialog
    }

    fun input(
        activity: Activity, title: String, initial: String, message: String? = null,
        onClose: (() -> Unit)? = null, onOk: (String) -> Unit,
    ): Dialog {
        val (dialog, root) = create(activity, title, onClose)
        message?.let { root.findViewById<TextView>(R.id.tv_dialog_message).apply { text = it; visibility = View.VISIBLE } }
        val edit = root.findViewById<EditText>(R.id.tv_dialog_input)
        edit.visibility = View.VISIBLE
        edit.setText(initial)
        edit.setSelectAllOnFocus(true)
        val confirm = {
            onOk(edit.text.toString())
            dialog.dismiss()
        }
        edit.setOnEditorActionListener { _, actionId, _ -> (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) confirm() } }
        root.findViewById<View>(R.id.tv_dialog_buttons).visibility = View.VISIBLE
        root.findViewById<Button>(R.id.tv_dialog_ok).setOnClickListener { confirm() }
        root.findViewById<Button>(R.id.tv_dialog_cancel).setOnClickListener { dialog.dismiss() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edit.requestFocus()
        edit.selectAll()
        return dialog
    }

    fun message(activity: Activity, title: String, message: String, onClose: (() -> Unit)? = null): Dialog =
        confirm(activity, title, message, activity.getString(android.R.string.ok), cancelLabel = null, onClose = onClose) {}

    /**
     * Question with buttons: [onConfirm] after the confirm button, [onCancel] after "Cancel" (not after BACK).
     * [cancelLabel] null – confirm button only. [focusCancel] – focus on "Cancel" when an accidental OK
     * would interrupt something important (a recording, a pause).
     */
    fun confirm(
        activity: Activity, title: String, message: String?, confirmLabel: String,
        cancelLabel: String? = activity.getString(android.R.string.cancel), focusCancel: Boolean = false,
        onCancel: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onConfirm: () -> Unit,
    ): Dialog {
        val (dialog, root) = create(activity, title, onClose)
        message?.let { root.findViewById<TextView>(R.id.tv_dialog_message).apply { text = it; visibility = View.VISIBLE } }
        root.findViewById<View>(R.id.tv_dialog_buttons).visibility = View.VISIBLE
        val ok = root.findViewById<Button>(R.id.tv_dialog_ok)
        val cancel = root.findViewById<Button>(R.id.tv_dialog_cancel)
        ok.text = confirmLabel
        ok.setOnClickListener { onConfirm(); dialog.dismiss() }
        if (cancelLabel == null) cancel.visibility = View.GONE
        else {
            cancel.text = cancelLabel
            cancel.setOnClickListener { onCancel?.invoke(); dialog.dismiss() }
        }
        dialog.show()
        (if (focusCancel && cancelLabel != null) cancel else ok).requestFocus()
        return dialog
    }

    /** Message with a QR code of [link] (and the link as text) – to open it on a phone. */
    fun qr(activity: Activity, title: String, message: String, link: String, onClose: (() -> Unit)? = null): Dialog {
        val dialog = message(activity, title, message, onClose)
        val code = QrCode.encode(link)
        dialog.findViewById<android.widget.ImageView>(R.id.tv_dialog_qr)?.apply {
            if (code != null) {
                setImageBitmap(code.toBitmap(8))
                visibility = View.VISIBLE
            }
        }
        dialog.findViewById<TextView>(R.id.tv_dialog_link)?.apply { text = link; visibility = View.VISIBLE }
        return dialog
    }

    /** Short menu-styled message instead of the system toast. */
    @Suppress("DEPRECATION") // a custom Toast view works for foreground apps
    fun toast(context: android.content.Context, text: String, long: Boolean = false) {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.toast_tv, null) as TextView
        view.text = text
        android.widget.Toast(context).apply {
            this.view = view
            duration = if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
            setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, 64)
        }.show()
    }

    private fun create(activity: Activity, title: String, onClose: (() -> Unit)?): Pair<Dialog, View> {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = activity.layoutInflater.inflate(R.layout.dialog_tv, null)
        root.findViewById<TextView>(R.id.tv_dialog_title).text = title
        dialog.setContentView(root)
        // Fixed width like the layout's 520dp: without it the window shrinks to the content (e.g. the QR image).
        dialog.window?.setLayout((520 * activity.resources.displayMetrics.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { onClose?.invoke() }
        return dialog to root
    }
}
