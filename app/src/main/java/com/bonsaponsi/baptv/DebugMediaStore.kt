package com.bonsaponsi.baptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only check whether the tuner input's recordings are reachable through MediaStore:
 * files under Android/media/ are shared media, so if the indexer sees them, the app can ask
 * the system to delete them (MediaStore.createDeleteRequest, one user confirmation per batch)
 * even on target SDKs where a direct file delete fails.
 */
object DebugMediaStore {
    private const val TAG = "MediaStore"

    private val collection get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** MediaStore rows under the tuner input's media directory, plus the .nomedia state on disk. */
    fun dump(context: Context, limit: Int): JSONObject {
        val result = JSONObject()
        val mediaRoot = File(Recorder.recordsDir.parentFile!!.path)
        result.put("recordsDirEntries", Recorder.recordsDir.listFiles()?.size ?: -1)
        result.put("nomediaInMediaRoot", File(mediaRoot, ".nomedia").exists())
        result.put("nomediaInRecords", File(Recorder.recordsDir, ".nomedia").exists())
        val rows = JSONArray()
        var count = 0
        var bytes = 0L
        try {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.RELATIVE_PATH,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                ),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("Android/media/${Dtv.DTV_INPUT_PACKAGE}/%"),
                "${MediaStore.Files.FileColumns._ID} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    count++
                    bytes += c.getLong(3)
                    if (rows.length() < limit) rows.put(
                        JSONObject()
                            .put("id", c.getLong(0))
                            .put("path", c.getString(1) + c.getString(2))
                            .put("bytes", c.getLong(3))
                            .put("mediaType", c.getInt(4))
                            .put("owner", c.getString(5) ?: JSONObject.NULL),
                    )
                }
            }
            result.put("indexedCount", count).put("indexedBytes", bytes).put("rows", rows)
        } catch (e: Exception) {
            result.put("error", e.toString())
        }
        DebugLog.log(TAG, "dump: $count rows, $bytes B under Android/media/${Dtv.DTV_INPUT_PACKAGE}")
        return result
    }

    /**
     * Asks the system to delete up to [limit] indexed record files (oldest first). Shows the
     * system confirmation dialog on the TV; the result is visible as rows disappearing from [dump].
     * With [direct] it instead tries ContentResolver.delete on the first row, to log the exact failure.
     */
    fun requestDelete(context: Context, limit: Int, direct: Boolean): String {
        val uris = ArrayList<Uri>()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}",
                arrayOf("Android/media/${Dtv.DTV_INPUT_PACKAGE}/Records/%"),
                "${MediaStore.Files.FileColumns._ID} ASC",
            )?.use { c ->
                while (c.moveToNext() && uris.size < limit) {
                    // createDeleteRequest rejects Files uris ("must be referenced by specific ID");
                    // the video collection shares the row id, so reference the item through it.
                    uris += android.content.ContentUris.withAppendedId(
                        if (direct) collection else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                        c.getLong(0),
                    )
                }
            }
        } catch (e: Exception) {
            return "query failed: $e"
        }
        if (uris.isEmpty()) return "no indexed record files"
        if (direct) {
            val r = runCatching { context.contentResolver.delete(uris.first(), null, null) }
            DebugLog.log(TAG, "direct delete ${uris.first()}: ${r.getOrElse { it.toString() }}")
            return "direct delete ${uris.first()}: ${r.getOrElse { it.toString() }}"
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "createDeleteRequest needs API 30"
        return try {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            // The confirmation must come from a visible activity – started from the app context
            // the dialog never appears (measured on the Kaon).
            val fromActivity = PlayerRemote.startIntentSender
            if (fromActivity != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { fromActivity(pi.intentSender) }
            } else {
                context.startIntentSender(
                    pi.intentSender, null,
                    Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0,
                )
            }
            DebugLog.log(TAG, "delete request for ${uris.size} uris launched (activity=${fromActivity != null})")
            "delete request for ${uris.size} uris launched – confirm on the TV, then re-check /dtv/mediastore"
        } catch (e: Exception) {
            DebugLog.warn(TAG, "delete request failed", e)
            "delete request failed: $e"
        }
    }
}
