package com.bonsaponsi.baptv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Brings the remote's colour keys back to the player. Some set-top boxes declare them as system-wide
 * "global keys" (`global_keys.xml`) owned by the operator's app, so Android sends them to that app and
 * never to the app on screen – HbbTV's "watch from the start" (blue) or channel list (green) cannot work.
 * An accessibility service with key filtering sees keys before that routing: while bapTV is on screen
 * the four colour keys are handed to it, otherwise they go on to the system as before.
 */
class ColourKeysService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        DebugLog.log(TAG, "colour keys service active")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in COLOUR_KEYS) return false
        val target = PlayerRemote.colourKey ?: return false
        if (App.foregroundActivity != MainActivity::class.java.simpleName) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DebugLog.log(TAG, "colour key ${KeyEvent.keyCodeToString(event.keyCode)} -> player")
        }
        val copy = KeyEvent(event)
        main.post { target(copy) }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ColourKeys"
        private val COLOUR_KEYS = setOf(
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_PROG_BLUE,
        )

        @Volatile var isConnected = false
            private set

        /** The service is switched on in the system accessibility settings (it may not be connected yet). */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { it.endsWith("/.ColourKeysService") || it.endsWith(ColourKeysService::class.java.name) }
        }
    }
}
