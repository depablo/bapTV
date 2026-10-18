package com.bonsaponsi.baptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale

class RegionTest {

    @Test
    fun europeanPlanMatchesPolishScan() {
        // Original scan in Poland: UHF 474-850 MHz in 8 MHz steps (48) and VHF 177.5-226.5 MHz in 7 MHz steps (8).
        val plan = Region.plan("PL")
        assertSame(Region.EUROPE, plan)
        assertEquals(48, plan.frequencyCount(includeVhf = false))
        assertEquals(56, plan.frequencyCount(includeVhf = true))
        assertEquals(474_000, plan.bands(false).single().minKhz)
    }

    @Test
    fun countriesWithOtherChannelWidths() {
        assertSame(Region.AUSTRALIA, Region.plan("au"))
        assertEquals(7_000, Region.plan("AU").uhf.bandwidthKhz)
        assertSame(Region.SIX_MHZ, Region.plan("CO"))
        assertNull(Region.SIX_MHZ.vhf)
        assertSame(Region.EUROPE, Region.plan(""))
    }

    @Test
    fun dvbLanguageUsesBibliographicCodes() {
        assertEquals("pol", Region.dvbLanguage(Locale("pl")))
        assertEquals("eng", Region.dvbLanguage(Locale("en")))
        assertEquals("ger", Region.dvbLanguage(Locale("de")))
        assertEquals("cze", Region.dvbLanguage(Locale("cs")))
    }

    @Test
    fun countryIso3() {
        assertEquals("POL", Region.countryIso3("PL"))
        assertEquals("", Region.countryIso3(""))
    }
}
