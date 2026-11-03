package com.bonsaponsi.baptv

import android.content.Context
import org.json.JSONObject

/**
 * Set-top boxes the phone has paired with, by their box ID ([RemoteAdvertiser]). The device token is kept here,
 * not only in the browser's cookie: the cookie belongs to one address, and the box may get a new one from the
 * router. The address last seen is kept too, for when the network does not announce the box.
 */
class PhoneRemoteTvs(context: Context) {

    private val prefs = context.getSharedPreferences("remote_tvs", Context.MODE_PRIVATE)

    class Tv(val id: String, val name: String, val host: String?, val port: Int, val token: String?)

    @Synchronized
    fun all(): List<Tv> {
        val json = runCatching { JSONObject(prefs.getString(KEY_TVS, "{}").orEmpty()) }.getOrElse { return emptyList() }
        return json.keys().asSequence().mapNotNull { id ->
            json.optJSONObject(id)?.let { o ->
                Tv(id, o.optString("name"), o.optString("host").ifEmpty { null }, o.optInt("port", DEFAULT_PORT),
                    o.optString("token").ifEmpty { null })
            }
        }.toList()
    }

    @Synchronized
    fun get(id: String): Tv? = all().firstOrNull { it.id == id }

    /**
     * Keeps the name and the address of a paired box – after pairing, or after the box at a new address proved it
     * knows the token ([PilotActivity]). Addresses only announced on the network are not stored.
     */
    @Synchronized
    fun remember(id: String, name: String, host: String, port: Int, token: String? = get(id)?.token) {
        val json = load()
        json.put(id, JSONObject().put("name", name).put("host", host).put("port", port).put("token", token.orEmpty()))
        save(json)
    }

    /** The device was removed on the box ("forget all paired phones") – the token is no longer accepted. */
    @Synchronized
    fun forgetToken(id: String) {
        val tv = get(id) ?: return
        remember(id, tv.name, tv.host.orEmpty(), tv.port, token = null)
    }

    /** Removes a box from the list (long press in [RemoteActivity]). */
    @Synchronized
    fun forget(id: String) {
        val json = load()
        json.remove(id)
        save(json)
    }

    private fun load(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_TVS, "{}").orEmpty()) }.getOrElse { JSONObject() }

    private fun save(json: JSONObject) = prefs.edit().putString(KEY_TVS, json.toString()).apply()

    private companion object {
        const val KEY_TVS = "tvs"
        const val DEFAULT_PORT = 8090
    }
}
