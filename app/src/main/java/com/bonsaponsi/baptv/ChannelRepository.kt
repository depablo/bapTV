package com.bonsaponsi.baptv

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvContract.Channels
import android.media.tv.TvContract.Programs
import org.json.JSONObject

data class Channel(
    val id: Long,
    val number: String,
    val name: String,
    val isRadio: Boolean,
    val videoFormat: String?,
    /** Channel discovered by probing IDs (not visible in TvContract). */
    val discovered: Boolean = false,
    /** Scrambled channel – dtvinput refuses playback with a CAS reason. */
    val scrambled: Boolean = false,
    /** Multiplex frequency (MHz) read from the tuner during discovery. */
    val frequencyMhz: Double? = null,
    /** DVB identifiers from a stream recording ([TsProbe]); null until the channel is identified. */
    val onid: Int? = null,
    val tsid: Int? = null,
    val sid: Int? = null,
    /** "HbbTV-only" channel – a still card over the air, video from the internet ([ServiceCatalog.Service.virtual]). */
    val virtual: Boolean = false,
    /** Hidden by the user (manually or via "hide scrambled"); visible only in channel management. */
    val hidden: Boolean = false,
) {
    val identified get() = sid != null

    val uri get() = TvContract.buildChannelUri(id)
}


class ChannelRepository(private val context: Context) {

    private val store = ChannelStore(context)
    private val services = HbbTvStore(context)
    private val epg = EpgStore.get(context)

    /**
     * Channels for playback: from TvContract, or when the input doesn't expose them (searchable=0) –
     * the list discovered by probing. User-assigned names take precedence.
     */
    fun loadChannels(inputId: String, includeHidden: Boolean = false): List<Channel> {
        val fromProvider = loadFromProvider(inputId)
        val channels = if (fromProvider.isNotEmpty()) {
            fromProvider
        } else {
            store.loadDiscovered().also { DebugLog.log("Channels", "TvContract empty – using ${it.size} discovered channels") }
        }
        // Channel settings from before a rescan (old IDs) move to DVB keys.
        services.all().forEach { e -> store.migrate(e.channelId, e.tsid, e.sid) }
        if (fromProvider.isNotEmpty()) services.carryOver(fromProvider)
        return organise(dropDuplicates(identify(channels)), includeHidden)
    }

    /**
     * User-defined order and hiding, then sequential numbering (if selected).
     * Channels missing from the saved order (e.g. new after a scan) go to the end.
     */
    private fun organise(channels: List<Channel>, includeHidden: Boolean): List<Channel> {
        val order = store.customOrder()
        val ordered = if (order.isEmpty()) channels else {
            val position = order.withIndex().associate { (i, key) -> key to i }
            channels.withIndex().sortedWith(compareBy({ position[store.settingsKey(it.value)] ?: (order.size + it.index) })).map { it.value }
        }
        val hideScrambled = store.hideScrambled
        var result = ordered.map { ch -> ch.copy(hidden = store.isHidden(ch) || (hideScrambled && ch.scrambled)) }
        if (!includeHidden) result = result.filter { !it.hidden }
        if (!lcnNumbering(context)) {
            // Sequential numbering: TV from 1, radio after it, in list order.
            val (tv, radio) = result.partition { !it.isRadio }
            result = (tv + radio).mapIndexed { i, ch -> ch.copy(number = (i + 1).toString()) }
        }
        return result
    }

