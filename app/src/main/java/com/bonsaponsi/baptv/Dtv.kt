package com.bonsaponsi.baptv

import android.content.ComponentName
import android.content.Context
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager

/**
 * Constants of the Google DTV ("CBS") stack used on Android TV 11 set-top boxes:
 * the TIF input `com.google.android.tv.dtvinput` and the data provider `com.google.android.tv.dtvprovider`.
 */
object Dtv {
    const val DTV_INPUT_PACKAGE = "com.google.android.tv.dtvinput"
    const val DTV_PROVIDER_PACKAGE = "com.google.android.tv.dtvprovider.service"
    const val DTV_SETUP_DEMO_PACKAGE = "com.google.android.tv.dtvsetup.demo"

    /** Packages whose state goes into the diagnostics report. */
    val RELATED_PACKAGES = listOf(
        DTV_INPUT_PACKAGE,
        DTV_PROVIDER_PACKAGE,
        DTV_SETUP_DEMO_PACKAGE,
        "com.google.android.tv.teletextservice",
        "com.android.providers.tv",
        "com.droidlogic.tvinput",
        "com.droidlogic.android.tv",
    )

    /**
     * Channel installer service of the dtvinput input. It is exported without permissions: on start
     * it stores the extras ("params") and starts the scan by itself once the tuner is ready.
     */
    val INSTALLER_SERVICE = ComponentName(DTV_INPUT_PACKAGE, "com.google.android.tv.dtvinput.setup.InstallerService")

    const val ACTION_START_INSTALL = "com.google.android.tv.dtvscan.action.ACTION_START_INSTALL"
    const val ACTION_CANCEL_INSTALL = "com.google.android.tv.dtvscan.action.ACTION_CANCEL_INSTALL"
    const val ACTION_INSTALL_EVENT = "com.google.android.tv.dtvscan.INSTALL_EVENT"
    const val ACTION_UNINSTALL_EVENT = "com.google.android.tv.dtvscan.UNINSTALL_EVENT"
    const val ACTION_SETUP_READY = "com.google.android.tv.dtvinput.setup.READY"
    const val ACTION_START_WATCHING_UPDATE = "com.google.android.tv.dtvinput.setup.NOTIFY_START_WATCHING_SERVICE_UPDATE"
    const val ACTION_END_WATCHING_UPDATE = "com.google.android.tv.dtvinput.setup.NOTIFY_END_WATCHING_SERVICE_UPDATE"
    const val EXTRA_PARAMS = "params"
    const val EXTRA_PROGRESS = "progress"
    const val EXTRA_CHANNEL_COUNT = "nb_channels"
    const val EXTRA_STATUS = "status"

    const val PERMISSION_INSTALL_TV_CHANNELS = "com.google.android.tv.dtvinput.permission.INSTALL_TV_CHANNELS"
    const val PERMISSION_READ_EPG = "com.android.providers.tv.permission.READ_EPG_DATA"
    /**
     * Reading channels and TV guide data of other inputs. TvProvider in Android 11 (emulator) checks this permission
     * (dangerous type), not READ_EPG_DATA – without it the app sees only its own rows.
     */
    const val PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

    /** Channel list permission to request on this system (the newer one, if the system knows it). */
    fun listingsPermission(context: Context): String =
        if (runCatching { context.packageManager.getPermissionInfo(PERMISSION_READ_TV_LISTINGS, 0) }.isSuccess) PERMISSION_READ_TV_LISTINGS
        else PERMISSION_READ_EPG
    const val PERMISSION_DTV_PROVIDER_READ = "com.google.android.tv.dtvprovider.permission.READ"


    const val PROVIDER_AUTHORITY = "content://com.google.android.tv.dtvprovider"
    const val STREAMERS_URI = "$PROVIDER_AUTHORITY/streamers"
    const val TVSETTINGS_URI = "$PROVIDER_AUTHORITY/tvsettings"
    val PROVIDER_TABLES = listOf("streamers", "players", "tvprobes", "tvsettings")

    /** Terrestrial tuner LNA setting, read by DtvTerrestrialSetup: "true"/"false". */
    const val KEY_LNA = "lna.enabled"

    /**
     * Permission we require from the sender of scan events (INSTALL_EVENT etc.) so that another app
     * cannot spoof them (a dynamic receiver with targetSdk 29 is exported). dtvinput itself
     * defines and holds it; if it did not on other hardware – null (no restriction, otherwise events would not arrive).
     */
    fun eventSenderPermission(context: Context): String? =
        PERMISSION_INSTALL_TV_CHANNELS.takeIf {
            context.packageManager.checkPermission(it, DTV_INPUT_PACKAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * Returns the TV input that handles terrestrial TV. The CBS input is preferred,
     * and if there is none (another manufacturer) – the first TUNER-type input.
     */
    fun findTunerInput(context: Context): TvInputInfo? {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return null
        val inputs = manager.tvInputList
        return inputs.firstOrNull { it.serviceInfo.packageName == DTV_INPUT_PACKAGE }
            ?: inputs.firstOrNull { it.type == TvInputInfo.TYPE_TUNER && !it.isPassthroughInput }
    }
}
