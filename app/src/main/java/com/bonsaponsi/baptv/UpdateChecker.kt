package com.bonsaponsi.baptv

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the GitHub releases of the project, for installs outside an app store.
 *
 * The latest release is compared with the running version; its APK is downloaded, checked against the
 * published SHA-256 and handed to [SelfUpdater.install], which also checks the package, the signing key
 * (must match the installed app) and the version before installing.
 */
object UpdateChecker {
    private const val TAG = "Update"
    private const val PREFS = "github_update"
    private const val KEY_AUTO = "auto"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L
    private const val MAX_NOTES = 600

    class Release(val version: String, val notes: String, val apkUrl: String, val sha256Url: String)

    /**
     * An app store (e.g. F-Droid) updates the app itself – then this updater stays off.
     * Sideloaded installs (system package installer, adb) and our own updates keep it.
     */
    fun supported(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        }.getOrNull()
        return installer == null || installer == context.packageName || installer in SelfUpdater.INSTALLER_PACKAGES ||
            installer == "com.android.shell"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoEnabled(context: Context) = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO, on).apply()

    /**
     * Automatic check at most once a day. Not in debug builds: they are signed with another key,
     * so a release could not be installed over them anyway.
     */
    fun dueForCheck(context: Context): Boolean =
        !BuildConfig.DEBUG && supported(context) && autoEnabled(context) &&
            System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0) > CHECK_INTERVAL_MS

    /**
     * Blocking: the latest release when it is newer than the running version, otherwise null.
     * [locale] – the store listing language for the release summary ("pl", "en-US").
     */
    fun check(context: Context, locale: String): Release? {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val json = JSONObject(get(latestUrl()).toString(Charsets.UTF_8))
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            when {
                name.endsWith(".apk") -> apk = a.getString("browser_download_url")
                name.endsWith(".apk.sha256") -> sha = a.getString("browser_download_url")
            }
        }
        val newer = isNewer(version, BuildConfig.VERSION_NAME)
        DebugLog.log(TAG, "latest release $version (running ${BuildConfig.VERSION_NAME}): ${if (newer) "newer" else "nothing new"}")
        if (!newer) return null
        if (apk == null || sha == null) error("release $version has no APK or SHA-256 file")
        val summary = runCatching { changelog(json.getString("tag_name"), locale) }
            .onFailure { DebugLog.warn(TAG, "no store changelog for $version ($locale)", it) }.getOrNull()
        return Release(version, summary ?: notes(json.optString("body")), apk, sha)
    }

    /** The short store changelog of the release (newest versionCode at its tag), in the given language. */
    private fun changelog(tag: String, locale: String): String? {
        val list = org.json.JSONArray(get("${apiBase()}/contents/fastlane/metadata/android/$locale/changelogs?ref=$tag").toString(Charsets.UTF_8))
        val newest = (0 until list.length()).map { list.getJSONObject(it) }
            .maxByOrNull { it.getString("name").removeSuffix(".txt").toIntOrNull() ?: -1 } ?: return null
        return get(newest.getString("download_url")).toString(Charsets.UTF_8).trim().take(MAX_NOTES).ifEmpty { null }
    }

    /** Blocking: downloads the APK and checks it against the published SHA-256. */
    fun download(context: Context, release: Release): File {
        val expected = get(release.sha256Url).toString(Charsets.US_ASCII).trim().substringBefore(' ').lowercase()
        val apk = SelfUpdater.updateFile(context)
        apk.writeBytes(get(release.apkUrl))
        val actual = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            apk.delete()
            error("SHA-256 mismatch for ${release.version}")
        }
        DebugLog.log(TAG, "downloaded ${release.version}: ${apk.length()} B, SHA-256 verified")
        return apk
    }

    /** "1.4.1" > "1.4.0-debug": numeric parts compared in order, suffixes ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /**
     * The "Changes" section of the release notes as plain text: wrapped lines joined, each list item
     * shortened to its first sentence.
     */
    fun notes(markdown: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.trim().equals("### Changes", ignoreCase = true) }
        val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { !it.startsWith("### ") }
        val items = ArrayList<String>()
        for (raw in section) {
            val line = raw.replace("**", "").replace("`", "").trim()
            when {
                line.isEmpty() -> items += ""
                line.startsWith("- ") || items.isEmpty() || items.last().isEmpty() -> items += line
                else -> items[items.size - 1] = items.last() + " " + line
            }
        }
        val text = items.filter { it.isNotEmpty() }.joinToString("\n") { item ->
            if (!item.startsWith("- ")) item else item.substringBefore(". ", item).let { if (it.endsWith('.')) it else "$it." }
        }
        return if (text.length <= MAX_NOTES) text else text.take(MAX_NOTES).substringBeforeLast('\n') + "\n…"
    }

    private fun apiBase() = "https://api.github.com/repos/" + BuildConfig.PROJECT_URL.removePrefix("https://github.com/").trimEnd('/')

    private fun latestUrl() = "${apiBase()}/releases/latest"

    private fun get(address: String): ByteArray {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "bapTV/${BuildConfig.VERSION_NAME}")
            c.setRequestProperty("Accept", "application/vnd.github+json, */*")
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code for $address")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
