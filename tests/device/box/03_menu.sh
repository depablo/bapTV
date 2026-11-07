#!/usr/bin/env bash
# The menu opens from the player with MENU (REC on the box's remote), shows its sections and closes with BACK.
source "$(dirname "$0")/../lib.sh"
scenario box "menu opens and closes"
SECTIONS='Watching|Oglądanie'
wait_for "the player is in front" 30 ui_activity_is MainActivity
menu_open() { ui_has "$SECTIONS" && ui_has 'Recordings|Nagrania' && ui_has 'Channels|Kanały' && ui_has 'Settings|Ustawienia' && ui_has 'Diagnostics|Diagnostyka'; }
menu_closed() { ! ui_has "$SECTIONS"; }
if ! menu_closed; then key BACK; fi
wait_for "no menu at the start" 10 menu_closed
key MENU
wait_for "MENU opens all five sections" 10 menu_open
key BACK
wait_for "BACK closes it" 10 menu_closed
check "the player stays in front" ui_activity_is MainActivity
pass
