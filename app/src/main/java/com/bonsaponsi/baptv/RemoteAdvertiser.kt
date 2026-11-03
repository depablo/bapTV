package com.bonsaponsi.baptv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.security.SecureRandom

/**
 * Announces the phone remote on the local network (DNS-SD `_baptv._tcp`) while its server runs, so the phone
 * app finds the set-top box without typing its address. The service carries a stable box ID: a phone app keeps
 * its pairing under that ID, so a new IP address from the router does not break it.
 */
object RemoteAdvertiser {
    const val SERVICE_TYPE = "_baptv._tcp."
    private const val PREFS = "remote_advertiser"
    private const val KEY_ID = "box_id"

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    /** The box ID: random once, then kept. Hex, 16 characters. */
    fun boxId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    /** Registers the service on [port]. A failure is logged only – the remote works by address as before. */
    @Synchronized
    fun start(context: Context, port: Int) {
        if (listener != null) return
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val id = boxId(context)
        val info = NsdServiceInfo().apply {
            serviceName = "bapTV-${id.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", id)
            setAttribute("ver", BuildConfig.VERSION_NAME.substringBefore('-'))
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                DebugLog.log("Remote", "announced as ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLog.warn("Remote", "announce failed: error $errorCode")
                synchronized(this@RemoteAdvertiser) { if (listener === this) { listener = null; manager = null } }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLog.warn("Remote", "unannounce failed: error $errorCode")
            }
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            manager = nsd
            listener = registration
        } catch (e: RuntimeException) {
            DebugLog.warn("Remote", "cannot announce the remote", e)
        }
    }

    @Synchronized
    fun stop() {
        val nsd = manager ?: return
        val registration = listener
        manager = null
        listener = null
        if (registration != null) runCatching { nsd.unregisterService(registration) }
    }
}
