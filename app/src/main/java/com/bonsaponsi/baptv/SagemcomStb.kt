package com.bonsaponsi.baptv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import java.lang.reflect.InvocationTargetException

/**
 * The Sagemcom set-top box middleware, reached the way the operator's own app (Play Now Box) reaches it: the
 * system shared library com.sagemcom.stb and a bound STB service (DvbtTv-private/docs/tvp-audio-silence.md, 3.5).
 * The library exists only on those boxes and only loads with the <uses-library> entry in the manifest (GitHub TV
 * build), so every call goes through reflection and the rest of the app never links against it.
 *
 * Play Now sets the middleware audio language preference to Polish whenever it connects to the service. The
 * Diagnostics menu entry does the same here, as an experiment for the silent TVP channels (issue #15): if the
 * preference drives which track the box decodes, this is the operator-sanctioned way to change it.
 */
object SagemcomStb {

    const val PERMISSION = "com.sagemcom.server.BIND_STB_SERVICE"
    private const val LIBRARY = "com.sagemcom.stb"
    private const val MANAGER_CLASS = "com.sagemcom.stb.StbManager"
    private const val INPUT_PACKAGE = "com.sagemcom.stb.tvinput"
    /** Slot 1 first; Play Now also knows "polski" and "pl" as later slots. */
    private val LANGUAGES = listOf("pol", "polski", "pl")
    private const val TAG = "Sagemcom"

    /** True only on a Sagemcom box (and only in a build with the uses-library entry). */
    val libraryPresent: Boolean get() = runCatching { Class.forName(MANAGER_CLASS) }.isSuccess

    /** The middleware can be tried: its library is loaded and the tuner input is Sagemcom's. */
    fun available(context: Context): Boolean =
        libraryPresent && Dtv.findTunerInput(context)?.id?.startsWith("$INPUT_PACKAGE/") == true

    /** Report lines: the shared libraries, the binding permission and the input package. */
    fun describe(context: Context): List<String> = buildList {
        val pm = context.packageManager
        val shared = runCatching { pm.systemSharedLibraryNames.orEmpty().filter { it.startsWith("com.sagemcom.") } }
            .getOrElse { emptyList() }
        add("System shared libraries com.sagemcom.*: ${shared.joinToString().ifEmpty { "none" }}")
        add("Library $LIBRARY on the classpath: $libraryPresent")
        val permission = runCatching { pm.getPermissionInfo(PERMISSION, 0) }.getOrNull()
        if (permission == null) {
            add("Permission $PERMISSION: not defined on this device")
        } else {
            val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
            @Suppress("DEPRECATION")
            add(
                "Permission $PERMISSION: ${if (granted) "granted" else "NOT granted"}, " +
                    "protection=0x${Integer.toHexString(permission.protectionLevel)}, declared by ${permission.packageName}"
            )
        }
        val input = runCatching { pm.getPackageInfo(INPUT_PACKAGE, 0) }.getOrNull()
        add("$INPUT_PACKAGE: ${input?.let { "${it.versionName} ${it.applicationInfo?.sourceDir}" } ?: "not installed / not visible"}")
    }

    /**
     * Binds the STB service and sets the audio language preference to Polish. [onDone] gets a short result for
     * the screen (on the main thread); every step is in the log. Returns why it could not start, or null.
     */
    fun setPolishAudioPreference(context: Context, onDone: (String) -> Unit): String? {
        if (!libraryPresent) return "library not present"
        val intent = runCatching {
            Class.forName(MANAGER_CLASS).getMethod("getStbServiceIntent").invoke(null) as Intent
        }.getOrElse { return "getStbServiceIntent: ${reason(it)}" }
        DebugLog.log(TAG, "binding STB service: ${intent.component ?: intent}")
        val appContext = context.applicationContext
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val result =
                    if (binder == null) "connected without a binder"
                    else runCatching { applyPreference(binder) }.getOrElse { reason(it) }
                DebugLog.log(TAG, "audio preference: $result")
                onDone(result)
                runCatching { appContext.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                DebugLog.log(TAG, "STB service disconnected")
            }
        }
        val bound = runCatching { appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .getOrElse { return "bindService: ${reason(it)}" }
        if (!bound) {
            runCatching { appContext.unbindService(connection) }
            return "bindService returned false (service missing or binding not permitted)"
        }
        return null
    }

    /** StbManager(binder).getAudioPrefManager().setAudioPreference(slot, language) for each slot the box has. */
    private fun applyPreference(binder: IBinder): String {
        val managerClass = Class.forName(MANAGER_CLASS)
        val manager = managerClass.getConstructor(IBinder::class.java).newInstance(binder)
        val audio = managerClass.getMethod("getAudioPrefManager").invoke(manager)
            ?: return "no AudioPrefManager"
        val set = audio.javaClass.getMethod("setAudioPreference", Int::class.javaPrimitiveType, String::class.java)
        val max = runCatching { audio.javaClass.getMethod("getMaxAudioPreferences").invoke(audio) as Int }
            .getOrDefault(1)
        val applied = LANGUAGES.take(maxOf(max, 1)).mapIndexed { index, language ->
            set.invoke(audio, index + 1, language)
            "${index + 1}=$language"
        }
        return "set ${applied.joinToString()} (box has $max slots)"
    }

    private fun reason(e: Throwable): String {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
