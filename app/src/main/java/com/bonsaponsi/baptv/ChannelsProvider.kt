package com.bonsaponsi.baptv

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only list of the channels this build discovered, for the other release builds (same signature only: the
 * permission is signature-level, see the manifest). The list holds DVB identifiers and TV channel IDs; nothing
 * private. Writes are refused – each build keeps its own list and checks what it takes from another one.
 */
class ChannelsProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.path != PATH_DISCOVERED) return null
        val json = ChannelStore(context ?: return null).discoveredJson().orEmpty()
        return MatrixCursor(arrayOf(COLUMN_JSON)).apply { addRow(arrayOf<Any>(json)) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
        const val PATH_DISCOVERED = "/discovered"
        const val COLUMN_JSON = "json"
    }
}
