package com.bonsaponsi.baptv

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics screen that replaces adb: the full report on screen, saving to the Download folder
 * and a web server through which the report and the live log can be opened from a phone/computer.
 */
class DiagnosticsActivity : Activity() {

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("diagnostics-io")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private lateinit var serverButton: Button
    private lateinit var serverInfo: TextView
    private var lastReport = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        text = findViewById(R.id.diag_text)
        scroll = findViewById(R.id.diag_scroll)
        serverButton = findViewById(R.id.diag_server)
        serverInfo = findViewById(R.id.diag_server_info)

        findViewById<Button>(R.id.diag_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.diag_save).setOnClickListener { save() }
        findViewById<Button>(R.id.diag_send_report).setOnClickListener {
            ReportSender.offer(this, ReportSender.Reason.USER, null, automatic = false)
        }
        findViewById<Button>(R.id.diag_clear).setOnClickListener { DebugLog.clear(); refresh() }
        serverButton.setOnClickListener { toggleServer() }
        findViewById<Button>(R.id.diag_allow_install).setOnClickListener {
            openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"), Settings.ACTION_SECURITY_SETTINGS)
        }
        findViewById<Button>(R.id.diag_auto_install).setOnClickListener {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, null, Settings.ACTION_SETTINGS)
        }
        // Network sharing and remote installation are developer tools (debug builds only).
        if (!DebugServerService.available) {
            listOf(R.id.diag_server, R.id.diag_server_info, R.id.diag_allow_install, R.id.diag_auto_install)
                .forEach { findViewById<View>(it).visibility = View.GONE }
        } else {
            updateServerUi()
        }
        refresh()
        DebugLog.log("Diag", "diagnostics screen opened")
    }

    override fun onResume() {
        super.onResume()
        // After returning from system settings, refresh the permission state (developer tools – debug builds only).
        if (DebugServerService.available) updateServerUi()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Page through the report with the remote's channel keys.
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { scroll.pageScroll(View.FOCUS_DOWN); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { scroll.pageScroll(View.FOCUS_UP); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refresh() {
        text.setText(R.string.diag_loading)
        io.execute {
            val report = Diagnostics(this).build()
            handler.post {
                lastReport = report
                text.text = report
            }
        }
    }

    private fun toggleServer() {
        val enable = DebugHttpServer.instance?.isRunning != true
        DebugServerService.setEnabled(this, enable)
        // The service starts the server asynchronously – check the state after a moment.
        handler.postDelayed({ updateServerUi() }, 500)
    }

    private fun updateServerUi() {
        val server = DebugHttpServer.instance?.takeIf { it.isRunning }
        serverButton.setText(if (server != null) R.string.diag_server_off else R.string.diag_server_on)
        val serverText = when {
            server != null -> {
                val urls = Diagnostics.localAddresses().joinToString("   ") { "http://$it:${server.port}/" }
                getString(R.string.diag_server_running, urls.ifEmpty { getString(R.string.diag_no_ip) }) + "\n" +
                    getString(R.string.diag_server_token, ServerAccess.token(this))
            }
            DebugServerService.isEnabled(this) && DebugServerService.lastError != null ->
                getString(R.string.diag_server_failed, DebugServerService.lastError)
            else -> getString(R.string.diag_server_hint)
        }
        val yes = getString(R.string.diag_yes)
        val no = getString(R.string.diag_no)
        val remote = getString(
            R.string.diag_remote_status,
            if (runCatching { packageManager.canRequestPackageInstalls() }.getOrDefault(false)) yes else no,
            if (InstallClickService.isConnected) yes else no,
        )
        serverInfo.text = serverText + "\n" + remote
    }

    /** Opens a system settings screen; some may not exist on Android TV – then the fallback is used. */
    private fun openSettings(action: String, data: Uri?, fallback: String) {
        val intent = Intent(action).apply { if (data != null) setData(data) }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            DebugLog.warn("Diag", "no $action screen, opening $fallback", e)
            runCatching { startActivity(Intent(fallback)) }
                .onFailure { serverInfo.text = getString(R.string.diag_settings_missing, action) }
        }
    }

    private fun save() {
        val report = lastReport.ifEmpty { return }
        val name = "dvbt-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        io.execute {
            val message = try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("Android 10+ required")
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert returned null")
                contentResolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) } ?: error("no output stream")
                DebugLog.log("Diag", "report saved: Download/$name")
                getString(R.string.diag_saved, "Download/$name")
            } catch (e: Exception) {
                DebugLog.error("Diag", "Saving the report failed", e)
                getString(R.string.diag_save_failed, e.message)
            }
            handler.post { serverInfo.text = message }
        }
    }
    companion object {
        const val EXTRA_ENABLE_SERVER = "enable_server"
    }
}
