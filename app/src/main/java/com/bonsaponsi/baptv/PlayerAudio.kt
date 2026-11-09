package com.bonsaponsi.baptv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.Build

/**
 * Player audio: audio focus and an explicit TvView.setStreamVolume(). The dtvinput session mutes itself
 * (TvApp[Audio] mute) at volume 0 and unmutes at >0, so the volume is set after every tune.
 */
class PlayerAudio(context: Context, private val tvView: TvView) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volume = 1f

    /** Session volume last set on the TvView (1 with focus, lower or 0 without). */
    val sessionVolume get() = volume
    private var request: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        volume = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> 1f
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> 0.2f
            else -> 0f
        }
        DebugLog.log("Audio", "audio focus change: ${focusName(change)} -> session volume $volume")
        apply("focus")
    }

    fun start() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        request = req
        val result = audioManager.requestAudioFocus(req)
        volume = if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) 1f else 0f
        DebugLog.log("Audio", "requestAudioFocus = ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "denied ($result)"}; ${systemState()}")
        apply("start")
    }

    private fun regainFocus() {
        val req = request ?: return
        val result = audioManager.requestAudioFocus(req)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) volume = 1f
        DebugLog.log("Audio", "audio focus requested again on tune = ${if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "GRANTED" else "denied ($result)"}")
    }

    fun stop() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }

    /** Sets the tuner session volume – called after tune and after playback starts. */
    fun apply(why: String) {
        // An HbbTV app playing internet video in the WebView takes the audio focus (permanent LOSS); a channel
        // change is a clear request for the TV sound again, so the focus is requested anew.
        if (why == "tune" && volume < 1f) regainFocus()
        tvView.setStreamVolume(volume)
        DebugLog.log("Audio", "TvView.setStreamVolume($volume) [$why]")
    }

    fun systemState(): String {
        val music = AudioManager.STREAM_MUSIC
        val muted = runCatching { audioManager.isStreamMute(music) }.getOrDefault(false)
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).joinToString { "${it.type}:${it.productName}" }
        return "STREAM_MUSIC ${audioManager.getStreamVolume(music)}/${audioManager.getStreamMaxVolume(music)}" +
            (if (muted) " MUTED" else "") +
            ", mode=${audioManager.mode}, outputs=[$devices]"
    }

    companion object {
        fun describeTrack(track: TvTrackInfo): String = buildString {
            append("${track.id} lang=${track.language} ch=${track.audioChannelCount} ${track.audioSampleRate}Hz")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                append(" enc=${track.encoding ?: "?"}")
                if (track.isAudioDescription) append(" AD")
            }
            // isEncrypted and getExtra are missing on older platforms (NoSuchMethodError on Android 9).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (track.isEncrypted) append(" ENCRYPTED")
                if (track.isHardOfHearing) append(" HOH")
                if (track.isSpokenSubtitle) append(" SPOKEN")
            }
            track.description?.let { append(" desc=\"$it\"") }
            // Whatever else the input puts in the track (it is not part of the API, so only the keys and short values).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                track.extra?.takeIf { !it.isEmpty }?.let { extra ->
                    append(" extra={")
                    append(extra.keySet().sorted().joinToString(", ") { key -> "$key=${extra.get(key)?.toString()?.take(40)}" })
                    append("}")
                }
            }
        }

        /** Audio outputs with the sample rates, channel counts and encodings each one reports. */
        fun describeOutputs(audioManager: AudioManager): List<String> =
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { d ->
                "type=${d.type} \"${d.productName}\" rates=${d.sampleRates.joinToString()} " +
                    "channels=${d.channelCounts.joinToString()} encodings=${d.encodings.joinToString()}"
            }

        private fun focusName(change: Int) = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "DUCK"
            else -> change.toString()
        }
    }
}
