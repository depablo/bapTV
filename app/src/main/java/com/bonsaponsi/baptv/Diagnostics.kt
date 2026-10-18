package com.bonsaponsi.baptv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Text report on the device, TV inputs, channels and the log – a replacement for `adb logcat`/`dumpsys`. */
class Diagnostics(private val context: Context) {

    private val sb = StringBuilder()

    fun build(includeChannels: Boolean = true, includeLogcat: Boolean = true): String {
        sb.setLength(0)
        section("App") { appInfo() }
        section("Device") { deviceInfo() }
        section("Device capabilities") { DeviceCapabilities.refresh(context).describe().forEach { line(it) } }
        section("Network") { line("IP addresses: ${localAddresses().ifEmpty { listOf("none") }.joinToString()}") }
        section("App permissions") { permissions() }
        section("DTV system packages") { packages() }
        section("TV inputs (TvInputManager)") { inputs() }
        section("Channels in TvContract") { channels(includeChannels) }
        section("Discovered channels (probing)") { discoveredChannels() }
        section("EPG (TvContract.Programs)") { epg() }
        section("DtvProvider (com.google.android.tv.dtvprovider)") { providerTables() }
        section("Tuner file system /mnt/dtvfs") { dtvfs() }
        section("Audio") {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            listOf("MUSIC" to android.media.AudioManager.STREAM_MUSIC, "SYSTEM" to android.media.AudioManager.STREAM_SYSTEM).forEach { (n, st) ->
                line("STREAM_$n: ${am.getStreamVolume(st)}/${am.getStreamMaxVolume(st)}, mute=${runCatching { am.isStreamMute(st) }.getOrNull()}")
            }
            line("Mode: ${am.mode}, music active: ${am.isMusicActive}")
            am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).forEach { d ->
                line("Output: type=${d.type} \"${d.productName}\" encodings=${d.encodings.joinToString()} channels=${d.channelCounts.joinToString()}")
            }
        }
        section("Signal statistics") { line(SignalReader(context).read()?.format() ?: "unavailable") }
        section("App event log (newest last)") { DebugLog.snapshot().takeLast(1500).forEach { line(it) } }
        if (includeLogcat) section("App process logcat") { sb.append(ownLogcat(1000)) }
        return sb.toString()
    }

    private fun appInfo() {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        line("Package: ${context.packageName} ${info?.versionName} (${info?.longVersionCodeCompat()})")
        line("Report time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        line("Uptime: ${SystemClock.elapsedRealtime() / 60000} min")
        line("DVB input selected by the app: ${Dtv.findTunerInput(context)?.id ?: "NONE"}")
    }

    private fun deviceInfo() {
        line("Manufacturer/model: ${Build.MANUFACTURER} / ${Build.MODEL} (${Build.DEVICE}, product ${Build.PRODUCT})")
        line("Board/hardware: ${Build.BOARD} / ${Build.HARDWARE}")
        line("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
        line("Build: ${Build.DISPLAY}")
        line("Fingerprint: ${Build.FINGERPRINT}")
        val tvFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV)
        line("Feature live_tv: $tvFeature, leanback: ${context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)}")
    }

    private fun permissions() {
        listOf(Dtv.PERMISSION_READ_EPG, Dtv.PERMISSION_READ_TV_LISTINGS, Dtv.PERMISSION_DTV_PROVIDER_READ, Dtv.PERMISSION_INSTALL_TV_CHANNELS).forEach { p ->
            val granted = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            @Suppress("DEPRECATION")
            val level = runCatching { "0x" + Integer.toHexString(context.packageManager.getPermissionInfo(p, 0).protectionLevel) }
                .getOrElse { "unknown (not defined)" }
            line("${if (granted) "[YES]" else "[NO]"} $p  (protection=$level)")
        }
    }

    @Suppress("DEPRECATION")
    private fun packages() {
        val pm = context.packageManager
        Dtv.RELATED_PACKAGES.forEach { name ->
            val info = runCatching { pm.getPackageInfo(name, 0) }.getOrNull()
            if (info == null) {
                line("$name: not installed / not visible")
                return@forEach
            }
            val app = info.applicationInfo
            val flags = buildList {
                if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) add("system")
                if (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) add("updated")
                if (!app.enabled) add("DISABLED")
                runCatching { if (pm.getApplicationEnabledSetting(name) >= PackageManager.COMPONENT_ENABLED_STATE_DISABLED) add("disabled-by-user") }
            }
            line("$name: ${info.versionName} (${info.longVersionCodeCompat()}) [${flags.joinToString()}] ${app.sourceDir}")
        }
        val installer = runCatching { pm.getServiceInfo(Dtv.INSTALLER_SERVICE, 0) }.getOrNull()
        line("InstallerService: ${if (installer == null) "NOT FOUND" else "exported=${installer.exported}, permission=${installer.permission}, enabled=${installer.enabled}"}")
        val setupIntent = Dtv.findTunerInput(context)?.createSetupIntent()
        line("Input setup wizard (createSetupIntent): ${setupIntent?.component ?: "none"}")
        val operator = OperatorApp.find(context)
        line("Operator app: ${operator?.let { "${it.label} (${it.packageName}), channel: ${it.viewActivity.className}, launcher=${it.launchIntent(context)?.component ?: "none (HOME only)"}" } ?: "none"}")
    }

    private fun inputs() {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        if (manager == null) {
            line("TV_INPUT_SERVICE not available")
            return
        }
        val list = manager.tvInputList
        line("Input count: ${list.size}")
        list.forEach { input ->
            val state = runCatching { manager.getInputState(input.id) }.getOrNull()
            line("- ${input.id}")
            line("    type=${inputType(input.type)}, label=\"${input.loadLabel(context)}\", passthrough=${input.isPassthroughInput}, hidden=${input.isHidden(context)}")
            line("    tuners=${input.tunerCount}, recording=${input.canRecord()}, state=${inputState(state)}, parent=${input.parentId}")
            line("    setup=${input.createSetupIntent()?.component}, settings=${runCatching { input.createSettingsIntent()?.component }.getOrNull()}")
        }
    }

    private fun channels(listAll: Boolean) {
        val projection = arrayOf(
            Channels._ID, Channels.COLUMN_INPUT_ID, Channels.COLUMN_TYPE, Channels.COLUMN_SERVICE_TYPE,
            Channels.COLUMN_DISPLAY_NUMBER, Channels.COLUMN_DISPLAY_NAME, Channels.COLUMN_BROWSABLE,
            Channels.COLUMN_ORIGINAL_NETWORK_ID, Channels.COLUMN_TRANSPORT_STREAM_ID, Channels.COLUMN_SERVICE_ID,
            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1, Channels.COLUMN_INTERNAL_PROVIDER_DATA, Channels.COLUMN_VIDEO_FORMAT,
            Channels.COLUMN_PACKAGE_NAME,
        )
        val rows = ArrayList<String>()
        val perInput = linkedMapOf<String, IntArray>() // all, TV, radio, other, hidden, scrambled
        try {
            val cursor = context.contentResolver.query(Channels.CONTENT_URI, projection, null, null, null)
            if (cursor == null) {
                line("Query returned null (no access to TvProvider?)")
                return
            }
            cursor.use { c ->
                while (c.moveToNext()) {
                    val input = c.str(1) ?: "?"
                    val serviceType = c.str(3)
                    val flag1 = if (c.isNull(10)) null else c.getLong(10)
                    val data = if (c.isNull(11)) null else runCatching { String(c.getBlob(11), Charsets.UTF_8) }.getOrNull()
                    val scrambled = (flag1 != null && (flag1 shr 3) and 1L == 1L) || data?.contains("\"scrambled\":true") == true
                    val hidden = !c.isNull(6) && c.getInt(6) == 0
                    val counts = perInput.getOrPut(input) { IntArray(6) }
                    counts[0]++
                    when (serviceType) {
                        Channels.SERVICE_TYPE_AUDIO_VIDEO -> counts[1]++
                        Channels.SERVICE_TYPE_AUDIO -> counts[2]++
                        else -> counts[3]++
                    }
                    if (hidden) counts[4]++
                    if (scrambled) counts[5]++
                    if (listAll && rows.size < 300) {
                        rows += "  #${c.getLong(0)} [${c.str(4)}] \"${c.str(5)}\" ${c.str(2)}/${serviceType?.removePrefix("SERVICE_TYPE_")}" +
                            " onid=${c.str(7)} tsid=${c.str(8)} sid=${c.str(9)} browsable=${c.str(6)} flag1=$flag1" +
                            (if (scrambled) " SCRAMBLED" else "") + " video=${c.str(12)} pkg=${c.str(13)}" +
                            (data?.let { " data=${it.take(160)}" } ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            line("ERROR reading channels: $e")
            return
        }
        if (perInput.isEmpty()) line("No channels (or READ_EPG_DATA permission missing).")
        perInput.forEach { (input, n) ->
            line("$input: all=${n[0]}, TV=${n[1]}, radio=${n[2]}, other=${n[3]}, hidden=${n[4]}, scrambled=${n[5]}")
        }
        val tuner = Dtv.findTunerInput(context)
        if (tuner != null) {
            val playable = ChannelRepository(context).loadChannels(tuner.id)
            line("Channels visible in the app for ${tuner.id}: ${playable.size}")
            if (playable.isNotEmpty()) {
                val programs = countPrograms()
                line("EPG programs in database: $programs")
            }
        }
        if (rows.isNotEmpty()) {
            line("")
            line("Channel list (max 300):")
            rows.forEach { line(it) }
        }
    }

    private fun discoveredChannels() {
        val list = ChannelStore(context).loadDiscovered()
        if (list.isEmpty()) {
            line("No saved list.")
            return
        }
        line("Total: ${list.size} (TV ${list.count { !it.isRadio && !it.scrambled }}, radio ${list.count { it.isRadio && !it.scrambled }}, scrambled ${list.count { it.scrambled }})")
        line("Multiplexes: ${ChannelStore.muxSummary(list)}")
        val store = ChannelStore(context)
        list.forEach { ch ->
            val type = when {
                ch.scrambled -> "scrambled"
                ch.isRadio -> "radio"
                else -> "TV"
            }
            line("  ${ch.number}. #${ch.id} ${store.customName(ch) ?: ch.name} [$type] ${ch.frequencyMhz?.let { "%.1f MHz".format(Locale.US, it) } ?: "? MHz"}")
        }
    }

    private fun epg() {
        try {
            context.contentResolver.query(
                TvContract.Programs.CONTENT_URI,
                arrayOf(TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS),
                null, null, null,
            )?.use { c ->
                val perChannel = sortedMapOf<Long, Int>()
                val samples = ArrayList<String>()
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                while (c.moveToNext()) {
                    perChannel[c.getLong(0)] = (perChannel[c.getLong(0)] ?: 0) + 1
                    if (samples.size < 30) samples += "#${c.getLong(0)} ${fmt.format(Date(c.getLong(2)))} ${c.getString(1)}"
                }
                line("Programs: ${c.count}, channels with EPG: ${perChannel.size} ${perChannel}")
                samples.forEach { line("  $it") }
            } ?: line("Query returned null")
        } catch (e: Exception) {
            line("ERROR: $e")
        }
    }

    /** Attempts to read service entries in dtvfs (service names live in the installer JSON / xattr attributes). */
    private fun dtvfs() {
        val root = java.io.File("/mnt/dtvfs")
        line("exists=${root.exists()} directory=${root.isDirectory} readable=${root.canRead()}")
        val top = root.list()
        line("contents: ${top?.joinToString() ?: "no access (list() = null)"}")
        listOf("/mnt/dtvfs", "/mnt/dtvfs/installer", "/mnt/dtvfs/live").forEach { path -> xattrs(path) }
        val live = java.io.File("/mnt/dtvfs/live").listFiles()
        line("live/: ${live?.size ?: "no access"}")
        live?.sortedBy { it.name }?.take(60)?.forEach { f ->
            line("  ${f.name} ${if (f.isDirectory) "<dir>" else "${f.length()} B"}")
            xattrs(f.path, indent = "    ")
            if (f.isFile && f.canRead()) {
                runCatching { f.inputStream().use { String(it.readNBytesCompat(300), Charsets.UTF_8) } }
                    .onSuccess { line("    content: ${it.replace('\n', ' ')}") }
                    .onFailure { line("    content: $it") }
            }
        }
    }

    private fun xattrs(path: String, indent: String = "  ") {
        try {
            val names = android.system.Os.listxattr(path)
            line("${indent}xattr $path: ${names.joinToString()}")
            names.take(20).forEach { n ->
                val v = runCatching { String(android.system.Os.getxattr(path, n), Charsets.UTF_8) }.getOrElse { "<$it>" }
                line("$indent  $n = ${v.take(300)}")
            }
        } catch (e: Exception) {
            line("${indent}xattr $path: $e")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf.copyOf(off)
    }

    private fun countPrograms(): String = try {
        context.contentResolver.query(TvContract.Programs.CONTENT_URI, arrayOf(TvContract.Programs._ID), null, null, null)
            ?.use { it.count.toString() } ?: "null"
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    private fun providerTables() {
        Dtv.PROVIDER_TABLES.forEach { table ->
            line("[$table]")
            try {
                val cursor = context.contentResolver.query(Uri.parse("${Dtv.PROVIDER_AUTHORITY}/$table"), null, null, null, null)
                if (cursor == null) {
                    line("  null (provider unavailable)")
                    return@forEach
                }
                cursor.use { c ->
                    line("  rows: ${c.count}, columns: ${c.columnNames.joinToString()}")
                    var row = 0
                    while (c.moveToNext() && row < 20) {
                        val values = (0 until c.columnCount).joinToString(", ") { i ->
                            val v = when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> "null"
                                Cursor.FIELD_TYPE_BLOB -> "blob(${c.getBlob(i).size})"
                                else -> c.getString(i)
                            }
                            "${c.getColumnName(i)}=${v.take(400)}"
                        }
                        line("  $values")
                        row++
                    }
                }
            } catch (e: Exception) {
                line("  ERROR: $e")
            }
        }
    }


    private fun section(title: String, body: () -> Unit) {
        sb.append("\n===== ").append(title).append(" =====\n")
        try {
            body()
        } catch (e: Exception) {
            line("Section ERROR: $e")
            DebugLog.error("Diagnostics", "Section '$title' failed", e)
        }
    }

    private fun line(text: String) {
        sb.append(text).append('\n')
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun inputType(type: Int) = when (type) {
        TvInputInfo.TYPE_TUNER -> "TUNER"
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_OTHER -> "OTHER"
        TvInputInfo.TYPE_COMPOSITE -> "COMPOSITE"
        TvInputInfo.TYPE_COMPONENT -> "COMPONENT"
        else -> type.toString()
    }

    private fun inputState(state: Int?) = when (state) {
        TvInputManager.INPUT_STATE_CONNECTED -> "CONNECTED"
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "STANDBY"
        TvInputManager.INPUT_STATE_DISCONNECTED -> "DISCONNECTED"
        else -> "?($state)"
    }

    companion object {
        /** Logcat of the app's own process (without READ_LOGS the system shows only the app's entries). */
        /** Logcat of the app's own process; the line count from the URL is clamped, the logcat process is always cleaned up. */
        fun ownLogcat(lines: Int): String = try {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", lines.coerceIn(1, 20_000).toString())
                .redirectErrorStream(true).start()
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            } finally {
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                process.destroy()
            }
        } catch (e: Exception) {
            "logcat unavailable: $e\n"
        }

        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() } }
        }.getOrDefault(emptyList())
    }
}
