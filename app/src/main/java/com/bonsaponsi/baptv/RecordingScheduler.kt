package com.bonsaponsi.baptv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import java.io.File

/**
 * Recordings scheduled from the TV guide. Entries ([Recorder.Entry] in the SCHEDULED state) live in
 * `files/schedule.json`; a system alarm (RTC_WAKEUP) fires [RecordingAlarmReceiver]:
 * a warning in the player a minute before the start, then the recording starts [START_EARLY_MS] before the program.
 * Alarms are re-armed on every process start (also after a set-top box reboot – [App]).
 */
object RecordingScheduler {
    private const val TAG = "Rec"
    const val ACTION_WARN = "com.bonsaponsi.baptv.action.RECORDING_WARN"
    const val ACTION_START = "com.bonsaponsi.baptv.action.RECORDING_START"
    const val EXTRA_ID = "id"

    /** Recording starts a minute before the program and ends 5 min after it (schedules drift). */
    const val START_EARLY_MS = 60_000L
    const val END_LATE_MS = 5 * 60_000L
    /** Warning in the player before a recording starts. */
    const val WARN_BEFORE_MS = 60_000L

    /**
     * The on-screen player ([MainActivity]): the pre-start warning and the channel switch.
     * null – the app is in the background or the set-top box is in standby; recording then starts without the player.
     */
    interface Player {
        fun onRecordingUpcoming(entry: Recorder.Entry)
        /**
         * Switches the player to the recording's channel (frees the tuner from another multiplex). Result: how long to wait
         * before starting the recording (0 – channel already being watched) or an error. [Recorder] starts the recording itself.
         */
        fun switchForRecording(entry: Recorder.Entry): Result<Long>
    }
    @Volatile var player: Player? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "schedule.json")

    @Synchronized
    fun list(context: Context): List<Recorder.Entry> =
        StoredFile.readList(file(context), TAG, Recorder.Entry::fromJson).sortedBy { it.startMs }

    @Synchronized
    private fun write(context: Context, entries: List<Recorder.Entry>) {
        StoredFile.write(file(context), JSONArray().apply { entries.sortedBy { it.startMs }.forEach { put(it.toJson()) } }.toString())
    }

    fun find(context: Context, channelId: Long, programme: Programme): Recorder.Entry? =
        list(context).firstOrNull { it.channelId == channelId && it.startMs == programme.startMs }

    /** Schedules recording of a program (one in progress – from now). Returns the entry or a conflict description. */
    @Synchronized
    fun schedule(context: Context, channel: Channel, programme: Programme): Result<Recorder.Entry> {
        val now = System.currentTimeMillis()
        if (programme.endMs <= now) return Result.failure(IllegalStateException("the program has already ended"))
        val start = maxOf(programme.startMs, now)
        val stopAt = programme.endMs + END_LATE_MS
        // One tuner: two recordings must not overlap (including the margins before and after).
        val newFrom = start - START_EARLY_MS
        list(context).firstOrNull { e ->
            (e.channelId == channel.id && e.startMs == programme.startMs) ||
                (e.startMs - START_EARLY_MS < stopAt && newFrom < (e.stopAtMs ?: e.startMs))
        }?.let { return Result.failure(IllegalStateException(context.getString(R.string.schedule_conflict, it.title))) }
        val entry = Recorder.Entry(
            id = programme.startMs * 100_000 + (channel.id % 100_000), channelId = channel.id, channelNumber = channel.number,
            channelName = channel.name, title = programme.title, description = programme.description,
            startMs = programme.startMs, endMs = programme.endMs, stopAtMs = stopAt,
            recordedUri = null, path = null, state = Recorder.Entry.SCHEDULED,
        )
        write(context, list(context) + entry)
        arm(context, entry)
        DebugLog.log(TAG, "scheduled: ${channel.number} ${channel.name} \"${programme.title}\" ${fmt(programme.startMs)}–${fmt(programme.endMs)}")
        return Result.success(entry)
    }

    @Synchronized
    fun cancel(context: Context, id: Long) {
        val entry = list(context).firstOrNull { it.id == id } ?: return
        write(context, list(context).filter { it.id != id })
        alarm(context).cancel(pending(context, ACTION_WARN, id))
        alarm(context).cancel(pending(context, ACTION_START, id))
        DebugLog.log(TAG, "canceled schedule: ${entry.title}")
    }

    @Synchronized
    private fun remove(context: Context, id: Long) = write(context, list(context).filter { it.id != id })

    /** Alarms for all scheduled entries (process start, set-top box reboot); expired ones are removed. */
    @Synchronized
    fun rearm(context: Context) {
        val now = System.currentTimeMillis()
        val (stale, valid) = list(context).partition { (it.stopAtMs ?: it.startMs) <= now }
        if (stale.isNotEmpty()) {
            write(context, valid)
            stale.forEach { Recorder.recordFailure(context, it, "skipped – the set-top box was off or the app was not running") }
        }
        valid.forEach { arm(context, it) }
        if (valid.isNotEmpty()) DebugLog.log(TAG, "recording alarms: ${valid.joinToString { "${fmt(it.startMs)} ${it.title}" }}")
    }

    private fun arm(context: Context, e: Recorder.Entry) {
        val now = System.currentTimeMillis()
        val startAt = maxOf(now + 1000, e.startMs - START_EARLY_MS)
        val warnAt = startAt - WARN_BEFORE_MS
        if (warnAt > now) set(context, ACTION_WARN, e.id, warnAt)
        set(context, ACTION_START, e.id, startAt)
    }

    private fun set(context: Context, action: String, id: Long, at: Long) =
        alarm(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, action, id))

    private fun alarm(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pending(context: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, (id xor (id ushr 32)).toInt() + if (action == ACTION_START) 1 else 0,
        Intent(context, RecordingAlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Alarm: warning or start (the receiver's main thread). */
    fun onAlarm(context: Context, action: String, id: Long) {
        val entry = list(context).firstOrNull { it.id == id } ?: return
        when (action) {
            ACTION_WARN -> {
                DebugLog.log(TAG, "recording about to start: ${entry.title}")
                player?.onRecordingUpcoming(entry)
            }
            ACTION_START -> {
                DebugLog.log(TAG, "starting scheduled: ${entry.channelNumber} ${entry.channelName} \"${entry.title}\"")
                RecordingService.start(context)
                val delay = player?.switchForRecording(entry)?.getOrElse { e ->
                    remove(context, id)
                    Recorder.recordFailure(context, entry, e.message ?: e.toString())
                    return
                } ?: 0L
                // The entry leaves the schedule only at start – if the process dies in the meantime, the alarm is re-armed.
                Recorder.startLater(context, entry.toChannel(), entry.title, entry.description, entry.stopAtMs, delay) { error ->
                    remove(context, id)
                    if (error != null) Recorder.recordFailure(context, entry, error)
                }
            }
        }
    }

    private fun Recorder.Entry.toChannel() = Channel(id = channelId, number = channelNumber, name = channelName, isRadio = false, videoFormat = null)

    private fun fmt(ms: Long) = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(RecordingScheduler.EXTRA_ID, -1)
        RecordingScheduler.onAlarm(context, intent.action ?: return, id)
    }
}

/**
 * Foreground service for the duration of a recording – the app process is not killed while the player
 * is not on screen (scheduled recording, set-top box in standby). Stops together with the recording.
 */
class RecordingService : Service() {
    private val onChange: (Recorder.Entry?) -> Unit = { if (it == null) stopSelf() }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recordings_title), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.rec_badge))
            .setContentText(Recorder.active?.title ?: getString(R.string.recordings_title))
            .build())
        Recorder.listeners += onChange
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The recording may have failed to start (error) – then the service is not needed.
        android.os.Handler(mainLooper).postDelayed({ if (Recorder.active == null) stopSelf() }, 30_000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Recorder.listeners -= onChange
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RecordingService::class.java)) }
                .onFailure { DebugLog.warn("Rec", "recording service", it) }
        }
    }
}
