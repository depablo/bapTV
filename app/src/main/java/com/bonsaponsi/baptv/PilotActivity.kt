package com.bonsaponsi.baptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The remote control page of a paired box, shown in the phone app. The page is the same one a browser opens
 * ([PhoneRemoteServer]); the device token from [PhoneRemoteTvs] is set as its cookie for the box's current address,
 * so the pairing survives a new IP address.
 *
 * The box's ID is announced to the whole network, so before the token goes to an address the box there has to
 * prove it knows the token (`GET /whoami`, [PhoneRemote.proofs]). A box that no longer accepts the token
 * ("forget all paired phones" on the TV) makes the phone forget the pairing.
 */
class PilotActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("pilot")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var tvs: PhoneRemoteTvs
    private lateinit var tv: PhoneRemoteTvs.Tv
    private lateinit var url: String
    private var loaded = false

    private enum class Check { VERIFIED, NOT_ACCEPTED, OLD_BOX, UNREACHABLE }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pilot)
        web = findViewById(R.id.pilot_web)
        tvs = PhoneRemoteTvs(this)
        val stored = intent.getStringExtra(EXTRA_ID)?.let { tvs.get(it) }
        // The address the box is announced at now, or the one it was last verified at.
        val host = intent.getStringExtra(EXTRA_HOST) ?: stored?.host
        val token = stored?.token
        if (stored == null || host == null || token == null) {
            Toast.makeText(this, R.string.remote_not_paired_any, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val port = intent.getIntExtra(EXTRA_PORT, stored.port)
        tv = PhoneRemoteTvs.Tv(stored.id, stored.name, host, port, token)
        url = "http://$host:$port/"
        title = tv.name

        // The box's own page; no JavaScript interface is added, so it can do no more than in a browser.
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Lets the page know it runs inside the app (no "add to home screen" tip).
        web.settings.userAgentString = web.settings.userAgentString + " $APP_AGENT"
        // WebView answers the page's confirm() and alert() with "no" and nothing shown unless the app handles them,
        // so the remote's delete buttons did nothing. The app shows the dialogs itself.
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@PilotActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@PilotActivity)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }
                    .show()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // The remote server runs only while bapTV is on the TV screen (and a minute after).
                if (request.isForMainFrame) unreachable()
            }
        }
        io.execute {
            val check = check(host, port, token)
            // An address other than the verified one is used only after the box there proved it knows the token.
            val sameAddress = host == stored.host && port == stored.port
            handler.post {
                if (isDestroyed) return@post
                when {
                    check == Check.VERIFIED || (check == Check.OLD_BOX && sameAddress) -> open(token)
                    check == Check.UNREACHABLE -> unreachable()
                    check == Check.OLD_BOX -> {
                        Toast.makeText(this, R.string.remote_cannot_verify, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    else -> {
                        tvs.forgetToken(tv.id)
                        Toast.makeText(this, R.string.remote_pairing_lost, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun open(token: String) {
        tvs.remember(tv.id, tv.name, tv.host.orEmpty(), tv.port, token)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(url, "$COOKIE=$token; Path=/")
            flush()
        }
        loaded = true
        web.loadUrl(url)
    }

    private fun unreachable() {
        Toast.makeText(this, getString(R.string.remote_unreachable, tv.name), Toast.LENGTH_LONG).show()
        finish()
    }

    /**
     * Asks the box to prove it knows [token], then (for a box with an older bapTV, without `/whoami`) whether it still
     * accepts it. Runs on the worker.
     */
    private fun check(host: String, port: Int, token: String): Check = try {
        val nonce = PhoneRemote.nonce()
        val (code, body) = get("http://$host:$port/whoami?nonce=$nonce", cookie = null)
        when (code) {
            200 -> {
                val proofs = JSONObject(body).optJSONArray("proofs")
                val mine = PhoneRemote.proof(PhoneRemote.tokenHash(token), nonce)
                if (proofs != null && (0 until proofs.length()).any { proofs.optString(it) == mine }) Check.VERIFIED else Check.NOT_ACCEPTED
            }
            404 -> if (get("http://$host:$port/api/state", cookie = token).first == 401) Check.NOT_ACCEPTED else Check.OLD_BOX
            else -> Check.UNREACHABLE
        }
    } catch (e: Exception) {
        DebugLog.warn("Pilot", "cannot check the box at $host:$port", e)
        Check.UNREACHABLE
    }

    private fun get(address: String, cookie: String?): Pair<Int, String> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            cookie?.let { connection.setRequestProperty("Cookie", "$COOKIE=$it") }
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.use { with(Iptv) { it.readNBytesCompat(MAX_ANSWER) } } else ByteArray(0)
            return code to String(body, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    override fun onPause() {
        if (loaded) {
            // Paired again on the page itself (code typed in the page): its new cookie becomes the stored token,
            // otherwise the old one would overwrite it at the next start.
            val cookie = CookieManager.getInstance().getCookie(url)?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')?.ifEmpty { null }
            if (cookie != null && cookie != tv.token) {
                tvs.remember(tv.id, tv.name, tv.host.orEmpty(), tv.port, cookie)
                tv = PhoneRemoteTvs.Tv(tv.id, tv.name, tv.host, tv.port, cookie)
            }
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("WebView history")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_ID = "tv_id"
        /** Address the box is announced at now (optional – otherwise the stored one). */
        const val EXTRA_HOST = "tv_host"
        const val EXTRA_PORT = "tv_port"
        /** Cookie name the box uses for its phone pairing ([PhoneRemoteServer]). */
        private const val COOKIE = "baptv_remote"
        /** Added to the WebView user agent; `remote.html` looks for it. */
        private const val APP_AGENT = "bapTV-app"
        private const val MAX_ANSWER = 64 * 1024

        fun intent(context: Context, tv: PhoneRemoteTvs.Tv): Intent = Intent(context, PilotActivity::class.java)
            .putExtra(EXTRA_ID, tv.id).putExtra(EXTRA_HOST, tv.host).putExtra(EXTRA_PORT, tv.port)
    }
}
