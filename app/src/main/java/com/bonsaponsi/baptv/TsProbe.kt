package com.bonsaponsi.baptv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.media.tv.TvRecordingClient
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Experiment: access to the raw stream through TIF recording.
 *
 * dtvinput reports canRecord=false, but it implements a recording session that writes
 * the service stream (media.ts from the tuner file system) to shared storage:
 * Android/media/com.google.android.tv.dtvinput/Records/<channel>_<time>/record.ts.
 * From the recording, [TsAnalyzer] reads the AIT (HbbTV app URLs) and SDT (channel names).
 */
object TsProbe {
    private const val TAG = "TsProbe"
    private const val MAX_COPY_BYTES = 48L * 1024 * 1024
    private const val MAX_FAILURES = 2
    const val EXTRA_REQUEST_STORAGE = "request_storage"

    /** Channel being watched in the player (set by [MainActivity]). */
    @Volatile var currentChannelUri: Uri? = null

    /**
     * Switches the player to a channel (set by [MainActivity]). There is a single tuner:
     * recording a different multiplex than the one being watched fails with RESOURCE_BUSY,
     * while the same multiplex records in parallel with playback.
     */
    @Volatile var tuneHook: ((Long) -> Boolean)? = null

    fun probeFile(context: Context) = File(context.cacheDir, "probe.ts")

    private val recordsDir: File get() = Recorder.recordsDir

    fun hasStoragePermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    class Recording(val log: String, val analyzer: TsAnalyzer?)

    /** Blocking (web server thread): records [seconds] s of the channel, stores the HbbTV data and returns a report. */
    fun run(context: Context, channelUri: Uri?, seconds: Int): String {
        val uri = channelUri ?: currentChannelUri ?: return "No channel: pass ?channel=<id> or start the player.\n"
        val rec = record(context, uri, seconds, switchPlayer = true)
        val entry = rec.analyzer?.let { save(context, android.content.ContentUris.parseId(uri), it) }
        return rec.log + (rec.analyzer?.let { "\n" + it.report() } ?: "") +
            (entry?.let { "\nSaved channel HbbTV data: ${it.toJson()}\n" } ?: "")
    }

    /** Reason for the last failed recording (e.g. "onError 2" – tuner busy with another multiplex). */
    @Volatile var lastError: String? = null
        private set

    val lastErrorWasBusy get() = lastError == "onError 2"

    /**
     * Some dtvinput versions (seen with CBS.U14 on Android 14) refuse the recording session with onError 0
     * and drop the live session along with it, so every probe cuts the picture. After [MAX_FAILURES] failures
     * in a row, with no recording ever read on this dtvinput version, stream analysis is switched off.
     * The flag is kept per dtvinput version – a firmware update gives recording another chance.
     */
    fun unsupported(context: Context): Boolean = prefs(context).getBoolean(versionKey(context, "unsupported"), false)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("ts_probe", Context.MODE_PRIVATE)

    private fun versionKey(context: Context, name: String): String {
        val version = runCatching { context.packageManager.getPackageInfo(Dtv.DTV_INPUT_PACKAGE, 0).versionName }.getOrNull()
        return "${name}_$version"
    }

    private fun noteOutcome(context: Context, ok: Boolean, reason: String) {
        val prefs = prefs(context)
        val okKey = versionKey(context, "ok")
        if (ok) {
            prefs.edit().putBoolean(okKey, true).putInt("failures", 0).apply()
            return
        }
        if (prefs.getBoolean(okKey, false)) return
        val failures = prefs.getInt("failures", 0) + 1
        prefs.edit().putInt("failures", failures).apply()
        if (failures < MAX_FAILURES) return
        prefs.edit().putBoolean(versionKey(context, "unsupported"), true).apply()
        DebugLog.warn(TAG, "recording does not work with this dtvinput ($reason, $failures times) – stream analysis switched off")
        DeviceCapabilities.refresh(context)
    }

    /**
     * Learns a channel in the background without switching the player: DVB identifiers, AIT and EPG
     * of the whole multiplex. Returns the saved entry or null (reason in [lastError]).
     */
    fun learn(context: Context, channelId: Long, seconds: Int = 4): HbbTvStore.Entry? {
        val rec = record(context, TvContract.buildChannelUri(channelId), seconds, switchPlayer = false)
        return rec.analyzer?.let { save(context, channelId, it) }
    }

