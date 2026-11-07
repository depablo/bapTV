#!/usr/bin/env bash
# First start of a fresh install on a box without dtvinput: the welcome page offers the scan, the scan screen opens
# the input's setup wizard after asking for READ_TV_LISTINGS, and the player loads the channels the wizard saved.
source "$(dirname "$0")/../lib.sh"
scenario emu "first start: scan through the input's setup wizard"
emu_ensure; emu_install; emu_fresh_install; emu_no_updates
emu_start

wait_for "welcome page offers the scan" 30 ui_dialog_has 'Welcome to bapTV|Witaj w bapTV'
key OK
wait_for "scan screen opens" 20 ui_activity_is ScanActivity
wait_for "'System wizard' is focused" 10 ui_focused 'System wizard|Kreator systemowy'
key OK
wait_for "READ_TV_LISTINGS is requested before the wizard" 15 log_has 'requesting android.permission.READ_TV_LISTINGS before the setup wizard'
wait_for "system permission prompt is on screen" 15 emu_permission_prompt
emu_keys KEYCODE_DPAD_CENTER
wait_for "the wizard returns 4 visible channels" 30 log_has 'returned from setup wizard: .*input channels=4, android.permission.READ_TV_LISTINGS=true'
wait_for "the player loads the 4 test channels" 30 log_has 'Player: loaded 4 channels'
check "channel list: Polsat, TVP1 HD, TVP2 HD, Test channel" channels_are 'Polsat,TVP1 HD,TVP2 HD,Test channel'
wait_for "first channel plays" 30 log_has 'onVideoAvailable'
pass
