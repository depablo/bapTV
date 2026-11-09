package com.bonsaponsi.baptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRulesTest {

    @Test
    fun infoBannerIsShownOnlyWithoutMenuOrDialog() {
        assertTrue(PlayerRules.mayShowInfo(menuVisible = false, dialogOpen = false))
        assertFalse(PlayerRules.mayShowInfo(menuVisible = true, dialogOpen = false))
        assertFalse(PlayerRules.mayShowInfo(menuVisible = false, dialogOpen = true))
        assertFalse(PlayerRules.mayShowInfo(menuVisible = true, dialogOpen = true))
    }

    @Test
    fun rescanIsOfferedOnlyForAChannelThatWorkedBeforeOnTheOpenPlayer() {
        assertTrue(PlayerRules.mayOfferRescan(workedBefore = true, menuVisible = false, dialogOpen = false, windowFocused = true))
        assertFalse(PlayerRules.mayOfferRescan(workedBefore = false, menuVisible = false, dialogOpen = false, windowFocused = true))
        assertFalse(PlayerRules.mayOfferRescan(workedBefore = true, menuVisible = true, dialogOpen = false, windowFocused = true))
        assertFalse(PlayerRules.mayOfferRescan(workedBefore = true, menuVisible = false, dialogOpen = true, windowFocused = true))
        assertFalse(PlayerRules.mayOfferRescan(workedBefore = true, menuVisible = false, dialogOpen = false, windowFocused = false))
    }
}
