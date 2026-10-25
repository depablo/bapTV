package com.bonsaponsi.baptv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HbbTV app browser: a transparent WebView over the TvView with an OIPF compatibility layer
 * (assets/hbbtv_shim.js). The app is started from the AUTOSTART URL in the channel's AIT ([HbbTvStore]).
 *
 * Keys reach the app only when they belong to its key set (keyset) – the rest
 * are handled by the player, just like on an HbbTV television.
 */
class HbbTvBrowser(private val activity: Activity, private val container: ViewGroup, private val host: Host) {

    /** Player control needed by the video/broadcast object. */
    interface Host {
        fun hbbChannel(): JSONObject?
        fun hbbChannels(): JSONArray
        fun hbbSetChannel(onid: Int, tsid: Int, sid: Int): Boolean
        fun hbbChannelStep(step: Int)
        fun hbbStopBroadcast()
        fun hbbBindBroadcast()
        fun hbbBroadcastRect(rect: String)
        /** The channel's app could not be loaded (e.g. no internet) – the player shows a short notice instead of an error page. */
        fun hbbAppUnavailable(offline: Boolean)
        /** The app put a colour key into its key set – it has functions under the colour keys. */
        fun hbbColourKeysWanted()
    }

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    /** OIPF layer with language and country ([Region.hbbTvConfig]) substituted for the __DVBT_…__ markers. */
    private val shim by lazy {
        var text = activity.assets.open("hbbtv_shim.js").use { String(it.readBytes(), Charsets.UTF_8) }
        Region.hbbTvConfig(activity).forEach { (marker, value) -> text = text.replace(marker, value) }
        text = text.replace("__DVBT_NONCE__", nonce)
        text.toByteArray(Charsets.UTF_8)
    }

    @Volatile var keyset = 0; private set
    /**
     * Whether the user launched the app with a colour key. Until then arrows, OK, digits and playback
     * keys are handled by the player – apps in the "red button" state register navigation keys
     * in advance, which would take the arrows away from the info panel.
     */
    @Volatile private var engaged = false
    /** WebView User-Agent – needed when fetching the page ourselves (network thread). */
    @Volatile private var userAgent: String = USER_AGENT_SUFFIX
    @Volatile var currentUrl: String? = null; private set
    @Volatile var visible = false; private set
    /** AUTOSTART URL of the current channel – the app returns to it after destroyApplication. */
    private var autostartUrl: String? = null

    val isActive get() = webView != null && currentUrl != null

    /**
     * Covered by a player menu: the app keeps running but doesn't show through the semi-transparent
     * panel. Uses alpha, not visibility – the app controls that itself (show/hide).
     */
    var covered = false
        set(value) {
            field = value
            webView?.alpha = if (value) 0f else 1f
        }

    /** Starts the channel's app (or closes the current one when [url] is null). */
    fun start(url: String?) {
        // After destroy() the activity no longer exists – a new WebView would leak.
        if (destroyed) return
        autostartUrl = url
        if (url == null) { stop(); return }
        load(url)
    }

    fun stop() {
        currentUrl = null
        keyset = 0
        webView?.let { wv ->
            wv.loadUrl("about:blank")
            wv.visibility = View.INVISIBLE
        }
        visible = false
        host.hbbBroadcastRect("full")
    }

    @Volatile private var destroyed = false

    /** HbbNative bridge key – known only to the compatibility layer injected into the app page. */
    private val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun trusted(n: String): Boolean {
        if (java.security.MessageDigest.isEqual(n.toByteArray(), nonce.toByteArray())) return true
        DebugLog.warn(TAG, "rejected bridge call without key (e.g. a frame from another domain)")
        return false
    }

    fun destroy() {
        destroyed = true
        webView?.let { container.removeView(it); it.destroy() }
        webView = null
        currentUrl = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView = webView ?: WebView(activity).also { wv ->
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.isFocusable = false
        wv.isFocusableInTouchMode = false
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // HbbTV apps loaded over https fetch images over http –
            // TV browsers allow this, WebView blocks it by default.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // HbbTV apps come from the network – no need for device file access (enabled by default with targetSdk 29).
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "$userAgentString $USER_AGENT_SUFFIX"
            userAgent = userAgentString
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(Bridge(), "HbbNative")
        wv.webViewClient = Client()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                DebugLog.log("HbbTV/js", "${m.messageLevel()} ${m.message()} (${m.sourceId()?.substringAfterLast('/')}:${m.lineNumber()})")
                return true
            }
        }
        // Below the player overlays (bar, list), above the video: index 1 – right after the TvView.
        container.addView(wv, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.alpha = if (covered) 0f else 1f
        webView = wv
    }

