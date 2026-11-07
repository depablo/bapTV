#!/usr/bin/env bash
# Shared helpers for the device test scenarios (tests/device/<target>/NN_name.sh).
#
# A scenario sources this file, calls `scenario <target> "<title>"`, sets up a known state, then runs steps:
#   check  "what"            command...   – must succeed now
#   wait_for "what" SECONDS  command...   – polled every 0.5 s until it succeeds or the time runs out
# and ends with `pass`. The first failing step ends the scenario with FAIL (exit 1) and saves the screen
# description (/ui), the app log since the start and a screenshot under tests/device/out/.
#
# Determinism: no fixed sleeps for results – every step waits for a concrete state (a log line after the
# scenario's own log mark, a JSON field, a text on screen). Settings for this machine go to tests/device/local.env
# (not in git), see README.md.
set -uo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$TESTS_DIR/../.." && pwd)"
# shellcheck disable=SC1091
[[ -f "$TESTS_DIR/local.env" ]] && source "$TESTS_DIR/local.env"

ADB="${ADB:-/mnt/c/Programs/Android/Sdk/platform-tools/adb.exe}"
EMU_SERIAL="${EMU_SERIAL:-emulator-5554}"
PHONE_SERIAL="${PHONE_SERIAL:-}"
BOX_HOST="${BOX_HOST:-}"
BOX_TOKEN_FILE="${BOX_TOKEN_FILE:-$TESTS_DIR/.box-token}"
# Windows folder with an ASCII path that adb.exe can read APKs and files from (it fails on non-ASCII user folders).
WIN_TMP="${WIN_TMP:-/mnt/c/Users/Public/baptv-tests}"
# Local ports forwarded to the emulator's servers (debug server 8080, phone remote 8091 in debug builds).
EMU_HTTP_PORT="${EMU_HTTP_PORT:-18080}"
EMU_REMOTE_PORT="${EMU_REMOTE_PORT:-18091}"
REMOTE_PORT="${REMOTE_PORT:-8091}"

# Debug builds get the ".debug" package suffix unless local.properties turns it off.
if [[ -z "${APP_PKG:-}" ]]; then
  if grep -q '^baptv.debugSuffix=false' "$ROOT/local.properties" 2>/dev/null; then APP_PKG=com.bonsaponsi.baptv; else APP_PKG=com.bonsaponsi.baptv.debug; fi
fi
TUNER_PKG=com.bonsaponsi.baptv.testtuner

TARGET=""; SCENARIO=""; OUT=""; TOKEN=""; LOG_NEXT=0; LOG_BOOT=0; STEP=0

say() { printf '  %s\n' "$*"; }

scenario() {
  TARGET="$1"; SCENARIO="$(basename "$0" .sh)"
  printf '== %s/%s – %s\n' "$TARGET" "$SCENARIO" "$2"
  OUT="$TESTS_DIR/out/$TARGET-$SCENARIO-$(date +%Y%m%d-%H%M%S)"
  case "$TARGET" in
    emu) [[ -x "$ADB" ]] || die "adb.exe not found at $ADB (set ADB)" ;;
    box) [[ -n "$BOX_HOST" ]] || die "set BOX_HOST (tests/device/local.env)"; [[ -f "$BOX_TOKEN_FILE" ]] && TOKEN="$(tr -d ' \r\n' < "$BOX_TOKEN_FILE")" ;;
    phone) [[ -n "$PHONE_SERIAL" ]] || die "set PHONE_SERIAL (tests/device/local.env)" ;;
  esac
}

die() { printf 'FAIL %s/%s: %s\n' "$TARGET" "$SCENARIO" "$*"; exit 2; }

pass() { printf 'PASS %s/%s (%d steps)\n' "$TARGET" "$SCENARIO" "$STEP"; exit 0; }

