package com.bonsaponsi.baptv

import android.content.ContentUris
import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * TV input of internet channels ([Iptv]): plays HLS, DASH and progressive streams with Media3 into the surface
 * of the player's TvView. Live streams that drop are reconnected a few times; why a stream failed is kept in
 * [lastError] for the player's status line (same process).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class IptvInputService : TvInputService() {

    override fun onCreateSession(inputId: String): Session = IptvSession(this)

    private inner class IptvSession(context: Context) : Session(context) {
        private val handler = Handler(Looper.getMainLooper())
        private var player: ExoPlayer? = null
        private var surface: Surface? = null
        private var volume = 1f
        private var stream: Iptv.Stream? = null
        private var retries = 0
        /** The address has no recognisable extension and did not play as a plain file – try it as HLS. */
        private var asHls = false
        private var firstFrame = false
        /** Buffering after the picture appeared – READY then means the picture is back. */
        private var stalled = false

        override fun onSetSurface(surface: Surface?): Boolean {
            this.surface = surface
            player?.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            this.volume = volume
            player?.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onTune(channelUri: Uri): Boolean {
            handler.removeCallbacksAndMessages(null)
            val id = runCatching { ContentUris.parseId(channelUri) }.getOrNull()
            val s = id?.let { Iptv.stream(this@IptvInputService, it) }
            if (s == null) {
                DebugLog.warn("Iptv", "no stream for $channelUri")
                lastError = null
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return false
            }
            stream = s
            retries = 0
            asHls = false
            start()
            return true
        }

        private fun start() {
            val s = stream ?: return
            releasePlayer()
            firstFrame = false
            stalled = false
            lastError = null
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(s.userAgent ?: Iptv.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
            s.referrer?.let { http.setDefaultRequestProperties(mapOf("Referer" to it)) }
            val mime = mimeType(s.url) ?: MimeTypes.APPLICATION_M3U8.takeIf { asHls }
            DebugLog.log("Iptv", "play \"${s.name}\" ${s.url}${mime?.let { " as $it" } ?: ""}")
            player = ExoPlayer.Builder(this@IptvInputService)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build().apply {
                    setVideoSurface(surface)
                    volume = this@IptvSession.volume
                    addListener(listener)
                    setMediaItem(MediaItem.Builder().setUri(s.url).setMimeType(mime).build())
                    playWhenReady = true
                    prepare()
                }
        }

        private fun releasePlayer() {
            player?.run {
                removeListener(listener)
                release()
            }
            player = null
        }

        override fun onRelease() {
            handler.removeCallbacksAndMessages(null)
            releasePlayer()
        }

        override fun onSelectTrack(type: Int, trackId: String?): Boolean {
            val p = player ?: return false
            if (type != TvTrackInfo.TYPE_AUDIO || trackId == null) return false
            val (g, i) = trackId.split(':').map { it.toIntOrNull() ?: return false }.takeIf { it.size == 2 } ?: return false
            val group = p.currentTracks.groups.getOrNull(g) ?: return false
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
            notifyTrackSelected(type, trackId)
            return true
        }

        private val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (firstFrame) {
                        stalled = true
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
                    }
                    Player.STATE_READY -> {
                        retries = 0
                        notifyContentAllowed()
                        val tracks = player?.currentTracks
                        when {
                            // Before the first frame onRenderedFirstFrame reports the picture.
                            stalled -> { stalled = false; notifyVideoAvailable() }
                            tracks != null && !tracks.containsType(C.TRACK_TYPE_VIDEO) && tracks.containsType(C.TRACK_TYPE_AUDIO) ->
                                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
                        }
                    }
                    // A live stream should not end – reconnect.
                    Player.STATE_ENDED -> retry("stream ended")
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrame = true
                notifyVideoAvailable()
            }

            override fun onTracksChanged(tracks: Tracks) = publishTracks(tracks)

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                player?.let { publishTracks(it.currentTracks) }
            }

            override fun onPlayerError(error: PlaybackException) {
                val p = player ?: return
                lastError = describe(error)
                DebugLog.warn("Iptv", "playback error ${error.errorCodeName}: $lastError")
                when {
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                        p.seekToDefaultPosition()
                        p.prepare()
                    }
                    !asHls && mimeType(stream?.url.orEmpty()) == null && error.errorCode in PARSING_ERRORS -> {
                        asHls = true
                        start()
                    }
                    else -> {
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                        // A refused or missing stream does not come back by retrying.
                        if (httpStatus(error) !in 400..499) retry(error.errorCodeName)
                    }
                }
            }
        }

        private fun retry(why: String) {
            if (retries >= MAX_RETRIES) {
                DebugLog.warn("Iptv", "giving up after $retries retries ($why)")
                return
            }
            retries++
            DebugLog.log("Iptv", "reconnecting in ${RETRY_DELAY_MS * retries} ms ($why)")
            handler.postDelayed({ start() }, RETRY_DELAY_MS * retries)
        }

        /** Audio tracks (selectable) and the playing video track (its size shows in the info panel). */
        private fun publishTracks(tracks: Tracks) {
            val list = ArrayList<TvTrackInfo>()
            var audio: String? = null
            // Adaptive streams select every variant – only the one playing (or else the first selected) is reported.
            var video: Pair<String, androidx.media3.common.Format>? = null
            val playing = player?.videoFormat
            tracks.groups.forEachIndexed { g, group ->
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    val id = "$g:$i"
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> {
                            list += TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, id).apply {
                                f.language?.let { setLanguage(it) }
                                f.label?.let { setDescription(it) }
                                if (f.channelCount > 0) setAudioChannelCount(f.channelCount)
                                if (f.sampleRate > 0) setAudioSampleRate(f.sampleRate)
                            }.build()
                            if (group.isTrackSelected(i)) audio = id
                        }
                        C.TRACK_TYPE_VIDEO -> if (group.isTrackSelected(i) && (video == null || f == playing)) video = id to f
                    }
                }
            }
            video?.let { (id, f) ->
                val size = player?.videoSize
                list += TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, id).apply {
                    (size?.width?.takeIf { it > 0 } ?: f.width.takeIf { it > 0 })?.let { setVideoWidth(it) }
                    (size?.height?.takeIf { it > 0 } ?: f.height.takeIf { it > 0 })?.let { setVideoHeight(it) }
                    if (f.frameRate > 0) setVideoFrameRate(f.frameRate)
                }.build()
            }
            notifyTracksChanged(list)
            notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, audio)
            notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, video?.first)
        }
    }

    companion object {
        /** Why the last stream failed (e.g. "HTTP 403"), for the player's status line; null while it plays. */
        @Volatile var lastError: String? = null
            private set

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 3_000L
        private val PARSING_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )

        /** Stream type from the address; null – let the player detect it. */
        fun mimeType(url: String): String? {
            val path = Uri.parse(url).path.orEmpty().lowercase()
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
                path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }

        private fun httpStatus(error: PlaybackException): Int? {
            var cause: Throwable? = error.cause
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
                cause = cause.cause
            }
            return null
        }

        private fun describe(error: PlaybackException): String = when (val status = httpStatus(error)) {
            null -> when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "no connection"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "unsupported format"
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED, PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "DRM"
                else -> error.errorCodeName
            }
            else -> "HTTP $status"
        }
    }
}
