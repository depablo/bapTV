package com.bonsaponsi.baptv

/**
 * Audio quirks of the Sagemcom set-top box tuner input (STB Input, com.sagemcom.stb.tvinput), kept apart from the
 * activity so they can be tested without a device.
 *
 * Its audio track ids are "PID:language:AD:mode" (mode 1 mono, 2 dual mono, 3 stereo, 4 multichannel, 0 not
 * signalled) and the extras carry the middleware's encoding ([ENCODING_EXTRA]: 7 = E-AC-3, none for a codec the
 * middleware cannot name, e.g. AC-4). Measured on a DCTIW362 PLAY: the middleware never starts the audio decoder for
 * an E-AC-3 track whose PMT descriptor says "not a full service" (component_type full service flag 0), even when the
 * channel has no other audio – TVP's national channels are signalled that way and play without sound.
 */
object SagemcomAudio {
    const val ENCODING_EXTRA = "com.sagemcom.stb.TvInput.audio.encoding"
    const val DESCRIPTION_EXTRA = "com.sagemcom.stb.TvInput.audio.description"
    const val PID_EXTRA = "com.sagemcom.stb.TvInput.audio.pid"
    private const val ENCODING_EAC3 = 7
    private const val MODE_MONO = 1

    /** What the player needs to know about one audio track; [sagemcom] is false for every other input. */
    data class Track(
        val id: String,
        val language: String?,
        val encoding: Int?,
        val description: Boolean,
        val sagemcom: Boolean,
    ) {
        /** Channel mode from the last field of the id, null when the id has another form. */
        val mode: Int? get() = id.split(':').takeIf { sagemcom && it.size == 4 }?.last()?.toIntOrNull()
    }

    /**
     * The input can select by default a track it cannot decode: TVP1's AC-4 track ("mul"), which comes without an
     * encoding. Returns a better track – one with a known encoding, in the preferred language when there is one,
     * not audio description – or null when the selection is fine or nothing better exists.
     */
    fun betterDefault(tracks: List<Track>, selectedId: String?, preferredLanguage: String?): String? {
        val selected = tracks.firstOrNull { it.id == selectedId } ?: return null
        if (!selected.sagemcom || selected.encoding != null) return null
        val known = tracks.filter { it.sagemcom && it.encoding != null }
        if (known.isEmpty()) return null
        return (known.firstOrNull { it.language == preferredLanguage && !it.description }
            ?: known.firstOrNull { !it.description }
            ?: known.first()).id
    }

    /**
     * Every track with a codec the middleware can name is E-AC-3 marked as mono (tracks without one, like TVP1's
     * AC-4, do not play either). This is the trace a PMT with component_type 0x00 ("not a full service", 1/0) leaves
     * in the ids, and those channels play without sound on this box. A real full-service mono E-AC-3 track looks the
     * same, so the result is only a reason for a hint, never for hiding a channel.
     */
    fun likelySilent(tracks: List<Track>): Boolean {
        val known = tracks.filter { it.encoding != null }
        return tracks.all { it.sagemcom } && known.isNotEmpty() && known.all { it.encoding == ENCODING_EAC3 && it.mode == MODE_MONO }
    }
}
