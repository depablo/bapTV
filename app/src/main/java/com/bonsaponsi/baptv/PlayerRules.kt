package com.bonsaponsi.baptv

/**
 * Rules for the player's windows, kept apart from the activity so they can be tested without a device.
 */
object PlayerRules {

        /** The rescan offer needs the player itself on screen: a channel that worked before, not under the menu or a dialog. */
    fun mayOfferRescan(workedBefore: Boolean, menuVisible: Boolean, dialogOpen: Boolean, windowFocused: Boolean): Boolean =
        workedBefore && !menuVisible && !dialogOpen && windowFocused
}
