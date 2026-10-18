package com.bonsaponsi.baptv

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * App update over the network, without adb: an APK uploaded to the web server is verified
 * (same package, same signature, no version downgrade) and installed via [PackageInstaller].
 *
 * Android 11 always requires the user to confirm the installation. The confirmation can be
 * clicked by [InstallClickService] (accessibility service), but only when the installation was
 * requested here – see [isAwaitingConfirmation]. After the update the app starts
 * again ([relaunchIfRequested]), as long as the system allows starting an activity from the background.
 */
object SelfUpdater {
    private const val TAG = "Update"
    private const val PREFS = "self_update"
    private const val KEY_PENDING_SINCE = "pending_since"
    private const val KEY_RELAUNCH_SINCE = "relaunch_since"
    private const val KEY_LAST_RESULT = "last_result"

    /** How long we wait for the installation confirmation and for the relaunch. */
    private const val PENDING_WINDOW_MS = 3 * 60_000L

    const val ACTION_INSTALL_STATUS = "com.bonsaponsi.baptv.INSTALL_STATUS"
    const val ACTION_RESTART = "com.bonsaponsi.baptv.RESTART"

    /** Known system installer packages (AOSP and Google). */
    val INSTALLER_PACKAGES = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")

    class Rejected(message: String, val httpStatus: String = "400 Bad Request") : Exception(message)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Closes the tuner session (TV picture, HbbTV) before committing the installation. The installer kills the process;
     * with a dtvinput session open, Android sometimes rebooted during the installation – closing the session
     * gracefully is meant to prevent that.
     */
    private fun releaseTunerBeforeInstall() {
        val release = PlayerRemote.releaseTuner ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { release() }.onFailure { DebugLog.warn(TAG, "releasing the tuner before installation", it) }
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
        DebugLog.log(TAG, "tuner released before installation")
        // A moment for dtvinput to close the session and the frontend.
        Thread.sleep(1500)
    }

    fun updateFile(context: Context) = File(context.cacheDir, "update.apk")

