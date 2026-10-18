package com.bonsaponsi.baptv

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvRecordingClient
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Records programs through the tuner input's recording session (`TvRecordingClient`) – the same one
 * [TsProbe] uses to identify channels. dtvinput writes the full service stream to
 * `Android/media/<dtvinput>/Records/<channel>_<time>/record.ts` (~3.5 GB/h in HD).
 *
 * One recording at a time (one tuner); you can record the channel being watched or another one from the same
 * multiplex. The recording list lives in `files/recordings.json` – the `RecordedPrograms` rows belong to dtvinput.
 */
object Recorder {
    private const val TAG = "Rec"

    data class Entry(
        val id: Long,
        val channelId: Long,
        val channelNumber: String,
        val channelName: String,
        val title: String,
        val description: String,
        val startMs: Long,
        val endMs: Long?,
        /** Scheduled end (recording an EPG program); null – until stopped manually. */
        val stopAtMs: Long?,
        val recordedUri: String?,
        val path: String?,
        val state: String,
        val error: String? = null,
    ) {
        val file get() = path?.let(::File)
        val sizeBytes get() = file?.takeIf { it.exists() }?.length() ?: 0L

        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("channelId", channelId).put("channelNumber", channelNumber).put("channelName", channelName)
            .put("title", title).put("description", description).put("startMs", startMs)
            .put("endMs", endMs ?: JSONObject.NULL).put("stopAtMs", stopAtMs ?: JSONObject.NULL)
            .put("recordedUri", recordedUri ?: JSONObject.NULL).put("path", path ?: JSONObject.NULL)
            .put("state", state).put("error", error ?: JSONObject.NULL)

        companion object {
            const val RECORDING = "recording"
            const val DONE = "done"
            const val FAILED = "failed"
            /** Scheduled ([RecordingScheduler]) – not started yet. */
            const val SCHEDULED = "scheduled"

            fun fromJson(o: JSONObject) = Entry(
                id = o.getLong("id"), channelId = o.getLong("channelId"), channelNumber = o.optString("channelNumber"),
                channelName = o.optString("channelName"), title = o.optString("title"), description = o.optString("description"),
                startMs = o.getLong("startMs"), endMs = o.optLongOrNull("endMs"), stopAtMs = o.optLongOrNull("stopAtMs"),
                recordedUri = o.optStringOrNull("recordedUri"), path = o.optStringOrNull("path"),
                state = o.optString("state", DONE), error = o.optStringOrNull("error"),
            )

            private fun JSONObject.optLongOrNull(k: String) = if (isNull(k)) null else optLong(k)
            private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var client: TvRecordingClient? = null
    @Volatile var active: Entry? = null
        private set

    /** Called on the main thread on state change (start – the entry, end/error – null): UI, service. */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<(Entry?) -> Unit>()

    private fun notifyListeners(entry: Entry?) = listeners.forEach { runCatching { it(entry) }.onFailure { e -> DebugLog.warn(TAG, "listener", e) } }

    private val autoStop = Runnable { stop("scheduled end") }

    /** The recording session has started (onTuned arrived and startRecording was called). */
    @Volatile private var recording = false

    /** No onTuned within a reasonable time – release the session, otherwise "recording in progress" blocks further recordings. */
    private val tuneTimeout = Runnable { if (active != null && !recording) failNow("no onTuned within ${TUNE_TIMEOUT_MS / 1000} s") }

    /** No onRecordingStopped after stop() – finish with whatever is on disk. */
    private val stopTimeout = Runnable {
        val entry = active ?: return@Runnable
        DebugLog.warn(TAG, "no onRecordingStopped after ${STOP_TIMEOUT_MS / 1000} s – finishing with the file on disk")
        val file = recordingDir(entry)
        release(appContext ?: return@Runnable, entry.copy(endMs = System.currentTimeMillis(), path = file?.path,
            state = if (file != null) Entry.DONE else Entry.FAILED, error = if (file == null) "no onRecordingStopped" else null))
    }

    @Volatile private var appContext: Context? = null
    private const val TUNE_TIMEOUT_MS = 20_000L
    private const val STOP_TIMEOUT_MS = 15_000L

    private fun failNow(error: String) { appContext?.let { fail(it, error) } }

    /**
     * Starts recording after [delayMs] (e.g. while the player is still switching channels) on the recorder's main thread –
     * independent of the activity lifecycle. [onResult] receives null or an error description.
     */
    fun startLater(context: Context, channel: Channel, title: String, description: String, stopAtMs: Long?, delayMs: Long, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        main.postDelayed({ onResult(start(app, channel, title, description, stopAtMs)) }, delayMs)
    }

    /** Minimum free space – below it recording stops by itself so the set-top box storage does not fill up. */
    const val MIN_FREE_BYTES = 500_000_000L

    private val spaceCheck = object : Runnable {
        override fun run() {
            if (active == null) return
            if (freeBytes() in 0 until MIN_FREE_BYTES) stop("low storage (${freeBytes() / 1_000_000} MB)")
            else main.postDelayed(this, 30_000)
        }
    }

    private fun storeFile(context: Context) = File(context.applicationContext.filesDir, "recordings.json")

    @Synchronized
    fun list(context: Context): List<Entry> =
        StoredFile.readList(storeFile(context), TAG, Entry::fromJson).sortedByDescending { it.startMs }

    @Synchronized
    private fun save(context: Context, entry: Entry) {
        val all = list(context).filter { it.id != entry.id } + entry
        writeAll(context, all)
    }

    /**
     * How many bytes an hour of recording takes: the average of successful recordings (min. 30 s), ~3 GB without any
     * (an HD channel: 52 MB in 82 s ≈ 2.3 GB/h; higher-bitrate channels take more).
     */
    fun bytesPerHour(entries: List<Entry>): Long {
        val measured = entries.filter { it.state == Entry.DONE && it.endMs != null && it.endMs - it.startMs >= 30_000 && it.sizeBytes > 0 }
        val ms = measured.sumOf { it.endMs!! - it.startMs }
        return if (ms <= 0) 3_000_000_000L else (measured.sumOf { it.sizeBytes } * 3_600_000L / ms).coerceAtLeast(500_000_000L)
    }

    private fun writeAll(context: Context, entries: List<Entry>) =
        StoredFile.write(storeFile(context), JSONArray().apply { entries.sortedBy { it.startMs }.forEach { put(it.toJson()) } }.toString())

    /** Free space where dtvinput stores recordings (shared storage). */
    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(-1L)

    /**
     * Starts recording a channel (main thread). [stopAtMs] – automatic end (e.g. the program end
     * plus a margin). Returns null or an error description.
     */
    fun start(context: Context, channel: Channel, title: String, description: String = "", stopAtMs: Long? = null): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (active != null) return "a recording is already in progress"
        val caps = DeviceCapabilities.get(context)
        val inputId = caps.inputId ?: return "no tuner input"
        if (!caps.streamAnalysis) return "this device cannot record"
        if (freeBytes() in 0 until MIN_FREE_BYTES) return "not enough storage (${freeBytes() / 1_000_000} MB)"
        val app = context.applicationContext
        appContext = app
        recording = false
        val now = System.currentTimeMillis()
        val entry = Entry(now, channel.id, channel.number, channel.name, title.ifBlank { channel.name }, description,
            now, null, stopAtMs, null, null, Entry.RECORDING)
        active = entry
        save(app, entry)
        DebugLog.log(TAG, "start: ${channel.number} ${channel.name} \"${entry.title}\"" +
            (stopAtMs?.let { " until ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it))}" } ?: "") +
            ", free ${freeBytes() / 1_000_000} MB")
        val callback = object : TvRecordingClient.RecordingCallback() {
            override fun onTuned(channelUri: Uri) {
                DebugLog.log(TAG, "onTuned $channelUri – startRecording")
                main.removeCallbacks(tuneTimeout)
                runCatching { client?.startRecording(null) }.onSuccess { recording = true }.onFailure { fail(app, "startRecording: $it") }
            }
            override fun onRecordingStopped(recordedProgramUri: Uri) = finished(app, recordedProgramUri)
            override fun onError(code: Int) = fail(app, "onError $code" + if (code == TvInputManagerCompat.RECORDING_ERROR_RESOURCE_BUSY) " (tuner busy)" else "")
            override fun onConnectionFailed(inputId: String) = fail(app, "onConnectionFailed")
            override fun onDisconnected(inputId: String) = fail(app, "onDisconnected")
        }
        return try {
            client = TvRecordingClient(app, TAG, callback, main).also { it.tune(inputId, channel.uri) }
            stopAtMs?.let { main.postDelayed(autoStop, (it - now).coerceAtLeast(1000)) }
            main.postDelayed(spaceCheck, 30_000)
            main.postDelayed(tuneTimeout, TUNE_TIMEOUT_MS)
            notifyListeners(entry)
            null
        } catch (e: Exception) {
            fail(app, "tune: $e")
            e.toString()
        }
    }

    /** Stops the current recording; the file shows up in [onRecordingStopped][finished]. */
    fun stop(reason: String = "manual") {
        main.post {
            main.removeCallbacks(autoStop)
            val c = client ?: return@post
            DebugLog.log(TAG, "stop ($reason)")
            if (!recording) {
                // Before onTuned TvRecordingClient only logs a warning – release the session right away.
                failNow("stopped before recording started")
                return@post
            }
            runCatching { c.stopRecording() }.onFailure { DebugLog.warn(TAG, "stopRecording", it) }
            main.removeCallbacks(stopTimeout)
            main.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
        }
    }

    private fun finished(context: Context, recordedUri: Uri) {
        val entry = active ?: return
        val file = recordingFile(context, recordedUri) ?: recordingDir(entry)
        val done = entry.copy(endMs = System.currentTimeMillis(), recordedUri = recordedUri.toString(), path = file?.path,
            state = if (file != null) Entry.DONE else Entry.FAILED, error = if (file == null) "no recording file" else null)
        DebugLog.log(TAG, "finished: ${done.title}, ${(done.endMs!! - done.startMs) / 1000} s, ${done.sizeBytes / 1_000_000} MB, $recordedUri, ${done.path}")
        release(context, done)
    }

    private fun fail(context: Context, error: String) {
        val entry = active ?: return
        DebugLog.warn(TAG, "error: $error")
        release(context, entry.copy(endMs = System.currentTimeMillis(), state = Entry.FAILED, error = error))
    }

    private fun release(context: Context, entry: Entry) {
        main.removeCallbacks(autoStop)
        main.removeCallbacks(tuneTimeout)
        main.removeCallbacks(stopTimeout)
        recording = false
        main.removeCallbacks(spaceCheck)
        save(context, entry)
        active = null
        runCatching { client?.release() }
        client = null
        notifyListeners(null)
    }

    /** Records a failed start (e.g. a scheduled recording while the tuner is busy) so it shows up in the list. */
    fun recordFailure(context: Context, planned: Entry, error: String) {
        DebugLog.warn(TAG, "failed: ${planned.title} – $error")
        save(context.applicationContext, planned.copy(id = System.currentTimeMillis(), state = Entry.FAILED, error = error,
            startMs = System.currentTimeMillis(), endMs = System.currentTimeMillis()))
        notifyListeners(active)
    }

    /** Deletes a recording: the directory with the file and the entry (dtvinput's RecordedPrograms row cannot be deleted). */
    fun delete(context: Context, id: Long): Boolean {
        val entry = list(context).firstOrNull { it.id == id } ?: return false
        if (entry.state == Entry.RECORDING && active?.id == id) return false
        entry.file?.parentFile?.let { deleteRecordingDir(it) }
        entry.recordedUri?.let { u -> runCatching { context.contentResolver.delete(Uri.parse(u), null, null) } }
        synchronized(this) {
            val rest = list(context).filter { it.id != id }
            writeAll(context, rest)
        }
        DebugLog.log(TAG, "deleted recording ${entry.title} (${entry.path})")
        return true
    }

    /** A recording interrupted e.g. by the process being killed – mark it as finished with whatever was written. */
    fun recoverInterrupted(context: Context) {
        if (active != null) return
        list(context).filter { it.state == Entry.RECORDING }.forEach { e ->
            DebugLog.warn(TAG, "recording ${e.title} interrupted (process killed)")
            save(context, e.copy(state = Entry.FAILED, error = "interrupted", endMs = e.endMs ?: System.currentTimeMillis()))
        }
    }

    /** dtvinput's recordings directory (shared with [TsProbe]). */
    val recordsDir get() = File(Environment.getExternalStorageDirectory(), "Android/media/${Dtv.DTV_INPUT_PACKAGE}/Records")

    /**
     * Deletes a recording directory only if it sits directly in [recordsDir] – the path comes
     * from data (dtvinput, the list file), and the app can write to all of shared storage.
     */
    fun deleteRecordingDir(dir: File): Boolean {
        val root = runCatching { recordsDir.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        if (target.parentFile != root) {
            DebugLog.warn(TAG, "refusing to delete $dir – outside the recordings directory $root")
            return false
        }
        return target.deleteRecursively()
    }

    /**
     * dtvinput does not fill in a RECORDING_DATA_URI we can read – the recording directory is named
     * `<channel id>_<start time>`; take the newest one for the channel created after the recording started.
     */
    private fun recordingDir(entry: Entry): File? = recordsDir.listFiles()
        ?.filter { it.name.startsWith("${entry.channelId}_") && (it.name.substringAfter('_').toLongOrNull() ?: 0) >= entry.startMs - 10_000 }
        ?.maxByOrNull { it.name.substringAfter('_').toLongOrNull() ?: 0 }
        ?.let { File(it, "record.ts") }?.takeIf { it.exists() }

    private fun recordingFile(context: Context, recordedUri: Uri): File? = runCatching {
        context.contentResolver.query(recordedUri, arrayOf(TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val s = c.getString(0) ?: return@use null
            val f = File(Uri.parse(s).path ?: s)
            (if (f.isDirectory) File(f, "record.ts") else f).takeIf { it.exists() }
        }
    }.onFailure { DebugLog.warn(TAG, "reading RecordedPrograms", it) }.getOrNull()

    fun json(context: Context): JSONObject = JSONObject()
        .put("active", active?.toJson() ?: JSONObject.NULL)
        .put("scheduled", JSONArray().apply { RecordingScheduler.list(context).forEach { put(it.toJson()) } })
        .put("freeBytes", freeBytes())
        .put("recordings", JSONArray().apply {
            list(context).forEach { put(it.toJson().put("sizeBytes", it.sizeBytes).put("exists", it.file?.exists() == true)) }
        })
}

/** TvInputManager constants from API 24+ (without referencing hidden fields). */
private object TvInputManagerCompat {
    const val RECORDING_ERROR_RESOURCE_BUSY = 2
}
