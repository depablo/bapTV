package com.bonsaponsi.baptv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that carries out the installation of an update of this app without the remote.
 *
 * Handles two dialogs: the system installer confirmation (“Install”) and the Google Play Protect
 * warning about an unknown developer (“More details” → “Install anyway”),
 * which appears for every new APK signed with the debug key.
 *
 * Acts narrowly: only when [SelfUpdater] itself requested the installation (a time window of a few minutes),
 * after the APK signature was verified, and only when the dialog concerns this app (its name in the content).
 * While active it also allows launching the player from the background after an update
 * (the system exempts apps with a bound accessibility service from the activity start restriction).
 */
class InstallClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var lastSeenPackage: String? = null
    /** “More details” is a toggle – we click it at most once every few seconds. */
    private var detailsClickedAt = 0L
    private val dumped = HashSet<String>()
    private val retry = Runnable { tryConfirm("retry") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        instance = this
        DebugLog.log(TAG, "install auto-confirm service active")
        SelfUpdater.relaunchIfRequested(this, "accessibility service")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Outside an installation requested by the server the service reads and clicks nothing.
        if (!SelfUpdater.isAwaitingConfirmation(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastSeenPackage) {
            lastSeenPackage = pkg
            DebugLog.log(TAG, "window during installation: $pkg / ${event.className}")
        }
        retries = 0
        tryConfirm("event ${AccessibilityEvent.eventTypeToString(event.eventType)}")
    }

    private fun tryConfirm(reason: String) {
        handler.removeCallbacks(retry)
        if (!SelfUpdater.isAwaitingConfirmation(this)) return
        // The Play Protect dialog is not always the “active window”, so we go through all windows.
        val roots = (listOfNotNull(rootInActiveWindow) + windows.mapNotNull { it.root })
            .filter { it.packageName != null && it.packageName.toString() != packageName }
            .distinctBy { it.windowId }
        if (roots.isEmpty()) return scheduleRetry()
        val label = applicationInfo.loadLabel(packageManager).toString().lowercase()
        for (root in roots) {
            val pkg = root.packageName.toString()
            val nodes = flatten(root)
            // Make sure the window concerns this app (name visible in the window content).
            if (nodes.none { label in it.label() }) continue
            if (pkg in SelfUpdater.INSTALLER_PACKAGES) {
                if (confirmInstaller(root, reason)) return
            } else {
                dumpOnce(pkg, nodes)
                if (handlePlayProtect(nodes, "$reason, $pkg")) return
            }
        }
        scheduleRetry()
    }

    /** System installer dialog: the “Install” button (android:id/button1). */
    private fun confirmInstaller(root: AccessibilityNodeInfo, reason: String): Boolean {
        val button = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull() ?: return false
        val text = button.text?.toString().orEmpty()
        if (text.lowercase() !in CONFIRM_LABELS) {
            DebugLog.log(TAG, "button “$text” is not an install confirmation – skipping")
            return false
        }
        // The installer enables the button only after the window gains focus – we retry until then.
        if (!button.isEnabled) return false
        return click(button, text, reason)
    }

    /**
     * Play Protect warning (Play Store, Compose UI – text search does not work,
     * hence a manual tree walk): “Install anyway”, and when it is not visible – “More details”.
     */
    private fun handlePlayProtect(nodes: List<AccessibilityNodeInfo>, reason: String): Boolean {
        nodes.firstOrNull { it.label() in INSTALL_ANYWAY_LABELS }?.let { return click(it, it.label(), reason) }
        val details = nodes.firstOrNull { it.label() in MORE_DETAILS_LABELS } ?: return false
        if (System.currentTimeMillis() - detailsClickedAt < DETAILS_INTERVAL_MS) return false
        detailsClickedAt = System.currentTimeMillis()
        click(details, details.label(), reason)
        return false
    }

    private fun AccessibilityNodeInfo.label(): String =
        (text ?: contentDescription)?.toString()?.trim()?.lowercase().orEmpty()

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
        }
        return out
    }

    /** Dumps the window tree to the log (once per package per installation) – helps when the dialog changes. */
    private fun dumpOnce(pkg: String, nodes: List<AccessibilityNodeInfo>) {
        val key = pkg + "@" + SelfUpdater.pendingSince(this)
        if (key in dumped) return
        dumped += key
        val lines = nodes.filter { it.label().isNotEmpty() || it.isClickable }.joinToString("\n") { n ->
            val r = Rect().also { n.getBoundsInScreen(it) }
            "  ${n.className?.toString()?.substringAfterLast('.')} \"${n.label()}\" click=${n.isClickable} en=${n.isEnabled} $r"
        }
        DebugLog.log(TAG, "window tree $pkg:\n$lines")
    }

    /**
     * Clicks the node or its nearest clickable ancestor (the label is often inside the button).
     * When the accessibility action is rejected, taps the center of the element with a gesture.
     */
    private fun click(node: AccessibilityNodeInfo, text: String, reason: String): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        val t = target ?: node
        if (!t.isEnabled) return false
        val clicked = t.isClickable && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        DebugLog.log(TAG, "clicked “$text” ($reason): $clicked")
        if (clicked) return true
        val r = Rect().also { t.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
        DebugLog.log(TAG, "tap “$text” at $r: $sent")
        return sent
    }

    private fun scheduleRetry() {
        if (retries++ < MAX_RETRIES) handler.postDelayed(retry, RETRY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isConnected = false
        handler.removeCallbacks(retry)
        return super.onUnbind(intent)
    }

    /**
     * Screenshot (API 30) for remote HbbTV tests: returns a PNG or null.
     * The TV picture (hardware layer) may come out black – the app UI is visible.
     */
    fun screenshot(timeoutMs: Long = 5000): ByteArray? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var png: ByteArray? = null
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                try {
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    png = bmp?.let { b -> java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
                } catch (e: Exception) {
                    DebugLog.warn(TAG, "screenshot: conversion failed", e)
                }
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) {
                DebugLog.warn(TAG, "screenshot failed: $errorCode")
                latch.countDown()
            }
        })
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return png
    }

    override fun onDestroy() {
        instance = null
        isConnected = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AutoInstall"
        /** The Play Protect warning appears only after scanning – we wait up to 2 minutes. */
        private const val MAX_RETRIES = 240
        private const val MAX_NODES = 400
        private const val RETRY_MS = 500L
        private const val DETAILS_INTERVAL_MS = 5000L
        private val CONFIRM_LABELS = setOf("zainstaluj", "install", "aktualizuj", "update")
        private val INSTALL_ANYWAY_LABELS = setOf("zainstaluj mimo to", "install anyway")
        private val MORE_DETAILS_LABELS = setOf("więcej szczegółów", "more details")

        @Volatile var isConnected = false
            private set
        @Volatile var instance: InstallClickService? = null
            private set
    }
}
