package com.bonsaponsi.baptv

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Writes app data files (recordings list, schedule, EPG, channels) via [AtomicFile]: new content
 * goes to a temporary file and only then replaces the old one, so a process interrupted during
 * a write leaves the previous version instead of truncated JSON.
 */
object StoredFile {

    fun write(file: File, text: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    /** File content (taking into account the backup after an interrupted write); null – no file. */
    fun read(file: File): String? {
        val atomic = AtomicFile(file)
        return if (atomic.baseFile.exists() || File(file.path + ".bak").exists()) String(atomic.readFully(), Charsets.UTF_8) else null
    }

    /**
     * Elements of a JSON array from a file; individual bad entries are skipped (with a log entry)
     * instead of invalidating the whole list.
     */
    fun <T> readList(file: File, tag: String, parse: (JSONObject) -> T): List<T> {
        val text = runCatching { read(file) }.onFailure { DebugLog.warn(tag, "reading $file", it) }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.onFailure { DebugLog.warn(tag, "corrupted file $file", it) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching { parse(array.getJSONObject(i)) }.onFailure { DebugLog.warn(tag, "skipped bad entry $i in $file", it) }.getOrNull()
        }
    }
}
