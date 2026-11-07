#!/usr/bin/env bash
# Refusing READ_TV_LISTINGS before the input's wizard: the wizard still runs, but the app cannot see its channels –
# the message after it says why, and a report is offered (closed here without sending).
source "$(dirname "$0")/../lib.sh"
scenario emu "TV listings permission refused before the wizard"
emu_ensure; emu_install; emu_fresh_install; emu_no_updates
emu_start

wait_for "welcome page offers the scan" 30 ui_dialog_has 'Welcome to bapTV|Witaj w bapTV'
key OK
wait_for "'System wizard' is focused on the scan screen" 20 ui_focused 'System wizard|Kreator systemowy'
key OK
wait_for "system permission prompt is on screen" 15 emu_permission_prompt
emu_keys KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
wait_for "the wizard still runs, its channels stay invisible" 30 log_has 'returned from setup wizard: .*input channels=0, android.permission.READ_TV_LISTINGS=false'
wait_for "the message names the missing permission" 15 ui_dialog_has 'Without the permission to read TV listings|Bez uprawnienia do odczytu listy programów'
key BACK
wait_for "the report offer closes without sending" 10 ui_no_dialog
check "no report was sent" log_lacks 'report sent'
pass
