#!/usr/bin/env bash
# TV guide from TvContract.Programs: the debug server sees the test programmes, the guide screen shows them.
source "$(dirname "$0")/../lib.sh"
scenario emu "TV guide from the input"
emu_ensure; emu_install; emu_reset; emu_skip_intro; emu_channels
emu_start

wait_for "the 4 test channels are loaded" 30 log_has 'Player: loaded 4 channels'
guide_count() {
  local id; id="$(api GET /channels | json_get 'next(c["id"] for c in d if c["name"]=="TVP1 HD")')" || return 1
  [[ "$(api GET "/epg?channel=$id" | json_get 'sum(1 for p in d if p["title"].startswith("TVP1 HD: programme"))')" -ge 48 ]]
}
check "TVP1 HD has at least a day of programmes" guide_count
on_now() { api GET /channels | grep -q "\"now\": \"$1: programme"; }
check "the channel list shows what is on now" on_now Polsat
api POST "/open?screen=guide" >/dev/null
wait_for "the guide screen opens" 15 ui_activity_is GuideActivity
wait_for "it lists the test programmes" 15 ui_has 'TVP1 HD: programme [0-9]{2}:[0-9]{2}'
key BACK
wait_for "BACK returns to the player" 15 ui_activity_is MainActivity
pass
