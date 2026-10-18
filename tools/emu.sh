#!/usr/bin/env bash
# Drive the Android TV emulator (Android Studio on Windows) from WSL, via the Windows-side adb.exe.
#
#   tools/emu.sh devices              list adb devices
#   tools/emu.sh install [-n]         build and install the app and the test tuner (-n: skip build)
#   tools/emu.sh setup                "scan channels" in the test tuner (saves 4 channels with a TV guide)
#   tools/emu.sh start                start the player
#   tools/emu.sh logs                 logcat of the app and test tuner (Ctrl+C to stop)
#   tools/emu.sh key NAME             remote key (e.g. DPAD_DOWN, MEDIA_PLAY_PAUSE, BACK)
#   tools/emu.sh remote               remote control in the terminal (arrows, Enter, Backspace, + -, m p e, r g y b; q quits)
#   tools/emu.sh shot FILE.png        emulator screenshot
#   tools/emu.sh server               enable the app server (Diagnostics) and save the access token to .emu-token
#   tools/emu.sh http PATH [options]  request to the app server (port 8080 forwarded via adb)
#
# SDK: ANDROID_SDK_WIN variable (default C:\Programs\Android\Sdk or C:\Android\Sdk), adb.exe from platform-tools.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Windows-side SDK: ANDROID_SDK_WIN variable, otherwise the first existing of the usual locations.
SDK_WIN="${ANDROID_SDK_WIN:-}"
if [[ -z "$SDK_WIN" ]]; then
  for c in 'C:\Programs\Android\Sdk' 'C:\Android\Sdk'; do
    [[ -x "$(wslpath -u "$c")/platform-tools/adb.exe" ]] && { SDK_WIN="$c"; break; }
  done
  SDK_WIN="${SDK_WIN:-C:\Programs\Android\Sdk}"
fi
ADB="$(wslpath -u "$SDK_WIN")/platform-tools/adb.exe"
[[ -x "$ADB" ]] || { echo "Missing $ADB - install Platform-Tools in Android Studio (SDK in $SDK_WIN)" >&2; exit 2; }
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TUNER_APK="$ROOT/testtuner/build/outputs/apk/debug/testtuner-debug.apk"

adb() { "$ADB" "$@"; }
win() { wslpath -w "$1"; }

case "${1:-}" in
  devices) adb devices -l ;;
  install)
    if [[ "${2:-}" != "-n" ]]; then
      (cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-$HOME/tools/jdk}" ANDROID_HOME="${ANDROID_HOME:-$HOME/tools/android-sdk}" \
        ./gradlew :app:assembleDebug :testtuner:assembleDebug --console=plain -q)
    fi
    adb install -r -g "$(win "$TUNER_APK")"
    adb install -r -g "$(win "$APP_APK")"
    adb forward tcp:8080 tcp:8080 >/dev/null
    echo "installed; app server: tools/emu.sh http /status (after enabling it in Diagnostics)" ;;
  setup) adb shell am start -n com.bonsaponsi.baptv.testtuner/.SetupActivity ;;
  start) adb shell am start -n com.bonsaponsi.baptv/.MainActivity ;;
  logs) adb logcat -v time -s 'bapTV/*:V' 'TestTuner:V' 'AndroidRuntime:E' ;;
  key) adb shell input keyevent "KEYCODE_${2:?give a key name, e.g. DPAD_DOWN}" ;;
  remote)
    # Remote in the terminal: single keystrokes in this window are sent to the emulator as remote control keys.
    cat <<'HELP'
Terminal remote (q to quit):
  arrows - ▲▼◀▶   Enter - OK   Backspace - back   h - home
  0-9 - digits   + / - - P+ / P−   m - menu (REC)   p - ⏯ pause   e - TV guide (PROGRAM)
  r g y b - colour keys A B C D   i - INFO
HELP
    send() { adb shell input keyevent "$1" >/dev/null 2>&1 & }
    old="$(stty -g)"; trap 'stty "$old"' EXIT; stty -icanon -echo min 1
    while IFS= read -r -s -n1 c; do
      if [[ "$c" == $'\e' ]]; then
        read -r -s -n2 -t 0.05 rest || true
        case "$rest" in '[A') send KEYCODE_DPAD_UP ;; '[B') send KEYCODE_DPAD_DOWN ;; '[C') send KEYCODE_DPAD_RIGHT ;; '[D') send KEYCODE_DPAD_LEFT ;; esac
        continue
      fi
      case "$c" in
        '') send KEYCODE_DPAD_CENTER ;;
        $'\x7f'|$'\b') send KEYCODE_BACK ;;
        [0-9]) send "KEYCODE_$c" ;;
        '+'|'=') send KEYCODE_CHANNEL_UP ;; '-') send KEYCODE_CHANNEL_DOWN ;;
        m) send KEYCODE_MEDIA_RECORD ;; p) send KEYCODE_MEDIA_PLAY_PAUSE ;; e) send KEYCODE_GUIDE ;;
        r) send KEYCODE_PROG_RED ;; g) send KEYCODE_PROG_GREEN ;; y) send KEYCODE_PROG_YELLOW ;; b) send KEYCODE_PROG_BLUE ;;
        i) send KEYCODE_INFO ;; h) send KEYCODE_HOME ;;
        q) break ;;
      esac
    done ;;
  shot)
    # Screenshot taken by the emulator (in-system screencap returns an empty file while TV input video is playing).
    out="${2:-emu.png}"; tmp="$(mktemp -d)"
    adb emu screenrecord screenshot "$(win "$tmp")" >/dev/null
    mv "$(ls -t "$tmp"/*.png | head -1)" "$out"; rmdir "$tmp"
    echo "saved $out" ;;
  server)
    adb shell am force-stop com.bonsaponsi.baptv
    adb shell am start -n com.bonsaponsi.baptv/.MainActivity --ez enable_server true >/dev/null
    sleep 2
    adb forward tcp:8080 tcp:8080 >/dev/null
    # Token from the app's preferences (run-as works in the debug build); created when Diagnostics is first shown.
    adb shell run-as com.bonsaponsi.baptv cat shared_prefs/server.xml | sed -n 's/.*name="token">\([0-9a-f]*\)<.*/\1/p' | tr -d '\r' > "$ROOT/.emu-token"
    echo "server enabled, token: $(cat "$ROOT/.emu-token")" ;;
  http)
    path="${2:-/status}"; shift 2 || shift $#
    token=""; [[ -f "$ROOT/.emu-token" ]] && token="$(tr -d ' \r\n' < "$ROOT/.emu-token")"
    adb forward tcp:8080 tcp:8080 >/dev/null
    if [[ -n "$token" ]]; then curl.exe -s -H "X-Tv-Token: $token" "$@" "http://127.0.0.1:8080$path"; else curl.exe -s "$@" "http://127.0.0.1:8080$path"; fi ;;
  *) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
