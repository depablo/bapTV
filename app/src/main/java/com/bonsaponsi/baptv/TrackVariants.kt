package com.bonsaponsi.baptv

/**
 * Some tuner inputs build the audio track id from the PMT entry as "PID:language:AD flag:codec class"
 * (e.g. "103:pol:0:1"). When such an input files a track under the wrong codec class, the track can be selected
 * again with another class: the input may set up its decoder from the id it is given rather than its own table.
 */
object TrackVariants {
    private val ID = Regex("""^(\d+:[^:]*:\d+):(\d+)$""")

    /** Classes offered in the test, 0 up to this. */
    const val MAX_CLASS = 5

    /** The codec class in [id], or null when the id does not have that form. */
    fun codecClass(id: String?): Int? = id?.let { ID.matchEntire(it) }?.groupValues?.get(2)?.toIntOrNull()

    /** [id] with its codec class replaced by [codecClass], or null when the id does not have that form. */
    fun withClass(id: String?, codecClass: Int): String? = id?.let { ID.matchEntire(it) }?.let { "${it.groupValues[1]}:$codecClass" }
}
