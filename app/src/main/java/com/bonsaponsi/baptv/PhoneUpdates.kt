package com.bonsaponsi.baptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Updates from GitHub releases on the phone: the same [UpdateChecker] and [SelfUpdater] as on the TV, with phone
 * dialogs. On a phone the system always shows its own installation dialog (there is no accessibility service
 * clicking it), so the user confirms the update there.
 */
class PhoneUpdates(private val activity: Activity, private val io: BackgroundWorker) {

    private val handler = Handler(Looper.getMainLooper())

    /** Looks for a newer release; an automatic check (at most once a day) says nothing unless there is one. */
    fun check(manual: Boolean) {
        if (!manual && !UpdateChecker.dueForCheck(activity)) return
        if (manual) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { UpdateChecker.check(activity.applicationContext, activity.getString(R.string.store_locale)) }
            handler.post {
                if (activity.isDestroyed) return@post
                result.onSuccess { release ->
                    if (release != null) offer(release)
                    else if (manual) message(R.string.menu_update_check, activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                }.onFailure {
                    DebugLog.warn("Update", "update check failed", it)
                    if (manual) message(R.string.menu_update_check, activity.getString(R.string.update_check_failed, it.message ?: it.toString()))
                }
            }
        }
    }

    private fun offer(release: UpdateChecker.Release) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, release.version))
            .setMessage(activity.getString(R.string.update_message, BuildConfig.VERSION_NAME, release.notes))
            .setPositiveButton(R.string.update_install) { _, _ -> install(release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** Download, checks and installation ([SelfUpdater] verifies the signing key and version). */
    private fun install(release: UpdateChecker.Release) {
        val title = activity.getString(R.string.update_title, release.version)
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    runCatching {
                        activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    }.onFailure { DebugLog.warn("Update", "no settings screen for installing apps", it) }
                }
                .setNegativeButton(R.string.phone_cancel, null)
                .show()
            return
        }
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_LONG).show()
        io.execute {
            val outcome = runCatching {
                val apk = UpdateChecker.download(activity.applicationContext, release)
                if (!SelfUpdater.sameSigner(activity.applicationContext, apk)) return@runCatching false
                SelfUpdater.install(activity.applicationContext, apk)
                true
            }
            handler.post {
                if (activity.isDestroyed) return@post
                outcome.onSuccess { installed ->
                    if (installed) Toast.makeText(activity, R.string.update_confirm, Toast.LENGTH_LONG).show()
                    else message(title, activity.getString(R.string.update_other_key))
                }.onFailure {
                    DebugLog.warn("Update", "update to ${release.version} failed", it)
                    message(title, activity.getString(R.string.update_failed, it.message ?: it.toString()))
                }
            }
        }
    }

    private fun message(title: Int, text: String) = message(activity.getString(title), text)

    private fun message(title: String, text: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
    }
}
