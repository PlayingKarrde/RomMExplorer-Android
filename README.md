# RomM Explorer for Android

A native Android client for a [RomM](https://github.com/rommapp/romm) server. Browse your collection with cover art, install games into a ROM folder on the device, and uninstall them later. Built for Android gaming handhelds (Retroid, AYN Odin/Thor, Anbernic…) with full controller support, and works on phones with touch.

It's the Android sibling of the desktop [RomMExplorer](https://github.com/PlayingKarrde/RomMExplorer). The two share no code, but they use the **same library format**, so a library folder on an SD card works in both.

## Install

Download the latest `RomMExplorer-x.y.z.apk` from [Releases](https://github.com/PlayingKarrde/RomMExplorer-Android/releases) and open it on your device (allow installing from your browser or file manager when Android asks). Android 8.0 or newer.

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

## Releasing

Releases are built and published by GitHub Actions ([.github/workflows/release.yml](.github/workflows/release.yml)):

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and commit.
2. Tag and push: `git tag v<versionName> && git push origin v<versionName>`.

The workflow runs the unit tests, builds a signed release APK and attaches it to a new GitHub release. It needs these repository secrets: `RELEASE_KEYSTORE_BASE64` (the keystore, base64-encoded), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

For a signed local build (`./gradlew assembleRelease`), put the same values in `local.properties` as `release.keystore.path`, `release.keystore.password`, `release.key.alias` and `release.key.password`, or set the `RELEASE_*` environment variables. Keep a backup of the keystore: updates must be signed with the same key.

## Library format

See [CLAUDE.md](CLAUDE.md#library-format-must-stay-compatible-with-the-desktop-app) for the full contract: platform folder naming, file name sanitising, install procedure and the `.romm-explorer.json` manifest.
