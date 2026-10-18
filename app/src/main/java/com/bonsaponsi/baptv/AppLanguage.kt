package com.bonsaponsi.baptv

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * UI language: the system one (default) or the one chosen in the menu. Strings are in `values` (English,
 * for any other system language) and `values-pl` (Polish). Activities call [wrap]
 * in `attachBaseContext`, so the change works without changing the whole device's language.
 */
object AppLanguage {
    private const val PREFS = "player"
    private const val KEY = "language"

    /** Codes of supported languages; "" = system language. */
    val SUPPORTED = listOf("", "pl", "en")

    fun get(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
        DebugLog.log("Player", "UI language: ${language.ifEmpty { "system" }}")
    }

    /** Context with the language chosen in the app (unchanged when the system one is selected). */
    fun wrap(base: Context): Context {
        val language = get(base)
        if (language.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale(language))
        return base.createConfigurationContext(config)
    }

    /** Language name for the menu: in that language itself ("Polski", "English"), the system one – in the current language. */
    fun label(context: Context, language: String): String =
        if (language.isEmpty()) context.getString(R.string.language_system)
        else Locale(language).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.titlecase(Locale(language)) }

    /** The language the app actually displays text in (for track language names, dates). */
    fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}

/** Name of the general programme category ([Programme.genre]) in the UI language; null when unknown. */
fun Context.genreLabel(genre: Int): String? =
    resources.getStringArray(R.array.genres).getOrNull(genre)?.takeIf { it.isNotEmpty() }
