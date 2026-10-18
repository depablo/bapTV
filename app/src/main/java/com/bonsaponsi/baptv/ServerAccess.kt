package com.bonsaponsi.baptv

import android.content.Context
import java.security.SecureRandom

/**
 * Protection of the diagnostic server:
 * - the Host header must be an IP address or “localhost” – blocks DNS rebinding,
 * - requests sent by pages from another origin (Origin, Sec-Fetch-Site) are rejected – blocks CSRF
 *   from a browser on the network and from HbbTV/ad apps in a WebView on the set-top box itself,
 * - the most sensitive endpoints require a token ([token]): the `X-Tv-Token` header, the `token` parameter
 *   or the `tvtoken` cookie. The token is given to whoever uploads a correctly signed update (`/install`),
 *   and is shown in Diagnostics.
 */
object ServerAccess {
    private const val PREFS = "server"
    private const val KEY_TOKEN = "token"
    const val HEADER = "x-tv-token"
    const val COOKIE = "tvtoken"

    /** Endpoints requiring the token (the rest: view pages, /signal, /status, log). */
    val PROTECTED = setOf(
        "/hbbtv/eval", "/key", "/screenshot.png", "/launch", "/restart", "/open", "/timeshift", "/recordings", "/probe", "/probe.ts",
        "/publish",
    )

    /** Developer tools – disabled in release builds. */
    val DEBUG_ONLY = setOf("/hbbtv/eval", "/key", "/screenshot.png", "/install", "/timeshift")

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    /** Host: an IPv4/IPv6 address or localhost (with or without port). No header – a non-browser client. */
    fun hostAllowed(host: String?): Boolean {
        if (host == null) return true
        val h = host.trim().lowercase()
        val name = when {
            h.startsWith("[") -> return h.substringAfter('[').substringBefore(']').let { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == ':' || c == '.' } }
            else -> h.substringBefore(':')
        }
        return name == "localhost" || IPV4.matches(name)
    }

    /**
     * Request from a page of another origin: Origin other than this server or Sec-Fetch-Site other than
     * same-origin/none. Non-browser calls (curl, scripts) do not send these headers.
     */
    fun crossSite(headers: Map<String, String>): Boolean {
        val site = headers["sec-fetch-site"]?.lowercase()
        if (site != null && site != "same-origin" && site != "none") return true
        val origin = headers["origin"] ?: return false
        val host = headers["host"] ?: return true
        return !origin.equals("http://$host", ignoreCase = true)
    }

    /** Token from the header, parameter or cookie. */
    fun presentedToken(headers: Map<String, String>, query: Map<String, String>): String? =
        headers[HEADER] ?: query["token"] ?: headers["cookie"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')

    fun tokenOk(context: Context, headers: Map<String, String>, query: Map<String, String>): Boolean {
        val presented = presentedToken(headers, query) ?: return false
        return java.security.MessageDigest.isEqual(presented.toByteArray(), token(context).toByteArray())
    }

    fun isProtected(path: String) = path in PROTECTED || path.startsWith("/recordings/")

    private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
}
