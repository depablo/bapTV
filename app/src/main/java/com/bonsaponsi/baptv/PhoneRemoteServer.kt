package com.bonsaponsi.baptv

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * HTTP server of the phone remote ([PhoneRemote]). Unlike [DebugHttpServer] it runs in release builds, so it
 * exposes only what the remote page needs:
 * - `GET /` – the page (static, no data); `GET /pair?code=…` – pairing from the QR code, sets the device cookie,
 * - `GET /api/state`, `GET /api/channels`, `POST /api/key?name=…`, `POST /api/tune?id=…` – paired devices only,
 * - `GET /api/manage` and `POST /api/manage/{order,hidden,name,hide-scrambled,reset}` – channel organisation,
 *   as on the Manage channels screen (paired devices only),
 * - `GET /api/signal`, `POST /api/signal/{tune,cycle,stop}` – signal measurement for aiming the antenna
 *   ([SignalMonitor]; paired devices only),
 * - `GET /api/iptv`, `POST /api/iptv/{load,probe,add,check,remove}` – internet channels ([Iptv]): reading a playlist,
 *   checking streams, adding, re-checking and removing channels (paired devices only).
 * The Host and cross-site checks of [ServerAccess] apply to every request.
 */
class PhoneRemoteServer(private val context: Context, val port: Int) {

    @Volatile private var server: ServerSocket? = null

    val isRunning get() = server?.isClosed == false

