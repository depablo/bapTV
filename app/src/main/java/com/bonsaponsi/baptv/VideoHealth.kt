package com.bonsaponsi.baptv

import android.content.Context

/**
 * Remembers which broadcast channels showed a picture since the last channel search. A channel that worked before
 * and shows no picture now is a sign that the channel list is out of step with dtvinput (e.g. after test scans or
 * channel changes in another app), so the player suggests a new search. Cleared when a scan completes, because a
 * scan changes channel IDs and the remembered IDs would then point at other channels.
 */
object VideoHealth {

    private const val PREFS = "video_health"
    private const val KEY_WORKING = "working_ids"

    /** The remembered IDs with [channelId] added (a new set; the stored one is not changed). */
    fun withChannel(ids: Set<String>, channelId: Long): Set<String> = ids + channelId.toString()

    fun contains(ids: Set<String>, channelId: Long): Boolean = channelId.toString() in ids

    fun markWorking(context: Context, channelId: Long) {
        val ids = working(context)
        if (contains(ids, channelId)) return
        prefs(context).edit().putStringSet(KEY_WORKING, withChannel(ids, channelId)).apply()
    }

    fun worked(context: Context, channelId: Long): Boolean = contains(working(context), channelId)

    /** A new channel search makes the remembered IDs stale. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_WORKING).apply()
    }

    private fun working(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_WORKING, null).orEmpty()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
