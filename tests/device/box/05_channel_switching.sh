#!/usr/bin/env bash
# P+ and P− switch channels on the box. Checked by the player's tune requests, not by the picture – the scenario
# does not depend on the antenna signal.
source "$(dirname "$0")/../lib.sh"
scenario box "channel switching"
wait_for "the player is in front" 30 ui_activity_is MainActivity
check "no dialog in the way" ui_no_dialog
last_tune() { log_since | sed -n 's/.*Player: tune \([0-9][0-9]*\) ".*/\1/p' | tail -1; }
log_from_process_start
START="$(last_tune)"
check "a channel is tuned" test -n "$START"
log_mark; key CHUP
UP=""; got_up() { UP="$(last_tune)"; [[ -n "$UP" && "$UP" != "$START" ]]; }
wait_for "P+ tunes a channel other than $START" 15 got_up
log_mark; key CHDOWN
back_to_start() { [[ "$(last_tune)" == "$START" ]]; }
wait_for "P− tunes back to $START" 15 back_to_start
pass
