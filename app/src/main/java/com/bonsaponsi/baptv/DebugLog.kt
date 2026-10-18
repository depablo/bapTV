package com.bonsaponsi.baptv

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App event log available without adb: kept in memory (for on-screen viewing)
 * and appended to a file in the app directory, so it survives restarts and crashes.
 */
object DebugLog {
    private const val MAX_ENTRIES = 3000
    private const val MAX_FILE_BYTES = 512 * 1024L

    private val entries = ArrayDeque<String>()
    /** Sequence number of the first entry in [entries]; numbers grow monotonically within a process. */
    private var firstSeq = 0L
    /** Sequence number of the "process start" entry – the current process's log starts there. */
    @Volatile var processStartSeq = 0L
        private set
    /** Current process identifier (start time); clients use it to detect a restart or update. */
    val bootId: Long = System.currentTimeMillis()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        val dir = context.filesDir
        file = File(dir, "debug.log")
        // Load the previous session so that after a crash you can see what happened before it.
        listOf(File(dir, "debug.log.1"), File(dir, "debug.log")).filter { it.exists() }.forEach { f ->
            runCatching { f.readLines().takeLast(MAX_ENTRIES).forEach { addEntry(it) } }
        }
        processStartSeq = synchronized(entries) { firstSeq + entries.size }
        log("App", "==== process start (pid ${android.os.Process.myPid()}) – Android up since ${systemStartText()} ====")
        noteSystemRestart(File(dir, "system_start"))
    }

    /** Compare with the saved Android start time: different (with slack for clock adjustments) = Android restarted. */
    private fun noteSystemRestart(f: File) {
        val previous = runCatching { f.readText().trim().toLong() }.getOrNull()
        val current = systemStartMs
        if (previous != null && kotlin.math.abs(current - previous) > 60_000) {
            val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
            warn("App", "Android restarted since the last app start (previously up since ${fmt.format(java.util.Date(previous))}, now since ${fmt.format(java.util.Date(current))})")
        }
        runCatching { f.writeText(current.toString()) }
    }

    /** Android start time (wall clock) – changes only when the system restarts. */
    val systemStartMs get() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    private fun systemStartText(): String {
        val up = android.os.SystemClock.elapsedRealtime() / 1000
        val at = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(java.util.Date(systemStartMs))
        return "$at (${up / 3600} h ${(up % 3600) / 60} min)"
    }

    fun log(tag: String, message: String) = write("I", tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) = write("W", tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) = write("E", tag, message, error)

    fun snapshot(): List<String> = synchronized(entries) { entries.toList() }

    /** Log slice for incremental reads: entries from number [from] and the next entry's number. */
    class Slice(val lines: List<String>, val next: Long)

    /**
     * Entries numbered >= [since]. When the client knows a different [bootId] (the process was restarted
     * or updated), it gets the log from the start of the current process.
     */
    fun since(since: Long, clientBootId: Long?): Slice = synchronized(entries) {
        val from = if (clientBootId != null && clientBootId != bootId) processStartSeq else since
        val skip = (from - firstSeq).coerceIn(0L, entries.size.toLong()).toInt()
        Slice(entries.drop(skip), firstSeq + entries.size)
    }

    fun clear() {
        synchronized(entries) {
            firstSeq += entries.size
            entries.clear()
            file?.delete()
            file?.let { File(it.parentFile, "debug.log.1").delete() }
        }
        log("App", "log cleared")
    }

    fun addListener(listener: (String) -> Unit) = listeners.add(listener)

    fun removeListener(listener: (String) -> Unit) = listeners.remove(listener)

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        when (level) {
            "E" -> Log.e("bapTV/$tag", message, error)
            "W" -> Log.w("bapTV/$tag", message, error)
            else -> Log.i("bapTV/$tag", message)
        }
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val stack = error?.let { e -> "\n" + StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().trimEnd() }.orEmpty()
        val line = "$time $level/$tag: $message$stack"
        synchronized(entries) {
            addEntry(line)
            appendToFile(line)
        }
        listeners.forEach { runCatching { it(line) } }
    }

    private fun addEntry(line: String) {
        entries.addLast(line)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
            firstSeq++
        }
    }

    private fun appendToFile(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_FILE_BYTES) f.renameTo(File(f.parentFile, "debug.log.1"))
            f.appendText(line + "\n")
        }
    }
}
