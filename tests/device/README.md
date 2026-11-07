# Device test scenarios

Scripts that check bapTV on a running device, one file per scenario. Each one sets up a known state, waits for
concrete results (a log line, a JSON field, a text on screen), and ends with `PASS` (exit 0) or `FAIL` (exit 1). A
failed run keeps the screen description, the app log and a screenshot in `tests/device/out/`.

The scripts do not use fixed sleeps for results. Each step polls until its condition holds or its time limit runs
out. The data is fixed too: the test tuner's four channels and TV guide, and a prepared channel store with a local
XMLTV file on the phone.

| Target | Needs | Scenarios |
|---|---|---|
| `emu/` | Android TV emulator (API 30) | first start with the input's setup wizard, refused TV listings permission, channel switching and the info panel, TV guide, network address notice, pairing accepted on the TV |
| `box/` | a set-top box running the **debug** build, reachable on the network | install and start, server access rules, menu, pairing accepted on the TV, channel switching (by tune requests – no antenna checks) |
| `phone/` | an Android phone over adb (debug phone build) | channel list and search, TV guide from XMLTV, pairing with the box |

The emulator uses the `testtuner` module, a TV input with four fixed channels. `--ez clear true` on its setup
activity removes them, so every scenario starts from the same state.

## Running

```sh
tests/device/emu/03_channels_and_switching.sh
SKIP_BUILD=1 tests/device/box/02_server_access.sh       # do not rebuild the APK first
for t in tests/device/emu/*.sh; do "$t" || break; done   # a whole target
```

Each script builds what it needs (`assembleTvDebug`, `:testtuner:assembleDebug`, `assemblePhoneDebug`) unless
`SKIP_BUILD=1`, and installs it when the installed copy differs.

## Settings

Machine settings go to `tests/device/local.env`, which is not in git and is read by `lib.sh`:

```sh
ADB=/mnt/c/Programs/Android/Sdk/platform-tools/adb.exe   # adb (WSL: the Windows adb.exe)
EMU_AVD=TV30                                             # started when no emulator runs
export ANDROID_EMULATOR_HOME='C:\…' ANDROID_AVD_HOME='C:\…\avd'
WIN_TMP=/mnt/c/Users/Public/baptv-tests                  # ASCII folder adb.exe can read files from
PHONE_SERIAL=…                                           # adb serial of the phone
BOX_HOST=192.168.1.x                                     # the box's address
BOX_TOKEN_FILE=…/.tv-token                               # debug server token (written by box/01 after /install)
```

## How the checks see the app

- `GET /ui` (debug builds, token): the activity on screen, its open dialogs, their visible texts and the focused
  element. Screenshots of TV input video come out black, so the scripts read the screen state as text instead.
- `GET /log.txt?since=…`: each scenario reads only the log lines written after its own mark, or after the app
  process started.
- `POST /key?name=…`: remote keys into the app. System dialogs on the emulator (permission prompt) get keys
  through adb.
- `POST /debug/remote/forget?name=…`: removes the test phones a pairing scenario added.
- On the phone: `uiautomator dump` for the texts. Taps are retried until their effect shows, because adb taps on
  some phones get lost.

Texts are matched in English and Polish, so a box or phone in either language passes.