    /**
     * Names, LCN numbers and channel type. Channels from TvContract (other devices) keep the name and number
     * assigned by the tuner input; [ServiceCatalog] only fills in gaps. Channels discovered by probing
     * (dtvinput) take name and LCN from the catalog by the DVB identifiers from the recording.
     * Order as on a TV: numbered channels by number, then unnumbered ones in their existing order,
     * numbered after the highest. A user-assigned name takes precedence.
     */
    private fun identify(channels: List<Channel>): List<Channel> {
        val decorated = channels.map { ch ->
            val info = services.get(ch.id)
            val onid = info?.onid ?: ch.onid
            val sid = info?.sid ?: ch.sid
            val service = ServiceCatalog.lookup(onid, sid)
            val fromInput = !ch.discovered
            ch.copy(
                name = ch.name.takeIf { fromInput && it.isNotBlank() } ?: service?.name ?: ch.name,
                number = ch.number.takeIf { fromInput && it.isNotBlank() } ?: service?.lcn?.toString() ?: ch.number,
                isRadio = info?.let { !it.hasVideo } ?: ch.isRadio,
                scrambled = info?.scrambled ?: ch.scrambled,
                onid = onid, tsid = info?.tsid ?: ch.tsid, sid = sid,
                virtual = service?.virtual == true || info?.looksVirtual == true,
            ) to (service != null || (fromInput && ch.number.isNotBlank()))
        }
        val known = decorated.filter { it.second }.map { it.first }.sortedWith(compareBy({ it.isRadio }, { numberKey(it.number) }))
        // Numeric numbers only – a non-numeric one would give Long.MAX_VALUE and numbers around 10^14 for the rest.
        val maxLcn = known.filter { !it.isRadio }.map { numberKey(it.number) }.filter { it != Long.MAX_VALUE }.maxOfOrNull { it / 10_000 } ?: 0
        // When nothing is identified, the existing numbers stay.
        val unknown = decorated.filter { !it.second }.mapIndexed { i, (ch, _) ->
            if (known.isEmpty()) ch else ch.copy(number = (maxLcn + 1 + i).toString())
        }
        val result = (known + unknown).map { ch ->
            if (ch.identified) store.migrate(ch)
            store.customName(ch)?.let { ch.copy(name = it) } ?: ch
        }
        DebugLog.log("Channels", "identified ${known.size}/${channels.size}: " + known.joinToString { "${it.number}=${it.name}" })
        return result
    }

    /**
     * The same service (multiplex + service number) under several dtvinput IDs: one is kept,
     * the one with the highest ID (latest scan). Duplicates appear when channels added by a single-frequency
     * scan ("unmanaged_manual" mode, e.g. from the /signal page) survive a full scan, which
     * clears only "managed" channels and adds the same multiplex again.
     */
    private fun dropDuplicates(channels: List<Channel>): List<Channel> {
        val keep = channels.filter { it.identified }.groupBy { store.settingsKey(it) }
            .mapValues { (_, list) -> list.maxOf { it.id } }
        val dropped = channels.filter { it.identified && keep[store.settingsKey(it)] != it.id }
        if (dropped.isNotEmpty()) {
            DebugLog.log("Channels", "skipped duplicate services: " + dropped.joinToString { "#${it.id} ${it.name} (keeping #${keep[store.settingsKey(it)]})" })
        }
        return channels - dropped.toSet()
    }

    private val providerProgrammes = HashMap<Long, Pair<Long, List<Programme>>>()

    /**
     * Channel TV guide: from recording EIT (dtvinput), or when missing – from TvContract.Programs, filled by
     * the tuner input on other devices. Empty list when there is no data.
     */
    /** @param cachedOnly no provider queries (main thread: channel list, recording start) – only what is already in memory. */
    fun programmes(channel: Channel, cachedOnly: Boolean = false): List<Programme> {
        val fromEit = epg.programmes(channel.onid, channel.tsid, channel.sid)
        if (fromEit.isNotEmpty() || channel.discovered) return fromEit
        val now = System.currentTimeMillis()
        synchronized(providerProgrammes) {
            val cached = providerProgrammes[channel.id]
            if (cachedOnly) return cached?.second.orEmpty()
            cached?.takeIf { now - it.first < PROVIDER_EPG_TTL_MS }?.let { return it.second }
        }
        val list = loadProgrammesFromProvider(channel.id, now - EpgSchedule.KEEP_PAST_MS, now + 8 * 24 * 3600_000L)
        synchronized(providerProgrammes) { providerProgrammes[channel.id] = now to list }
        return list
    }

    private fun loadProgrammesFromProvider(channelId: Long, fromMs: Long, toMs: Long): List<Programme> {
        val projection = arrayOf(
            Programs.COLUMN_TITLE, Programs.COLUMN_SHORT_DESCRIPTION, Programs.COLUMN_LONG_DESCRIPTION,
            Programs.COLUMN_START_TIME_UTC_MILLIS, Programs.COLUMN_END_TIME_UTC_MILLIS, Programs.COLUMN_CANONICAL_GENRE,
        )
        val result = ArrayList<Programme>()
        try {
            context.contentResolver.query(TvContract.buildProgramsUriForChannel(channelId, fromMs, toMs), projection, null, null,
                Programs.COLUMN_START_TIME_UTC_MILLIS)?.use { c ->
                while (c.moveToNext()) {
                    val description = c.getStringOrNull(2)?.takeIf { it.isNotBlank() } ?: c.getStringOrNull(1).orEmpty()
                    result += Programme(c.getStringOrNull(0).orEmpty(), description, c.getLong(3), c.getLong(4),
                        genreFromCanonical(c.getStringOrNull(5)))
                }
            }
        } catch (e: Exception) {
            DebugLog.warn("Channels", "failed to read TV guide of channel $channelId from TvContract", e)
        }
        return result
    }

