# RomM Explorer for Android

A native Android client for a [RomM](https://github.com/rommapp/romm) server. Browse your collection with cover art, install games into a ROM folder on the device, and uninstall them later. Built for Android gaming handhelds (Retroid, AYN Odin/Thor, Anbernic…) with full controller support, and works on phones with touch.

It's the Android sibling of the desktop [RomMExplorer](https://github.com/PlayingKarrde/RomMExplorer). The two share no code, but they use the **same library format**, so a library folder on an SD card works in both.

## Features

- Sign in with username/password or a RomM API token (`rmm_…`, takes priority). Optional "allow self-signed HTTPS certificates", scoped to your server only.
- Pick any folder as your library (Storage Access Framework, no "all files" permission).
- Platform rail (handheld/landscape) or platform chips (phone portrait), cover grid or compact list.
- Filter by platform and installed / not installed; sort by name, platform, size, release date or recently installed. Search across title, file name and platform.
- Details page with cover, summary, genres, regions, languages, release date, developer, rating, file and size.
- Background downloads (two at a time, the rest queued) with progress, speed and ETA in the app and in a notification. Cancel, retry, and "Install all" for a platform.
- Multi-file games are extracted into a folder (or kept as `.zip`, your choice).
- Library tools: detect existing files, verify installed, reorganize folders after changing the naming scheme.

## Controls

| Button | Action |
|---|---|
| D-pad / stick | Move focus |
| A | Open / confirm |
| B | Back |
| X | Install / uninstall the focused game |
| Y | Search |
| L1 / R1 | Previous / next platform |
| L2 / R2 | Page up / down |
| Start | Downloads |
| Select | Sort and filter |

"Swap A and B" in Settings flips confirm/back for Nintendo-style layouts. On touch: tap opens details, long-press opens a menu, and install/uninstall is always on the details page.

## Building

Requirements: JDK 17 or newer and the Android SDK with Platform 36. Create `local.properties` with `sdk.dir=...` (or set `ANDROID_HOME`).

```bash
./gradlew assembleDebug          # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected device
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew lint
```

## Library format

See [CLAUDE.md](CLAUDE.md#library-format-must-stay-compatible-with-the-desktop-app) for the full contract: platform folder naming, file name sanitising, install procedure and the `.romm-explorer.json` manifest.