fail() {
  printf 'FAIL %s/%s, step %d: %s\n' "$TARGET" "$SCENARIO" "$STEP" "$1"
  mkdir -p "$OUT"
  if [[ "$TARGET" == phone ]]; then
    phone_texts > "$OUT/screen.txt" 2>/dev/null
    adbp exec-out screencap -p > "$OUT/screen.png" 2>/dev/null
    adbp logcat -d -t 400 > "$OUT/logcat.txt" 2>/dev/null
  else
    api GET /ui > "$OUT/ui.json" 2>/dev/null
    log_since > "$OUT/log.txt" 2>/dev/null
    screenshot "$OUT/screen.png" 2>/dev/null
  fi
  printf '  saved: %s\n' "$OUT"
  exit 1
}

check() {
  local what="$1"; shift
  STEP=$((STEP + 1))
  if "$@"; then say "ok  $what"; else fail "$what"; fi
}

wait_for() {
  local what="$1" secs="$2"; shift 2
  STEP=$((STEP + 1))
  local end=$((SECONDS + secs))
  while (( SECONDS < end )); do
    if "$@"; then say "ok  $what"; return 0; fi
    sleep 0.5
  done
  fail "$what (not within ${secs} s)"
}

# ---- adb (emulator and phone) ----

adbe() { "$ADB" -s "$EMU_SERIAL" "$@"; }
adbp() { "$ADB" -s "$PHONE_SERIAL" "$@"; }

win() { wslpath -w "$1"; }

# Copies a file to WIN_TMP and pushes it to /data/local/tmp of the device (adb <serial-fn>).
push_tmp() {
  local fn="$1" src="$2" name; name="$(basename "$src")"
  mkdir -p "$WIN_TMP" && cp "$src" "$WIN_TMP/$name" || return 1
  "$fn" push "$(win "$WIN_TMP/$name")" "/data/local/tmp/$name" >/dev/null 2>&1
}

# Installs through pm (adb install can hang on the Play Protect verifier).
pm_install() {
  local fn="$1" apk="$2"
  push_tmp "$fn" "$apk" || return 1
  "$fn" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1
  "$fn" shell pm install -r -t "/data/local/tmp/$(basename "$apk")" | tr -d '\r' | grep -q Success
}

# Writes a SharedPreferences XML file of the debug app (run-as), app stopped.
put_prefs() {
  local fn="$1" name="$2" xml="$3" tmp
  tmp="$(mktemp)"; printf '%s\n' "$xml" > "$tmp"
  mkdir -p "$WIN_TMP" && cp "$tmp" "$WIN_TMP/$name.xml"; rm -f "$tmp"
  "$fn" push "$(win "$WIN_TMP/$name.xml")" "/data/local/tmp/$name.xml" >/dev/null 2>&1 || return 1
  "$fn" shell "run-as $APP_PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/$name.xml shared_prefs/$name.xml'"
}

# ---- the app's debug server (emulator through adb forward, box directly) ----

base_url() {
  case "$TARGET" in
    emu) echo "http://127.0.0.1:$EMU_HTTP_PORT" ;;
    *) echo "http://$BOX_HOST:8080" ;;
  esac
}

# The emulator's forwarded ports live on the Windows side – curl.exe reaches them, WSL's curl does not.
curl_() { if [[ "$TARGET" == emu ]]; then curl.exe "$@"; else curl "$@"; fi; }

# api METHOD PATH [curl options…] – body on stdout; fails on HTTP errors.
api() {
  local method="$1" path="$2"; shift 2
  curl_ -sf --max-time 30 -X "$method" -H "X-Tv-Token: $TOKEN" "$@" "$(base_url)$path"
}

# HTTP status only (for access checks).
api_code() {
  local method="$1" path="$2"; shift 2
  curl_ -s -o /dev/null -w '%{http_code}' --max-time 30 -X "$method" "$@" "$(base_url)$path"
}

