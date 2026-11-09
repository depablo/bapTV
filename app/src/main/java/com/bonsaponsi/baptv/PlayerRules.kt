package com.bonsaponsi.baptv

/**
 * Rules for the player's windows, kept apart from the activity so they can be tested without a device.
 */
object PlayerRules {

    /**
     * The channel info banner stays off the menu and off an open dialog. Its arrow keys would otherwise bring it
     * up while a track or scan dialog is open.
     */
    fun mayShowInfo(menuVisible: Boolean, dialogOpen: Boolean): Boolean = !menuVisible && !dialogOpen

    /** The rescan offer needs the player itself on screen: a channel that worked before, not under the menu or a dialog. */
    fun mayOfferRescan(workedBefore: Boolean, menuVisible: Boolean, dialogOpen: Boolean, windowFocused: Boolean): Boolean =
        workedBefore && !menuVisible && !dialogOpen && windowFocused
}
