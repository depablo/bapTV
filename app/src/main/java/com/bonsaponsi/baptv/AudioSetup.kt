package com.bonsaponsi.baptv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.concurrent.TimeUnit

/**
 * How the device is set up to play compressed audio, for the report. Works on Android 9 too, where the tuner
 * input's tracks carry no codec: decoders on the device, the encoded surround setting, audio related system
 * and vendor settings and properties, and (Android 10+) direct playback of the Dolby formats.
 */
object AudioSetup {

    fun describe(context: Context): List<String> = buildList {
        add("Decoders: " + decoders())
        add("Encoded surround output: " + surround(context))
        directPlayback()?.let { add("Direct playback: $it") }
        settings(context).forEach { add("Setting $it") }
        properties().forEach { add("Property $it") }
    }

    /** AudioFormat encoding constants by value, up to the 32-bit PCM of Android 12. */
    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_PCM_8BIT -> "PCM8"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "E_AC3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS_HD"
        AudioFormat.ENCODING_MP3 -> "MP3"
        AudioFormat.ENCODING_AAC_LC -> "AAC_LC"
        AudioFormat.ENCODING_AAC_HE_V1 -> "AAC_HE_V1"
        AudioFormat.ENCODING_AAC_HE_V2 -> "AAC_HE_V2"
        AudioFormat.ENCODING_IEC61937 -> "IEC61937"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TRUEHD"
        15 -> "AAC_ELD"
        16 -> "AAC_XHE"
        ENCODING_AC4 -> "AC4"
        ENCODING_E_AC3_JOC -> "E_AC3_JOC"
        19 -> "DOLBY_MAT"
        20 -> "OPUS"
        21 -> "PCM24"
        22 -> "PCM32"
        else -> encoding.toString()
    }

    /** Decoders the device lists for the broadcast audio formats; the tuner may decode in its own pipeline. */
    private fun decoders(): String = runCatching {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        DECODER_TYPES.joinToString("; ") { type ->
            val names = infos.filter { info -> info.supportedTypes.any { it.equals(type, ignoreCase = true) } }.map { it.name }
            "$type: ${names.joinToString().ifEmpty { "none" }}"
        }
    }.getOrElse { "unavailable (${it.javaClass.simpleName})" }

    /** Settings.Global encoded_surround_output: 0 auto, 1 never (PCM), 2 always, 3 manual (Android 10+). */
    private fun surround(context: Context): String {
        val cr = context.contentResolver
        val mode = runCatching { Settings.Global.getString(cr, "encoded_surround_output") }.getOrNull()
        val name = when (mode) { "0" -> "auto"; "1" -> "never (PCM)"; "2" -> "always"; "3" -> "manual"; null -> "not set"; else -> mode }
        val formats = runCatching { Settings.Global.getString(cr, "encoded_surround_output_enabled_formats") }.getOrNull()
        return name + formats?.let { f -> ", enabled formats ${f.split(',').mapNotNull { it.trim().toIntOrNull() }.joinToString { encodingName(it) }}" }.orEmpty()
    }

    /** Which Dolby formats the output takes as they are (Android 10+; null on older systems). */
    private fun directPlayback(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        return listOf(AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, ENCODING_E_AC3_JOC, ENCODING_AC4).joinToString { encoding ->
            val masks = listOf("2.0" to AudioFormat.CHANNEL_OUT_STEREO, "5.1" to AudioFormat.CHANNEL_OUT_5POINT1)
            val supported = masks.filter { (_, mask) ->
                runCatching {
                    val format = AudioFormat.Builder().setEncoding(encoding).setSampleRate(48000).setChannelMask(mask).build()
                    android.media.AudioTrack.isDirectPlaybackSupported(format, attributes)
                }.getOrDefault(false)
            }.map { it.first }
            "${encodingName(encoding)} ${supported.joinToString("/").ifEmpty { "no" }}"
        }
    }

    /** Rows of the system, secure and global settings whose names point at the audio output. */
    private fun settings(context: Context): List<String> = listOf(
        "system" to Settings.System.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI,
        "global" to Settings.Global.CONTENT_URI,
    ).flatMap { (table, uri) -> settingsRows(context, table, uri) }.take(MAX_LINES)

    private fun settingsRows(context: Context, table: String, uri: Uri): List<String> = runCatching {
        context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    if (AUDIO_KEY.containsMatchIn(name)) add("$table/$name=${c.getString(1)?.lineSequence()?.first()?.take(80)}")
                }
            }
        }.orEmpty()
    }.getOrElse { listOf("$table: unreadable (${it.javaClass.simpleName})") }

    /** System properties whose names point at the audio output; vendors keep the HDMI/S/PDIF mode there. */
    private fun properties(): List<String> = runCatching {
        val process = ProcessBuilder("getprop").redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor(2, TimeUnit.SECONDS)
        // getprop prints "[name]: [value]".
        lines.filter { line -> line.substringBefore("]:").let { AUDIO_KEY.containsMatchIn(it) && !it.startsWith("[log.tag.") } }
            .map { it.trim() }.take(MAX_LINES)
    }.getOrElse { listOf("unreadable (${it.javaClass.simpleName})") }

    private const val ENCODING_AC4 = 17
    private const val ENCODING_E_AC3_JOC = 18
    private const val MAX_LINES = 60
    private val DECODER_TYPES = listOf("audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/ac4", "audio/mpeg-L2", "audio/mp4a-latm")
    private val AUDIO_KEY = Regex("audio|surround|hdmi|spdif|dolby|passthrough|passthru|bitstream|digital|downmix|ac3|ac4|dts", RegexOption.IGNORE_CASE)
}
