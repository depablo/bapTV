#!/usr/bin/env bash
# The phone app finds the box (debug builds on both) and pairs without a code: the box asks on the TV, the
# scenario accepts with the box's remote keys, and the phone opens the remote. Needs the box (BOX_HOST) too.
source "$(dirname "$0")/../lib.sh"
scenario phone "pairing with the box, accepted on the TV"
[[ -n "$BOX_HOST" && -f "$BOX_TOKEN_FILE" ]] || die "needs BOX_HOST and BOX_TOKEN_FILE"
TOKEN="$(tr -d ' \r\n' < "$BOX_TOKEN_FILE")"
box() { local t=$TARGET; TARGET=box; "$@"; local r=$?; TARGET=$t; return $r; }
MODEL="$(adbp shell getprop ro.product.model | tr -d '\r')"
cleanup() { box api POST "/debug/remote/forget?name=$(python3 -I -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$MODEL")" >/dev/null 2>&1; }
trap cleanup EXIT

phone_reset
box key BACK 2>/dev/null
box_ready() { box ui_activity_is MainActivity && box ui_no_dialog; }
wait_for "the box shows the player without dialogs" 20 box_ready
phone_start
remote_list() { phone_has 'bapTV-[0-9a-f]{6}'; }
on_remote_screen() { phone_has 'bapTV-[0-9a-f]{6}|Looking for bapTV boxes|Szukam dekoderów'; }
check "the remote screen opens" phone_tap_until "resource-id=\"$APP_PKG:id/phone_header_remote\"" on_remote_screen
wait_for "the phone finds the box" 30 remote_list
box_asks() { box ui_dialog_has 'Pair a phone\?|Sparować telefon\?'; }
BOX_NAME="$(phone_texts | grep -Eo 'bapTV-[0-9a-f]{6}' | head -1)"
check "tapping the box asks on the TV" phone_tap_until "$BOX_NAME" box_asks
check "the question names this phone" box ui_dialog_has "$MODEL"
box key RIGHT; box key OK
pilot_open() { phone_foreground | grep -q PilotActivity; }
wait_for "the phone opens the remote" 20 pilot_open
adbp shell input keyevent KEYCODE_BACK
pass
