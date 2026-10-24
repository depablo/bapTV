package com.bonsaponsi.baptv

import android.app.Activity
import android.app.Application
import android.os.Bundle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
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
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // The web server comes back by itself after an update, a process restart and a set-top box reboot.
        DebugServerService.restoreIfEnabled(this)
        // Alarms for scheduled recordings are lost on a set-top box reboot – we set them at every process start.
        Recorder.recoverInterrupted(this)
        RecordingScheduler.rearm(this)
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
