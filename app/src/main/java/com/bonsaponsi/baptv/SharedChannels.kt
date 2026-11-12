package com.bonsaponsi.baptv

import android.content.Context
import android.net.Uri

/**
 * Reads the discovered-channel lists of the other release builds on this set-top box ([ChannelsProvider]).
 * Stage 1: only reads and logs them. Nothing is taken over yet – a list from another build needs a check first.
 */
object SharedChannels {

    /** Release builds with the release signature; a debug build is signed differently and gets SecurityException. */
    val RELEASE_PACKAGES = listOf("com.bonsaponsi.baptv", "com.bonsaponsi.baptv.playtest")

    /** The discovered JSON of each other installed release build that grants access; empty when none does. */
    fun peerDiscovered(context: Context): Map<String, String> =
        RELEASE_PACKAGES.filter { it != context.packageName }.mapNotNull { pkg ->
            runCatching {
                val uri = Uri.parse("content://$pkg.channels${ChannelsProvider.PATH_DISCOVERED}")
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) pkg to c.getString(0) else null
                }
            }.onFailure {
                DebugLog.log("Shared", "$pkg: not readable (${it.javaClass.simpleName})")
            }.getOrNull()
        }.toMap()
}
