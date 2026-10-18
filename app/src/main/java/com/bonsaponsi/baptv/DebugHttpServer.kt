package com.bonsaponsi.baptv

import android.content.Context
import android.os.Process
import android.provider.Settings
import android.text.Html
import com.bonsaponsi.baptv.SelfUpdater.versionCodeCompat
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Minimal HTTP server on the local network that replaces adb: diagnostic report, log
 * (also incremental), the process logcat and, for remote work, uploading a new version (POST /install),
 * launching and restarting the app. Debug builds only; meant for development with curl or a script on a computer.
 */
class DebugHttpServer(private val context: Context, val port: Int = DEFAULT_PORT) {

    @Volatile private var server: ServerSocket? = null

    val isRunning get() = server?.isClosed == false

    fun start() {
        if (isRunning) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = socket
        DebugLog.log("Http", "server started on port $port, addresses: ${Diagnostics.localAddresses()}")
        thread(name = "debug-http", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(name = "debug-http-client", isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
        DebugLog.log("Http", "server stopped")
    }

    /** [file] – body streamed from a file (recordings are gigabytes), in which case [body] is empty. */
    private class Response(val status: String, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap(), val file: java.io.File? = null) {
        constructor(status: String, type: String, body: String, headers: Map<String, String> = emptyMap()) :
            this(status, type, body.toByteArray(Charsets.UTF_8), headers)
    }

    private fun text(body: String, status: String = "200 OK") = Response(status, TEXT, body)

    /**
     * Handles one connection. Every exception stays here: an idle connection (timeout),
     * a client that aborted a download, a malformed request – an uncaught exception in the thread would kill the process
     * (and an ongoing recording).
     */
    private fun handle(client: Socket) = try {
        client.use { s -> serve(s) }
    } catch (e: java.net.SocketTimeoutException) {
        // A connection the browser opened in advance and never used.
    } catch (e: java.io.IOException) {
        DebugLog.log("Http", "connection aborted: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
    } catch (e: Throwable) {
        DebugLog.error("Http", "connection handling error", e)
    }

    private fun serve(s: Socket) {
        s.soTimeout = 30_000
        val input = s.getInputStream()
        val out = s.getOutputStream()
        val request = try {
            HttpRequest.read(input) ?: return
        } catch (e: HttpRequest.BadRequest) {
            send(out, text(e.message.orEmpty(), "400 Bad Request"))
            return
        }
        // Frequent polling of /status and /log.txt is not logged, to keep the log clean.
        if (request.path !in QUIET_PATHS) DebugLog.log("Http", "${s.inetAddress.hostAddress} ${request.method} ${request.path}")
        refusal(request)?.let { (status, why) ->
            DebugLog.warn("Http", "rejected ${request.method} ${request.path} from ${s.inetAddress.hostAddress}: $why")
            send(out, text("$why\n", status))
            return
        }
        val response = try {
            route(request, input, out)
        } catch (e: SelfUpdater.Rejected) {
            DebugLog.warn("Http", "rejected: ${e.message}")
            text(e.message.orEmpty() + "\n", e.httpStatus)
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            DebugLog.error("Http", "error handling ${request.method} ${request.path}", e)
            text("Error: $e\n", "500 Internal Server Error")
        }
        send(out, withTokenCookie(request, response))
    }

    /** Reason for rejecting the request (HTTP status, description) or null – see [ServerAccess]. */
    private fun refusal(request: HttpRequest): Pair<String, String>? {
        val path = if (RECORDING_FILE.matches(request.path)) "/recordings/" else request.path
        return when {
            !ServerAccess.hostAllowed(request.headers["host"]) -> "403 Forbidden" to "Host header not allowed – connect using the set-top box IP address."
            ServerAccess.crossSite(request.headers) -> "403 Forbidden" to "Cross-site request rejected."
            path in ServerAccess.DEBUG_ONLY && !BuildConfig.DEBUG -> "404 Not Found" to "404"
            ServerAccess.isProtected(path) && !ServerAccess.tokenOk(context, request.headers, request.query) ->
                "401 Unauthorized" to "Access token required – pass ?token=… (Diagnostics → Share on network) or the X-Tv-Token header."
            else -> null
        }
    }

    /** A token given in the URL is stored in a cookie, so links on the page (e.g. recording downloads) keep working. */
    private fun withTokenCookie(request: HttpRequest, response: Response): Response {
        val token = request.query["token"] ?: return response
        if (!ServerAccess.tokenOk(context, emptyMap(), mapOf("token" to token))) return response
        return Response(response.status, response.type, response.body,
            response.headers + ("Set-Cookie" to "${ServerAccess.COOKIE}=$token; Path=/; HttpOnly; SameSite=Strict"), response.file)
    }

    private fun route(request: HttpRequest, input: InputStream, out: OutputStream): Response {
        RECORDING_FILE.matchEntire(request.path)?.takeIf { request.method == "GET" }?.let { m ->
            val entry = Recorder.list(context).firstOrNull { it.id == m.groupValues[1].toLong() }
            val file = entry?.file?.takeIf { it.exists() } ?: return text("No such recording\n", "404 Not Found")
            val name = "${entry.channelName}_${entry.title}".replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").take(80) + ".ts"
            return Response("200 OK", "video/mp2t", ByteArray(0),
                mapOf("Content-Disposition" to "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")), file)
        }
        return routeExact(request, input, out)
    }

    private fun routeExact(request: HttpRequest, input: InputStream, out: OutputStream): Response = when (request.method to request.path) {
        "GET" to "/" -> Response("200 OK", HTML, indexPage())
        "GET" to "/report.txt" -> text(Diagnostics(context).build())
        "GET" to "/log.txt" -> logText(request)
        "GET" to "/log" -> Response("200 OK", HTML, livePage())
        "GET" to "/logcat.txt" -> text(Diagnostics.ownLogcat(request.query["lines"]?.toIntOrNull() ?: 2000))
        "GET" to "/status" -> Response("200 OK", JSON, status().toString(2))
        "POST" to "/install" -> install(request, input, out)
        "POST" to "/launch" -> text(SelfUpdater.launch(context) + "\n")
        "POST" to "/probe" -> text(
            TsProbe.run(
                context,
                request.query["channel"]?.toLongOrNull()?.let { android.media.tv.TvContract.buildChannelUri(it) },
                (request.query["secs"]?.toIntOrNull() ?: 8).coerceIn(2, 60),
            ),
        )
        "POST" to "/key" -> {
            val name = request.query["name"]?.uppercase()
            val code = name?.let { HbbTvBrowser.KEY_NAMES[it] } ?: request.query["code"]?.toIntOrNull()
            // The key goes to the activity on screen (player, TV guide, channel management…).
            val activity = App.resumed?.get()
            when {
                code == null -> text("Unknown key. Available: ${HbbTvBrowser.KEY_NAMES.keys.joinToString()}\n", "400 Bad Request")
                activity == null -> text("No app activity is on screen\n", "409 Conflict")
                else -> {
                    // Injection into our own window (like the remote: focus, buttons, dialogs);
                    // when another app's window is on screen the system refuses – then straight to the activity.
                    val how = try {
                        android.app.Instrumentation().sendKeyDownUpSync(code)
                        "injected"
                    } catch (e: SecurityException) {
                        onMain {
                            val now = android.os.SystemClock.uptimeMillis()
                            activity.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0))
                            activity.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0))
                        }
                        "to activity"
                    }
                    text("OK ${android.view.KeyEvent.keyCodeToString(code)} -> ${activity.javaClass.simpleName} ($how)\n")
                }
            }
        }
        "GET" to "/timeshift" -> Response("200 OK", JSON, (onMain { PlayerRemote.timeshiftState?.invoke() } ?: JSONObject().put("error", "player not running")).toString(2))
        "POST" to "/timeshift" -> {
            val f = PlayerRemote.timeshift ?: return text("Player not running\n", "409 Conflict")
            text((onMain { f(request.query["action"].orEmpty(), request.query["arg"]) } ?: "no response") + "\n")
        }
        "GET" to "/recordings" -> if (request.query["format"] == "html") Response("200 OK", HTML, recordingsPage())
            else Response("200 OK", JSON, Recorder.json(context).toString(2))
        "POST" to "/recordings" -> {
            val action = request.query["action"].orEmpty()
            if (action == "delete") text(if (Recorder.delete(context, request.query["id"]?.toLongOrNull() ?: -1)) "OK\n" else "Not deleted\n")
            else if (action == "schedule") scheduleRecording(request)
            else if (action == "unschedule") { RecordingScheduler.cancel(context, request.query["id"]?.toLongOrNull() ?: -1); text("OK\n") }
            else {
                val f = PlayerRemote.recording ?: return text("Player not running\n", "409 Conflict")
                text((onMain { f(action, request.query["id"]) } ?: "no response") + "\n")
            }
        }
        "GET" to "/capabilities" -> Response("200 OK", "application/json; charset=utf-8",
            DeviceCapabilities.refresh(context).toJson().toString(2).toByteArray())
        "POST" to "/open" -> {
            val target = when (request.query["screen"]) {
                "channels" -> ChannelsActivity::class.java
                "guide" -> GuideActivity::class.java
                "diagnostics" -> DiagnosticsActivity::class.java
                "player" -> MainActivity::class.java
                "scan" -> ScanActivity::class.java
                else -> return text("Specify ?screen=channels|guide|diagnostics|player|scan\n", "400 Bad Request")
            }
            runCatching { context.startActivity(android.content.Intent(context, target).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .fold({ text("Opened ${target.simpleName}\n") }, { text("Error: $it\n", "500 Internal Server Error") })
        }
        "GET" to "/hbbtv" -> Response("200 OK", JSON, JSONObject()
            .put("enabled", HbbTvStore(context).enabled)
            .put("browser", onMain { PlayerRemote.hbb?.state() } ?: JSONObject.NULL)
            .put("channels", org.json.JSONArray().apply { HbbTvStore(context).all().forEach { put(it.toJson()) } })
            .toString(2))
        "POST" to "/hbbtv/eval" -> {
            val js = request.query["js"] ?: return text("Specify ?js=...\n", "400 Bad Request")
            val latch = java.util.concurrent.CountDownLatch(1)
            var result: String? = null
            onMain { PlayerRemote.hbb?.evaluate(js) { result = it; latch.countDown() } ?: run { result = "no player"; latch.countDown() } }
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            text((result ?: "no result within 10 s") + "\n")
        }
        "GET" to "/signal" -> Response("200 OK", HTML, context.assets.open("signal.html").use { it.readBytes() })
        "GET" to "/signal.json" -> Response("200 OK", JSON, SignalMonitor.poll(context).toString())
        "POST" to "/signal/cycle" -> text(SignalMonitor.startCycle(context) + "\n")
        "POST" to "/signal/stop" -> text(SignalMonitor.stopCycle() + "\n")
        "POST" to "/signal/probe" -> {
            val f = request.query["freq"]?.toDoubleOrNull() ?: return text("Specify ?freq=<MHz>\n", "400 Bad Request")
            val t2 = request.query["std"] != "t"
            text(FrequencyProbe.start(context, f, t2, request.query["repeat"] == "1") + "\n")
        }
        "POST" to "/signal/lna" -> {
            val on = request.query["on"] == "1"
            val ok = TunerSettings(context).setValue(Dtv.KEY_LNA, on.toString())
            DebugLog.log("Signal", "tuner LNA = $on (saved: $ok)")
            text("LNA amplifier: ${if (on) "on" else "off"}${if (ok) "" else " – saving failed"}. Takes effect on the next scan/probe.\n")
        }
        "POST" to "/signal/probe/stop" -> text(FrequencyProbe.stop() + "\n")
        "POST" to "/signal/rediscover" -> {
            val p = SignalMonitor.player ?: return text("Player not running\n", "409 Conflict")
            SignalMonitor.onMainBlocking { p.rediscover() }
            text("Channel discovery started – followed by automatic identification (a few minutes).\n")
        }
        "POST" to "/signal/tune" -> {
            val f = request.query["freq"]?.toDoubleOrNull() ?: return text("Specify ?freq=<MHz>\n", "400 Bad Request")
            text(SignalMonitor.tuneMultiplex(context, f) + "\n")
        }
        "GET" to "/channels" -> Response("200 OK", JSON, channelsJson().toString(2))
        "GET" to "/epg" -> {
            val id = request.query["channel"]?.toLongOrNull() ?: return text("Specify ?channel=<id>\n", "400 Bad Request")
            val input = Dtv.findTunerInput(context) ?: return text("No tuner input\n", "503 Service Unavailable")
            val channel = ChannelRepository(context).loadChannels(input.id).firstOrNull { it.id == id }
                ?: return text("Unknown channel $id\n", "404 Not Found")
            val list = ChannelRepository(context).programmes(channel)
            Response("200 OK", JSON, org.json.JSONArray().apply {
                list.forEach { p ->
                    put(JSONObject().put("start", p.startMs).put("end", p.endMs).put("title", p.title)
                        .put("genre", context.genreLabel(p.genre) ?: JSONObject.NULL).put("description", p.description))
                }
            }.toString(1))
        }
        "GET" to "/screenshot.png" -> {
            val service = InstallClickService.instance
                ?: return text("Screenshot requires the accessibility service to be enabled\n", "503 Service Unavailable")
            service.screenshot()?.let { Response("200 OK", "image/png", it) } ?: text("Screenshot failed\n", "500 Internal Server Error")
        }
        "GET" to "/probe.ts" -> TsProbe.probeFile(context).let { f ->
            if (f.exists()) Response("200 OK", "video/mp2t", f.readBytes()) else text("No recording – run POST /probe first\n", "404 Not Found")
        }
        "POST" to "/restart" -> {
            SelfUpdater.restart(context)
            text("Process restarting shortly; the app will start again.\n")
        }
        else -> if (request.path in KNOWN_PATHS) text("Method not allowed: ${request.method}\n", "405 Method Not Allowed") else text("404\n", "404 Not Found")
    }

    /** Log: all of it (no parameters) or incrementally from `since`, detecting a process restart via `boot`. */
    private fun logText(request: HttpRequest): Response {
        val since = request.query["since"]?.toLongOrNull()
            ?: return text(DebugLog.snapshot().joinToString("\n"))
        val slice = DebugLog.since(since, request.query["boot"]?.toLongOrNull())
        val body = if (slice.lines.isEmpty()) "" else slice.lines.joinToString("\n", postfix = "\n")
        return Response(
            "200 OK", TEXT, body,
            mapOf("X-Log-Next" to slice.next.toString(), "X-Log-Boot" to DebugLog.bootId.toString()),
        )
    }

    /** One installation at a time – parallel uploads would write to the same file. */
    @Synchronized
    private fun install(request: HttpRequest, input: InputStream, out: OutputStream): Response {
        val length = request.contentLength ?: return text("Missing Content-Length header\n", "411 Length Required")
        if (length <= 0 || length > MAX_APK_BYTES) return text("Invalid size: $length\n", "413 Payload Too Large")
        // For large files curl waits for “100 Continue” before sending the body.
        if (request.headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
        val file = SelfUpdater.updateFile(context)
        val tmp = File(file.path + ".part")
        tmp.outputStream().use { dst ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) {
                    tmp.delete()
                    return text("Upload aborted after ${length - remaining} of $length B\n", "400 Bad Request")
                }
                dst.write(buffer, 0, n)
                remaining -= n
            }
        }
        if (!tmp.renameTo(file)) error("cannot write ${file.path}")
        DebugLog.log("Update", "received APK: $length B")
        // Only someone who uploaded a correctly signed update gets the access token (otherwise install throws Rejected).
        return text(SelfUpdater.install(context, file).trimEnd() + "\ntoken: ${ServerAccess.token(context)}\n")
    }

    /** Runs code on the main thread and waits for the result (up to 5 s). */
    private fun <T> onMain(block: () -> T): T? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: T? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { value = block() } finally { latch.countDown() }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return value
    }

    /** Channel list with identified names, DVB identifiers and the current programme. */
    private fun channelsJson(): org.json.JSONArray {
        val input = Dtv.findTunerInput(context) ?: return org.json.JSONArray()
        val repository = ChannelRepository(context)
        val now = System.currentTimeMillis()
        return org.json.JSONArray().apply {
            repository.loadChannels(input.id, includeHidden = true).forEach { c ->
                val (on, next) = repository.loadNowNext(c, now)
                put(JSONObject().put("id", c.id).put("number", c.number).put("name", c.name)
                    .put("radio", c.isRadio).put("scrambled", c.scrambled).put("virtual", c.virtual).put("hidden", c.hidden).put("freq", c.frequencyMhz ?: JSONObject.NULL)
                    .put("onid", c.onid ?: JSONObject.NULL).put("tsid", c.tsid ?: JSONObject.NULL).put("sid", c.sid ?: JSONObject.NULL)
                    .put("now", on?.title ?: JSONObject.NULL).put("next", next?.title ?: JSONObject.NULL)
                    .put("epgCount", repository.programmes(c).size))
            }
        }
    }

    private fun status(): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject()
            .put("package", context.packageName)
            .put("versionName", info.versionName)
            .put("versionCode", info.versionCodeCompat())
            .put("buildTime", BuildConfig.BUILD_TIME)
            .put("lastUpdateTime", info.lastUpdateTime)
            .put("pid", Process.myPid())
            .put("boot", DebugLog.bootId)
            .put("systemStart", DebugLog.systemStartMs)
            .put("uptimeSec", android.os.SystemClock.elapsedRealtime() / 1000)
            .put("logStart", DebugLog.processStartSeq)
            .put("foreground", App.foregroundActivity ?: JSONObject.NULL)
            .put("autoConfirm", InstallClickService.isConnected)
            .put("overlay", Settings.canDrawOverlays(context))
            .put("canInstall", context.packageManager.canRequestPackageInstalls())
            .put("installing", SelfUpdater.isAwaitingConfirmation(context))
            .put("lastInstall", SelfUpdater.lastResult(context) ?: JSONObject.NULL)
    }

    private fun send(out: OutputStream, r: Response) {
        val extra = r.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        out.write(
            ("HTTP/1.1 ${r.status}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.file?.length() ?: r.body.size}\r\n$extra" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        if (r.file != null) r.file.inputStream().use { it.copyTo(out, 256 * 1024) } else out.write(r.body)
        out.flush()
    }

    /** Scheduling test without the TV guide: ?channel=<id>&start=<ms>&end=<ms>&title=… */
    private fun scheduleRecording(request: HttpRequest): Response {
        val input = DeviceCapabilities.get(context).inputId ?: return text("No tuner input\n", "409 Conflict")
        val channelId = request.query["channel"]?.toLongOrNull() ?: return text("Specify channel=<id>\n", "400 Bad Request")
        val channel = ChannelRepository(context).loadChannels(input, includeHidden = true).firstOrNull { it.id == channelId }
            ?: return text("No channel $channelId\n", "404 Not Found")
        val start = request.query["start"]?.toLongOrNull() ?: return text("Specify start=<ms>\n", "400 Bad Request")
        val end = request.query["end"]?.toLongOrNull() ?: (start + 30 * 60_000L)
        val programme = Programme(request.query["title"] ?: "Test", "", start, end)
        return RecordingScheduler.schedule(context, channel, programme).fold(
            { text("Scheduled: ${it.toJson()}\n") }, { text("Error: ${it.message}\n", "409 Conflict") })
    }

    /** Recording list with download links (TS files: HEVC/H.264 video, E-AC-3/AAC audio – e.g. VLC). */
    private fun recordingsPage(): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val rows = Recorder.list(context).joinToString("") { e ->
            val size = android.text.format.Formatter.formatShortFileSize(context, e.sizeBytes)
            val minutes = ((e.endMs ?: System.currentTimeMillis()) - e.startMs) / 60_000
            val link = if (e.file?.exists() == true && Recorder.active?.id != e.id) "<a href=\"/recordings/${e.id}.ts\">download</a>" else Html.escapeHtml(e.error ?: e.state)
            "<tr><td>${fmt.format(java.util.Date(e.startMs))}</td><td>${Html.escapeHtml("${e.channelNumber} ${e.channelName}")}</td>" +
                "<td>${Html.escapeHtml(e.title)}</td><td>$minutes min</td><td>$size</td><td>$link</td></tr>"
        }
        val free = android.text.format.Formatter.formatShortFileSize(context, Recorder.freeBytes())
        return page("Recordings", "<p>Free space: $free · .ts files open in e.g. VLC</p><table border=\"1\" cellpadding=\"6\">" +
            "<tr><th>Start</th><th>Channel</th><th>Title</th><th>Duration</th><th>Size</th><th></th></tr>$rows</table>")
    }

    private fun indexPage(): String {
        val report = Html.escapeHtml(Diagnostics(context).build())
        return page(
            "DVB-T diagnostics",
            """<p><a href="/log">Live log (refreshed every 2 s)</a> · <a href="/report.txt">report.txt</a> · <a href="/log.txt">log.txt</a> · <a href="/logcat.txt">logcat.txt</a> · <a href="/status">status</a> · <a href="/signal"><b>signal / antenna</b></a> · <a href="/recordings?format=html">recordings</a> (requires the token from Diagnostics: <code>?token=…</code>)</p><pre>$report</pre>""",
        )
    }

    private fun livePage(): String {
        val log = Html.escapeHtml(DebugLog.snapshot().takeLast(400).joinToString("\n"))
        return page(
            "DVB-T log",
            """<meta http-equiv="refresh" content="2"><p><a href="/">Full report</a></p><pre>$log</pre>
               <script>window.scrollTo(0, document.body.scrollHeight);</script>""",
        )
    }

    private fun page(title: String, body: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title>
        <style>body{font-family:sans-serif;margin:16px;background:#fff;color:#111}pre{font-size:12px;white-space:pre-wrap;word-break:break-all}
        @media (prefers-color-scheme:dark){body{background:#111;color:#ddd}a{color:#8ab4f8}}</style></head>
        <body><h2>$title</h2>$body</body></html>"""

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
        private val RECORDING_FILE = Regex("/recordings/(\\d+)\\.ts")
        private val QUIET_PATHS = setOf("/status", "/log.txt", "/probe.ts", "/screenshot.png", "/hbbtv", "/signal.json")
        private val KNOWN_PATHS = setOf("/", "/report.txt", "/log.txt", "/log", "/logcat.txt", "/status", "/install", "/launch", "/restart", "/probe", "/probe.ts", "/key", "/hbbtv", "/hbbtv/eval", "/screenshot.png", "/channels", "/epg", "/open", "/capabilities", "/recordings", "/timeshift", "/signal", "/signal.json", "/signal/cycle", "/signal/stop", "/signal/tune", "/signal/probe", "/signal/probe/stop", "/signal/lna", "/signal/rediscover")

        /** One server per process – kept alive by [DebugServerService]. */
        @Volatile var instance: DebugHttpServer? = null
    }
}

/** HTTP/1.1 request header (the body is read separately by the handler of the specific endpoint). */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    class BadRequest(message: String) : Exception(message)

    val contentLength: Long? get() = headers["content-length"]?.trim()?.toLongOrNull()

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024

        /** Reads the header byte by byte so as not to consume the start of the body. Null – the client disconnected. */
        fun read(input: InputStream): HttpRequest? {
            val head = StringBuilder()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) return if (head.isEmpty()) null else throw BadRequest("Incomplete header")
                head.append(b.toChar())
                if (head.length > MAX_HEADER_BYTES) throw BadRequest("Header too large")
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = head.toString().split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) throw BadRequest("Invalid request line: ${lines.first()}")
            val target = parts[1]
            val headers = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            return HttpRequest(parts[0].uppercase(), target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers)
        }

        /** Query parameters; bad encoding (e.g. `%zz`) is a [BadRequest], not a thread exception. */
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: IllegalArgumentException) {
            throw BadRequest("Invalid URL encoding: $s")
        }
    }
}
