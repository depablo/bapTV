package com.bonsaponsi.baptv

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * The remote control page of a paired box, shown in the phone app. The page is the same one a browser opens
 * ([PhoneRemoteServer]); the device token from [PhoneRemoteTvs] is set as its cookie for the box's current address,
 * so the pairing survives a new IP address. If the box no longer accepts the token, the pairing is forgotten.
 */
class PilotActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private lateinit var web: WebView
    private lateinit var tvs: PhoneRemoteTvs
    private lateinit var tvId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pilot)
        web = findViewById(R.id.pilot_web)
        tvs = PhoneRemoteTvs(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val tv = id?.let { tvs.get(it) }
        val host = tv?.host
        val token = tv?.token
        if (tv == null || host == null || token == null) {
            Toast.makeText(this, R.string.remote_not_paired_any, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tvId = tv.id
        val url = "http://$host:${tv.port}/"
        title = tv.name

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie(url, "$COOKIE=$token; Path=/")
            flush()
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode == 401) {
                    // The box has forgotten this phone ("forget all paired phones") – pairing has to be done again.
                    tvs.forgetToken(tvId)
                    Toast.makeText(this@PilotActivity, R.string.remote_pairing_lost, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
        web.loadUrl(url)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("WebView history")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_ID = "tv_id"
        /** Cookie name the box uses for its phone pairing ([PhoneRemoteServer]). */
        private const val COOKIE = "baptv_remote"
    }
}
