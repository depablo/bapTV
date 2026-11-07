#!/usr/bin/env bash
# The player with the 4 test channels: plays the first one, P+/P− and number keys switch, the info panel shows the
# programme from the input's TV guide.
source "$(dirname "$0")/../lib.sh"
scenario emu "channel switching and the info panel"
emu_ensure; emu_install; emu_reset; emu_skip_intro; emu_channels
emu_start

wait_for "the 4 test channels are loaded" 30 log_has 'Player: loaded 4 channels'
wait_for "channel 1 plays" 30 log_has 'onVideoAvailable'
log_mark; key CHUP
wait_for "P+ tunes TVP2 HD" 15 log_has 'tune 2 "TVP2 HD"'
wait_for "TVP2 HD plays" 30 log_has 'onVideoAvailable'
log_mark; key CHDOWN
wait_for "P− tunes back to TVP1 HD" 15 log_has 'tune 1 "TVP1 HD"'
log_mark; key 7; key 7
wait_for "number 77 tunes the test channel" 15 log_has 'tune 77 "Test channel"'
wait_for "the info panel shows the guide's programme" 15 ui_has 'Test channel: programme [0-9]{2}:[0-9]{2}'
pass
