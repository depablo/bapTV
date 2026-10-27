package com.bonsaponsi.baptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Phone remote: a web page on the local network that works as a remote control and channel list on a phone.
 *
 * A phone is paired by scanning a QR code shown on the TV. The code holds a one-time pairing code that is valid
 * only while the QR code is on screen, so pairing needs someone in front of the TV. The phone then keeps a device
 * token in a cookie; only its hash is stored here. The server ([PhoneRemoteServer]) runs while an app screen is
 * visible – keys can only be delivered to this app's own windows anyway.
 */
object PhoneRemote {
    const val PORT = 8090
    private const val PREFS = "phone_remote"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICES = "devices"
    private const val PAIRING_TTL_MS = 10 * 60_000L
    /** Grace period after the last app screen closes – moving between screens must not restart the server. */
    private const val STOP_DELAY_MS = 60_000L

    class Device(val name: String, val tokenHash: String, val addedMs: Long)

    private val handler = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    @Volatile private var server: PhoneRemoteServer? = null
    private var startedActivities = 0
    @Volatile private var pairingCode: String? = null
    @Volatile private var pairingExpiresMs = 0L

    /** Error of the last start attempt (e.g. port in use), null when the server runs or is off. */
    @Volatile var lastError: String? = null
        private set
    /** Called on the main thread with the device name when a phone pairs – closes the QR dialog. */
    @Volatile var onPaired: ((String) -> Unit)? = null

    val isRunning get() = server?.isRunning == true

    private val stopIdle = Runnable { stop() }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop()
    }

    /** App screen lifecycle (from [App]) – main thread. */
    fun activityStarted(context: Context) {
        startedActivities++
        handler.removeCallbacks(stopIdle)
        if (isEnabled(context)) start(context)
    }

    fun activityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            handler.removeCallbacks(stopIdle)
            handler.postDelayed(stopIdle, STOP_DELAY_MS)
        }
    }

    @Synchronized
    private fun start(context: Context) {
        if (isRunning) return
        val s = PhoneRemoteServer(context.applicationContext, PORT)
        try {
            s.start()
            server = s
            lastError = null
        } catch (e: java.io.IOException) {
            lastError = e.message ?: e.javaClass.simpleName
            DebugLog.error("Remote", "phone remote server failed to start", e)
        }
    }

    @Synchronized
    private fun stop() {
        server?.stop()
        server = null
        pairingCode = null
    }

    /** A new one-time link for the QR code, or null when the device has no network address. */
    fun pairingLink(): String? {
        val address = Diagnostics.localAddresses().firstOrNull() ?: return null
        val code = randomHex(16)
        pairingCode = code
        pairingExpiresMs = SystemClock.elapsedRealtime() + PAIRING_TTL_MS
        return "http://$address:$PORT/pair?code=$code"
    }

    /** The QR code left the screen – its pairing code stops working. */
    fun cancelPairing() {
        pairingCode = null
    }

    /** Checks the one-time [code] and registers the device; returns the new device token, or null. */
    @Synchronized
    fun pair(context: Context, code: String?, name: String): String? {
        val expected = pairingCode ?: return null
        if (code == null || SystemClock.elapsedRealtime() > pairingExpiresMs) return null
        if (!MessageDigest.isEqual(code.toByteArray(), expected.toByteArray())) return null
        pairingCode = null
        val token = randomHex(16)
        saveDevices(context, devices(context) + Device(name, sha256(token), System.currentTimeMillis()))
        DebugLog.log("Remote", "phone paired: $name")
        handler.post { onPaired?.invoke(name) }
        return token
    }

    fun isPaired(context: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val hash = sha256(token).toByteArray()
        return devices(context).any { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash) }
    }

    fun devices(context: Context): List<Device> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_DEVICES, "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { Device(it.getString("name"), it.getString("hash"), it.optLong("added")) }
        }
    }.getOrDefault(emptyList())

    fun forgetAll(context: Context) {
        saveDevices(context, emptyList())
        DebugLog.log("Remote", "paired phones forgotten")
    }

    private fun saveDevices(context: Context, devices: List<Device>) {
        val array = JSONArray()
        devices.forEach { array.put(JSONObject().put("name", it.name).put("hash", it.tokenHash).put("added", it.addedMs)) }
        prefs(context).edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
