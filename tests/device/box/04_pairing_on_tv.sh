#!/usr/bin/env bash
# Pairing without a code on the box (see pairing.inc.sh); the test phones are forgotten afterwards.
source "$(dirname "$0")/../lib.sh"
scenario box "phone pairing accepted on the TV"
cleanup() { api POST '/debug/remote/forget?name=test-accept' >/dev/null 2>&1; }
trap cleanup EXIT
wait_for "the player is in front" 30 ui_activity_is MainActivity
check "no dialog in the way" ui_no_dialog
source "$TESTS_DIR/pairing.inc.sh"
forget_test_phone() { api POST '/debug/remote/forget?name=test-accept' | grep -q '^Forgotten: 1$'; }
check "the test phone is forgotten again" forget_test_phone
pass