remote_url() {
  case "$TARGET" in
    emu) echo "http://127.0.0.1:$EMU_REMOTE_PORT" ;;
    *) echo "http://$BOX_HOST:$REMOTE_PORT" ;;
  esac
}

# Phone remote server: remote METHOD PATH [curl options…]
remote() {
  local method="$1" path="$2"; shift 2
  curl_ -s --max-time 15 -X "$method" "$@" "$(remote_url)$path"
}

key() { api POST "/key?name=$1" >/dev/null; }

# Remembers the end of the app log: log_since / log_has see only what comes after.
log_mark() {
  local headers
  headers="$(curl_ -s -D - -o /dev/null --max-time 15 -H "X-Tv-Token: $TOKEN" "$(base_url)/log.txt?since=9000000000000000000")" || return 1
  LOG_NEXT="$(printf '%s' "$headers" | tr -d '\r' | sed -n 's/^[Xx]-[Ll]og-[Nn]ext: //p')"
  LOG_BOOT="$(printf '%s' "$headers" | tr -d '\r' | sed -n 's/^[Xx]-[Ll]og-[Bb]oot: //p')"
  [[ -n "$LOG_NEXT" ]]
}

# App log lines after the mark (after a process restart: since the new process start).
log_since() { curl_ -s --max-time 15 -H "X-Tv-Token: $TOKEN" "$(base_url)/log.txt?since=$LOG_NEXT&boot=$LOG_BOOT"; }

log_has() { log_since | grep -Eq "$1"; }
log_lacks() { ! log_has "$1"; }