    /** Channels of the given input, without scrambled and hidden ones, sorted by LCN number. */
    private fun loadFromProvider(inputId: String): List<Channel> {
        val projection = arrayOf(
            Channels._ID,
            Channels.COLUMN_DISPLAY_NUMBER,
            Channels.COLUMN_DISPLAY_NAME,
            Channels.COLUMN_SERVICE_TYPE,
            Channels.COLUMN_BROWSABLE,
            Channels.COLUMN_VIDEO_FORMAT,
            Channels.COLUMN_INTERNAL_PROVIDER_FLAG1,
            Channels.COLUMN_INTERNAL_PROVIDER_DATA,
            Channels.COLUMN_ORIGINAL_NETWORK_ID,
            Channels.COLUMN_TRANSPORT_STREAM_ID,
            Channels.COLUMN_SERVICE_ID,
        )
        val result = ArrayList<Channel>()
        var total = 0
        var skippedType = 0
        var skippedHidden = 0
        var skippedScrambled = 0
        try {
            context.contentResolver.query(TvContract.buildChannelsUriForInput(inputId), projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    total++
                    val serviceType = c.getStringOrNull(3)
                    if (serviceType != Channels.SERVICE_TYPE_AUDIO_VIDEO && serviceType != Channels.SERVICE_TYPE_AUDIO) { skippedType++; continue }
                    // Channel hidden by the user in system settings.
                    if (!c.isNull(4) && c.getInt(4) == 0) { skippedHidden++; continue }
                    // Scrambled channels stay on the list (marked) – the "Scrambled: hidden" toggle hides them.
                    val scrambled = isScrambled(c)
                    if (scrambled) skippedScrambled++
                    result += Channel(
                        id = c.getLong(0),
                        number = c.getStringOrNull(1).orEmpty(),
                        name = c.getStringOrNull(2).orEmpty(),
                        isRadio = serviceType == Channels.SERVICE_TYPE_AUDIO,
                        videoFormat = c.getStringOrNull(5),
                        scrambled = scrambled,
                        // 0 or missing = the input doesn't provide DVB identifiers.
                        onid = c.positiveIntOrNull(8), tsid = c.positiveIntOrNull(9), sid = c.positiveIntOrNull(10),
                    )
                }
            }
        } catch (e: Exception) {
            DebugLog.error("Channels", "no access to channels of input $inputId", e)
        }
        DebugLog.log(
            "Channels",
            "$inputId: in database $total, skipped: service type $skippedType, hidden $skippedHidden; scrambled $skippedScrambled, playable ${result.size}"
        )
        return result.sortedWith(compareBy<Channel>({ it.isRadio }, { numberKey(it.number) }, { it.name }))
    }

    /** Current and next programme: EPG from recordings, or when missing – from TvContract (other inputs). */
    fun loadNowNext(channel: Channel, nowMs: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val fromEit = EpgSchedule.nowNext(programmes(channel), nowMs)
        if (fromEit.first != null || fromEit.second != null) return fromEit
        return loadNowNextFromProvider(channel.id, nowMs)
    }

    private fun loadNowNextFromProvider(channelId: Long, nowMs: Long): Pair<Programme?, Programme?> {
        val projection = arrayOf(Programs.COLUMN_TITLE, Programs.COLUMN_START_TIME_UTC_MILLIS, Programs.COLUMN_END_TIME_UTC_MILLIS)
        val uri = TvContract.buildProgramsUriForChannel(channelId, nowMs, nowMs + 6 * 3600_000L)
        val programs = ArrayList<Programme>(2)
        try {
            context.contentResolver.query(uri, projection, null, null, Programs.COLUMN_START_TIME_UTC_MILLIS)?.use { c ->
                while (c.moveToNext() && programs.size < 2) {
                    val program = Programme(c.getStringOrNull(0).orEmpty(), "", c.getLong(1), c.getLong(2))
                    if (program.endMs > nowMs) programs += program
                }
            }
        } catch (e: Exception) {
            DebugLog.warn("Channels", "failed to read EPG of channel $channelId", e)
        }
        return EpgSchedule.nowNext(programs, nowMs)
    }

    /**
     * Highest ID in the TvProvider channels table (without system-reserved columns, e.g. browsable). The _id column is AUTOINCREMENT, so
     * we insert and immediately delete our own row – channels from the last scan sit just below it.
     */
    fun findChannelIdCeiling(): Long? = try {
        val values = ContentValues().apply {
            put(Channels.COLUMN_INPUT_ID, "${context.packageName}/.Probe")
            put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER)
            put(Channels.COLUMN_DISPLAY_NAME, "probe")
        }
        val uri = context.contentResolver.insert(Channels.CONTENT_URI, values)
        if (uri == null) {
            DebugLog.warn("Channels", "inserting the temporary channel returned null")
            null
        } else {
            val id = ContentUris.parseId(uri)
            val deleted = context.contentResolver.delete(uri, null, null)
            DebugLog.log("Channels", "temporary channel: id=$id (deleted $deleted)")
            id
        }
    } catch (e: Exception) {
        DebugLog.error("Channels", "failed to determine the highest channel id", e)
        null
    }

    /** IDs of channels that have programmes in the EPG (Programs have searchable=1, so they are visible). */
    fun epgChannelIds(): Map<Long, String> {
        val now = System.currentTimeMillis()
        val result = HashMap<Long, String>()
        try {
            context.contentResolver.query(
                Programs.CONTENT_URI,
                arrayOf(Programs.COLUMN_CHANNEL_ID, Programs.COLUMN_TITLE, Programs.COLUMN_START_TIME_UTC_MILLIS, Programs.COLUMN_END_TIME_UTC_MILLIS),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val current = c.getLong(2) <= now && c.getLong(3) > now
                    if (current || id !in result) result[id] = c.getStringOrNull(1).orEmpty()
                }
            }
        } catch (e: Exception) {
            DebugLog.warn("Channels", "reading Programs failed", e)
        }
        DebugLog.log("Channels", "EPG: programmes for ${result.size} channels: ${result.keys.sorted()}")
        return result
    }

    /**
     * The CBS input marks scrambled channels in two ways: bit 3 in INTERNAL_PROVIDER_FLAG1
     * (some set-top boxes, newer firmware) or a "scrambled" field in the INTERNAL_PROVIDER_DATA JSON (older firmware).
     */
    private fun isScrambled(c: Cursor): Boolean {
        if (!c.isNull(6) && (c.getLong(6) shr 3) and 1L == 1L) return true
        if (c.isNull(7)) return false
        return try {
            JSONObject(String(c.getBlob(7), Charsets.UTF_8)).optBoolean("scrambled", false)
        } catch (e: Exception) {
            false
        }
    }

    private fun Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.positiveIntOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index).takeIf { it > 0 }

    companion object {
        private const val PREF_LCN = "numbering_lcn"
        private const val PROVIDER_EPG_TTL_MS = 5 * 60_000L
        /** Upper bound of the channel number in [numberKey] – a larger one (bad data) must not overflow the key. */
        private const val MAX_MAJOR = 1_000_000_000_000L

        /** TvContract genre (e.g. "MOVIES", several separated by commas) → general DVB category (`Context.genreLabel`). */
        fun genreFromCanonical(genres: String?): Int {
            val first = genres?.split(',')?.map { it.trim().trim('"') }?.firstOrNull { it.isNotEmpty() } ?: return -1
            return when (first) {
                "MOVIES" -> 1
                "NEWS" -> 2
                "ENTERTAINMENT", "COMEDY", "DRAMA", "PREMIER" -> 3
                "SPORTS" -> 4
                "FAMILY_KIDS", "ANIMAL_WILDLIFE" -> 5
                "MUSIC" -> 6
                "ARTS" -> 7
                "EDUCATION", "TECH_SCIENCE" -> 9
                "LIFE_STYLE", "TRAVEL", "SHOPPING", "GAMING" -> 10
                else -> -1
            }
        }

        /** Numbering as on a TV (LCN, with gaps) or sequential 1, 2, 3… */
        fun lcnNumbering(context: Context) = context.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean(PREF_LCN, true)

        fun setLcnNumbering(context: Context, lcn: Boolean) {
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean(PREF_LCN, lcn).apply()
            DebugLog.log("Channels", "numbering: " + if (lcn) "LCN" else "sequential")
        }

        /** Sort key for numbers like "12", "12.1", "12-1". */
        fun numberKey(number: String): Long {
            val parts = number.split('.', '-', ' ').mapNotNull { it.toLongOrNull() }
            val major = parts.getOrNull(0)?.takeIf { it in 0..MAX_MAJOR } ?: return Long.MAX_VALUE
            return major * 10_000 + (parts.getOrNull(1)?.coerceIn(0, 9_999) ?: 0)
        }
    }
}
