package com.bonsaponsi.baptv

import android.app.Activity
import android.app.Application
import android.os.Bundle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        ProcessExits.logNew(this)
        // Once per start: what the other release builds hold (stage 1 of sharing, logging only).
        Thread({ SharedChannels.peerDiscovered(this).forEach { (pkg, json) ->
            DebugLog.log("Shared", "$pkg holds ${json.length} characters of discovered channels")
        } }, "shared-channels").start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Write the crash to the log – after a restart it is visible in Diagnostics.
            DebugLog.error("Crash", "Uncaught exception in thread ${thread.name}", error)
            runCatching { ReportSender.rememberCrash(this, error) }
            previous?.uncaughtException(thread, error)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                foregroundActivity = activity.javaClass.simpleName
                resumed = java.lang.ref.WeakReference(activity)
                SelfUpdater.onAppVisible(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (foregroundActivity == activity.javaClass.simpleName) foregroundActivity = null
                if (resumed?.get() === activity) resumed = null
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            // The phone remote server runs while any app screen is visible.
            override fun onActivityStarted(activity: Activity) { if (BuildConfig.TV) PhoneRemote.activityStarted(activity) }
            override fun onActivityStopped(activity: Activity) { if (BuildConfig.TV) PhoneRemote.activityStopped() }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // The phone build has no server, recordings or scans.
        if (!BuildConfig.TV) return
        // The web server comes back by itself after an update, a process restart and a set-top box reboot.
        // Debug builds only: the release code must not reference the server at all (see verifyPlayTvReleaseHasNoDevServer).
        if (BuildConfig.DEV_SERVER) DebugServerService.restoreIfEnabled(this)
        // Alarms for scheduled recordings are lost on a set-top box reboot – we set them at every process start.
        // Both write files: a full storage must not crash every process start before any screen shows.
        runCatching { Recorder.recoverInterrupted(this) }.onFailure { DebugLog.error("App", "recovering interrupted recordings", it) }
        runCatching { RecordingScheduler.rearm(this) }.onFailure { DebugLog.error("App", "re-arming recording alarms", it) }
        runCatching { ScanSession.recoverInterrupted(this) }.onFailure { DebugLog.error("App", "recovering an interrupted scan", it) }
    }

    companion object {
        /** Name of the app activity visible on screen, or null when the app is in the background. */
        @Volatile var foregroundActivity: String? = null
            private set
        /** Activity on screen – for remote keys from the web server. */
        @Volatile var resumed: java.lang.ref.WeakReference<Activity>? = null
            private set
    }
}