    /**
     * Verifies the uploaded APK and starts the installation. Returns a description for the HTTP client;
     * throws [Rejected] if verification fails.
     */
    fun install(context: Context, apk: File): String {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: throw Rejected("Not a valid APK file")
        if (archive.packageName != context.packageName) {
            throw Rejected("APK has package ${archive.packageName}, expected ${context.packageName}")
        }
        // Installation kills the process – an ongoing recording would be lost.
        Recorder.active?.let { throw Rejected("Recording “${it.title}” in progress – update after it finishes", "409 Conflict") }
        val installed = installedInfo(pm, context.packageName)
        val newSigners = signerDigests(archive)
        val oldSigners = signerDigests(installed)
        if (newSigners.isEmpty() || newSigners != oldSigners) {
            throw Rejected("APK signed with a different key (${newSigners.joinToString { it.take(16) }}) than the installed app (${oldSigners.joinToString { it.take(16) }})")
        }
        if (archive.versionCodeCompat() < installed.versionCodeCompat()) {
            throw Rejected("APK has a lower versionCode (${archive.versionCodeCompat()}) than the installed one (${installed.versionCodeCompat()}) – Android does not allow downgrades")
        }
        if (!pm.canRequestPackageInstalls()) {
            throw Rejected(
                "No permission to install apps from unknown sources. Grant it on the set-top box: Diagnostics → “Allow installing”.",
                "409 Conflict",
            )
        }

        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_PENDING_SINCE, now)
            .putLong(KEY_RELAUNCH_SINCE, now)
            .putString(KEY_LAST_RESULT, "installation of ${archive.versionName} (${archive.versionCodeCompat()}) started")
            // commit() on purpose (not apply): the installer is about to kill the process, the write must hit the disk before that.
            .commit()

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Since Android 12 a self-update can proceed without confirmation; this does not work on 11.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            releaseTunerBeforeInstall()
            session.commit(statusIntent(context).intentSender)
        }
        val desc = "${archive.versionName} (${archive.versionCodeCompat()}), ${apk.length()} B"
        DebugLog.log(TAG, "install session $sessionId: $desc; auto-confirm service: ${if (InstallClickService.isConnected) "active" else "disabled"}")
        return buildString {
            append("Installation started: $desc, session $sessionId.\n")
            if (InstallClickService.isConnected) {
                append("The accessibility service will click the confirmation.\n")
            } else {
                append("Confirm the installation with the remote on the set-top box (or enable the “DVB-T: automatic installation” accessibility service).\n")
            }
        }
    }

    /** Installation session result (from [UpdateReceiver]). */
    fun onInstallStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                DebugLog.log(TAG, "installer asks for confirmation – opening the dialog")
                if (confirm != null) {
                    // Confirmation dialog: starting from the background works when the app is on screen or
                    // when the accessibility service is enabled (system exemption).
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { DebugLog.error(TAG, "failed to open the confirmation", it) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                DebugLog.log(TAG, "installation succeeded")
                finish(context, "installed")
            }
            else -> {
                DebugLog.warn(TAG, "installation failed: status=$status $message")
                prefs(context).edit().remove(KEY_RELAUNCH_SINCE).apply()
                finish(context, "error: status=$status $message")
            }
        }
    }

    private fun finish(context: Context, result: String) {
        prefs(context).edit().remove(KEY_PENDING_SINCE).putString(KEY_LAST_RESULT, result).apply()
    }

    /** Whether an installation requested by the app is in progress (only then does the accessibility service click “Install”). */
    fun isAwaitingConfirmation(context: Context): Boolean {
        val since = prefs(context).getLong(KEY_PENDING_SINCE, 0)
        return since > 0 && System.currentTimeMillis() - since < PENDING_WINDOW_MS
    }

    /** The new version is running – installation finished, the accessibility service stops watching. */
    fun onUpdated(context: Context) {
        finish(context, "installed")
    }

    fun pendingSince(context: Context): Long = prefs(context).getLong(KEY_PENDING_SINCE, 0)

    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)

    /**
     * Launches the player if an update or restart was requested recently.
     * Called from several places (receiver, accessibility service), because it is not known
     * which of them the system will allow to start an activity from the background.
     */
    fun relaunchIfRequested(context: Context, from: String) {
        val since = prefs(context).getLong(KEY_RELAUNCH_SINCE, 0)
        if (since == 0L || System.currentTimeMillis() - since > PENDING_WINDOW_MS) return
        if (App.foregroundActivity != null) return
        DebugLog.log(TAG, "relaunching the player ($from)")
        launch(context)
    }

    /**
     * The app is on screen – no need to relaunch it any more. Only a process started after
     * the request counts: the old process returns to the screen after the installer dialog closes
     * and keeps running for several tens of seconds before the installer stops it.
     */
    fun onAppVisible(context: Context) {
        val p = prefs(context)
        val since = p.getLong(KEY_RELAUNCH_SINCE, 0)
        if (since != 0L && DebugLog.bootId > since) p.edit().remove(KEY_RELAUNCH_SINCE).apply()
    }

    /** Launches the player. From the background it only works with the accessibility service enabled or the overlay permission. */
    fun launch(context: Context): String {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            "Launch command sent. Start from background possible: ${if (canStartFromBackground(context)) "yes" else "no (enable the accessibility service)"}."
        } catch (e: Exception) {
            DebugLog.error(TAG, "failed to launch the player", e)
            "Launch error: $e"
        }
    }

    fun canStartFromBackground(context: Context) =
        InstallClickService.isConnected || Settings.canDrawOverlays(context)

    /** Process restart: kills the current process, and an alarm starts the app again shortly after. */
    fun restart(context: Context) {
        // commit() on purpose – we are about to kill the process.
        prefs(context).edit().putLong(KEY_RELAUNCH_SINCE, System.currentTimeMillis()).commit()
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, UpdateReceiver::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pi)
        DebugLog.log(TAG, "process restart on request")
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    private fun statusIntent(context: Context): PendingIntent {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        // The system adds the installation result to the intent, so on Android 12+ it must be mutable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(
            context, 0,
            Intent(context, UpdateReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
            flags,
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }

    /** SHA-256 of the signing certificates (hex). */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
