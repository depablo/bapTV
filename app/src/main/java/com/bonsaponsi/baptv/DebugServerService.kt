package com.bonsaponsi.baptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps the Diagnostics web server running independently of the app screens.
 * Once enabled, the server comes back by itself after an update, a process restart and a set-top box reboot,
 * so a new version can always be uploaded and the log read through it.
 */
class DebugServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tv)
            .setContentTitle(getString(R.string.server_notification, DebugHttpServer.DEFAULT_PORT))
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DebugHttpServer.instance?.isRunning != true) {
            val server = DebugHttpServer(applicationContext)
            try {
                server.start()
                DebugHttpServer.instance = server
                lastError = null
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                DebugLog.error("Http", "Failed to start the server", e)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DebugHttpServer.instance?.stop()
        DebugHttpServer.instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "debug_server"
        private const val NOTIFICATION_ID = 1
        private const val PREFS = "debug_server"
        private const val KEY_ENABLED = "enabled"

        @Volatile var lastError: String? = null
            private set

        /** The server is a developer tool: release builds never run it. */
        val available get() = BuildConfig.DEBUG

        fun isEnabled(context: Context) =
            available && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        /** Enables/disables the server and remembers the choice (survives an update and restart). */
        fun setEnabled(context: Context, enabled: Boolean) {
            if (!available) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            val intent = Intent(context, DebugServerService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }

        /** Called at process start: restores the server if it was enabled. */
        fun restoreIfEnabled(context: Context) {
            if (!isEnabled(context)) return
            runCatching { context.startForegroundService(Intent(context, DebugServerService::class.java)) }
                .onFailure { DebugLog.error("Http", "Failed to restore the server", it) }
        }
    }
}
