package com.bonsaponsi.baptv

import android.content.Context
import android.net.Uri

/**
 * Reads the discovered-channel lists of the other release builds on this set-top box ([ChannelsProvider]).
 * A list taken over is only a candidate until [SharedChannelCheck] accepts it.
 */
object SharedChannels {

    /** Release builds with the release signature; a debug build is signed differently and gets SecurityException. */
    val RELEASE_PACKAGES = listOf("com.bonsaponsi.baptv", "com.bonsaponsi.baptv.playtest")

    /** One other build: its discovered list and the identities it recorded. */
    class Peer(val pkg: String, val discovered: String, val identities: String)

    /** The other installed release builds that grant access. */
    fun peers(context: Context): List<Peer> =
        RELEASE_PACKAGES.filter { it != context.packageName }.mapNotNull { pkg ->
            runCatching {
                val uri = Uri.parse("content://$pkg.channels${ChannelsProvider.PATH_DISCOVERED}")
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) Peer(pkg, c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null
                }
            }.onFailure {
                DebugLog.log("Shared", "$pkg: not readable (${it.javaClass.simpleName})")
            }.getOrNull()
        }
}
