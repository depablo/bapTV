<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/brand/baptv-logo-on-dark.svg">
    <img alt="bapTV" src="docs/brand/baptv-logo-on-light.svg" width="320">
  </picture>
</p>

# bapTV

A standalone terrestrial TV (DVB-T/T2) player for Android TV.

bapTV plays live TV from the device's built-in tuner through the standard Android
TV Input Framework (TIF: `TvView`, `TvContract`, `TvRecordingClient`). It is written in Kotlin
with no external libraries.

## Features

- Live TV and radio from the device's tuner input, last channel remembered
- Channel list with broadcaster (LCN) or sequential numbering
- Channel management: reorder, hide (including all encrypted channels at once), rename
- TV guide (EPG) from `TvContract` and from the DVB EIT tables in the broadcast stream
- Info panel with now/next, programme details, audio and subtitle selection and technical data
- Audio track and subtitle selection, with the preferred language remembered
- Live pause (timeshift) with seeking
- Recording: manual, or scheduled from the TV guide; recordings list and playback
- HbbTV applications (red button) started from the channel's AIT
- In-app channel scan (DVB-T/T2, UHF with optional VHF) on devices with the Google DTV stack
- Identified channels can be published back to the system, with names and LCN numbers, for other TV apps
- Signal information (strength, SNR, BER) where the tuner reports it
- Diagnostics screen with a full report that you can save to a file or send to the developer
- English and Polish user interface

## Requirements and supported devices

- Android TV with Android 8.0 or newer (API 26+) and a TIF tuner input (`TvInputInfo.TYPE_TUNER`).
- **Basic features** (playback, channel list, info panel, track selection, guide from `TvContract`)
  work with any tuner input that exposes its channels to other apps.
- **Full feature set** (in-app scan, channel discovery, recording, live pause, HbbTV, stream
  analysis for channel names and EIT guide data) is available on Android TV set-top boxes that use
  the Google DTV stack (`com.google.android.tv.dtvinput`).

bapTV checks what the device supports at startup and hides menu entries that would not work.
The Diagnostics screen shows the detected capabilities.

## Installing

1. Download the latest APK from [GitHub Releases](https://github.com/depablo/bapTV/releases).
2. Sideload it onto the Android TV device. Common ways:
   - copy the APK to a USB drive or network share and open it with a file manager app on the TV, or
   - use a "send files to TV" style app, or
   - from a computer with developer options enabled on the TV: `adb install baptv.apk`.
3. Allow installing from unknown sources for the app you use to open the APK when Android asks.
4. Start **bapTV** from the launcher. On first start it asks for access to TV listings (channel list
   and guide) and then opens the channel search screen.

## Building from source

Requirements:

- JDK 17 or newer (the project compiles for Java 17)
- Android SDK with platform 34 (`compileSdk 34`); `minSdk 26`, `targetSdk 29`
- Gradle 8.11.1 is downloaded by the wrapper; Android Gradle Plugin 8.7.3, Kotlin 2.1.0

```
export JAVA_HOME=/path/to/jdk ANDROID_HOME=/path/to/android-sdk   # or set sdk.dir in local.properties
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (minified)
./gradlew test               # JVM unit tests
```

Release builds are signed only when a signing config is provided: point `baptv.signing` in
`local.properties` (or the `BAPTV_SIGNING` environment variable) to a properties file with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without it `assembleRelease` produces
an unsigned APK.

The `testtuner` module builds a fake TV input for the Android TV emulator, so the app can be
developed without a tuner. See [DOCUMENTATION.md](DOCUMENTATION.md) for details.

## Remote control

| Key | Action |
|---|---|
| CH+ / CH− | Next / previous channel |
| 0–9 | Select a channel by number |
| OK | Menu (channel list: Menu → Channels) |
| ▼ / ▲ | Open / step through / close the info panel |
| ⏯ (Play/Pause) | Pause live TV; while paused: pause / resume |
| ◀ ▶ (while paused) | Seek 10 s (hold: 30 s) |
| MENU, SETTINGS or REC | Menu |
| GUIDE | TV guide |
| Colour keys | HbbTV application (when the channel has one) |
| Blue | Diagnostics (when no HbbTV app uses it) |
| BACK | Close panel / list, stop recording playback |

The full key map is in [DOCUMENTATION.md](DOCUMENTATION.md#remote-control).

## Documentation

[DOCUMENTATION.md](DOCUMENTATION.md) contains the user guide (menus, recording, live pause, HbbTV,
channel scan, diagnostics) and the developer guide (architecture, emulator setup, debug tools,
building and testing).

## Reporting issues

The easiest way is **Send report to the developer** in the menu (Diagnostics section) or **Send report**
on the Diagnostics screen. The app also offers it after a failed channel scan, when no tuner is found,
and after a crash. Nothing is sent without your confirmation. The report goes to the bapTV report
service (`reports.bonsaponsi.com`, source in [`server/reports`](server/reports)): a short summary
(app version, device model, Android version, error) becomes a public GitHub issue, while the full
report (log, channel list) stays private. After sending, the TV shows a QR code of the issue so you
can follow it or add details from your phone.

Questions and anything that does not fit an issue: **bap@bonsaponsi.com** (also shown in the app under
menu → Settings → About bapTV).

You can also open an issue yourself at <https://github.com/depablo/bapTV/issues> and attach the
report saved with **Save to file** on the Diagnostics screen (it goes to the device's `Download` folder).

## License

See [LICENSE](LICENSE).
