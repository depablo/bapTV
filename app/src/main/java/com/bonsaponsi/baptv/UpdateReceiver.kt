package com.bonsaponsi.baptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lifecycle events needed for remote work without adb:
 * installation result, completed update, set-top box reboot and on-demand process restart.
 * Process start itself restores the web server (see [App]); here we only finish the action.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SelfUpdater.ACTION_INSTALL_STATUS -> SelfUpdater.onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DebugLog.log("Update", "app updated to ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                SelfUpdater.onUpdated(context)
                SelfUpdater.relaunchIfRequested(context, "after update")
            }
            SelfUpdater.ACTION_RESTART -> SelfUpdater.relaunchIfRequested(context, "restart")
            Intent.ACTION_BOOT_COMPLETED -> DebugLog.log("App", "set-top box boot (BOOT_COMPLETED), Android up for ${android.os.SystemClock.elapsedRealtime() / 1000} s")
        }
    }
}
