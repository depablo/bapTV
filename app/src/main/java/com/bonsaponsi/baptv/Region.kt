package com.bonsaponsi.baptv

import android.content.Context
import android.content.res.Resources
import java.util.Locale

/**
 * Country-dependent settings, taken from system settings (country from the system locale, e.g. pl-PL → PL):
 * scan frequency plan and the languages/country reported to HbbTV apps.
 * Poland and most of Europe: VHF III with 7 MHz channels and UHF with 8 MHz channels.
 */
object Region {

    /** Scan band in kHz (dtvinput format: frequency-min/max/step, bandwidth). */
    data class Band(val minKhz: Int, val maxKhz: Int, val stepKhz: Int, val bandwidthKhz: Int = stepKhz) {
        val count get() = (maxKhz - minKhz) / stepKhz + 1
    }

    data class Plan(val name: String, val vhf: Band?, val uhf: Band) {
        fun bands(includeVhf: Boolean) = listOfNotNull(vhf.takeIf { includeVhf }, uhf)
        fun frequencyCount(includeVhf: Boolean) = bands(includeVhf).sumOf { it.count }
    }

    /** Europe (GE06): VHF 177.5–226.5 MHz every 7 MHz, UHF 474–850 MHz every 8 MHz (channels 21–68). */
    val EUROPE = Plan("Europe (UHF 8 MHz, VHF 7 MHz)", Band(177_500, 226_500, 7_000), Band(474_000, 850_000, 8_000))
    /** Australia: VHF 6–12 and UHF 28–51, 7 MHz channels. */
    val AUSTRALIA = Plan("Australia (7 MHz)", Band(177_500, 226_500, 7_000), Band(529_500, 690_500, 7_000))
    /** DVB-T/T2 countries with 6 MHz channels (e.g. Colombia, Taiwan): UHF 14–51. */
    val SIX_MHZ = Plan("UHF 6 MHz", null, Band(473_000, 695_000, 6_000))

    private val SIX_MHZ_COUNTRIES = setOf("CO", "TW", "PA")

    fun plan(country: String): Plan = when (country.uppercase(Locale.ROOT)) {
        "AU" -> AUSTRALIA
        in SIX_MHZ_COUNTRIES -> SIX_MHZ
        else -> EUROPE
    }

    /** Country from system settings (not from the language chosen in the app); "" – unknown. */
    fun country(): String = Resources.getSystem().configuration.locales[0].country.orEmpty()

    fun plan(): Plan = plan(country())

    /** dtvinput takes the country as an English lower-case name ("poland"); "" – unknown. */
    fun countryName(code: String): String =
        if (code.isEmpty()) "" else Locale("", code).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)

    /** ISO 3166 alpha-3 country code (e.g. "POL") for HbbTV; "" – unknown. */
    fun countryIso3(country: String): String =
        runCatching { Locale("", country).isO3Country.uppercase(Locale.ROOT) }.getOrDefault("")

    /**
     * ISO 639-2 language code used in DVB (bibliographic variant, e.g. "ger", "cze", "fre"),
     * "pol" for Polish.
     */
    fun dvbLanguage(locale: Locale): String {
        val terminology = runCatching { locale.isO3Language }.getOrDefault("")
        return BIBLIOGRAPHIC[terminology] ?: terminology
    }

    /** Languages and country for HbbTV apps: the app's UI language, the system country. */
    fun hbbTvConfig(context: Context): Map<String, String> {
        val locale = AppLanguage.locale(context)
        val country = country()
        return mapOf(
            "__DVBT_LANG3__" to dvbLanguage(locale).ifEmpty { "und" },
            "__DVBT_LANG2__" to locale.language.ifEmpty { "und" },
            "__DVBT_COUNTRY3__" to countryIso3(country),
        )
    }

    private val BIBLIOGRAPHIC = mapOf(
        "sqi" to "alb", "hye" to "arm", "eus" to "baq", "mya" to "bur", "zho" to "chi", "ces" to "cze",
        "nld" to "dut", "fra" to "fre", "kat" to "geo", "deu" to "ger", "ell" to "gre", "isl" to "ice",
        "mkd" to "mac", "mri" to "mao", "msa" to "may", "fas" to "per", "ron" to "rum", "slk" to "slo",
        "bod" to "tib", "cym" to "wel",
    )
}