# JSON helper: json_get 'python expression on d' < json
json_get() { python3 -I -c "import json,sys
d=json.load(sys.stdin)
v=($1)
print(v if not isinstance(v,(dict,list)) else json.dumps(v))" 2>/dev/null; }

ui() { api GET /ui; }
ui_activity_is() { [[ "$(ui | json_get 'd.get("activity")')" == "$1" ]]; }
# A text (regex) on the screen or in an open dialog.
ui_has() { ui | python3 -I -c "import json,re,sys
d=json.load(sys.stdin); t=(d.get('screen') or {}).get('texts',[])+[x for g in d.get('dialogs',[]) for x in g.get('texts',[])]
sys.exit(0 if any(re.search(sys.argv[1],x) for x in t) else 1)" "$1" 2>/dev/null; }
ui_dialog_has() { ui | python3 -I -c "import json,re,sys
d=json.load(sys.stdin); t=[x for g in d.get('dialogs',[]) for x in g.get('texts',[])]
sys.exit(0 if any(re.search(sys.argv[1],x) for x in t) else 1)" "$1" 2>/dev/null; }
ui_no_dialog() { [[ "$(ui | json_get 'len(d.get("dialogs",[]))')" == 0 ]]; }
# Focused element of the top dialog (or the screen) matches the regex.
ui_focused() { ui | python3 -I -c "import json,re,sys
d=json.load(sys.stdin); g=d.get('dialogs') or []; f=(g[-1] if g else d.get('screen') or {}).get('focused') or ''
sys.exit(0 if re.search(sys.argv[1],f) else 1)" "$1" 2>/dev/null; }

# Broadcast channel names, sorted and comma-separated (internet channels left out).
channel_names() { api GET /channels | python3 -I -c "import json,sys
print(','.join(sorted(c['name'] for c in json.load(sys.stdin) if not c.get('iptv'))))" 2>/dev/null; }
channels_are() { [[ "$(channel_names)" == "$1" ]]; }

status_field() { api GET /status | json_get "d.get('$1')"; }

screenshot() {
  case "$TARGET" in
    emu) local tmp; tmp="$(mktemp -d -p "$WIN_TMP" 2>/dev/null || (mkdir -p "$WIN_TMP" && mktemp -d -p "$WIN_TMP"))"
         adbe emu screenrecord screenshot "$(win "$tmp")" >/dev/null && mv "$(ls -t "$tmp"/*.png | head -1)" "$1"; rm -rf "$tmp" ;;
    box) api GET /screenshot.png -o "$1" ;;
  esac
}

# ---- emulator ----

# Boots the AVD when no emulator is attached (EMU_AVD and the emulator's homes from local.env).
emu_ensure() {
  if ! "$ADB" devices | tr -d '\r' | grep -q "^$EMU_SERIAL[[:space:]]*device"; then
    [[ -n "${EMU_AVD:-}" ]] || die "no emulator running and EMU_AVD not set"
    say "starting emulator $EMU_AVD"
    (cd /mnt/c && WSLENV="ANDROID_EMULATOR_HOME:ANDROID_AVD_HOME:ANDROID_SDK_ROOT" \
      nohup "${EMULATOR:-/mnt/c/Programs/Android/Sdk/emulator/emulator.exe}" -avd "$EMU_AVD" -no-snapshot-save -no-boot-anim \
      -no-window -gpu swiftshader_indirect >/dev/null 2>&1 &)
  fi
  local end=$((SECONDS + 240))
  until [[ "$(adbe shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    (( SECONDS < end )) || die "emulator did not boot"
    sleep 2
  done
  adbe forward "tcp:$EMU_HTTP_PORT" tcp:8080 >/dev/null
  adbe forward "tcp:$EMU_REMOTE_PORT" "tcp:$REMOTE_PORT" >/dev/null
}

# Builds (unless SKIP_BUILD=1) and installs the app and the test tuner when the installed copies differ.
# On API 30 the system does not bind a TV input again after its package is updated (sessions then wait forever),
# so installing the test tuner reboots the emulator.
emu_install() {
  if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    (cd "$ROOT" && ./gradlew -q :app:assembleTvDebug :testtuner:assembleDebug 2>&1 | grep -E '^e:|FAIL') && die "build failed"
  fi
  local app="$ROOT/app/build/outputs/apk/tv/debug/app-tv-debug.apk" tuner="$ROOT/testtuner/build/outputs/apk/debug/testtuner-debug.apk"
  local sum
  sum="$(sha256sum < "$tuner" | cut -c1-16)"
  if [[ "$(adbe shell cat /data/local/tmp/baptv-tuner.sum 2>/dev/null | tr -d '\r')" != "$sum" ]]; then
    pm_install adbe "$tuner" || die "installing the test tuner failed"
    adbe shell "echo $sum > /data/local/tmp/baptv-tuner.sum"
    emu_reboot
  fi
  sum="$(sha256sum < "$app" | cut -c1-16)"
  if [[ "$(adbe shell cat /data/local/tmp/baptv-app.sum 2>/dev/null | tr -d '\r')" != "$sum" ]]; then
    pm_install adbe "$app" || die "installing the app failed"
    adbe shell "echo $sum > /data/local/tmp/baptv-app.sum"
  fi
}

emu_reboot() {
  say "rebooting the emulator"
  adbe reboot
  adbe wait-for-device
  local end=$((SECONDS + 240))
  until [[ "$(adbe shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    (( SECONDS < end )) || die "emulator did not boot again"
    sleep 2
  done
  adbe forward "tcp:$EMU_HTTP_PORT" tcp:8080 >/dev/null
  adbe forward "tcp:$EMU_REMOTE_PORT" "tcp:$REMOTE_PORT" >/dev/null
}

# Clean state: app data cleared, no test channels, TV listings permission not granted. Optional prefs keep the
# introduction and the daily tip away, so a scenario starts on a bare player unless it tests them.
emu_reset() {
  adbe shell am force-stop "$APP_PKG"
  adbe shell pm clear "$APP_PKG" >/dev/null
  adbe shell pm revoke "$APP_PKG" android.permission.READ_TV_LISTINGS >/dev/null 2>&1
  emu_tuner clear
}

# Runs the test tuner's setup: "clear" removes its channels, "install" saves the 4 test channels with a TV guide.
# Waits for its log line (the activity finishes at once, so am start -W would wait for nothing).
emu_tuner() {
  adbe logcat -c
  if [[ "$1" == clear ]]; then adbe shell am start -n "$TUNER_PKG/.SetupActivity" --ez clear true >/dev/null; else adbe shell am start -n "$TUNER_PKG/.SetupActivity" >/dev/null; fi
  local pattern; [[ "$1" == clear ]] && pattern='TestTuner.*removed' || pattern='TestTuner.*saved .* test channels'
  local end=$((SECONDS + 20))
  until adbe logcat -d -s TestTuner:I | tr -d '\r' | grep -Eq "$pattern"; do
    (( SECONDS < end )) || die "test tuner did not $1 its channels"
    sleep 0.5
  done
}

emu_skip_intro() {
  put_prefs adbe tour '<map><int name="seen_version" value="999999" /></map>'
  put_prefs adbe tips '<map><boolean name="daily" value="false" /></map>'
  emu_no_updates
}

# The update check asks GitHub – not deterministic; off in every scenario.
emu_no_updates() { put_prefs adbe github_update '<map><boolean name="auto" value="false" /></map>'; }

# A fresh install (the introduction shows only then): uninstall and install the built APK again.
emu_fresh_install() {
  adbe shell am force-stop "$APP_PKG"
  adbe shell pm uninstall "$APP_PKG" >/dev/null 2>&1
  pm_install adbe "$ROOT/app/build/outputs/apk/tv/debug/app-tv-debug.apk" || die "installing the app failed"
  adbe shell pm revoke "$APP_PKG" android.permission.READ_TV_LISTINGS >/dev/null 2>&1
  emu_tuner clear
}

# Installs the test channels (as the input's setup wizard does) and allows the app to see them.
emu_channels() {
  emu_tuner install
  adbe shell pm grant "$APP_PKG" android.permission.READ_TV_LISTINGS
}

# Starts the player with the debug server on and reads the server token (run-as).
emu_start() {
  adbe shell am start -n "$APP_PKG/com.bonsaponsi.baptv.MainActivity" --ez enable_server true >/dev/null
  local end=$((SECONDS + 30))
  until TOKEN="$(adbe shell run-as "$APP_PKG" cat shared_prefs/server.xml 2>/dev/null | sed -n 's/.*name="token">\([0-9a-f]*\)<.*/\1/p' | tr -d '\r')" \
      && [[ -n "$TOKEN" ]] && curl_ -sf --max-time 3 "$(base_url)/status" >/dev/null; do
    (( SECONDS < end )) || die "the app's server did not start"
    sleep 0.5
  done
  log_from_process_start
}

# The log of the current app process, from its start – for steps right after a (re)start, when the app may log
# before the scenario could mark the log. A boot ID that matches no process makes the server start there.
log_from_process_start() { LOG_NEXT=0; LOG_BOOT=-1; }

# The system permission prompt is in front.
emu_permission_prompt() { adbe shell dumpsys activity activities | tr -d '\r' | grep -E 'ResumedActivity' | grep -q 'GrantPermissionsActivity'; }

# A system dialog (permission prompt) – adb keys, the app's /key does not reach it.
emu_keys() { local k; for k in "$@"; do adbe shell input keyevent "$k"; done; }

# ---- box (set-top box with the debug build, over HTTP) ----

# Uploads an APK to the running debug app (/install) and keeps the token it returns.
box_install() {
  local apk="$1" body code
  body="$(mktemp)"
  code="$(curl -s -o "$body" -w '%{http_code}' --max-time 300 -H 'Expect:' -H 'Content-Type: application/vnd.android.package-archive' \
    --data-binary "@$apk" "$(base_url)/install")"
  local token; token="$(sed -n 's/^token: //p' "$body" | tr -d '\r\n')"; rm -f "$body"
  [[ "$code" == 200 ]] || return 1
  if [[ -n "$token" ]]; then TOKEN="$token"; printf '%s\n' "$token" > "$BOX_TOKEN_FILE"; fi
}

# ---- phone (adb, uiautomator) ----

# Visible texts and content descriptions, one per line.
phone_texts() {
  adbp shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adbp shell cat /sdcard/ui.xml | tr -d '\r' | grep -o 'text="[^"]*"\|content-desc="[^"]*"' | sed 's/^[a-z-]*="//; s/"$//' | grep -v '^$'
}

phone_has() { phone_texts | grep -Eq "$1"; }
phone_lacks() { ! phone_has "$1"; }

# Taps the element whose text or description matches exactly (or given as a raw attribute: 'resource-id="…"'). adb taps on some phones get lost after an
# interrupted gesture, so a neutral tap first resets the touch state.
phone_tap() {
  adbp shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  local attr; if [[ "$1" == *'="'* ]]; then attr="$1"; else attr="\(text\|content-desc\)=\"$1\""; fi
  local b; b="$(adbp shell cat /sdcard/ui.xml | tr -d '\r' | grep -o "$attr[^>]*bounds=\"[^\"]*\"" | grep -o 'bounds="[^"]*"' | head -1 | tr -d 'bounds="[' | tr ']' ',')"
  [[ -n "$b" ]] || return 1
  local x1 y1 x2 y2; IFS=, read -r x1 y1 x2 y2 _ <<<"$b"
  adbp shell input keyevent KEYCODE_WAKEUP
  adbp shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
}

# Taps the element until the condition holds (a lost tap is repeated; at most 5 tries).
phone_tap_until() {
  local text="$1"; shift
  local i
  for i in 1 2 3 4 5; do
    phone_tap "$text"
    sleep 1
    "$@" && return 0
  done
  return 1
}

# Installs the phone debug build (built unless SKIP_BUILD=1) and clears its data.
phone_reset() {
  local apk="$ROOT/app/build/outputs/apk/phone/debug/app-phone-debug.apk"
  if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    (cd "$ROOT" && ./gradlew -q :app:assemblePhoneDebug 2>&1 | grep -E '^e:|FAIL') && die "build failed"
  fi
  local sum; sum="$(sha256sum < "$apk" | cut -c1-16)"
  if [[ "$(adbp shell cat /data/local/tmp/baptv-phone.sum 2>/dev/null | tr -d '\r')" != "$sum" ]]; then
    pm_install adbp "$apk" || die "installing the phone build failed"
    adbp shell "echo $sum > /data/local/tmp/baptv-phone.sum"
  fi
  adbp shell am force-stop "$APP_PKG"
  adbp shell pm clear "$APP_PKG" >/dev/null
  # Update checks ask GitHub – not deterministic.
  put_prefs adbp github_update '<map><boolean name="auto" value="false" /></map>'
}

# Writes a file into the debug app's files/ folder (run-as), app stopped.
put_file() {
  local fn="$1" name="$2" content="$3" tmp
  tmp="$(mktemp)"; printf '%s' "$content" > "$tmp"
  mkdir -p "$WIN_TMP" && cp "$tmp" "$WIN_TMP/$name"; rm -f "$tmp"
  "$fn" push "$(win "$WIN_TMP/$name")" "/data/local/tmp/$name" >/dev/null 2>&1 || return 1
  "$fn" shell "run-as $APP_PKG sh -c 'mkdir -p files && cp /data/local/tmp/$name files/$name'"
}

phone_start() { adbp shell input keyevent KEYCODE_WAKEUP; adbp shell am start -n "$APP_PKG/com.bonsaponsi.baptv.PhoneActivity" >/dev/null; }

phone_foreground() { adbp shell dumpsys window | tr -d '\r' | grep -m1 mCurrentFocus; }