    fun start() {
        if (isRunning) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = socket
        DebugLog.log("Remote", "phone remote server started on port $port")
        thread(name = "phone-remote", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                thread(name = "phone-remote-client", isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        server?.close()
        server = null
        DebugLog.log("Remote", "phone remote server stopped")
    }

    private class Response(val status: String, val type: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
        constructor(status: String, type: String, body: String, headers: Map<String, String> = emptyMap()) :
            this(status, type, body.toByteArray(Charsets.UTF_8), headers)
    }

    private fun json(value: Any, status: String = "200 OK") = Response(status, JSON, value.toString())

    private fun error(status: String, message: String) = json(JSONObject().put("error", message), status)

    /** Every exception stays here – an uncaught one in the thread would kill the process (and a recording). */
    private fun handle(client: Socket) = try {
        client.use { s -> serve(s) }
    } catch (e: java.io.IOException) {
        // Connection aborted or left idle by the browser.
    } catch (e: Throwable) {
        DebugLog.error("Remote", "connection handling error", e)
    }

    private fun serve(s: Socket) {
        s.soTimeout = 15_000
        val out = s.getOutputStream()
        val input = s.getInputStream()
        val request = try {
            HttpRequest.read(input) ?: return
        } catch (e: HttpRequest.BadRequest) {
            send(out, Response("400 Bad Request", TEXT, e.message.orEmpty()))
            return
        }
        val response = when {
            !ServerAccess.hostAllowed(request.headers["host"]) -> Response("403 Forbidden", TEXT, "Connect using the IP address.\n")
            ServerAccess.crossSite(request.headers) -> Response("403 Forbidden", TEXT, "Cross-site request rejected.\n")
            else -> try {
                route(request, input)
            } catch (e: BadBody) {
                error("400 Bad Request", e.message.orEmpty())
            } catch (e: Exception) {
                DebugLog.error("Remote", "error handling ${request.method} ${request.path}", e)
                error("500 Internal Server Error", e.toString())
            }
        }
        send(out, response)
    }

    private class BadBody(message: String) : Exception(message)

    /** Request body as text (playlists, JSON lists of streams), up to [limit] bytes. */
    private fun body(request: HttpRequest, input: java.io.InputStream, limit: Int = MAX_BODY): String {
        val length = request.contentLength ?: throw BadBody("Content-Length required")
        if (length > limit) throw BadBody("body too large")
        val bytes = ByteArray(length.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw BadBody("incomplete body")
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun streamsFrom(text: String): List<Iptv.Stream> = try {
        val array = JSONArray(text)
        (0 until array.length()).map { Iptv.Stream.fromJson(array.getJSONObject(it)) }
    } catch (e: org.json.JSONException) {
        throw BadBody("expected a JSON list of streams")
    }

    private fun route(request: HttpRequest, input: java.io.InputStream): Response {
        when (request.method to request.path) {
            "GET" to "/" -> return Response("200 OK", HTML, context.assets.open("remote.html").use { it.readBytes() })
            "GET" to "/pair" -> return pair(request)
        }
        if (!PhoneRemote.isPaired(context, cookie(request))) return error("401 Unauthorized", "not paired")
        return when (request.method to request.path) {
            "GET" to "/api/state" -> json(state())
            "GET" to "/api/channels" -> json(channels())
            "POST" to "/api/key" -> key(request.query["name"]?.uppercase())
            "POST" to "/api/tune" -> tune(request.query["id"]?.toLongOrNull())
            "GET" to "/api/manage" -> manageList()?.let { json(it) } ?: error("409 Conflict", "no tuner input")
            "POST" to "/api/manage/order" -> manage(request) { store, channels ->
                val ids = request.query["ids"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
                val byId = channels.associateBy { it.id }
                // Channels the phone did not list (e.g. new after a scan) keep their place after the listed ones.
                val ordered = ids.mapNotNull { byId[it] } + channels.filter { it.id !in ids }
                store.setCustomOrder(ordered.map { store.settingsKey(it) })
            }
            "POST" to "/api/manage/hidden" -> manage(request) { store, channels ->
                channelFor(request, channels)?.let { store.setHidden(it, request.query["hidden"] == "true") }
            }
            "POST" to "/api/manage/name" -> manage(request) { store, channels ->
                // An empty name brings back the broadcaster's name.
                channelFor(request, channels)?.let { store.setCustomName(it, request.query["name"]?.take(MAX_NAME)) }
            }
            "POST" to "/api/manage/hide-scrambled" -> manage(request) { store, _ -> store.hideScrambled = request.query["on"] == "true" }
            "POST" to "/api/manage/reset" -> manage(request) { store, _ -> store.resetOrganisation() }
            "GET" to "/api/signal" -> json(SignalMonitor.poll(context).apply { remove("raw"); remove("probe"); remove("lna") })
            "POST" to "/api/signal/tune" -> {
                val frequency = request.query["freq"]?.toDoubleOrNull() ?: return error("400 Bad Request", "missing freq")
                json(JSONObject().put("message", SignalMonitor.tuneMultiplex(context, frequency)))
            }
            "POST" to "/api/signal/cycle" -> json(JSONObject().put("message", SignalMonitor.startCycle(context)))
            "POST" to "/api/signal/stop" -> json(JSONObject().put("message", SignalMonitor.stopCycle()))
            "GET" to "/api/iptv" -> json(iptvList())
            "POST" to "/api/iptv/load" -> loadPlaylist(request.query["url"], request, input)
            "POST" to "/api/iptv/probe" -> {
                val results = IptvProbe.probeAll(streamsFrom(body(request, input)).take(MAX_PROBE))
                json(JSONArray().apply {
                    results.forEach {
                        put(JSONObject().put("status", it.status.name).put("detail", it.detail).put("format", it.format ?: JSONObject.NULL)
                            .put("speed", it.speed ?: JSONObject.NULL))
                    }
                })
            }
            "POST" to "/api/iptv/add" -> {
                val added = Iptv.add(context, streamsFrom(body(request, input)))
                channelsChanged()
                json(iptvList().put("added", added))
            }
            "POST" to "/api/iptv/check" -> {
                // The added channels with the same check as before adding; what fails is marked like a problem seen while watching.
                val own = Iptv.streams(context)
                val problems = Iptv.problems(context)
                IptvProbe.probeAll(own.map { it.second }).zip(own).forEach { (result, entry) ->
                    val id = entry.first.id
                    when (result.status) {
                        // A passed check clears what a check can tell; "keeps buffering" seen while watching stays.
                        IptvProbe.Status.OK -> if (problems[id]?.kind != Iptv.PROBLEM_BUFFERING) Iptv.clearProblem(context, id)
                        IptvProbe.Status.SLOW -> Iptv.reportProblem(context, id, Iptv.PROBLEM_SLOW)
                        else -> Iptv.reportProblem(context, id, Iptv.PROBLEM_ERROR, result.detail)
                    }
                }
                json(iptvList())
            }
            "POST" to "/api/iptv/remove" -> {
                val ids = request.query["ids"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
                val removed = Iptv.remove(context, ids)
                channelsChanged()
                json(iptvList().put("removed", removed))
            }
            else -> error("404 Not Found", "not found")
        }
    }

    /** All channels with their organisation (hidden ones too), as on the Manage channels screen. */
    private fun manageList(): JSONObject? {
        val input = Dtv.findTunerInput(context) ?: return null
        val array = JSONArray()
        ChannelRepository(context).loadChannels(input.id, includeHidden = true).forEach { c ->
            array.put(channelJson(c).put("hidden", c.hidden).put("scrambled", c.scrambled).put("virtual", c.virtual))
        }
        return JSONObject().put("hideScrambled", ChannelStore(context).hideScrambled).put("channels", array)
    }

    private fun channelFor(request: HttpRequest, channels: List<Channel>) =
        request.query["id"]?.toLongOrNull()?.let { id -> channels.firstOrNull { it.id == id } }

    /** Applies a change to the channel organisation, refreshes the TV screens and returns the new list. */
    private fun manage(request: HttpRequest, change: (ChannelStore, List<Channel>) -> Unit): Response {
        val input = Dtv.findTunerInput(context) ?: return error("409 Conflict", "no tuner input")
        val channels = ChannelRepository(context).loadChannels(input.id, includeHidden = true)
        change(ChannelStore(context), channels)
        DebugLog.log("Remote", "channel organisation changed from the phone: ${request.path}")
        channelsChanged()
        return manageList()?.let { json(it) } ?: error("409 Conflict", "no tuner input")
    }

    /** Pairing from the QR code: a valid code sets the device cookie; either way back to the page, which shows the result. */
    private fun pair(request: HttpRequest): Response {
        val token = PhoneRemote.pair(context, request.query["code"], deviceName(request.headers["user-agent"]))
        DebugLog.log("Remote", "pairing attempt: ${if (token != null) "accepted" else "rejected"}")
        val headers = if (token == null) mapOf("Location" to "/?pair=failed")
        else mapOf("Location" to "/", "Set-Cookie" to "$COOKIE=$token; Path=/; Max-Age=$COOKIE_MAX_AGE; HttpOnly; SameSite=Lax")
        return Response("303 See Other", TEXT, "", headers)
    }

    private fun cookie(request: HttpRequest): String? = request.headers["cookie"]?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')

    /** The player and an open Manage channels screen reload their lists. */
    private fun channelsChanged() {
        onMain {
            PlayerRemote.channelsChanged?.invoke()
            PlayerRemote.manageChanged?.invoke()
        }
    }

    /** Internet channels already added and the playlists used before. */
    private fun iptvList(): JSONObject {
        val channels = JSONArray()
        val problems = Iptv.problems(context)
        Iptv.streams(context).sortedBy { ChannelRepository.numberKey(it.first.number) }.forEach { (c, s) ->
            channels.put(JSONObject().put("id", c.id).put("number", c.number).put("name", c.name).put("url", s.url).put("group", s.group).apply {
                problems[c.id]?.let { put("problem", it.kind).put("problemDetail", it.detail ?: JSONObject.NULL).put("problemAt", it.atMs) }
            })
        }
        return JSONObject().put("channels", channels).put("sources", JSONArray(Iptv.sources(context)))
    }

    /** A playlist from an address (fetched here – the phone's browser may not reach it across origins) or uploaded as the body. */
    private fun loadPlaylist(url: String?, request: HttpRequest, input: java.io.InputStream): Response {
        val text = if (url.isNullOrBlank()) body(request, input, MAX_PLAYLIST) else {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return error("400 Bad Request", "address must start with http:// or https://")
            try {
                Iptv.download(url, MAX_PLAYLIST)
            } catch (e: java.io.IOException) {
                return error("502 Bad Gateway", e.message ?: e.javaClass.simpleName)
            }
        }
        val playlist = Iptv.parseM3u(text)
        if (playlist.streams.isEmpty()) return error("422 Unprocessable Entity", "no channels in the playlist")
        if (!url.isNullOrBlank()) Iptv.rememberSource(context, url)
        // Kept with each channel added from it (shown in the player's info panel).
        val source = url?.takeIf { it.isNotBlank() } ?: request.query["name"].orEmpty().take(MAX_NAME)
        val streams = JSONArray().apply { playlist.streams.forEach { put(it.copy(source = source).toJson()) } }
        return json(JSONObject().put("streams", streams).put("epgUrl", playlist.epgUrl ?: JSONObject.NULL))
    }

    /** What is on screen: the player's channel with now/next, or the name of another app screen. */
    private fun state(): JSONObject {
        val state = JSONObject().put("screen", App.foregroundActivity ?: JSONObject.NULL)
            // The Signal tab needs tuner statistics, which not every device exposes.
            .put("signal", DeviceCapabilities.get(context).signal)
        val channel = onMain { PlayerRemote.currentChannel?.invoke() }
        if (channel != null) {
            val (now, next) = ChannelRepository(context).loadNowNext(channel)
            state.put("channel", channelJson(channel))
            now?.let { state.put("now", JSONObject().put("title", it.title).put("start", it.startMs).put("end", it.endMs)) }
            next?.let { state.put("next", JSONObject().put("title", it.title).put("start", it.startMs)) }
        }
        Recorder.active?.let { state.put("recording", it.title) }
        return state
    }

    private fun channels(): JSONObject {
        val list = onMain { PlayerRemote.channels?.invoke() } ?: return JSONObject().put("channels", JSONArray())
        val repository = ChannelRepository(context)
        val now = System.currentTimeMillis()
        val array = JSONArray()
        list.forEach { c -> array.put(channelJson(c).put("now", repository.loadNowNext(c, now).first?.title ?: JSONObject.NULL)) }
        return JSONObject().put("channels", array)
    }

    private fun channelJson(c: Channel) = JSONObject().put("id", c.id).put("number", c.number).put("name", c.name).put("radio", c.isRadio).put("iptv", c.iptv)

    /** A key as if pressed on the remote: injected into the app's focused window (dialogs too), otherwise to the activity. */
    private fun key(name: String?): Response {
        val code = name?.let { KEYS[it] } ?: return error("400 Bad Request", "unknown key")
        val activity = App.resumed?.get() ?: return error("409 Conflict", "app not on screen")
        try {
            android.app.Instrumentation().sendKeyDownUpSync(code)
        } catch (e: SecurityException) {
            onMain {
                val now = android.os.SystemClock.uptimeMillis()
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        }
        return json(JSONObject().put("ok", true))
    }

    private fun tune(id: Long?): Response {
        id ?: return error("400 Bad Request", "missing id")
        val tune = PlayerRemote.tune ?: return error("409 Conflict", "player not running")
        return if (onMain { tune(id) } == true) json(JSONObject().put("ok", true)) else error("409 Conflict", "cannot tune now")
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

    private fun send(out: OutputStream, r: Response) {
        val extra = r.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        out.write(
            ("HTTP/1.1 ${r.status}\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\n$extra" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(r.body)
        out.flush()
    }

    companion object {
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"
        private const val COOKIE = "baptv_remote"
        private const val COOKIE_MAX_AGE = 10 * 365 * 24 * 3600
        private const val MAX_NAME = 60
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_PLAYLIST = 30 * 1024 * 1024
        /** Streams checked per request – the phone asks in batches, so the page can show progress. */
        private const val MAX_PROBE = 24

        private val KEYS = HbbTvBrowser.KEY_NAMES + mapOf(
            "GUIDE" to KeyEvent.KEYCODE_GUIDE, "LAST" to KeyEvent.KEYCODE_LAST_CHANNEL,
            "AUDIO" to KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, "SUBTITLES" to KeyEvent.KEYCODE_CAPTIONS,
        )

        /** Short device name for the paired list, from the User-Agent (e.g. "Pixel 7", "iPhone"). */
        fun deviceName(userAgent: String?): String {
            val inside = userAgent?.substringAfter('(', "")?.substringBefore(')').orEmpty()
            val parts = inside.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            val name = when {
                "iPhone" in inside -> "iPhone"
                "iPad" in inside -> "iPad"
                parts.any { it.startsWith("Android") } ->
                    parts.lastOrNull { !it.startsWith("Android") && it !in setOf("Linux", "K", "wv", "U") && !it.startsWith("Build/") }
                        ?.substringBefore(" Build/") ?: "Android"
                else -> parts.firstOrNull() ?: "Browser"
            }
            return name.take(40)
        }
    }
}