    private fun save(context: Context, channelId: Long, a: TsAnalyzer): HbbTvStore.Entry? {
        // A recording covers one service; if the stream contains several PMTs, take the service
        // whose streams have the most packets (the recorded one), not just the first one.
        val program = a.programs.values.maxByOrNull { p -> p.streams.sumOf { a.pidCounts[it.pid] ?: 0L } } ?: return null
        val ids = a.eitIds[program.number]
        val entry = HbbTvStore.Entry(
            channelId = channelId,
            onid = a.originalNetworkId ?: ids?.second,
            tsid = a.transportStreamId ?: ids?.first ?: a.patTransportStreamId,
            sid = program.number,
            scrambled = program.scrambled,
            hasVideo = program.hasVideo,
            hasAudio = program.hasAudio,
            apps = a.apps.values.mapNotNull { app ->
                app.url?.let { HbbTvStore.App(app.organisationId, app.applicationId, app.controlName, app.names.firstOrNull()?.substringAfter(':').orEmpty(), it) }
            },
            learnedAt = System.currentTimeMillis(),
        )
        HbbTvStore(context).put(entry)
        // The recording's EIT is the TV guide for the whole multiplex.
        val onid = entry.onid
        val tsid = entry.tsid
        if (onid != null && tsid != null && a.events.isNotEmpty()) EpgStore.get(context).update(onid, tsid, a.events.mapValues { it.value.values })
        DebugLog.log(TAG, "channel #$channelId: sid=${entry.sid} tsid=${entry.tsid} onid=${entry.onid} scrambled=${entry.scrambled} apps: ${entry.apps.joinToString { "${it.control} ${it.url}" }}")
        return entry
    }

