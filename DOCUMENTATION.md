# bapTV documentation

bapTV is a standalone terrestrial TV (DVB-T/T2) player for Android TV, built on the Android TV
Input Framework (TIF). This document has two parts:

- **User guide** – features, remote control, menus, recording, live pause, HbbTV, channel scan,
  diagnostics.
- **Developer guide** – architecture, emulator development, debug-only tools, building and testing.

Contents:

1. [Device support](#1-device-support)
2. [Getting started](#2-getting-started)
3. [Remote control](#remote-control)
4. [Watching TV](#4-watching-tv)
5. [The menu](#5-the-menu)
6. [Channels: list, numbering, management](#6-channels-list-numbering-management)
7. [Channel names and the TV guide](#7-channel-names-and-the-tv-guide)
8. [Live pause (timeshift)](#8-live-pause-timeshift)
9. [Recording](#9-recording)
10. [HbbTV](#10-hbbtv)
11. [Channel scan](#11-channel-scan)
12. [Diagnostics](#12-diagnostics)
13. [Architecture](#13-architecture)
14. [Emulator development](#14-emulator-development)
15. [Debug-only developer tools](#15-debug-only-developer-tools)
16. [Building and testing](#16-building-and-testing)

---

# User guide

## 1. Device support

bapTV needs an Android TV device with Android 8.0 or newer (API 26+) and a TIF tuner input.
It never talks to the tuner hardware directly: playback, channel data and recording all go through
the public TIF APIs (`TvView`, `TvContract`, `TvRecordingClient`).

What works depends on the tuner input. At startup the app detects the device capabilities
(`DeviceCapabilities`) and hides menu entries that would not work, instead of showing errors.

| Feature | Any tuner input that shares its channels | Google DTV stack (`com.google.android.tv.dtvinput`) |
|---|---|---|
| Live TV and radio, channel switching | yes | yes |
| Channel list, numbering, channel management | yes | yes |
| Info panel, audio and subtitle selection | yes | yes |
| TV guide from `TvContract.Programs` | yes (if the input provides it) | yes |
| In-app channel scan | – | yes |
| Channel discovery when the input hides its channels | – | yes |
| Channel names, LCN numbers and guide from the broadcast stream (EIT) | – | yes |
| Recording and recordings playback | – | yes |
| Live pause (timeshift) | depends on the input | yes |
| HbbTV applications | – | yes |
| Signal statistics (RSSI, SNR, BER), tuner LNA setting | – | yes, when `dtvprovider` responds |

If the tuner input has its own setup wizard, the channel search screen can open it. If the
device has an operator TV app that can open TIF channels, bapTV can open the current channel in
it and offers it as a scan method (see [Channel scan](#11-channel-scan)).

The Diagnostics report (section "Device capabilities") shows what was detected: the tuner input,
whether the Google DTV stack is present, whether tuner statistics are available, whether the input
has a setup wizard, whether its channels are visible in `TvContract`, whether stream analysis is
possible, the operator app, and the country and scan frequency plan.

## 2. Getting started

1. Install the APK (see [README](README.md#installing)) and start **bapTV**.
2. Grant access to TV listings when asked. It is needed to read the channel list and guide of the
   tuner input.
3. If there are no channels, the app opens the channel search screen. On devices with the Google
   DTV stack, **Scan** runs the whole setup in one go (see [Channel scan](#11-channel-scan)).
   On other devices, scan for channels in the system TV settings or in the tuner's own app; bapTV
   then shows the channels found there.
4. On Google DTV stack devices the app also asks for storage access (at most once a day). It is
   needed to read the short stream recordings used to identify channels, read the EIT guide and
   find HbbTV applications. Without it, the app shows a message instead of silently skipping
   identification.

The app keeps the screen on while you watch and returns to the last watched channel.

## Remote control

Android key codes are given so that the map also applies to other remotes. Some remotes have no
MENU key; the menu is therefore also available on REC and SETTINGS.

| Key | Android key code | While watching | During live pause |
|---|---|---|---|
| CH+ / CH− | `CHANNEL_UP` / `CHANNEL_DOWN` (also `PAGE_UP` / `PAGE_DOWN`) | next / previous channel | asks for confirmation: changing the channel ends the pause |
| 0–9 | `0`…`9` | channel number entry (up to 4 digits, confirmed after 1.5 s or with OK) | as above |
| OK | `DPAD_CENTER` / `ENTER` | menu (channel search screen when there are no channels) | with the info banner open: pause / resume |
| ▼ | `DPAD_DOWN` | open the info panel / go one level deeper | banner with the pause bar |
| ▲ | `DPAD_UP` | go one level back / close the info panel | |
| ▶ | `DPAD_RIGHT` | open the info panel; at level 3 move between buttons | seek forward 10 s (hold: 30 s) |
| ◀ | `DPAD_LEFT` | close the channel list; at level 3 move between buttons | seek back 10 s (hold: 30 s) |
| BACK | `BACK` | close the panel or list; stop channel identification; stop recording playback | |
| ⏯ | `MEDIA_PLAY_PAUSE` (also `MEDIA_PAUSE`) | start live pause; in recording playback: pause / resume | pause / resume |
| ⏹ | `MEDIA_STOP` | in recording playback: back to live | back to live |
| ⏪ / ⏩ | `MEDIA_REWIND` / `MEDIA_FAST_FORWARD` | | seek 30 s |
| MENU / SETTINGS / REC | `MENU` / `SETTINGS` / `MEDIA_RECORD` | menu | menu (with "Back to live TV") |
| GUIDE | `GUIDE` (also `TV_DATA_SERVICE`) | TV guide | TV guide (the pause is kept) |
| INFO | `INFO` | info panel, technical data level | |
| LAST CHANNEL | `LAST_CHANNEL` | previous channel | |
| AUDIO | `MEDIA_AUDIO_TRACK` | next audio track | |
| CC / SUBTITLES | `CAPTIONS` | next subtitle track | |
| Red | `PROG_RED` | HbbTV application if the channel has one; otherwise toggles the debug overlay | |
| Green / Yellow | `PROG_GREEN` / `PROG_YELLOW` | HbbTV application (if it uses the key) | |
| Blue | `PROG_BLUE` | HbbTV application if it uses the key; otherwise opens Diagnostics | |

Notes:

- While an HbbTV application is running, keys that belong to its key set go to the application.
  Arrows, OK and digits reach the HbbTV application only after it has been engaged with a colour
  key; until then they control the player. When the info panel or channel list is open, the
  arrows, OK and BACK stay with the player.
- In the TV guide, GUIDE closes the guide and MENU/REC open the player menu.

## 4. Watching TV

### Info panel

▼ opens the info panel and each further ▼ goes one level deeper; ▲ goes back; BACK closes it.
INFO opens the technical data level directly.

1. **Banner**: channel number and name, current programme with progress bar, next programme.
2. **Details**: genre, time remaining, full descriptions of the current and next programme.
3. **Audio and subtitles**: ◀ ▶ choose a button, OK opens the track list (including alternative
   commentary and audio description tracks where broadcast). This level also has a **Record** /
   **Stop recording** button where recording is supported.
4. **Technical data**: multiplex, frequency, service identifiers (SID/TSID/ONID), signal, video
   format, audio codec, HbbTV application.

The audio and subtitle language you pick is remembered and applied to other channels.

### Channel switching

CH+/CH− step through the visible channels (hidden channels are skipped), digits select a channel
by number, and LAST CHANNEL returns to the previous channel. During a recording the device's
single tuner is busy, so switching to a channel on another multiplex asks whether to stop the
recording first.

### Status messages

When there is no picture, the player shows the reason reported by the tuner input: tuning, weak
signal, buffering, audio only (radio), not connected, or encrypted (conditional access).

### Encrypted and HbbTV-only channels

Encrypted channels are marked and can be hidden all at once (see
[channel management](#channel-management)). Some broadcasters air "HbbTV-only" channels that
carry a still card on air while the actual video comes over the internet from the broadcaster's
HbbTV application. These channels are marked "HbbTV" in the lists, and the info banner says that
the picture comes from the internet. A channel is treated as HbbTV-only if the built-in service
catalogue says so, or if its PMT has video but no audio and its AIT has an AUTOSTART application.

## 5. The menu

The menu opens with MENU, SETTINGS or REC, and from the TV guide ("Menu" button). It is a
two-column panel: sections on the left, entries with their current value on the right.
▲▼ choose, ◀ ▶ switch column, OK confirms, BACK goes back and closes. Toggles change without
closing the menu, and screens or dialogs opened from the menu return to the same section.

Entries that the device does not support are hidden.

| Section | Entries |
|---|---|
| **Watching** | Channel list · TV guide · Audio track · Subtitles · Pause live TV / Back to live TV |
| **Recordings** | Record current programme / Stop recording · Recordings · Free space (with an estimate in hours) |
| **Channels** | Search for channels · Manage channels · Channel numbers (broadcaster LCN / in order) · Identify channels and get the guide · Detect channels again |
| **Settings** | Language (system / Polish / English) · HbbTV on/off ("Not available on this device" where unsupported) · Refresh HbbTV data for channel |
| **Diagnostics** | Signal information · Diagnostics · Debug overlay · Open current channel in *operator app* |

## 6. Channels: list, numbering, management

### Channel list

**Menu → Channels** (OK opens the menu) opens the channel list. It shows the channel number and name, the current programme with its
progress, and markers for radio (♪), encrypted and HbbTV-only channels. A long press on OK on a
channel renames it.

### Numbering

- **Broadcaster (LCN)** – the default: numbers as on a TV set (with gaps). Channels supplied
  through `TvContract` keep the number assigned by the tuner input; identified channels get their
  LCN from the built-in service catalogue; unidentified channels are numbered after the highest
  known number.
- **In order 1, 2, 3…** – TV channels numbered from 1 in list order, radio channels after them.

### Channel management

Menu → Channels → **Manage channels**:

- OK on a channel opens its options: **Move**, **Rename**, **Hide** / **Show**.
- Moving: ▲▼ move the channel, OK drops it.
- ▶ hides or shows a channel directly.
- **Encrypted: hidden / visible** hides or shows all encrypted channels at once.
- **Restore defaults** clears the custom order and hiding.
- Renaming with an empty name restores the broadcaster's name.
- BACK saves and exits.

Hidden channels disappear from the channel list, channel switching and the TV guide.

Channel settings (names, hiding, order) are tied to the multiplex and service number, not to
the tuner input's internal channel ID, so they survive a rescan. Channels new after a scan are
added at the end of a custom order.

## 7. Channel names and the TV guide

### Where the data comes from

- **Tuner inputs that share their channels** (`TvContract`): names, numbers and DVB identifiers
  come from the input. The guide comes from `TvContract.Programs` if the input provides it.
- **Google DTV stack**: the tuner input may store its channels as not searchable, which hides
  them from third-party apps. bapTV then discovers the channel IDs itself (see
  [Architecture](#13-architecture)) and identifies channels by analysing short recordings of the
  broadcast stream made through the TIF recording session:
  - **PMT** gives the service number (SID), whether the service has video and whether it is
    encrypted (CA descriptor);
  - **EIT** (present/following and schedule) gives about a week of guide data for **all**
    services of the multiplex, so one recording of 8–10 s per multiplex is enough;
  - **AIT** lists HbbTV applications.
  The recording does not contain the SDT, so the broadcaster's service names are not available
  from it. For Poland the app has a built-in service catalogue (national multiplexes) that maps
  the service number to a name and LCN. Elsewhere, discovered channels are named generically and
  can be renamed manually. A manually assigned name always takes precedence.

Guide data is stored per multiplex and merged with the previous data (schedule changes replace
old entries).

### When channels are identified

- In the background: a few seconds after a channel switch the current channel is briefly recorded,
  then unidentified channels of the same multiplex, without changing the picture. If the tuner
  does not allow a second recording session on the same multiplex, the app remembers this and only
  identifies watched channels.
- On demand: Menu → Channels → **Identify channels and get the guide** goes through all multiplexes
  with progress shown on screen (BACK stops it) and returns to the watched channel at the end.
- Automatically after a scan (see [Channel scan](#11-channel-scan)).

### Where the guide is shown

- The info banner: current programme with progress and description, next programme.
- The channel list: current programme and progress under each channel name.
- **TV guide** (GUIDE key or menu): channels on the left, programmes grouped by day on the right,
  the description of the selected programme at the bottom; "NOW" and progress mark the current
  programme. OK on a programme offers **Watch channel** and **Record** (or cancelling a scheduled
  recording). Programmes scheduled for recording are marked with ●. The top of the screen has
  **Menu** and **Diagnostics** buttons.

Genres are shown in the app language.

## 8. Live pause (timeshift)

Live pause uses the tuner input's own timeshift buffer through `TvView.timeShiftPause`,
`timeShiftResume` and `timeShiftSeekTo`.

- ⏯ (or Menu → Watching → **Pause live TV**) pauses live TV. A "⏸" marker is shown while paused.
- ⏯ again resumes playback behind live; the delay is shown in the info banner.
- ◀ ▶ seek by 10 s, or 30 s while the key is held. Repeated presses are collected and sent to the
  tuner together, so the target moves on the bar without overloading the input.
- ▼ shows the banner with the pause bar (buffer, position, delay). With the banner open, OK pauses
  or resumes.
- **Back to live TV** in the menu (or ⏹) returns to live; the channel is tuned again and the buffer
  is discarded.
- The pause survives opening the TV guide and other app screens.
- A running HbbTV application is hidden during live pause and comes back after returning to live.
- Changing the channel ends the pause; the app asks for confirmation first because the buffered
  part is lost.

Limitations:

- The app assumes a buffer of up to 30 minutes; the real size depends on the tuner input.
- Live pause is not offered on HbbTV-only channels or during recording playback.
- On devices where the tuner input does not support timeshift, pausing does nothing useful.

## 9. Recording

Recording uses the tuner input's TIF recording session (`TvRecordingClient`). It is available on
devices with the Google DTV stack, where the recording can be read back as a transport stream file.

### Manual recording

- Menu → Recordings → **Record current programme**, or the **Record** button at info panel level 3.
- If the guide has the current programme, the recording takes its title and stops 5 minutes after
  its scheduled end. Otherwise it records until stopped.
- **Stop recording** in the menu or the info panel stops it. A "● REC" marker is shown while
  recording.

### Scheduled recording

- In the TV guide, OK on a future programme → **Record**.
- The recording starts 1 minute before the programme and ends 5 minutes after it (programmes often
  run late).
- One minute before the start, the player shows a warning; when the player is on screen it then
  switches to the recorded channel.
- Schedules use system alarms that wake the device, and are restored when the app process starts
  (including after a device restart). If the app is in the background or the device is in standby,
  recording starts without the player.
- A scheduled recording can be cancelled from the TV guide.

### Recordings screen

Menu → Recordings → **Recordings** lists recordings with their duration and state (recording,
failed, scheduled) and the free space (about how many hours of HD fit).

- OK plays a recording. ⏯ pauses and resumes; BACK or ⏹ returns to live TV. When the recording
  ends, the player returns to live TV by itself.
- ▶ (or DEL or the red key) deletes a recording after confirmation.

### Limitations

- The device has one tuner: only one recording at a time, and while recording you can watch only
  channels of the same multiplex. Switching to another multiplex asks whether to stop the recording.
- Encrypted channels and HbbTV-only channels cannot be recorded (the result would be unusable).
- Recording does not start with less than 500 MB of free storage, and stops when free storage
  drops below that. HD recordings take several GB per hour.
- Recordings are stored where the tuner input writes them (shared storage); the app keeps its own
  list of recordings.
- Installing an app update is refused while a recording is in progress (debug builds, see
  [section 15](#15-debug-only-developer-tools)).

## 10. HbbTV

HbbTV applications are available on devices with the Google DTV stack (stream analysis is needed
to read the AIT). They can be turned off in Menu → Settings → **HbbTV**.

How it works:

1. When a channel is first watched, the app makes a short background recording, reads the AIT and
   the DVB identifiers (ONID from the EIT, TSID and SID from the PMT), and stores them
   (`HbbTvStore`). The data is refreshed weekly, or on demand with Menu → Settings → **Refresh HbbTV
   data for channel**.
2. The AUTOSTART application URL from the AIT is loaded in a transparent `WebView` over the TV
   picture.
3. `assets/hbbtv_shim.js` provides the OIPF APIs that HbbTV applications expect:
   `oipfApplicationManager` (application, key set, show/hide), `oipfConfiguration`,
   `oipfCapabilities`, the `video/broadcast` object (current channel, channel list, `setChannel`,
   stop), A/V objects (through HTML `<video>`) and the `VK_*` key constants. The page is laid out at
   1280 px width and scaled to the screen.
4. Keys in the application's key set are delivered to the page as `VK_*` key events; other keys are
   handled by the player, as on a TV set with HbbTV.

Details:

- The User-Agent contains `HbbTV/1.4.1` (HbbTV 2.0.1), so broadcasters serve their HTML5 video
  variants.
- The language and country given to HbbTV applications come from the app language and the system
  country.
- Many broadcasters show a "press red" prompt only for a short time after a channel switch; the
  red key reaches their application only while the application has it in its key set. Afterwards
  red returns to the player.
- The HbbTV overlay is hidden while the menu is open.
- HTTP images on HTTPS pages are allowed (as in TV browsers).

## 11. Channel scan

Menu → Channels → **Search for channels**. The screen offers the methods the device supports.

| Method | When available | How it works |
|---|---|---|
| **Scan** (default) | Google DTV stack | Starts a scan through the tuner input's installer service, which accepts scan parameters from other apps. Progress arrives as broadcast events. |
| **Scan (system permission)** | only when the app holds `INSTALL_TV_CHANNELS` (installed as a privileged app or signed with the platform key) | Starts the scan with the tuner input's install broadcast. |
| **System wizard** | the tuner input has a setup activity | Opens the input's own setup wizard (`TvInputInfo.createSetupIntent()`); come back to the app when it finishes. |
| **Scan in *operator app*** | an operator TV app is installed | Shows instructions and opens the operator app; channels found there also appear in bapTV. |

If the tuner does not allow other apps to scan at all, the screen says so and suggests scanning in
the TV settings.

### Scan options (Google DTV stack)

- **DVB-T2 mode** (also receives DVB-T).
- **Include the VHF band** – VHF band III, in addition to UHF.
- **Tuner LNA amplifier** – stored in the tuner settings and used by the next scan.

The frequency plan follows the country in the system settings:

| Country | Plan |
|---|---|
| Most countries (Europe, GE06) | UHF 474–850 MHz in 8 MHz steps; VHF 177.5–226.5 MHz in 7 MHz steps |
| Australia | UHF 529.5–690.5 MHz and VHF 177.5–226.5 MHz, 7 MHz steps |
| Colombia, Taiwan, Panama | UHF 473–695 MHz in 6 MHz steps |

During the scan the screen shows the tuner (frontend) state, progress, the number of channels found
and the scan events. If no frequency locked, it suggests checking the antenna, cable and direction
and trying the LNA amplifier.

### One-pass setup

On Google DTV stack devices **Scan** runs three stages without further steps:

1. the tuner input's scan,
2. discovery of the channel IDs in the player (when the input hides its channels),
3. identification: names, guide, HbbTV.

Before starting, the screen shows an estimate of the total time; during the run it shows the time
remaining. The estimate uses per-frequency, per-ID and per-channel times that are updated with an
average after each run (`SetupProgress`). Discovery ends once as many channels have been found as
the scan reported.

**Detect channels again** in the menu repeats stage 2 (and then identification) without a new scan.

## 12. Diagnostics

Menu → Diagnostics → **Diagnostics**, the blue key (when no HbbTV application uses it), or the
**Diagnostics** button in the TV guide.

The screen shows a full report:

- app and device information, detected device capabilities, network addresses, app permissions;
- DTV system packages and TV inputs;
- channels in `TvContract`, discovered channels, guide data;
- tuner provider tables, tuner file system access, audio state, signal statistics;
- the app event log (newest last) and the app process logcat.

Buttons:

- **Refresh** – collect the report again. CH+/CH− scroll the report.
- **Save to file** – saves the report as a text file to the `Download` folder. **Attach this file
  when reporting an issue.**
- **Clear log** – clears the app event log.

The app event log is written to a file in the app's private storage (rotated when it grows), so it
survives app restarts and crashes and is included in the report.

Other diagnostic tools:

- **Signal information** (menu) – shows the info banner with live signal values where the tuner
  provides them.
- **Debug overlay** (menu, or the red key when there is no HbbTV application) – tuner state,
  tracks, signal and the last player event drawn over the picture.

Debug builds additionally offer a local HTTP server; see
[Debug-only developer tools](#15-debug-only-developer-tools).

---

# Developer guide

## 13. Architecture

```
  bapTV (TvView, TvRecordingClient)              Hardware
       |  tune(inputId, channelUri)                 |
       v                                            v
  TIF tuner input (e.g. dtvinput)  ------------>  DVB tuner
       |
       v
  TvProvider / TvContract (channels, programmes)  [+ dtvprovider on the Google DTV stack]
```

- **Playback**: `TvView.tune()` on the public API, no special permissions.
- **Channel list**: from `TvContract` for the tuner input. On the Google DTV stack the input writes
  its channels with `searchable = 0`, which `TvProvider` exposes only to the owner and to apps with a
  system permission; an ordinary app gets an empty list. bapTV then **discovers channel IDs by
  probing**: channel `_id` values are autoincrement, so the app inserts and immediately deletes a
  temporary channel to learn the highest ID, then tunes candidate IDs downwards. The input's reaction
  classifies each ID: video available (TV), audio only (radio), tracks plus a conditional access
  refusal (encrypted), or an immediate unknown error (no such ID).
- **Stream analysis**: the Google DTV tuner input reports `canRecord = false` but has a working TIF
  recording session. A `TvRecordingClient` recording is written to the input's folder in shared
  storage, which the app can read (hence `targetSdk 29` with `requestLegacyExternalStorage` and the
  storage permission). The recording contains PAT, the service's PMT, AIT, EIT and the service's
  elementary streams, but no SDT or NIT. It can run in parallel with playback on the same multiplex;
  recording another multiplex fails with `RESOURCE_BUSY` (one tuner). Probe recordings are deleted
  after analysis.
- **Scanning**: the Google DTV tuner input's installer service is exported without a permission and
  accepts JSON scan parameters; progress arrives as an `INSTALL_EVENT` broadcast.

### Main classes (`app/src/main/java/com/bonsaponsi/baptv`)

| Class | Role |
|---|---|
| `App` | Application class: language, background services and recording alarms at process start |
| `MainActivity` | The player: `TvView`, channel switching, info panel, channel list, menu, key handling, channel discovery by probing, identification, recording and live pause wiring |
| `MenuPanel` | Two-column menu panel (sections and entries) |
| `TvDialog` | Dialogs in the menu style |
| `ChannelRepository` | Loads channels (from `TvContract` or discovered), applies identification, the service catalogue, custom names, order, hiding, numbering and duplicate removal; provides programmes |
| `ChannelStore` | Persistent channel settings keyed by multiplex and service number |
| `ServiceCatalog` | Built-in names and LCNs of Polish national terrestrial services by service ID |
| `EpgStore` | Guide data from EIT, stored per multiplex and merged |
| `TsProbe` | Short TIF recordings for stream analysis |
| `TsAnalyzer` | MPEG-TS parser: PAT, PMT, AIT, EIT, DVB text decoding (including ISO 6937) |
| `GuideActivity` | TV guide screen |
| `ChannelsActivity` | Channel management screen |
| `ScanActivity` | Channel search screen and scan methods |
| `SetupProgress` | Time estimates for the one-pass setup |
| `Region` | Country-dependent scan frequency plan and HbbTV language/country |
| `Dtv`, `TunerSettings` | Google DTV stack constants and helpers; tuner settings such as LNA |
| `DeviceCapabilities` | Detects which features the device supports |
| `OperatorApp` | Finds an installed operator TV app (opening the current channel, scanning, launcher shortcut) |
| `Recorder` | Recordings through `TvRecordingClient`, recording list, free space checks |
| `RecordingScheduler` | Scheduled recordings: alarms, warning, recording service |
| `RecordingsActivity` | Recordings list and playback start |
| `TimeShiftController`, `TimeShiftModel` | Live pause: tuner control and UI; time and buffer model (unit tested) |
| `HbbTvBrowser`, `HbbTvStore` | HbbTV WebView with the OIPF shim and key routing; stored AIT data per channel |
| `PlayerAudio` | Audio focus and volume handling |
| `SignalReader`, `SignalMonitor`, `FrequencyProbe` | Signal statistics, multiplex cycling, single-frequency checks |
| `AppLanguage` | In-app language selection (system / Polish / English) |
| `Diagnostics`, `DiagnosticsActivity` | Diagnostics report and screen |
| `DebugLog` | App event log in memory and in a rotating file |
| `StoredFile`, `BackgroundWorker` | Atomic file writes; background work that does not crash the process |
| `DebugHttpServer`, `DebugServerService`, `ServerAccess`, `PlayerRemote` | Debug-only HTTP server, its foreground service, access control, and player hooks used by it |
| `SelfUpdater`, `UpdateReceiver`, `InstallClickService` | Debug-only remote APK install, restart after update, and the accessibility service that confirms installs |

Resources: English strings in `res/values` (default) and Polish in `res/values-pl`. Assets:
`hbbtv_shim.js` (OIPF layer) and `signal.html` (debug server signal page).

### Permissions

| Permission | Purpose |
|---|---|
| `READ_EPG_DATA`, `READ_TV_LISTINGS` | read channels and programmes of tuner inputs |
| `WRITE_EPG_DATA` | insert and delete a temporary channel to find the highest channel ID (discovery) |
| `dtvprovider.permission.READ` / `WRITE` | tuner statistics; tuner settings such as LNA (Google DTV stack) |
| `INSTALL_TV_CHANNELS` | direct scan; granted only to privileged/platform-signed apps, otherwise unused |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` | read and delete the tuner input's recordings |
| `INTERNET` | HbbTV applications; debug server |
| `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED` | recording service; restoring alarms and the debug server after boot |
| `REQUEST_INSTALL_PACKAGES` | debug-only remote install |

### Fragile points

- Playback relies on the public TIF API and is stable.
- In-app scanning, channel discovery and stream analysis rely on behaviour of the Google DTV tuner
  input. A system update of that package could change it; failures show up in Diagnostics. The
  fallback is scanning with the system wizard or the operator app, and playback through the public
  API.
- If a tuner input marks its channels searchable, probing is not needed and channels come with
  their names directly from `TvContract`.

## 14. Emulator development

The `testtuner` module (`com.bonsaponsi.baptv.testtuner`) is a fake TV input (`TvInputService`)
for the Android TV emulator. Its setup activity writes four test channels to `TvContract` with DVB
identifiers (three from the Polish service catalogue, one outside it, to check that names and
numbers from `TvContract` are kept) and a two-day guide with a programme every 30 minutes. The
picture is a drawn test card with a "broadcast" clock; the input supports pause and seeking. It
exercises the generic path of the app (no Google DTV stack).

### Setup

1. In Android Studio create an Android TV device (e.g. *Television (1080p)*) with an Android TV
   API 30 x86 image. Avoid non-ASCII characters in the SDK and AVD paths; the emulator may fail with
   them.
2. Build and install both APKs (`tools/emu.sh install`, or `./gradlew :app:assembleDebug
   :testtuner:assembleDebug` and `adb install -r -g` for each).
3. Create the test channels: `tools/emu.sh setup` (or open the test tuner's setup).
4. Add the test tuner's channels once through the system Live Channels app, which marks them as
   visible to other apps: Live Channels → *Set up your sources* → *DVB-T test tuner* → *Done*.
5. Start bapTV: `tools/emu.sh start`.

### `tools/emu.sh`

A helper for working with the emulator from WSL when the Android SDK is installed on the Windows
side. It calls the Windows `adb.exe` from `platform-tools`; the SDK location is taken from
`ANDROID_SDK_WIN` or common default locations. When starting the emulator from WSL, set
`ANDROID_AVD_HOME` to the Windows AVD folder; from Android Studio use the ▶ button in Device Manager.

| Command | Action |
|---|---|
| `tools/emu.sh devices` | list adb devices |
| `tools/emu.sh install [-n]` | build and install the app and the test tuner (`-n`: skip the build); forwards port 8080 |
| `tools/emu.sh setup` | create the test tuner's channels and guide |
| `tools/emu.sh start` | start the player |
| `tools/emu.sh logs` | logcat of the app and the test tuner |
| `tools/emu.sh key NAME` | send a key, e.g. `DPAD_DOWN`, `MEDIA_PLAY_PAUSE`, `BACK` |
| `tools/emu.sh remote` | remote control in the terminal (see below) |
| `tools/emu.sh shot FILE.png` | emulator screenshot (taken by the emulator, so the TV picture is included) |
| `tools/emu.sh server` | debug builds: enable the debug server and save the access token to `.emu-token` |
| `tools/emu.sh http PATH [curl options]` | request to the debug server through the forwarded port, with the saved token |

### Emulator keys

Verified in the app log; the emulator window must have focus.

| Remote key | Keyboard in the emulator window | From the shell |
|---|---|---|
| ▲ ▼ ◀ ▶, OK | arrow keys, Enter | `tools/emu.sh key DPAD_UP` etc. |
| BACK | **Ctrl+Backspace** (Esc does not work) | `tools/emu.sh key BACK` |
| HOME | Ctrl+H | `tools/emu.sh key HOME` |
| 0–9 | digit keys | `tools/emu.sh key 5` |
| Menu | **Ctrl+M** (sends `MENU`) | `tools/emu.sh key MEDIA_RECORD` |
| CH+ / CH− | – (PageUp/PageDown do not arrive) | `tools/emu.sh key CHANNEL_UP` / `CHANNEL_DOWN` |
| ⏯ | – | `tools/emu.sh key MEDIA_PLAY_PAUSE` |
| GUIDE | – | `tools/emu.sh key GUIDE` |
| Colour keys | – | `tools/emu.sh key PROG_RED` / `PROG_GREEN` / `PROG_YELLOW` / `PROG_BLUE` |

The most convenient option is **`tools/emu.sh remote`**, a remote control in the terminal:
arrows, Enter (OK), Backspace (BACK), `+`/`-` (CH+/CH−), digits, `m` menu, `p` ⏯, `e` TV guide,
`r g y b` colour keys, `i` INFO, `h` HOME, `q` quit.

The emulator's on-screen remote ("…" next to the window → *Directional pad*) has arrows, OK, BACK
and HOME, but no media keys.

## 15. Debug-only developer tools

The following features exist **only in debug builds** (`./gradlew assembleDebug`). They are meant
for development on devices without adb access and are not part of release builds.

### Local HTTP server

The server listens on port **8080** in the local network. It runs in a foreground service; once
enabled it comes back by itself after an app update, a process restart and a device restart.

Enabling it:

- Diagnostics → **Share on network**. The screen then shows the address and the access token.
- On the emulator: `tools/emu.sh server`, which starts the app with the `enable_server` intent
  extra (accepted only by debug builds) and reads the token.

#### Security model

- **Host check**: requests are accepted only with a `Host` header that is an IP address or
  `localhost` (or no `Host` header, as from non-browser clients). This blocks DNS rebinding.
- **Origin check**: requests sent by pages from another origin (`Origin` header different from the
  server, or `Sec-Fetch-Site` other than `same-origin`/`none`) are rejected. This blocks web pages
  in a browser on the network, and HbbTV or ad pages in the app's own `WebView`, from calling the
  server.
- **Access token**: sensitive endpoints require a random token generated on the device. It can be
  passed as the `X-Tv-Token` header, as the `?token=` query parameter (then also stored in an
  `HttpOnly`, `SameSite=Strict` cookie so links on the page keep working), or as the `tvtoken`
  cookie. The token is shown on the Diagnostics screen while the server is enabled, and is returned
  by a successful `POST /install`. Tokens are compared in constant time.
- **Install check**: `POST /install` accepts only an APK with the same package name, the same
  signing key and a version code not lower than the installed one, so nobody on the network can
  install a different app through it.
- The server has no TLS and is intended for a trusted local network only.

Endpoints marked with 🔑 require the token.

| Endpoint | Action |
|---|---|
| `GET /` | report as HTML, with links |
| `GET /log` | live log in the browser |
| `GET /log.txt` | full event log; with `?since=N&boot=B` only new entries (headers `X-Log-Next`, `X-Log-Boot`; process restarts are detected) |
| `GET /logcat.txt` | logcat of the app process (`?lines=N`) |
| `GET /report.txt` | text report |
| `GET /status` | JSON: version, build time, PID, activity on screen, install permission state, last install |
| `GET /capabilities` | detected device capabilities as JSON |
| `GET /channels` | channel list: number, name, SID, current programme |
| `GET /epg?channel=ID` | guide of a channel |
| `GET /hbbtv` | HbbTV browser state and stored channel data |
| `POST /hbbtv/eval?js=…` 🔑 | run JavaScript in the HbbTV application page |
| `POST /key?name=RED` 🔑 | remote key (e.g. `RED`, `GREEN`, `OK`, `BACK`, `UP`, `0`–`9`, `CHUP`; or `?code=` with an Android key code), injected into the app window like a remote key press |
| `GET /screenshot.png` 🔑 | screenshot through the accessibility service (the TV picture comes out black) |
| `POST /open?screen=…` 🔑 | open a screen: `channels`, `guide`, `diagnostics`, `player`, `scan` |
| `POST /launch` 🔑 | start the player |
| `POST /restart` 🔑 | restart the app process |
| `POST /install` | upload an APK (request body = file); returns the token on success |
| `GET /timeshift`, `POST /timeshift?action=…&arg=…` 🔑 | live pause state (delay, buffer); actions `pause`, `resume`, `toggle`, `seek` (arg = ±ms), `panel`, `live` |
| `GET /recordings` 🔑 | recordings as JSON (`?format=html` – page with download links) |
| `POST /recordings?action=start\|stop\|play\|live\|pause\|resume\|delete\|unschedule&id=…` 🔑 | control recordings |
| `POST /recordings?action=schedule&channel=…&start=<ms>&end=<ms>&title=…` 🔑 | schedule a recording |
| `GET /recordings/<id>.ts` 🔑 | download a recording (streamed) |
| `POST /probe?channel=ID&secs=8` 🔑 | record a channel briefly and analyse the stream (PAT, PMT, AIT, EIT) |
| `GET /probe.ts` 🔑 | the last probe recording |
| `GET /signal` | antenna alignment page (see below) |
| `GET /signal.json` | current signal measurement and 2-minute history |
| `POST /signal/tune?freq=<MHz>` | tune to a multiplex |
| `POST /signal/cycle`, `POST /signal/stop` | cycle through multiplexes / stop |
| `POST /signal/probe?freq=<MHz>&std=t2\|t&repeat=1`, `POST /signal/probe/stop` | single-frequency check |
| `POST /signal/lna?on=1\|0` | set the tuner LNA amplifier |
| `POST /signal/rediscover` | detect channels again, then identify them |

#### Antenna alignment page (`/signal`)

Open `http://<device-ip>:8080/signal` on a phone next to the antenna:

- live RSSI, SNR and BER (a sample every 0.5 s) with trend and a 2-minute chart; the header colour
  reflects the quality; optional audible ticks;
- multiplex buttons that switch the player to a channel of that multiplex (starting the player if
  needed);
- multiplex cycling: each known multiplex in turn (2.5 s tuning + 5 s measurement) with a
  min/average/max table, then back to the original channel;
- raw tuner statistics columns, for calibrating units that depend on the tuner driver.

There is one tuner, so only one multiplex can be measured live at a time.

**Check frequency** scans a single frequency (DVB-T2 or DVB-T, optionally repeated every few
seconds while aiming the antenna). Without signal the tuner reports no values, so the result is
"locked / not locked"; after a lock it shows statistics and the number of services. When services
are found, **Add new channels** runs discovery and identification. Do not use this on multiplexes
that are already received, because their channels would get new IDs. Channels added this way are
"unmanaged" for the tuner input: a later full scan does not remove them and adds the same multiplex
again; bapTV shows each service once (the highest ID).

### Remote APK install

`POST /install` saves the uploaded APK, checks it (package, signature, version code) and installs it
with `PackageInstaller`. The tuner session is closed before installing, and the install is refused
while a recording is in progress.

Android 11 and older always ask the user to confirm an install, even a self-update. There are
therefore two modes:

- **With a remote**: the installer dialog appears on screen; press *Install*.
- **Without a remote**: the accessibility service **InstallClickService** confirms the dialog,
  including the Google Play Protect warning shown for new APKs signed with a debug key. It acts only
  within a few minutes after the server requested an install, and only on dialogs for this app.

One-time setup on the device: Diagnostics → **Share on network**, **Allow installing** (allow
installs from this app), and optionally **Automatic installation** (enable the accessibility
service). The state of both permissions is shown on the Diagnostics screen and in `/status`.

After an update the system stops the process. The `MY_PACKAGE_REPLACED` receiver starts it again,
the server comes back and the player starts by itself. Android 10+ blocks activity starts from the
background unless the app has an active accessibility service; without it, open the app with the
remote after an update.

Example from a computer in the same network:

```
curl -s http://<device-ip>:8080/status
curl -s --data-binary @app/build/outputs/apk/debug/app-debug.apk \
     -H 'Content-Type: application/vnd.android.package-archive' http://<device-ip>:8080/install
curl -s -H "X-Tv-Token: <token>" -X POST "http://<device-ip>:8080/key?name=OK"
```

## 16. Building and testing

### Requirements

- JDK 17 or newer (sources and bytecode target Java 17)
- Android SDK with platform 34; `compileSdk 34`, `minSdk 26`, `targetSdk 29`
- Gradle 8.11.1 (wrapper), Android Gradle Plugin 8.7.3, Kotlin 2.1.0
- No external libraries; unit tests use JUnit 4

`targetSdk 29` is deliberate: it is the highest target for which `requestLegacyExternalStorage`
still works on Android 11, which the app needs to read the tuner input's recordings. Lint's
`ExpiredTargetSdkVersion` check is disabled because it only applies to Google Play.

### Commands

```
export JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk   # or sdk.dir in local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk (+ testtuner debug APK)
./gradlew assembleRelease      # minified release build (R8 with proguard-rules.pro)
./gradlew test                 # JVM unit tests
./gradlew lint
```

To distribute your own release build, configure a signing config with your own key in
`app/build.gradle.kts`. Updates install over an existing installation only if they are signed with
the same key.

### Unit tests (`app/src/test`)

| Test | Covers |
|---|---|
| `ChannelRepositoryTest` | genre mapping, channel number ordering |
| `DebugLogTest` | event log |
| `EpgTest` | EIT parsing from a real recording, now/next, schedule merging, catalogue names |
| `HttpRequestTest` | debug server request parsing |
| `RegionTest` | frequency plans and language codes |
| `ServerAccessTest` | debug server host, origin and token checks |
| `SetupProgressTest` | setup time estimates |
| `StringsTest` | English and Polish string resources have the same keys, placeholders and genre lists |
| `TimeShiftModelTest` | live pause time and buffer model |
| `TsAnalyzerTest` | transport stream parsing |

Unit tests run on the JVM with `unitTests.isReturnDefaultValues = true`, so calls to
`android.util.Log` return default values.