    private fun load(url: String) {
        // The URL comes from the AIT (over-the-air data): only http/https – not file://, javascript: etc.
        val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            DebugLog.warn(TAG, "rejected app URL (scheme $scheme): $url")
            return
        }
        val wv = ensureWebView()
        keyset = 0
        engaged = false
        visible = true
        wv.visibility = View.VISIBLE
        currentUrl = url
        DebugLog.log(TAG, "starting app: $url")
        wv.loadUrl(url)
    }

    /**
     * Remote control key. Returns true when it went to the HbbTV app (belongs to its key set).
     */
    fun onKey(event: KeyEvent): Boolean {
        val wv = webView ?: return false
        if (!isActive) return false
        val (vk, mask) = VK_MAP[event.keyCode] ?: return false
        if (keyset and mask == 0) return false
        if (mask and COLOUR_KEYS != 0) {
            engaged = true
        } else if (!engaged) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            DebugLog.log(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} -> VK $vk")
            wv.evaluateJavascript("window.__dvbtHbb && window.__dvbtHbb.key($vk)", null)
        }
        return true
    }

    /** The player changed channel at the app's request – notify the video/broadcast objects. */
    fun notifyChannelChanged() {
        webView?.evaluateJavascript("window.__dvbtHbb && window.__dvbtHbb.channelChanged()", null)
    }

    fun evaluate(js: String, callback: (String?) -> Unit) {
        val wv = webView ?: return callback("no active app")
        wv.evaluateJavascript(js) { callback(it) }
    }

    fun state(): JSONObject = JSONObject()
        .put("active", isActive).put("url", currentUrl ?: JSONObject.NULL).put("pageUrl", webView?.url ?: JSONObject.NULL)
        .put("autostart", autostartUrl ?: JSONObject.NULL).put("visible", visible).put("keyset", keyset).put("engaged", engaged)

    private inner class Bridge {
        @JavascriptInterface fun log(n: String, m: String) { if (trusted(n)) DebugLog.log("HbbTV/js", m) }
        @JavascriptInterface fun version(n: String) = if (trusted(n)) BuildConfig.VERSION_NAME else ""
        @JavascriptInterface fun setKeyset(n: String, mask: Int) {
            if (!trusted(n)) return
            keyset = mask
            DebugLog.log(TAG, "keyset = 0x%X".format(mask))
            if (mask and COLOUR_KEYS != 0) main.post { host.hbbColourKeysWanted() }
        }
        @JavascriptInterface fun show(n: String) { if (trusted(n)) main.post { visible = true; webView?.visibility = View.VISIBLE } }
        @JavascriptInterface fun hide(n: String) { if (trusted(n)) main.post { visible = false; webView?.visibility = View.INVISIBLE } }
        @JavascriptInterface fun destroyApplication(n: String) {
            if (!trusted(n)) return
            main.post {
                // After the app is closed, the channel's AUTOSTART app comes back (as on a TV).
                val url = autostartUrl
                DebugLog.log(TAG, "destroyApplication – returning to $url")
                host.hbbBindBroadcast()
                if (url != null) load(url) else stop()
            }
        }
        @JavascriptInterface fun createApplication(n: String, uri: String) {
            if (!trusted(n)) return
            main.post {
                val target = when {
                    uri.startsWith("http://") || uri.startsWith("https://") -> uri
                    else -> autostartUrl // dvb://current.ait/... – the only known app is AUTOSTART
                }
                if (target != null) load(target)
            }
        }
        @JavascriptInterface fun channel(n: String): String = if (trusted(n)) host.hbbChannel()?.toString() ?: "null" else "null"
        @JavascriptInterface fun channels(n: String): String = if (trusted(n)) host.hbbChannels().toString() else "[]"
        @JavascriptInterface fun setChannel(n: String, onid: Int, tsid: Int, sid: Int) {
            if (trusted(n)) main.post { if (host.hbbSetChannel(onid, tsid, sid)) notifyChannelChanged() }
        }
        @JavascriptInterface fun channelStep(n: String, step: Int) { if (trusted(n)) main.post { host.hbbChannelStep(step) } }
        @JavascriptInterface fun stopBroadcast(n: String) { if (trusted(n)) main.post { host.hbbStopBroadcast() } }
        @JavascriptInterface fun releaseBroadcast(n: String) { if (trusted(n)) main.post { host.hbbStopBroadcast() } }
        @JavascriptInterface fun bindBroadcast(n: String) { if (trusted(n)) main.post { host.hbbBindBroadcast() } }
        /** Full-screen TV video is handled by broadcastRect (size of the video/broadcast object) – nothing to do here. */
        @JavascriptInterface fun setFullScreen(n: String, full: Boolean) = Unit
        @JavascriptInterface fun broadcastRect(n: String, rect: String) { if (trusted(n)) main.post { host.hbbBroadcastRect(rect) } }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.path?.endsWith(SHIM_PATH) == true) {
                return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(shim))
            }
            if (!request.isForMainFrame || request.method != "GET" || url.scheme !in setOf("http", "https")) return null
            return fetchWithShim(request)
        }

        override fun onPageFinished(view: WebView, url: String) {
            DebugLog.log(TAG, "page loaded: $url")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (!request.isForMainFrame) return
            DebugLog.warn(TAG, "load error ${request.url}: ${error.errorCode} ${error.description}")
            // No WebView error page over the TV picture ("Webpage not available") – a short notice from the player instead.
            view.stopLoading()
            view.loadUrl("about:blank")
            view.visibility = View.INVISIBLE
            visible = false
            val offline = error.errorCode in OFFLINE_ERRORS
            main.post { host.hbbAppUnavailable(offline) }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            DebugLog.warn(TAG, "HTTP ${response.statusCode} ${request.url}")
        }
    }

    /**
     * The app's main page is fetched by us so the compatibility layer can be inserted before
     * the app's scripts. Redirects and non-(X)HTML content are left to the WebView.
     */
    private fun fetchWithShim(request: WebResourceRequest): WebResourceResponse? = try {
        val url = request.url.toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 15_000
            request.requestHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
        val code = conn.responseCode
        conn.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
        val type = conn.contentType.orEmpty()
        val mime = type.substringBefore(';').trim().lowercase()
        val location = conn.getHeaderField("Location")
        if (code in 300..399 && location != null) {
            // A redirect handed to the WebView bypasses interception (the target page would lack the layer) –
            // instead, return a page that navigates to the new URL itself as a normal navigation.
            val target = URL(URL(url), location).toString()
            conn.disconnect()
            DebugLog.log(TAG, "redirect $code: $url -> $target")
            val html = "<!doctype html><html><head><script>location.replace(${JSONObject.quote(target)})</script></head><body></body></html>"
            WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(html.toByteArray()))
        } else if (code in 300..399 || !(mime.contains("html") || mime.endsWith("+xml"))) {
            conn.disconnect()
            null
        } else {
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1) ?: "utf-8"
            val body = (if (code >= 400) conn.errorStream else conn.inputStream)?.use { readLimited(it) }
            if (body == null) {
                // Page too large – don't keep it in memory; the WebView will fetch it itself (without the compatibility layer).
                conn.disconnect()
                DebugLog.warn(TAG, "app page larger than ${MAX_PAGE_BYTES / 1_000_000} MB – not injecting: $url")
                null
            } else {
                val html = String(body, charset(charset))
                val tag = "<script type=\"text/javascript\" src=\"$SHIM_PATH\"></script>"
                val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
                val patched = if (head != null) html.substring(0, head.range.last + 1) + tag + html.substring(head.range.last + 1) else tag + html
                // Serve application/vnd.hbbtv.xhtml+xml as XHTML, which the WebView is sure to render.
                val outMime = if (mime.endsWith("+xml")) "application/xhtml+xml" else "text/html"
                DebugLog.log(TAG, "injected HbbTV layer: $url ($mime, HTTP $code)")
                WebResourceResponse(outMime, charset, code, conn.responseMessage ?: "OK",
                    mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(patched.toByteArray(charset(charset))))
            }
        }
    } catch (e: Exception) {
        DebugLog.warn(TAG, "fetching app page failed: ${request.url}", e)
        null
    }

    private fun readLimited(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > MAX_PAGE_BYTES) return null
        }
    }

    companion object {
        /** WebViewClient errors that mean the network or the server cannot be reached. */
        private val OFFLINE_ERRORS = setOf(
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_IO, WebViewClient.ERROR_PROXY_AUTHENTICATION,
        )
        private const val TAG = "HbbTV"
        /** HbbTV app pages are tens to hundreds of kB; above this limit the layer is not injected. */
        private const val MAX_PAGE_BYTES = 4_000_000
        private const val SHIM_PATH = "/__dvbt_hbbtv_shim.js"
        /** HbbTV 1.4.1 = HbbTV 2.0.1 specification (apps then use HTML5 video). */
        private const val USER_AGENT_SUFFIX = "HbbTV/1.4.1 (;Bonsaponsi;bapTV;1.0;1.0;bapTV;)"

        private const val RED = 0x1; private const val GREEN = 0x2; private const val YELLOW = 0x4; private const val BLUE = 0x8
        private const val NAVIGATION = 0x10; private const val VCR = 0x20; private const val SCROLL = 0x40
        private const val INFO = 0x80; private const val NUMERIC = 0x100
        private const val COLOUR_KEYS = RED or GREEN or YELLOW or BLUE

        /** Android key -> (HbbTV VK_* code, key set bit). */
        val VK_MAP: Map<Int, Pair<Int, Int>> = buildMap {
            put(KeyEvent.KEYCODE_PROG_RED, 403 to RED)
            put(KeyEvent.KEYCODE_PROG_GREEN, 404 to GREEN)
            put(KeyEvent.KEYCODE_PROG_YELLOW, 405 to YELLOW)
            put(KeyEvent.KEYCODE_PROG_BLUE, 406 to BLUE)
            put(KeyEvent.KEYCODE_DPAD_LEFT, 37 to NAVIGATION)
            put(KeyEvent.KEYCODE_DPAD_UP, 38 to NAVIGATION)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, 39 to NAVIGATION)
            put(KeyEvent.KEYCODE_DPAD_DOWN, 40 to NAVIGATION)
            put(KeyEvent.KEYCODE_DPAD_CENTER, 13 to NAVIGATION)
            put(KeyEvent.KEYCODE_ENTER, 13 to NAVIGATION)
            put(KeyEvent.KEYCODE_BACK, 461 to NAVIGATION)
            put(KeyEvent.KEYCODE_MEDIA_PLAY, 415 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_PAUSE, 19 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 402 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_STOP, 413 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 417 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_REWIND, 412 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_NEXT, 425 to VCR)
            put(KeyEvent.KEYCODE_MEDIA_PREVIOUS, 424 to VCR)
            put(KeyEvent.KEYCODE_PAGE_UP, 33 to SCROLL)
            put(KeyEvent.KEYCODE_PAGE_DOWN, 34 to SCROLL)
            put(KeyEvent.KEYCODE_INFO, 457 to INFO)
            for (d in 0..9) put(KeyEvent.KEYCODE_0 + d, (48 + d) to NUMERIC)
        }

        /** Key names for the remote /key?name=... */
        val KEY_NAMES = mapOf(
            "RED" to KeyEvent.KEYCODE_PROG_RED, "GREEN" to KeyEvent.KEYCODE_PROG_GREEN,
            "YELLOW" to KeyEvent.KEYCODE_PROG_YELLOW, "BLUE" to KeyEvent.KEYCODE_PROG_BLUE,
            "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "OK" to KeyEvent.KEYCODE_DPAD_CENTER, "ENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
            "BACK" to KeyEvent.KEYCODE_BACK, "MENU" to KeyEvent.KEYCODE_MENU, "INFO" to KeyEvent.KEYCODE_INFO,
            "PLAY" to KeyEvent.KEYCODE_MEDIA_PLAY, "PAUSE" to KeyEvent.KEYCODE_MEDIA_PAUSE,
            "PLAYPAUSE" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "STOP" to KeyEvent.KEYCODE_MEDIA_STOP,
            "FF" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "REW" to KeyEvent.KEYCODE_MEDIA_REWIND,
            "CHUP" to KeyEvent.KEYCODE_CHANNEL_UP, "CHDOWN" to KeyEvent.KEYCODE_CHANNEL_DOWN,
        ) + (0..9).associate { "$it" to KeyEvent.KEYCODE_0 + it }
    }
}