    /** One recording at a time (the tuner and the recordings directory are shared). */
    @Synchronized
    private fun record(context: Context, uri: Uri, seconds: Int, switchPlayer: Boolean): Recording {
        val input = Dtv.findTunerInput(context) ?: return Recording("No tuner input.\n", null)
        // An ongoing recording holds the recording session and the recordings directory – learning will wait.
        if (Recorder.active != null) { lastError = "recording in progress"; return Recording("A programme is being recorded – skipping.\n", null) }
        if (!hasStoragePermission(context)) {
            // The permission prompt appears in the player – it has to be accepted once with the remote.
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_REQUEST_STORAGE, true))
            return Recording("No storage read permission. A prompt has appeared on screen – accept it and try again.\n", null)
        }
        val log = StringBuilder()
        fun note(msg: String) { log.appendLine(msg); DebugLog.log(TAG, msg) }
        lastError = null

        if (switchPlayer && uri != currentChannelUri) {
            val id = android.content.ContentUris.parseId(uri)
            val hook = tuneHook
            val switched = CountDownLatch(1)
            var ok = false
            Handler(Looper.getMainLooper()).post { ok = hook?.invoke(id) == true; switched.countDown() }
            switched.await(5, TimeUnit.SECONDS)
            note(if (ok) "player switched to channel #$id" else "player inactive or channel #$id missing – recording may hit a busy tuner")
            if (ok) Thread.sleep(4000)
        }

        val before = recordsDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        note("recording $uri via ${input.id} for $seconds s; recordings directory: $recordsDir (${before.size} entries, readable: ${recordsDir.canRead()})")

        val main = Handler(Looper.getMainLooper())
        val tuned = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var recordedUri: Uri? = null
        var error: String? = null
        lateinit var client: TvRecordingClient
        val callback = object : TvRecordingClient.RecordingCallback() {
            override fun onConnectionFailed(inputId: String) { error = "onConnectionFailed"; tuned.countDown(); stopped.countDown() }
            override fun onDisconnected(inputId: String) { error = error ?: "onDisconnected"; tuned.countDown(); stopped.countDown() }
            override fun onTuned(channelUri: Uri) { note("onTuned $channelUri"); tuned.countDown() }
            override fun onRecordingStopped(recordedProgramUri: Uri) { note("onRecordingStopped $recordedProgramUri"); recordedUri = recordedProgramUri; stopped.countDown() }
            override fun onError(code: Int) { error = "onError $code"; tuned.countDown(); stopped.countDown() }
        }
        val created = CountDownLatch(1)
        main.post {
            try {
                client = TvRecordingClient(context, TAG, callback, main)
                client.tune(input.id, uri)
            } catch (e: Exception) {
                error = "tune: $e"
                tuned.countDown(); stopped.countDown()
            }
            created.countDown()
        }
        created.await(5, TimeUnit.SECONDS)
        try {
            if (!tuned.await(20, TimeUnit.SECONDS)) error = error ?: "no onTuned within 20 s"
            if (error == null) {
                main.post { runCatching { client.startRecording(null) }.onFailure { error = "startRecording: $it"; stopped.countDown() } }
                Thread.sleep(seconds * 1000L)
                main.post { runCatching { client.stopRecording() }.onFailure { error = "stopRecording: $it"; stopped.countDown() } }
                if (!stopped.await(20, TimeUnit.SECONDS)) error = error ?: "no onRecordingStopped within 20 s"
            }
        } finally {
            main.post { runCatching { client.release() } }
        }
        val file = if (error == null) findRecording(context, recordedUri, before) else null
        try {
            error?.let {
                lastError = it
                note("recording session error: $it" + if (it == "onError 2") " (RECORDING_ERROR_RESOURCE_BUSY – tuner busy)" else "")
                // A busy tuner is expected (another multiplex) and says nothing about recording support.
                if (!lastErrorWasBusy) noteOutcome(context, ok = false, reason = it)
                return Recording(log.toString(), null)
            }
            if (file == null) {
                note("recording file not found; directory: ${recordsDir.listFiles()?.joinToString { it.name } ?: "unreadable"}")
                noteOutcome(context, ok = false, reason = "no recording file")
                return Recording(log.toString(), null)
            }
            note("recording file: $file (${file.length()} B)")
            val copy = probeFile(context)
            runCatching {
                file.inputStream().use { src -> copy.outputStream().use { dst -> copyLimited(src, dst, MAX_COPY_BYTES) } }
            }.onFailure {
                note("copy failed: $it")
                noteOutcome(context, ok = false, reason = "copy failed")
                return Recording(log.toString(), null)
            }
            noteOutcome(context, ok = true, reason = "")
            val analyzer = TsAnalyzer()
            // Over-the-air data: a broken stream must not abort learning – whatever was read so far is kept.
            runCatching { copy.inputStream().buffered().use { analyzer.feed(it) } }
                .onFailure { DebugLog.warn(TAG, "stream analysis aborted – partial result", it) }
            DebugLog.log(TAG, "analysis: SDT services ${analyzer.services.size}, AIT apps ${analyzer.apps.size}: " +
                analyzer.apps.values.joinToString { "${it.controlName} ${it.url}" })
            return Recording(log.toString(), analyzer)
        } finally {
            // Always clean up, also after a session error – otherwise probe recordings fill up recording storage.
            val dirs = listOfNotNull(file?.parentFile) + sessionDirs(uri, before)
            cleanup(context, recordedUri, dirs.distinct())
        }
    }

    /**
     * Directories created by this session when the file is unknown (session error): new ones with the channel ID.
     * Only when no user recording is in progress – its directory would also be "new".
     */
    private fun sessionDirs(uri: Uri, before: Set<String>): List<File> {
        if (Recorder.active != null) return emptyList()
        val prefix = "${android.content.ContentUris.parseId(uri)}_"
        return recordsDir.listFiles()?.filter { it.isDirectory && it.name !in before && it.name.startsWith(prefix) }.orEmpty()
    }

    /** Deletes the recording (RecordedPrograms row and directories) so channel learning doesn't fill up storage. */
    private fun cleanup(context: Context, recordedUri: Uri?, dirs: List<File>) {
        val rows = recordedUri?.let { runCatching { context.contentResolver.delete(it, null, null) }.getOrNull() }
        val files = dirs.map { runCatching { Recorder.deleteRecordingDir(it) }.getOrDefault(false) }
        DebugLog.log(TAG, "recording cleanup: rows=$rows, directories=${dirs.size} deleted=${files.count { it }}")
    }

    private fun findRecording(context: Context, recordedUri: Uri?, before: Set<String>): File? {
        recordedUri?.let { u ->
            runCatching {
                context.contentResolver.query(u, arrayOf(TvContract.RecordedPrograms.COLUMN_RECORDING_DATA_URI), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { s ->
                        DebugLog.log(TAG, "recording_data_uri: $s")
                        val f = File(Uri.parse(s).path ?: s)
                        val ts = if (f.isDirectory) File(f, "record.ts") else f
                        if (ts.exists()) return ts
                    }
                }
            }.onFailure { DebugLog.warn(TAG, "reading RecordedPrograms failed", it) }
        }
        // Newest new directory – but not during a user recording (it could be that recording's directory).
        if (Recorder.active != null) return null
        val fresh = recordsDir.listFiles()?.filter { it.name !in before }?.maxByOrNull { it.lastModified() }
        return fresh?.let { File(it, "record.ts") }?.takeIf { it.exists() }
    }

    private fun copyLimited(src: java.io.InputStream, dst: java.io.OutputStream, limit: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n < 0) break
            dst.write(buf, 0, n)
            total += n
        }
    }
}
