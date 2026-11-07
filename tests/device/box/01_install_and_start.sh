#!/usr/bin/env bash
# Installs the freshly built debug APK over the running debug app (/install), then checks that the new version runs
# and the player loads the box's channels. Needs only the network and the app – no antenna checks.
source "$(dirname "$0")/../lib.sh"
scenario box "install the debug build and start"
APK="$ROOT/app/build/outputs/apk/tv/debug/app-tv-debug.apk"
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  (cd "$ROOT" && ./gradlew -q :app:assembleTvDebug 2>&1 | grep -E '^e:|FAIL') && die "build failed"
fi
CODE="$(sed -n 's/^ *versionCode = \([0-9]*\).*/\1/p' "$ROOT/app/build.gradle.kts")"
BUILT="$(stat -c %Y "$APK")"

check "the box accepts the APK" box_install "$APK"
installed() { [[ "$(status_field lastInstall)" == installed ]] && [[ "$(status_field versionCode)" == "$CODE" ]] \
  && (( $(status_field lastUpdateTime) / 1000 >= BUILT )); }
wait_for "the new build runs (versionCode $CODE)" 120 installed
wait_for "the player is in front" 60 ui_activity_is MainActivity
log_from_process_start
wait_for "the player loads the box's channels" 60 log_has 'Player: loaded [1-9][0-9]* channels'
wait_for "it tunes a channel" 30 log_has 'Player: tune [0-9]+ "'
pass
