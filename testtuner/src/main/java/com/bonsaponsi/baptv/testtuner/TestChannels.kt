package com.bonsaponsi.baptv.testtuner

import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.Programs
import android.util.Log

/**
 * Test channels: two from the TVP multiplex, Polsat, and a channel outside the Polish service catalog (checks
 * that the app takes the name and number from TvContract). DVB identifiers as in the Polish network (ONID 8808).
 */
object TestChannels {
    data class Def(val number: String, val name: String, val onid: Int, val tsid: Int, val sid: Int, val color: Int)

    val ALL = listOf(
        Def("1", "TVP1 HD", 8808, 3, 1, 0xFF1E3A8A.toInt()),
        Def("2", "TVP2 HD", 8808, 3, 2, 0xFF7C2D12.toInt()),
        Def("4", "Polsat", 8808, 2, 3, 0xFF14532D.toInt()),
        Def("77", "Test channel", 9999, 1, 77, 0xFF4C1D95.toInt()),
    )

    private const val TAG = "TestTuner"
    private const val SLOT_MS = 30 * 60_000L

    /** Replaces the input's channels with the test ones and adds a 2-day TV guide (a programme every 30 min). */
    fun install(context: Context, inputId: String): Int {
        val resolver = context.contentResolver
        resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)
        val now = System.currentTimeMillis()
        val firstSlot = now - now % SLOT_MS - SLOT_MS
        val hhmm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        ALL.forEach { def ->
            val values = ContentValues().apply {
                put(Channels.COLUMN_INPUT_ID, inputId)
                put(Channels.COLUMN_TYPE, Channels.TYPE_DVB_T2)
                put(Channels.COLUMN_SERVICE_TYPE, Channels.SERVICE_TYPE_AUDIO_VIDEO)
                put(Channels.COLUMN_DISPLAY_NUMBER, def.number)
                put(Channels.COLUMN_DISPLAY_NAME, def.name)
                put(Channels.COLUMN_ORIGINAL_NETWORK_ID, def.onid)
                put(Channels.COLUMN_TRANSPORT_STREAM_ID, def.tsid)
                put(Channels.COLUMN_SERVICE_ID, def.sid)
                put(Channels.COLUMN_SEARCHABLE, 1)
            }
            val uri = resolver.insert(Channels.CONTENT_URI, values) ?: return@forEach
            val channelId = android.content.ContentUris.parseId(uri)
            val programmes = (0 until 2 * 48).map { i ->
                val start = firstSlot + i * SLOT_MS
                ContentValues().apply {
                    put(Programs.COLUMN_CHANNEL_ID, channelId)
                    put(Programs.COLUMN_TITLE, "${def.name}: programme ${hhmm.format(java.util.Date(start))}")
                    put(Programs.COLUMN_SHORT_DESCRIPTION, "Test programme no. ${i + 1} on ${def.name}.")
                    put(Programs.COLUMN_START_TIME_UTC_MILLIS, start)
                    put(Programs.COLUMN_END_TIME_UTC_MILLIS, start + SLOT_MS)
                    put(Programs.COLUMN_CANONICAL_GENRE, Programs.Genres.encode(if (i % 3 == 0) Programs.Genres.NEWS else Programs.Genres.MOVIES))
                }
            }
            resolver.bulkInsert(Programs.CONTENT_URI, programmes.toTypedArray())
        }
        Log.i(TAG, "saved ${ALL.size} test channels for $inputId")
        return ALL.size
    }

    /** Channel definition by TvContract channel ID (matched by its stored number). */
    fun byChannelId(context: Context, channelId: Long): Def? =
        context.contentResolver.query(TvContract.buildChannelUri(channelId), arrayOf(Channels.COLUMN_DISPLAY_NUMBER), null, null, null)
            ?.use { c -> if (c.moveToFirst()) ALL.firstOrNull { it.number == c.getString(0) } else null }
}
