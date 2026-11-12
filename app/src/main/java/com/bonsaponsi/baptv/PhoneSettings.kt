package com.bonsaponsi.baptv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup of the phone app's channel settings: the internet channels with their order, names and hidden flags, as one
 * JSON file the user keeps wherever they like (Storage Access Framework). Nothing from the TV set is in it.
 */
object PhoneSettings {

    const val FORMAT = "baptv-phone-settings"
    const val VERSION = 1

    fun export(store: PhoneIptvStore): String = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("channels", JSONArray().apply { store.load().forEach { put(it.toJson()) } })
        .toString(2)

    /**
     * Restores a backup: [replace] takes the file's list as it is, otherwise only the channels whose addresses are
     * not on the list yet are added. Returns the number of channels now added (or in the list, when replacing).
     * Throws [IllegalArgumentException] when the text is not a backup of this app.
     */
    fun import(store: PhoneIptvStore, text: String, replace: Boolean): Int {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("not JSON", it) }
        require(root.optString("format") == FORMAT) { "not a bapTV settings file" }
        require(root.optInt("version", -1) in 1..VERSION) { "settings file from another version" }
        val array = root.optJSONArray("channels") ?: throw IllegalArgumentException("no channels in the file")
        val channels = (0 until array.length()).mapNotNull { i ->
            runCatching { PhoneIptvStore.Channel.fromJson(array.getJSONObject(i)) }.getOrNull()
        }.filter { it.url.isNotBlank() }
        return store.importChannels(channels, replace)
    }
}
