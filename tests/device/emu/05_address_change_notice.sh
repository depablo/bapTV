#!/usr/bin/env bash
# The box's network address differs from the one the phones paired at: the player says so once and offers a new
# pairing code; after that the new address is remembered.
source "$(dirname "$0")/../lib.sh"
scenario emu "network address change notice"
emu_ensure; emu_install; emu_reset; emu_skip_intro; emu_channels
put_prefs adbe phone_remote '<map><boolean name="enabled" value="true" /><string name="devices">[{&quot;name&quot;:&quot;Test phone&quot;,&quot;hash&quot;:&quot;00&quot;,&quot;added&quot;:1,&quot;used&quot;:1}]</string><string name="address">10.0.2.99</string></map>'
emu_start

wait_for "the notice names the new address" 40 ui_dialog_has 'new network address: 10\.0\.2\.[0-9]+|nowy adres w sieci: 10\.0\.2\.[0-9]+'
check "'Show the code' is focused" ui_focused 'Show the code|Pokaż kod'
key OK
wait_for "the pairing QR code opens with a fresh link" 10 ui_dialog_has '^http://10\.0\.2\.[0-9]+:[0-9]+/pair\?code=[0-9a-f]+$'
key BACK
wait_for "the dialog closes" 10 ui_no_dialog
adbe shell am force-stop "$APP_PKG"
emu_start
wait_for "the player starts again" 30 log_has 'Player: loaded 4 channels'
# The notice is checked 12 s after the start; past that it must not come again.
sleep 16
check "no second notice for the same address" log_lacks 'network address changed'
pass
