#!/usr/bin/env bash
# Pairing without a code: a phone app's request shows "Pair a phone?" on the TV with Reject focused; rejecting and
# accepting give the phone the right answer, and only the accepted token opens the remote's API.
source "$(dirname "$0")/../lib.sh"
scenario emu "phone pairing accepted on the TV"
emu_ensure; emu_install; emu_reset; emu_skip_intro; emu_channels
put_prefs adbe phone_remote '<map><boolean name="enabled" value="true" /></map>'
emu_start
source "$TESTS_DIR/pairing.inc.sh"
pass
