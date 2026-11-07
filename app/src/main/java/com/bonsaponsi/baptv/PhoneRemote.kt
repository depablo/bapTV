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
    val PORT = BuildConfig.PHONE_REMOTE_PORT
    private const val PREFS = "phone_remote"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DEVICES = "devices"
    private const val KEY_ADDRESS = "address"
    private const val PAIRING_TTL_MS = 10 * 60_000L
    /** Time to answer a pairing request on the TV. */
    private const val APPROVAL_TTL_MS = 60_000L
    private const val SHORT_CODE_LENGTH = 6
    private const val MAX_WRONG_ATTEMPTS = 5
    private const val MAX_DEVICES = 20
    /** Grace period after the last app screen closes – moving between screens must not restart the server. */
    private const val STOP_DELAY_MS = 60_000L
    private const val USED_UPDATE_MS = 3600_000L

    /** A paired phone; [lastUsedMs] is updated at most every [USED_UPDATE_MS], for evicting the least used one. */
    class Device(val name: String, val tokenHash: String, val addedMs: Long, val lastUsedMs: Long = addedMs)

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
            RemoteAdvertiser.start(context, PORT)
        } catch (e: java.io.IOException) {
            lastError = e.message ?: e.javaClass.simpleName
            DebugLog.error("Remote", "phone remote server failed to start", e)
        }
    }

    @Synchronized
    private fun stop() {
        RemoteAdvertiser.stop()
        server?.stop()
        server = null
        pairingCode = null
        shortCode = null
    }

    /**
     * The set-top box's new network address when it differs from the one the phones were paired at (null otherwise),
     * remembered from now on. A browser remote is bound to the old address (bookmark, home-screen icon, cookie);
     * the Android app finds the box by itself. Network queries – not on the main thread.
     */
    fun takeAddressChange(context: Context): String? {
        if (!isEnabled(context) || devices(context).isEmpty()) return null
        val now = Diagnostics.localAddresses().firstOrNull() ?: return null
        val old = prefs(context).getString(KEY_ADDRESS, null)
        if (old == now) return null
        prefs(context).edit().putString(KEY_ADDRESS, now).apply()
        // Pairings from before this check knew no address – nothing to compare with yet.
        return old?.let { now }
    }

    /** A new one-time link for the QR code, or null when the device has no network address. */
    fun pairingLink(): String? {
        val address = Diagnostics.localAddresses().firstOrNull() ?: return null
        val code = randomHex(16)
        pairingCode = code
        shortCode = (0 until SHORT_CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        wrongAttempts = 0
        pairingExpiresMs = SystemClock.elapsedRealtime() + PAIRING_TTL_MS
        return "http://$address:$PORT/pair?code=$code"
    }

    /**
     * The same pairing as the QR code, typed on the phone: a home-screen web app on iOS keeps its own cookies,
     * so it pairs again from its "not paired" page. Valid as long as the QR code; [MAX_WRONG_ATTEMPTS] wrong
     * codes end it (the QR code has to be opened again).
     */
    @Volatile var shortCode: String? = null
        private set
    private var wrongAttempts = 0

    /** The QR code left the screen – its pairing code stops working. */
    fun cancelPairing() {
        pairingCode = null
        shortCode = null
    }

    /** Checks the one-time [code] and registers the device; returns the new device token, or null. */
    @Synchronized
    fun pair(context: Context, code: String?, name: String): String? {
        val expected = pairingCode ?: return null
        if (code == null || SystemClock.elapsedRealtime() > pairingExpiresMs) return null
        val typed = code.filter { it.isDigit() }
        val short = shortCode
        val matches = MessageDigest.isEqual(code.toByteArray(), expected.toByteArray()) ||
            (short != null && typed.length == SHORT_CODE_LENGTH && MessageDigest.isEqual(typed.toByteArray(), short.toByteArray()))
        if (!matches) {
            if (++wrongAttempts >= MAX_WRONG_ATTEMPTS) {
                DebugLog.warn("Remote", "$wrongAttempts wrong pairing codes – pairing closed until the QR code is shown again")
                pairingCode = null
                shortCode = null
            }
            return null
        }
        pairingCode = null
        shortCode = null
        return addDevice(context, name)
    }

    /** Registers a phone that proved itself (code or confirmation on the TV); returns its new token. */
    private fun addDevice(context: Context, name: String): String {
        val token = randomHex(16)
        // Bounded: each pairing adds an entry; beyond the limit the phone used longest ago has to pair again.
        val devices = devices(context) + Device(name, sha256(token), System.currentTimeMillis())
        saveDevices(context, devices.sortedByDescending { it.lastUsedMs }.take(MAX_DEVICES))
        Diagnostics.localAddresses().firstOrNull()?.let { prefs(context).edit().putString(KEY_ADDRESS, it).apply() }
        DebugLog.log("Remote", "phone paired: $name")
        handler.post { onPaired?.invoke(name) }
        return token
    }

    enum class ApprovalState { WAITING, ACCEPTED, REJECTED, EXPIRED }

    /** A phone app's request to pair, answered on the TV with the remote. [id] is known only to that phone. */
    private class Approval(val id: String, val name: String, val expiresMs: Long) {
        var state = ApprovalState.WAITING
        var token: String? = null
    }

    private var approval: Approval? = null

    /**
     * Asks on the TV whether to pair the phone [name]; set by the player while it is on screen. The answer goes to
     * the callback; one that comes after [APPROVAL_TTL_MS] is ignored.
     */
    @Volatile var onApprovalRequest: ((name: String, ttlMs: Long, answer: (Boolean) -> Unit) -> Unit)? = null

    /**
     * Pairing without a code: the request is shown on the TV and someone in front of it accepts it. One request at a
     * time, so a device on the network cannot pile questions up on the screen. Returns the request ID the phone
     * asks [approvalStatus] with, or null when no question can be shown now (player not on screen, one pending).
     */
    @Synchronized
    fun requestApproval(context: Context, name: String): String? {
        val ask = onApprovalRequest ?: return null
        val now = SystemClock.elapsedRealtime()
        approval?.let { if (it.state == ApprovalState.WAITING && now < it.expiresMs) return null }
        val request = Approval(randomHex(16), name, now + APPROVAL_TTL_MS)
        approval = request
        DebugLog.log("Remote", "pairing request from \"$name\" – asking on the TV")
        val app = context.applicationContext
        handler.post { ask(name, APPROVAL_TTL_MS) { accept -> answer(app, request, accept) } }
        return request.id
    }

    @Synchronized
    private fun answer(context: Context, request: Approval, accept: Boolean) {
        if (approval !== request || request.state != ApprovalState.WAITING) return
        if (SystemClock.elapsedRealtime() > request.expiresMs) { request.state = ApprovalState.EXPIRED; return }
        DebugLog.log("Remote", "pairing request from \"${request.name}\" ${if (accept) "accepted" else "rejected"} on the TV")
        if (accept) request.token = addDevice(context, request.name)
        request.state = if (accept) ApprovalState.ACCEPTED else ApprovalState.REJECTED
    }

    /** State of the request [id]; the token is handed out once, with ACCEPTED. */
    @Synchronized
    fun approvalStatus(id: String): Pair<ApprovalState, String?> {
        val request = approval?.takeIf { MessageDigest.isEqual(it.id.toByteArray(), id.toByteArray()) }
            ?: return ApprovalState.EXPIRED to null
        if (request.state == ApprovalState.WAITING && SystemClock.elapsedRealtime() > request.expiresMs) request.state = ApprovalState.EXPIRED
        if (request.state != ApprovalState.WAITING) approval = null
        return request.state to request.token
    }

    @Synchronized
    fun isPaired(context: Context, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val hash = sha256(token).toByteArray()
        val devices = devices(context)
        val device = devices.firstOrNull { MessageDigest.isEqual(it.tokenHash.toByteArray(), hash) } ?: return false
        val now = System.currentTimeMillis()
        if (now - device.lastUsedMs >= USED_UPDATE_MS) {
            saveDevices(context, devices.map { if (it === device) Device(it.name, it.tokenHash, it.addedMs, now) else it })
        }
        return true
    }

    /**
     * Answers for `GET /whoami?nonce=…`: one [proof] per paired phone. A phone checks that one of them is made with
     * its own token before it sends the token to an address that claims to be this box – the box's ID is public.
     */
    fun proofs(context: Context, nonce: String): List<String> = devices(context).map { proof(it.tokenHash, nonce) }

    /** HMAC-SHA256 of [nonce] keyed with the token's hash – what the box stores, and what the phone can compute. */
    fun proof(tokenHash: String, nonce: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(tokenHash.toByteArray(), "HmacSHA256"))
        return mac.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun tokenHash(token: String): String = sha256(token)

    /** A random challenge for [proofs]. */
    fun nonce(): String = randomHex(16)

    fun devices(context: Context): List<Device> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_DEVICES, "[]"))
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let {
                val added = it.optLong("added")
                Device(it.getString("name"), it.getString("hash"), added, it.optLong("used", added))
            }
        }
    }.getOrDefault(emptyList())

    /** Forgets the phones called [name] (test scripts); returns how many. */
    @Synchronized
    fun forget(context: Context, name: String): Int {
        val devices = devices(context)
        val kept = devices.filter { it.name != name }
        if (kept.size != devices.size) saveDevices(context, kept)
        return devices.size - kept.size
    }

    fun forgetAll(context: Context) {
        saveDevices(context, emptyList())
        DebugLog.log("Remote", "paired phones forgotten")
    }

    private fun saveDevices(context: Context, devices: List<Device>) {
        val array = JSONArray()
        devices.forEach { array.put(JSONObject().put("name", it.name).put("hash", it.tokenHash).put("added", it.addedMs).put("used", it.lastUsedMs)) }
        prefs(context).edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomHex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
