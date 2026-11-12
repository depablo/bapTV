package com.bonsaponsi.baptv

import android.content.Context

/**
 * Takes over another build's channel list only after a check. The first channel that this build recognises from its
 * own recording and that the other build also recorded decides: the same TSID and SID means the list belongs to the
 * same scan (the channel IDs come from one tuner database shared by all builds), anything else refuses it.
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
    fun onRecognised(context: Context, channelId: Long, tsid: Int?, sid: Int?) {
        val store = ChannelStore(context)
        val identities = store.pendingIdentities() ?: return
        val expected = (0 until identities.length()).map { identities.getJSONObject(it) }
            .firstOrNull { it.optLong("channelId", -1) == channelId } ?: return
        val sameTsid = expected.optInt("tsid", -1) == (tsid ?: -2)
        val sameSid = expected.optInt("sid", -1) == (sid ?: -2)
        if (sameTsid && sameSid) {
            store.clearPending()
            DebugLog.log("Shared", "candidate list accepted (channel $channelId matches)")
        } else {
            store.refusedListHash = store.discoveredJson()?.hashCode()
            store.clearDiscovered()
            store.clearPending()
            DebugLog.log("Shared", "candidate list refused: channel $channelId is tsid=$tsid sid=$sid, other build has ${expected.optInt("tsid")}/${expected.optInt("sid")}")
        }
    }
}
