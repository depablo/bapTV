package com.bonsaponsi.baptv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.integration.android.IntentIntegrator
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.URLEncoder

/**
 * The phone's remote control screen: finds the bapTV boxes on the Wi-Fi network, lists them with the ones paired
 * before, and pairs a new one with the 6-digit code shown on the box. A paired box opens [PilotActivity]. Only
 * paired boxes are stored; a long press forgets one.
 */
class RemoteActivity : Activity() {

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguage.wrap(base))

    private val io = BackgroundWorker("remote-pair")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvs: PhoneRemoteTvs
    private lateinit var list: ListView
    private lateinit var hint: TextView
    private lateinit var nsd: NsdManager

    /** Boxes seen on the network in this session, by box ID. */
    private val online = LinkedHashMap<String, PhoneRemoteTvs.Tv>()
    /** Services found but not resolved yet – NSD resolves one at a time. */
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovering = false
    /** What the list shows: boxes seen now, then the stored ones not seen ([shown]). */
    private var items: List<PhoneRemoteTvs.Tv> = emptyList()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            DebugLog.warn("RemoteActivity", "discovery failed: error $errorCode")
            handler.post { hint.setText(R.string.remote_discovery_failed) }
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceType.startsWith(RemoteAdvertiser.SERVICE_TYPE.removeSuffix("."))) {
                handler.post { toResolve.addLast(serviceInfo); resolveNext() }
            }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            handler.post {
                online.values.removeAll { it.name == serviceInfo.serviceName }
                applyList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        list = findViewById(R.id.remote_list)
        hint = findViewById(R.id.remote_hint)
        tvs = PhoneRemoteTvs(this)
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        list.adapter = Adapter()
        list.emptyView = findViewById(R.id.remote_empty)
        list.setOnItemClickListener { _, _, position, _ -> open(items[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> confirmForget(items[position]) }
        findViewById<Button>(R.id.remote_scan).setOnClickListener { startScan() }
        applyList()
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        stopDiscovery()
        // Boxes switched off in the meantime must not stay "on the network" when the screen comes back.
        online.clear()
        toResolve.clear()
        applyList()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        runCatching { nsd.discoverServices(RemoteAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure {
                discovering = false
                DebugLog.warn("RemoteActivity", "cannot start discovery", it)
                hint.setText(R.string.remote_discovery_failed)
            }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
    }

    /** Resolves the next found service (address and box ID), then the one after it. */
    private fun resolveNext() {
        if (resolving) return
        val next = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLog.warn("RemoteActivity", "resolve failed: error $errorCode")
                handler.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
                val host = ipv4(serviceInfo)
                handler.post {
                    resolving = false
                    if (id.isNotEmpty() && host != null && discovering) {
                        // Not stored: anyone on the network can announce a box ID. A paired box is stored again
                        // under its new address only after it proves it knows the token ([PilotActivity]).
                        online[id] = PhoneRemoteTvs.Tv(id, serviceInfo.serviceName, host, serviceInfo.port, tvs.get(id)?.token)
                        applyList()
                    }
                    resolveNext()
                }
            }
        })
    }

    /** The box's IPv4 address – the box's server accepts only those in Host, and a bare IPv6 breaks the URL. */
    private fun ipv4(info: NsdServiceInfo): String? {
        val addresses = if (android.os.Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(hostOf(info))
        return addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo) = info.host

    /** Boxes seen now, then the paired ones that are not on the network at the moment. */
    private fun shown(): List<PhoneRemoteTvs.Tv> {
        val live = online.values.toList()
        val offline = tvs.all().filter { tv -> online.containsKey(tv.id).not() }
        return live + offline
    }

    private fun applyList() {
        items = shown()
        hint.setText(if (online.isEmpty()) R.string.remote_searching else R.string.remote_pick)
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun confirmForget(tv: PhoneRemoteTvs.Tv): Boolean {
        if (tvs.get(tv.id) == null) return false
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_forget_title, tv.name))
            .setPositiveButton(R.string.remote_forget) { _, _ -> tvs.forget(tv.id); applyList() }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
        return true
    }

    private fun open(tv: PhoneRemoteTvs.Tv) {
        if (tv.token != null && tv.host != null) {
            startActivity(PilotActivity.intent(this, tv))
            return
        }
        if (tv.host == null) {
            Toast.makeText(this, R.string.remote_not_found, Toast.LENGTH_LONG).show()
            return
        }
        askForCode(tv)
    }

    private fun askForCode(tv: PhoneRemoteTvs.Tv) {
        val input = EditText(this).apply {
            hint = getString(R.string.remote_code_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remote_code_title, tv.name))
            .setMessage(R.string.remote_code_message)
            .setView(input)
            .setPositiveButton(R.string.remote_pair) { _, _ -> pair(tv, input.text.toString().trim()) }
            .setNeutralButton(R.string.remote_scan) { _, _ -> startScan() }
            .setNegativeButton(R.string.phone_cancel, null)
            .show()
    }

    /** Opens the camera to read the pairing QR code on the TV; the code is not typed. */
    private fun startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.remote_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startScan()
        } else {
            Toast.makeText(this, R.string.remote_camera_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scan = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scan == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        scan.contents?.let { scanned(it) }
    }

    /** A code read from the TV's QR: pairs with the box at the address it names, when that box is on the network now. */
    private fun scanned(text: String) {
        val link = parsePairingLink(text)
        if (link == null) {
            Toast.makeText(this, R.string.remote_scan_bad, Toast.LENGTH_LONG).show()
            return
        }
        val tv = online.values.firstOrNull { it.host == link.host && it.port == link.port }
        if (tv == null) {
            Toast.makeText(this, R.string.remote_not_found, Toast.LENGTH_LONG).show()
            return
        }
        pair(tv, link.code)
    }

    private fun pair(tv: PhoneRemoteTvs.Tv, code: String) {
        val host = tv.host ?: return
        io.execute {
            val token = try {
                requestToken(host, tv.port, code)
            } catch (e: java.io.IOException) {
                DebugLog.warn("RemoteActivity", "pairing request failed", e)
                null
            }
            handler.post {
                if (isDestroyed) return@post
                if (token == null) {
                    Toast.makeText(this, R.string.remote_pair_failed, Toast.LENGTH_LONG).show()
                } else {
                    tvs.remember(tv.id, tv.name, host, tv.port, token)
                    applyList()
                    startActivity(PilotActivity.intent(this, PhoneRemoteTvs.Tv(tv.id, tv.name, host, tv.port, token)))
                }
            }
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_phone_channel, parent, false)
            val tv = items[position]
            val isOnline = online.containsKey(tv.id)
            view.findViewById<TextView>(R.id.phone_item_name).text = tv.name
            view.findViewById<TextView>(R.id.phone_item_group).text = listOfNotNull(
                getString(if (isOnline) R.string.remote_online else R.string.remote_offline),
                tv.host,
                getString(if (tv.token != null) R.string.remote_paired else R.string.remote_not_paired),
            ).joinToString(" · ")
            view.findViewById<TextView>(R.id.phone_item_now).visibility = View.GONE
            return view
        }
    }

    /** A pairing address read from the TV's QR code: the box's address and the code shown next to it. */
    class PairingLink(val host: String, val port: Int, val code: String)

    companion object {
        private const val REQUEST_CAMERA = 2

        /** Reads `http://<host>:<port>/pair?code=<6 digits>` (what the TV's QR code holds); null for anything else. */
        fun parsePairingLink(text: String): PairingLink? {
            val uri = try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return null
            }
            if (uri.scheme != "http" || uri.path != "/pair" || uri.host == null) return null
            val code = uri.query?.split('&')?.firstOrNull { it.startsWith("code=") }?.removePrefix("code=") ?: return null
            if (!Regex("""\d{6}""").matches(code)) return null
            return PairingLink(uri.host, if (uri.port == -1) 80 else uri.port, code)
        }

        /**
         * Asks the box for a token with the code on its screen. The box answers 303 with a cookie when the code is
         * right, and without one when it is not. Returns the token, or null.
         */
        fun requestToken(host: String, port: Int, code: String): String? {
            val query = URLEncoder.encode(code, "UTF-8")
            val connection = URL("http://$host:$port/pair?code=$query").openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.responseCode
                val cookie = connection.getHeaderField("Set-Cookie") ?: return null
                return cookie.substringAfter('=').substringBefore(';').ifEmpty { null }
            } finally {
                connection.disconnect()
            }
        }
    }
}
