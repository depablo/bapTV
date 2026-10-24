package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only tools for channels of the tuner input in TvContract: a raw dump, direct requests to the
 * dtvinput installer, and delete/update attempts. Used to investigate channels that a scan does not remove.
 */
object DebugTvTools {
    private const val TAG = "TvTools"
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null

    /** All TvContract channel rows visible to the app (every input), all columns; blobs as UTF-8 text. */
    fun dump(context: Context, inputFilter: String?): JSONArray {
        val result = JSONArray()
        context.contentResolver.query(Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val input = c.getString(c.getColumnIndexOrThrow(Channels.COLUMN_INPUT_ID)).orEmpty()
                if (inputFilter != null && inputFilter !in input) continue
                result.put(row(c))
            }
        }
        return result
    }

    private fun row(c: Cursor): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            val value: Any = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> continue
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> String(c.getBlob(i), Charsets.UTF_8)
                else -> c.getString(i)
            }
            o.put(c.getColumnName(i), value)
        }
        return o
    }

    /** Sends [params] to dtvinput's InstallerService and logs installer events for 10 minutes. */
    fun install(context: Context, params: String): String {
        listen(context)
        val intent = Intent().setComponent(Dtv.INSTALLER_SERVICE).putExtra(Dtv.EXTRA_PARAMS, params)
        val result = runCatching { context.startService(intent) }
            .recoverCatching { context.startForegroundService(intent) }
        DebugLog.log(TAG, "-> InstallerService params=$params: ${result.getOrElse { it.toString() }}")
        return "sent: ${result.getOrElse { "failed: $it" }}"
    }

    /** Sends an intent action (e.g. ACTION_CANCEL_INSTALL) to the dtvinput package as a broadcast and as a service start. */
    fun action(context: Context, action: String, params: String?): String {
        listen(context)
        val broadcast = Intent(action).setPackage(Dtv.DTV_INPUT_PACKAGE).apply { params?.let { putExtra(Dtv.EXTRA_PARAMS, it) } }
        val b = runCatching { context.sendBroadcast(broadcast) }.fold({ "ok" }, { it.toString() })
        val service = Intent(action).setComponent(Dtv.INSTALLER_SERVICE).apply { params?.let { putExtra(Dtv.EXTRA_PARAMS, it) } }
        val s = runCatching { context.startService(service) }.fold({ it.toString() }, { it.toString() })
        DebugLog.log(TAG, "-> action $action params=$params: broadcast=$b, service=$s")
        return "broadcast: $b\nservice: $s"
    }

    private fun listen(context: Context) {
        val app = context.applicationContext
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val extras = intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }
                    DebugLog.log(TAG, "<- ${intent.action?.substringAfterLast('.')} {$extras}")
                }
            }
            val filter = IntentFilter().apply {
                addAction(Dtv.ACTION_INSTALL_EVENT); addAction(Dtv.ACTION_UNINSTALL_EVENT); addAction(Dtv.ACTION_SETUP_READY)
            }
            val permission = Dtv.eventSenderPermission(app)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(r, filter, permission, handler, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(r, filter, permission, handler)
            receiver = r
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null }, 10 * 60_000L)
    }

    /** Tries to delete channel [id], or all channels of an input whose id contains [input]. */
    fun delete(context: Context, id: Long?, input: String?): String {
        val ids = when {
            id != null -> listOf(id)
            input != null -> dump(context, input).let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getLong(Channels._ID) } }
            else -> return "specify ?id= or ?input="
        }
        val lines = ids.map { channelId ->
            val r = runCatching { context.contentResolver.delete(TvContract.buildChannelUri(channelId), null, null) }
            "#$channelId: ${r.getOrElse { it.toString() }}"
        }
        DebugLog.log(TAG, "delete: " + lines.joinToString())
        return lines.joinToString("\n")
    }

    /** Tries to set columns (e.g. browsable=0, searchable=0) on channel [id]. */
    fun update(context: Context, id: Long, values: Map<String, String>): String {
        val cv = ContentValues()
        values.forEach { (k, v) -> v.toLongOrNull()?.let { cv.put(k, it) } ?: cv.put(k, v) }
        val r = runCatching { context.contentResolver.update(TvContract.buildChannelUri(id), cv, null, null) }
        DebugLog.log(TAG, "update #$id $values: ${r.getOrElse { it.toString() }}")
        return "#$id: ${r.getOrElse { it.toString() }}"
    }
}
