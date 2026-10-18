package com.bonsaponsi.baptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle

/**
 * The operator's own live TV app, if the device has one: any other installed app that can open a TIF
 * channel (`ACTION_VIEW` of `vnd.android.cursor.item/channel`). Used for "Open current channel in …",
 * for scanning with the operator app, and for a launcher shortcut when that app has no launcher entry
 * of its own (some operator apps register only as HOME).
 */
object OperatorApp {
    private const val TAG = "Operator"
    private const val CHANNEL_TYPE = "vnd.android.cursor.item/channel"

    class Info(val packageName: String, val label: String, val viewActivity: ComponentName) {
        fun launchIntent(context: Context): Intent? = context.packageManager.let { pm ->
            pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        }
    }

    fun find(context: Context): Info? = runCatching {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(TvContract.buildChannelUri(1), CHANNEL_TYPE)
        pm.queryIntentActivities(probe, 0)
            .map { it.activityInfo }
            .firstOrNull { it.packageName != context.packageName && it.exported }
            ?.let { Info(it.packageName, it.applicationInfo.loadLabel(pm).toString(), ComponentName(it.packageName, it.name)) }
    }.getOrNull()

    /**
     * Opens the operator app; with [channelUri] it first asks the app to tune to that channel and falls
     * back to the app's main screen.
     */
    fun launch(context: Context, channelUri: Uri?): Boolean {
        val app = find(context)
        if (app == null) {
            TvDialog.toast(context, context.getString(R.string.operator_launch_failed), long = true)
            return false
        }
        val attempts = buildList {
            if (channelUri != null) add(Intent(Intent.ACTION_VIEW).setDataAndType(channelUri, CHANNEL_TYPE).setComponent(app.viewActivity))
            app.launchIntent(context)?.let { add(it) }
            add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(app.viewActivity))
            add(Intent(Intent.ACTION_MAIN).setComponent(app.viewActivity))
        }
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                DebugLog.log(TAG, "started ${app.label}: $intent")
                return true
            } catch (e: ActivityNotFoundException) {
                DebugLog.warn(TAG, "failed: $intent", e)
            } catch (e: SecurityException) {
                DebugLog.warn(TAG, "denied: $intent", e)
            }
        }
        TvDialog.toast(context, context.getString(R.string.operator_launch_failed), long = true)
        return false
    }

    /** Shows the launcher shortcut only when the operator app exists but has no launcher entry. */
    fun syncShortcut(context: Context) {
        val app = find(context)
        val wanted = app != null && app.launchIntent(context) == null
        val component = ComponentName(context, OperatorShortcutActivity::class.java)
        val state = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (context.packageManager.getComponentEnabledSetting(component) != state) {
                context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                DebugLog.log(TAG, "launcher shortcut ${if (wanted) "enabled for ${app?.label}" else "disabled"}")
            }
        }.onFailure { DebugLog.warn(TAG, "launcher shortcut", it) }
    }
}

/** Launcher tile that opens the operator app (enabled by [OperatorApp.syncShortcut]). */
class OperatorShortcutActivity : Activity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OperatorApp.launch(this, null)
        finish()
    }
}
