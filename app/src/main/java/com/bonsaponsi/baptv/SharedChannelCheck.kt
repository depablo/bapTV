package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONObject

/**
 * Takes over another build's channel list only after a check. The first channel that this build recognises from its
 * own recording and that the other build also recorded decides: the same ONID, TSID and SID means the list belongs
 * to the same scan (the channel IDs come from one tuner database shared by all builds), anything else refuses it.
 * Accepted: the other build's recognition data (names, numbers, HbbTV marks) is stored for the channels that do not
 * have their own yet, so the channels show as recognised at once.
 */
object SharedChannelCheck {

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
            store.clearPending()
            val hbb = HbbTvStore(context)
            var taken = 0
            (0 until identities.length()).map { identities.getJSONObject(it) }.forEach { o ->
                val entry = runCatching { HbbTvStore.Entry.fromJson(o) }.getOrNull() ?: return@forEach
                if (hbb.get(entry.channelId) == null) { hbb.put(entry); taken++ }
            }
            DebugLog.log("Shared", "candidate list accepted (channel $channelId matches); recognition taken for $taken channels")
        } else {
            store.refusedListHash = store.discoveredJson()?.hashCode()
            store.clearDiscovered()
            store.clearPending()
            DebugLog.log("Shared", "candidate list refused: channel $channelId is onid=$onid tsid=$tsid sid=$sid, other build has ${expected.optInt("onid", -1)}/${expected.optInt("tsid", -1)}/${expected.optInt("sid", -1)}")
        }
    }

    /** A field the other build did not record cannot be compared; a recorded one must be equal. */
    private fun sameOrUnknown(expected: JSONObject, key: String, actual: Int?): Boolean {
        if (expected.isNull(key) || !expected.has(key)) return true
        return expected.optInt(key, -1) == (actual ?: -2)
    }
}
