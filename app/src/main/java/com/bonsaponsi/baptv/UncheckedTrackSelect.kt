package com.bonsaponsi.baptv

import android.media.tv.TvView
import android.os.IBinder
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * [TvView.selectTrack] drops a track id that is not in the session's track list before it reaches the tuner
 * input. The codec class test ([TrackVariants]) needs such an id to arrive, so this makes the session's own call
 * to the TV input service without that check. It uses non-SDK members of the framework and may be refused.
 */
object UncheckedTrackSelect {

    /** Sends [id] to the input of [tvView]'s session. Returns null when the call was made, else why it was not. */
    fun select(tvView: TvView, type: Int, id: String): String? = try {
        val session = field(TvView::class.java, "mSession").get(tvView)
        if (session == null) {
            "no session"
        } else {
            val token = field(session.javaClass, "mToken").get(session) as IBinder?
            val service = field(session.javaClass, "mService").get(session)
            val userId = field(session.javaClass, "mUserId").getInt(session)
            if (token == null || service == null) {
                "session released"
            } else {
                method(service.javaClass, "selectTrack", IBinder::class.java, Int::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!)
                    .invoke(service, token, type, id, userId)
                null
            }
        }
    } catch (e: Throwable) {
        val cause = (e as? InvocationTargetException)?.targetException ?: e
        "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun field(cls: Class<*>, name: String): Field {
        val field = try {
            cls.getDeclaredField(name)
        } catch (e: NoSuchFieldException) {
            // Android 9 and 10 hide non-SDK members from the app, but not from the reflection classes themselves.
            Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java).invoke(cls, name) as Field
        }
        return field.apply { isAccessible = true }
    }

    private fun method(cls: Class<*>, name: String, vararg parameters: Class<*>): Method {
        val method = try {
            cls.getDeclaredMethod(name, *parameters)
        } catch (e: NoSuchMethodException) {
            Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, parameters.javaClass)
                .invoke(cls, name, parameters) as Method
        }
        return method.apply { isAccessible = true }
    }
}
