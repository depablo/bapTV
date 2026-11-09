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

    /**
     * On targetSdk >= 30 the app can delete neither the recording file nor dtvinput's RecordedPrograms row,
     * so probes are kept under one second. dtvinput then deletes its own row (DtvRecordSession treats a
     * completed recording shorter than 1 s as empty) and its observer removes the orphaned directory with
     * dtvinput's own file ownership, so nothing accumulates. Verified on a set-top box, targetSdk 35:
     * the AIT and the DVB triplet are still captured at 300-700 ms. 500 ms keeps a safe margin below 1000 ms.
     */
    private const val SELF_CLEAN_MS = 500L

    /**
     * In the self-clean regime a single sub-second clip often misses the AIT (the HbbTV carousel repeats about
     * once a second), so we take up to this many clips and merge them into one analyzer. The DVB triplet already
     * comes on the first clip; extra clips are only to catch the AIT. We stop early once both are present.
     */
    private const val SELF_CLEAN_PASSES = 3

    /** Pause between clips so dtvinput releases the recording session before the next tune. */
    private const val SETTLE_MS = 400L
    /** How long [selfCleanTest] waits for dtvinput to remove the clip; removals were seen up to 12 s after the stop. */
    private const val SELF_CLEAN_WAIT_S = 20

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

    /** Debug listing of the tuner input's Records directory: one entry per recording directory, newest first. */
    /**
     * Lists the Records directory. For the directories named in [positionsOf] (or all, with "all") it also parses
     * record.ts for the dtvfs positions that decide dtvinput's self-clean (slow: reads each whole file).
     */
    fun recordsJson(context: Context, positionsOf: Set<String> = emptySet()): org.json.JSONArray {
        val out = org.json.JSONArray()
        val dirs = recordsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()
        for (dir in dirs) {
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            val entry = org.json.JSONObject()
                .put("name", dir.name)
                .put("bytes", files.sumOf { it.length() })
                .put("files", files.size)
                .put("modified", dir.lastModified())
            if ("all" in positionsOf || dir.name in positionsOf) {
                val a = TsAnalyzer()
                runCatching { File(dir, "record.ts").inputStream().buffered().use { a.feed(it, MAX_COPY_BYTES) } }
                entry.put("positions", a.positionsText()).put("positionsDurationMs", a.positionsDurationMs() ?: -1)
            }
            out.put(entry)
        }
        return out
    }

    fun hasStoragePermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Legacy storage (targetSdk < 30) lets the app delete the recordings it reads; otherwise rely on dtvinput's self-clean. */
    private fun canDeleteRecordings(context: Context) = context.applicationInfo.targetSdkVersion < 30

    /**
     * True when the app cannot delete dtvinput's recordings (targetSdk >= 30) and must rely on dtvinput's self-clean
     * of sub-second clips. Callers use it to time recordings into the self-clean window (identify ~1.5 s after a tune,
     * HbbTV/EPG after a ~10 s dwell); on legacy storage the timing does not matter because the app deletes the files.
     */
    fun selfCleanRegime(context: Context) = !canDeleteRecordings(context)

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
    fun learn(context: Context, channelId: Long, seconds: Int = 4, maxPasses: Int = SELF_CLEAN_PASSES): HbbTvStore.Entry? {
        val rec = record(context, TvContract.buildChannelUri(channelId), seconds, switchPlayer = false, maxPasses = maxPasses)
        return rec.analyzer?.let { save(context, channelId, it) }
    }

    /**
     * Debug diagnostic: checks whether dtvinput cleans up a sub-second recording by itself on this device/firmware,
     * where on targetSdk 35 the app can delete neither the file nor the RecordedPrograms row. Self-clean is device
     * dependent, so this stays as a tool to verify it on other boxes before relying on it there.
     *
     * Mechanism under test: DtvRecordSession.updateRecordEntry() deletes its OWN RecordedPrograms row when a
     * completed recording measures a duration below 1000 ms; its DtvRecordedProgramsObserver then removes the
     * now-unreferenced record directory with dtvinput's own file ownership (works on any targetSdk).
     *
     * The test records [ms] ms, parses AIT/SDT/EIT from the file, and then polls the row and the directory for
     * a few seconds WITHOUT running the app's own cleanup, so any disappearance is dtvinput's doing.
     */
    @Synchronized
    fun selfCleanTest(context: Context, channelUri: Uri?, ms: Long): String {
        val uri = channelUri ?: currentChannelUri
            ?: return "No channel: park the player on a channel (ideally one with HbbTV, e.g. TVP) or pass ?channel=<id>.\n"
        val input = Dtv.findTunerInput(context) ?: return "No tuner input.\n"
        if (Recorder.active != null) return "A programme is being recorded – skipping.\n"
        if (!hasStoragePermission(context)) return "No storage read permission – accept it once in the player and retry.\n"
        val log = StringBuilder()
        fun note(msg: String) { log.appendLine(msg); DebugLog.log(TAG, msg) }

        val before = recordsDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        note("self-clean test: recording $uri via ${input.id} for $ms ms (targetSdk ${context.applicationInfo.targetSdkVersion}); " +
            "dir ${before.size} entries, free ${Recorder.freeBytes() / 1_000_000} MB")

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
            try { client = TvRecordingClient(context, TAG, callback, main); client.tune(input.id, uri) }
            catch (e: Exception) { error = "tune: $e"; tuned.countDown(); stopped.countDown() }
            created.countDown()
        }
        created.await(5, TimeUnit.SECONDS)
        if (!tuned.await(20, TimeUnit.SECONDS)) error = error ?: "no onTuned within 20 s"
        if (error == null) {
            main.post { runCatching { client.startRecording(null) }.onFailure { error = "startRecording: $it"; stopped.countDown() } }
            Thread.sleep(ms)
            main.post { runCatching { client.stopRecording() }.onFailure { error = "stopRecording: $it"; stopped.countDown() } }
            if (!stopped.await(20, TimeUnit.SECONDS)) error = error ?: "no onRecordingStopped within 20 s"
        }
        main.post { runCatching { client.release() } }
        error?.let { note("ERROR: $it") }

        // The newest directory that appeared during this session; parse it before dtvinput may remove it.
        val recDir = recordsDir.listFiles()?.filter { it.isDirectory && it.name !in before }
            ?.maxByOrNull { it.name.substringAfter('_').toLongOrNull() ?: 0 }
        val tsFile = recDir?.let { File(it, "record.ts") }?.takeIf { it.exists() }
        note("record dir: ${recDir?.name ?: "none"}; file: ${tsFile?.let { "${it.length()} B" } ?: "none"}")
        var analyzed: TsAnalyzer? = null
        if (tsFile != null) {
            val a = TsAnalyzer().also { analyzed = it }
            runCatching { tsFile.inputStream().buffered().use { a.feed(it, MAX_COPY_BYTES) } }
                .onFailure { note("analysis aborted (partial): $it") }
            note("captured in $ms ms -> AIT apps=${a.apps.size}, SDT services=${a.services.size}, " +
                "PMT programs=${a.programs.size}, EIT events=${a.events.values.sumOf { it.size }}" +
                (if (a.apps.isNotEmpty()) "; apps: " + a.apps.values.joinToString { "${it.controlName} ${it.url}" } else ""))
            // The DVB triplet derived exactly as save() does, to check identification survives a sub-second probe.
            val prog = a.programs.values.maxByOrNull { p -> p.streams.sumOf { a.pidCounts[it.pid] ?: 0L } }
            val ids = prog?.let { a.eitIds[it.number] }
            note("identification: sid=${prog?.number} onid=${a.originalNetworkId ?: ids?.second} " +
                "tsid=${a.transportStreamId ?: ids?.first ?: a.patTransportStreamId} scrambled=${prog?.scrambled} " +
                "video=${prog?.hasVideo} audio=${prog?.hasAudio}")
            note(positionsNote(a))
        }
        val predictedKept = analyzed?.let { (it.positionsDurationMs() ?: -1) >= 1000 }

        fun rowExists(u: Uri?): Boolean = u != null && runCatching {
            context.contentResolver.query(u, arrayOf(TvContract.RecordedPrograms._ID), null, null, null)?.use { it.moveToFirst() } == true
        }.getOrDefault(false)

        note("t=0s: row=${rowExists(recordedUri)}, dir=${recDir?.exists() == true}, row duration ${recordedDurationMs(context, recordedUri) ?: "-"} ms")
        var cleaned = false
        for (i in 1..SELF_CLEAN_WAIT_S) {
            Thread.sleep(1000)
            val r = rowExists(recordedUri)
            val d = recDir?.exists() == true
            note("t=${i}s: row=$r, dir=$d")
            if (!r && !d) { note("RESULT: dtvinput self-cleaned after ${i}s (app ran no delete)"); cleaned = true; break }
        }
        if (!cleaned) note("RESULT: not self-cleaned within $SELF_CLEAN_WAIT_S s (row=${rowExists(recordedUri)}, dir=${recDir?.exists() == true})")
        predictedKept?.let { note("PREDICTION: " + if (it == !cleaned) "matched" else "WRONG (expected ${if (it) "kept" else "self-clean"})") }

        // Leave nothing behind regardless of the outcome (on targetSdk 35 both are expected to be no-ops).
        recordedUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        recDir?.let { runCatching { Recorder.deleteRecordingDir(it) } }
        return log.toString()
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
    private fun record(context: Context, uri: Uri, seconds: Int, switchPlayer: Boolean, maxPasses: Int = SELF_CLEAN_PASSES): Recording {
        val input = Dtv.findTunerInput(context) ?: return Recording("No tuner input.\n", null)
        // An ongoing recording holds the recording session and the recordings directory – learning will wait.
        if (Recorder.active != null) { lastError = "recording in progress"; return Recording("A programme is being recorded – skipping.\n", null) }
        // Probe recordings stay on shared storage and the app cannot delete them on targetSdk 35: when space runs
        // low they are not made, so the system and the app's own files keep their room.
        val free = Recorder.freeBytes()
        if (free in 0 until Recorder.MIN_FREE_BYTES) {
            lastError = "low storage (${free / 1_000_000} MB)"
            return Recording("Not enough free storage (${free / 1_000_000} MB) – channel analysis skipped.\n", null)
        }
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
            val ok = MainThread.call { hook?.invoke(id) } == true
            note(if (ok) "player switched to channel #$id" else "player inactive or channel #$id missing – recording may hit a busy tuner")
            if (ok) Thread.sleep(4000)
        }

        val legacy = canDeleteRecordings(context)
        // On targetSdk >= 30 keep each clip below one second so dtvinput deletes it itself (see [SELF_CLEAN_MS]) and
        // repeat, merging all clips into one analyzer: a single sub-second clip often misses the AIT (the HbbTV
        // carousel repeats ~1 s), while the DVB triplet comes on the first clip. On legacy storage one longer clip is
        // enough (we delete it ourselves). [TsAnalyzer] accumulates across feeds, so merging is just feeding each clip.
        val recordMs = if (legacy) seconds * 1000L else SELF_CLEAN_MS
        // The self-clean window depends on WHEN this runs after the caller tuned the channel: identification records
        // one clip ~1.5 s after the tune (triplet is there, clip self-cleans), HbbTV/EPG after a ~10 s dwell (AIT is
        // there, stream stable). The caller owns that timing; record() just takes the clip(s) now.
        val passes = if (legacy) 1 else maxPasses
        note("analysing $uri via ${input.id}: up to $passes clip(s) of $recordMs ms (requested ${seconds}s), free ${Recorder.freeBytes() / 1_000_000} MB")

        val analyzer = TsAnalyzer()
        var fed = false
        for (pass in 1..passes) {
            val r = recordPass(context, input.id, uri, recordMs, ::note)
            var stop = false
            try {
                when {
                    r.error != null -> {
                        lastError = r.error
                        note("recording session error: ${r.error}" + if (r.error == "onError 2") " (RECORDING_ERROR_RESOURCE_BUSY – tuner busy)" else "")
                        // A busy tuner is expected (another multiplex) and says nothing about recording support.
                        if (!fed) {
                            if (!lastErrorWasBusy) noteOutcome(context, ok = false, reason = r.error)
                            return Recording(log.toString(), null)
                        }
                        stop = true // keep whatever earlier clips gathered
                    }
                    r.file == null -> {
                        note("recording file not found; directory: ${recordsDir.listFiles()?.joinToString { it.name } ?: "unreadable"}")
                        if (!fed) { noteOutcome(context, ok = false, reason = "no recording file"); return Recording(log.toString(), null) }
                        stop = true
                    }
                    r.input == null -> {
                        // Found, but dtvinput removed it before we could open it: nothing to analyse, take the next clip.
                        note("clip $pass/$passes vanished before it could be read")
                    }
                    else -> {
                        note("clip $pass/$passes: ${r.file} (${r.file.length()} B)")
                        val positionsFrom = analyzer.positions.size
                        // Over-the-air data: a broken stream must not abort learning – whatever was read so far is kept.
                        runCatching { r.input.buffered().use { analyzer.feed(it, MAX_COPY_BYTES) } }
                            .onFailure { DebugLog.warn(TAG, "stream analysis aborted – partial result", it) }
                        note("clip $pass/$passes " + positionsNote(analyzer, positionsFrom))
                        fed = true
                        // Keep the latest clip for the /probe.ts download (manual probe only); best effort.
                        if (switchPlayer) runCatching {
                            r.file.inputStream().use { src -> probeFile(context).outputStream().use { dst -> copyLimited(src, dst, MAX_COPY_BYTES) } }
                        }
                        if (hasEnough(analyzer)) { note("got the AIT and the DVB triplet after clip $pass"); stop = true }
                    }
                }
            } finally {
                // Always clean up (on targetSdk 29); on 35 these are no-ops – dtvinput removes the short clip itself.
                val dirs = listOfNotNull(r.file?.parentFile) + sessionDirs(uri, r.before)
                cleanup(context, r.recordedUri, dirs.distinct())
            }
            if (stop) break
            if (pass < passes) Thread.sleep(SETTLE_MS) // let dtvinput release the session before the next tune
        }
        if (!fed) return Recording(log.toString(), null)
        noteOutcome(context, ok = true, reason = "")
        DebugLog.log(TAG, "analysis: SDT services ${analyzer.services.size}, AIT apps ${analyzer.apps.size}: " +
            analyzer.apps.values.joinToString { "${it.controlName} ${it.url}" })
        return Recording(log.toString(), analyzer)
    }

    /**
     * [input] is the clip opened at once after it was found. dtvinput removes a sub-second clip soon after it stops, but
     * an open descriptor still reads the data after the unlink; a null [input] with a [file] means it vanished first.
     */
    private class PassResult(
        val file: File?,
        val recordedUri: Uri?,
        val before: Set<String>,
        val error: String?,
        val input: java.io.InputStream? = null,
    )

    /** One tune + record ([recordMs]) + stop + release. Returns the written file (dtvinput self-cleans short clips). */
    private fun recordPass(context: Context, tunerInputId: String, uri: Uri, recordMs: Long, note: (String) -> Unit): PassResult {
        val before = recordsDir.listFiles()?.map { it.name }?.toSet().orEmpty()
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
                client.tune(tunerInputId, uri)
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
                Thread.sleep(recordMs)
                main.post { runCatching { client.stopRecording() }.onFailure { error = "stopRecording: $it"; stopped.countDown() } }
                if (!stopped.await(20, TimeUnit.SECONDS)) error = error ?: "no onRecordingStopped within 20 s"
            }
        } finally {
            main.post { runCatching { client.release() } }
        }
        val file = if (error == null) findRecording(context, recordedUri, before) else null
        val input = file?.let { runCatching { it.inputStream() }.getOrNull() }
        // Measurement: the duration dtvinput gives the recording, read before the row can be removed (self-clean < 1000 ms).
        note("measure: requested $recordMs ms, dtvinput duration ${recordedDurationMs(context, recordedUri) ?: "-"} ms, file ${file?.length() ?: "-"} B")
        return PassResult(file, recordedUri, before, error, input)
    }

    /** recording_duration_millis of a RecordedPrograms row; null when the row is gone (self-cleaned) or unreadable. */
    private fun recordedDurationMs(context: Context, recordedUri: Uri?): Long? = recordedUri?.let { u ->
        runCatching {
            context.contentResolver.query(u, arrayOf(TvContract.RecordedPrograms.COLUMN_RECORDING_DURATION_MILLIS), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    /** Line describing a clip's dtvfs positions and the duration dtvinput derives from them (see [TsAnalyzer.positions]). */
    private fun positionsNote(a: TsAnalyzer, from: Int = 0): String {
        val ms = a.positionsDurationMs(from) ?: -1
        return "positions (PID 23): ${a.positionsText(from)} -> dtvinput duration $ms ms, expect ${if (ms < 1000) "self-clean" else "kept"}"
    }

    /** Enough gathered to stop multi-pass learning: the DVB triplet and at least one HbbTV app. */
    private fun hasEnough(a: TsAnalyzer): Boolean {
        val prog = a.programs.values.maxByOrNull { p -> p.streams.sumOf { a.pidCounts[it.pid] ?: 0L } } ?: return false
        val ids = a.eitIds[prog.number]
        val onid = a.originalNetworkId ?: ids?.second
        val tsid = a.transportStreamId ?: ids?.first ?: a.patTransportStreamId
        return a.apps.isNotEmpty() && onid != null && tsid != null
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
