package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Takes over another build's channel list only after a check. Channels that this build recognises from its own
 * recording are compared with what the other build recorded for them: the same ONID, TSID and SID means the list
 * belongs to the same scan (the channel IDs come from one tuner database shared by all builds). Any mismatch refuses
 * the list. It is accepted when the matching channels cover [REQUIRED] multiplexes (or all of the list's, if fewer).
 * Accepted: the other build's recognition data (names, numbers, HbbTV marks) is stored for the channels that do not
 * have their own yet, so the channels show as recognised at once.
 */
object SharedChannelCheck {

    /** Multiplexes that must match before a candidate list is taken over. */
    const val REQUIRED = 3

    /** Takes a candidate list from another build when this build has no list of its own. Main or worker thread. */
    fun adoptIfNeeded(context: Context, store: ChannelStore) {
        if (store.loadDiscovered().isNotEmpty() || store.pendingIdentities() != null) return
        val peer = SharedChannels.peers(context).firstOrNull { it.discovered.length > 2 } ?: return
        if (store.refusedListHash == peer.discovered.hashCode()) return
        store.saveDiscoveredJson(peer.discovered)
        store.setPending(peer.pkg, peer.identities)
        DebugLog.log("Shared", "candidate list from ${peer.pkg} – waiting for a check")
    }

    /** Called when a channel was recognised from its recording. */
    fun onRecognised(context: Context, channelId: Long, onid: Int?, tsid: Int?, sid: Int?) {
        val store = ChannelStore(context)
        val identities = store.pendingIdentities() ?: return
        val expected = (0 until identities.length()).map { identities.getJSONObject(it) }
            .firstOrNull { it.optLong("channelId", -1) == channelId } ?: return
        val same = sameOrUnknown(expected, "onid", onid) && sameOrUnknown(expected, "tsid", tsid) &&
            sameOrUnknown(expected, "sid", sid) && expected.optInt("tsid", -1) >= 0
        if (same) {
            val confirmed = (store.pendingConfirmed() + channelId).distinct()
            store.setPendingConfirmed(confirmed)
            val needed = requiredMultiplexes(store)
            val covered = confirmed.map { multiplexOf(store, it) }.distinct().size
            // Several channels must match as well: one matching channel says little about the whole list.
            val neededChannels = minOf(REQUIRED, candidates(store).size).coerceAtLeast(1)
            if (covered < needed || confirmed.size < neededChannels) {
                DebugLog.log("Shared", "channel $channelId matches; $covered of $needed multiplexes, ${confirmed.size} of $neededChannels channels confirmed")
                return
            }
            store.clearPending()
            val hbb = HbbTvStore(context)
            var taken = 0
            // Only the channels of the candidate list: the other build may hold data for channels it no longer has.
            val listed = candidates(store).map { it.first }.toSet()
            (0 until identities.length()).map { identities.getJSONObject(it) }.forEach { o ->
                val entry = runCatching { HbbTvStore.Entry.fromJson(o) }.getOrNull() ?: return@forEach
                if (entry.channelId in listed && hbb.get(entry.channelId) == null) { hbb.put(entry); taken++ }
            }
            DebugLog.log("Shared", "candidate list accepted ($covered multiplexes match); recognition taken for $taken channels")
        } else {
            store.refusedListHash = store.discoveredJson()?.hashCode()
            store.clearDiscovered()
            store.clearPending()
            DebugLog.log("Shared", "candidate list refused: channel $channelId is onid=$onid tsid=$tsid sid=$sid, other build has ${expected.optInt("onid", -1)}/${expected.optInt("tsid", -1)}/${expected.optInt("sid", -1)}")
        }
    }

    /** Multiplexes to confirm: [REQUIRED], or fewer when the list has fewer. */
    private fun requiredMultiplexes(store: ChannelStore): Int {
        val all = candidates(store).map { multiplexOf(store, it.first) }.distinct().size
        return minOf(REQUIRED, all).coerceAtLeast(1)
    }

    /** Frequency of a candidate channel (its multiplex); the channel itself when the list has no frequency for it. */
    private fun multiplexOf(store: ChannelStore, channelId: Long): String =
        candidates(store).firstOrNull { it.first == channelId }?.second?.let { "f$it" } ?: "c$channelId"

    /** (channel ID, frequency or null) of the candidate list. */
    private fun candidates(store: ChannelStore): List<Pair<Long, Double?>> {
        val list = store.discoveredJson() ?: return emptyList()
        val array = runCatching { JSONArray(list) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            o.getLong("id") to (if (o.has("freq")) o.getDouble("freq") else null)
        }
    }

    /** A field the other build did not record cannot be compared; a recorded one must be equal. */
    private fun sameOrUnknown(expected: JSONObject, key: String, actual: Int?): Boolean {
        if (expected.isNull(key) || !expected.has(key)) return true
        return expected.optInt(key, -1) == (actual ?: -2)
    }
}
