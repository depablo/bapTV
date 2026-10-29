package com.bonsaponsi.baptv

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.net.Uri
import org.json.JSONObject

/**
 * What the device running the app can do. The basics (playback via TIF, channel list,
 * info panel, TV guide from TvContract) work with any tuner input; the rest depends on the
 * Google DTV "CBS" stack (dtvinput + dtvprovider) or on installed apps. Unavailable features
 * are hidden from the menu instead of reporting errors.
 */
data class DeviceCapabilities(
    /** Tuner input (dtvinput or the first TUNER-type input); null – device without a tuner. */
    val inputId: String?,
    val inputLabel: String?,
    /** The input is dtvinput with the installer service – in-app scan and single-frequency check. */
    val cbs: Boolean,
    /** dtvprovider responds – signal (SNR/BER/RSSI), tuner state, LNA setting. */
    val tunerStats: Boolean,
    /** The input has a channel setup wizard (Activity from createSetupIntent). */
    val systemSetup: Boolean,
    /** The input's channels are visible in TvContract (otherwise discovery by probing IDs – CBS only). */
    val channelsVisible: Boolean,
    /** A service recording can be read as a TS file – channel identification, EPG from EIT and HbbTV from AIT. */
    val streamAnalysis: Boolean,
) {
    /** Channel discovery by probing IDs – a workaround for `searchable=0` in dtvinput. */
    val channelDiscovery get() = cbs && !channelsVisible
    val inAppScan get() = cbs
    val signal get() = tunerStats
    val hbbtv get() = streamAnalysis

    fun toJson(): JSONObject = JSONObject()
        .put("inputId", inputId).put("inputLabel", inputLabel)
        .put("cbs", cbs).put("tunerStats", tunerStats).put("systemSetup", systemSetup)
        .put("channelsVisible", channelsVisible).put("streamAnalysis", streamAnalysis)
        .put("country", Region.country()).put("scanPlan", Region.plan().name)
        .put("features", JSONObject()
            .put("inAppScan", inAppScan).put("channelDiscovery", channelDiscovery)
            .put("signal", signal).put("hbbtv", hbbtv))

    /** Lines for the diagnostics report. */
    fun describe(): List<String> = listOf(
        "Tuner input: ${inputId ?: "none"}" + (inputLabel?.let { " ($it)" } ?: ""),
        "Google DTV stack (dtvinput, in-app scan): ${yes(cbs)}",
        "Tuner statistics (dtvprovider – signal, LNA): ${yes(tunerStats)}",
        "Input channel setup wizard: ${yes(systemSetup)}",
        "Channels visible in TvContract: ${yes(channelsVisible)}" + if (channelDiscovery) " – discovery by probing" else "",
        "Stream analysis from recordings (names, EPG from EIT, HbbTV): ${yes(streamAnalysis)}",
        "Country (from system settings): ${Region.country().ifEmpty { "unknown" }}, scan plan: ${Region.plan().name}",
    )

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    companion object {
        @Volatile private var cached: DeviceCapabilities? = null

        /** Cached result; [refresh] after a scan or package installation. */
        fun get(context: Context): DeviceCapabilities = cached ?: refresh(context)

        @Synchronized
        fun refresh(context: Context): DeviceCapabilities {
            val app = context.applicationContext
            val input = Dtv.findTunerInput(app)
            val cbs = input?.serviceInfo?.packageName == Dtv.DTV_INPUT_PACKAGE &&
                app.packageManager.resolveService(Intent().setComponent(Dtv.INSTALLER_SERVICE), 0) != null
            val caps = DeviceCapabilities(
                inputId = input?.id,
                inputLabel = input?.let { runCatching { it.loadLabel(app).toString() }.getOrNull() },
                cbs = cbs,
                tunerStats = providerResponds(app),
                systemSetup = input?.let { hasSetup(app, it) } ?: false,
                channelsVisible = input?.let { countChannels(app, it.id) > 0 } ?: false,
                // Reading the recording file is verified only with dtvinput (directory Android/media/<package>/Records);
                // switched off when this dtvinput version turned out to refuse recording.
                streamAnalysis = cbs && !TsProbe.unsupported(app),
            )
            if (caps != cached) DebugLog.log("Caps", caps.describe().joinToString("; "))
            cached = caps
            return caps
        }

        private fun providerResponds(context: Context): Boolean = runCatching {
            context.contentResolver.query(Uri.parse(Dtv.STREAMERS_URI), null, null, null, null)?.use { true } ?: false
        }.getOrDefault(false)

        private fun hasSetup(context: Context, input: TvInputInfo): Boolean = runCatching {
            input.createSetupIntent()?.let { context.packageManager.resolveActivity(it, 0) != null } ?: false
        }.getOrDefault(false)

        private fun countChannels(context: Context, inputId: String): Int = runCatching {
            context.contentResolver.query(TvContract.buildChannelsUriForInput(inputId), arrayOf(TvContract.Channels._ID), null, null, null)
                ?.use { it.count } ?: 0
        }.getOrDefault(0)
    }
}
