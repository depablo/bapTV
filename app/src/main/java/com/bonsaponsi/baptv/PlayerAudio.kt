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

    fun stop() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }

    /** Sets the tuner session volume – called after tune and after playback starts. */
    fun apply(why: String) {
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
            track.description?.let { append(" desc=\"$it\"") }
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
