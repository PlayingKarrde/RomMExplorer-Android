# RomM Explorer for Android

A native Android client for a [RomM](https://github.com/rommapp/romm) server. You browse your collection with cover art, install games into a ROM folder on the device, and uninstall them later. It is the Android sibling of the desktop app [RomMExplorer](https://github.com/PlayingKarrde/RomMExplorer) (Rust/egui), and the two apps share **no code**, only the on-disk library format described below.

**Primary target: Android gaming handhelds** (Retroid, AYN Odin/Thor, Anbernic and similar). These are landscape, 4–7" screens with a built-in controller, and the controller is the main input. **Secondary target: phones**, in portrait and landscape, with touch. Every screen has to work fully with a controller alone and with touch alone.

## Stack

- Kotlin, Jetpack Compose (Material 3), single activity.
- `minSdk 26` (Android 8). `targetSdk` must be whatever the Play Store currently requires.
- Gradle with Kotlin DSL and a version catalog (`gradle/libs.versions.toml`).
- Networking: OkHttp + kotlinx.serialization (or Retrofit on top, if it stays simple).
- Images: Coil 3, using the shared OkHttp client.
- Async: coroutines + Flow. ViewModels per screen; no RxJava.
- Background downloads: WorkManager with foreground workers (`setForeground`), so progress shows in a notification and downloads survive the app going to the background.
- Settings: DataStore. Secrets (password, API token) are encrypted with an Android Keystore key, never stored in plain text.
- DI: keep it manual (a small `AppContainer`) unless the app grows enough to need Hilt.

Prefer these libraries over writing your own, and don't add new dependencies without a clear reason.

## Commands

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew installDebug           # install on a connected device / emulator
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented tests (needs a device)
./gradlew lint
```

After a change that touches UI or input, install it on a device or emulator and check it with **both** a controller (or the emulator's D-pad keys) and touch.

## Features (v1)

1. **Connect:** server URL, then username + password **or** a RomM client API token (`rmm_…`). The token takes priority when both are set. Optional "Allow self-signed HTTPS certificates".
2. **Pick a library folder** with the system folder picker (Storage Access Framework).
3. **Browse:** a platform list/rail plus a cover-art grid, with a compact list as an alternative. Filter by platform and installed / not installed. Sort by name, platform, size, release date or recently installed.
4. **Search** across title, file name and platform.
5. **Details:** large cover, summary, genres, regions, languages, release date, developer, rating, file name and size. Install / uninstall from here.
6. **Downloads:** two at a time, the rest queued. Progress, speed and ETA, with cancel and retry. "Install all" for the current platform view.
7. **Library tools:** detect existing files (adopt games already in the folder), verify installed (forget entries whose files are gone), reorganize folders (when the naming scheme changes).

## Library format: must stay compatible with the desktop app

A library folder on an SD card or shared drive must work in **both** apps. Treat this section as a contract. Port the desktop rules exactly, and cover them with unit tests that use fixtures from the desktop repo.

### Layout

```
<library root>/
  .romm-explorer.json            ← manifest
  snes/Super Mario World (USA).sfc
  psx/Final Fantasy VII (USA)/   ← multi-file game, extracted
  psx/Some Game (USA).zip        ← multi-file game, extraction turned off
```

### Platform folder name

The setting `folderNaming` is one of `FsSlug` (default), `Slug` or `DisplayName`. Use the first non-blank value from the list for that setting, falling back to `"unknown"`:

| Setting | Order tried |
|---|---|
| `FsSlug` | `platform_fs_slug`, `platform_slug`, display label |
| `Slug` | `platform_slug`, `platform_fs_slug`, display label |
| `DisplayName` | display label, `platform_fs_slug`, `platform_slug` |

The display label is the first non-blank of `platform_display_name`, `platform_custom_name`, `platform_name`, `platform_slug`.

### Sanitising a path component (platform folder and file name)

1. Replace each of `< > : " / \ | ? *` and every control character with `_`.
2. Trim whitespace, then strip trailing `.` and space characters.
3. If the result is empty or `..`, use `_`.

Example: `Sega Mega Drive/Genesis` becomes `Sega Mega Drive_Genesis`.

### ROM file name

`fs_name` from the API, or `rom-<id>` if it's empty, then sanitised.

### Install procedure

- **Single file:** download to `<platform>/<name>.part`, then rename to `<platform>/<name>` (replacing any existing file).
- **Multi-file** (`has_multiple_files` is true, or the legacy `multi` field): the server sends a zip. Download to `<platform>/.<name>.zip.part`, then either:
  - **extract** (the default, setting `extractMultiFile`) into `<platform>/<name>/`, then delete the zip. If the archive's only top-level entry is a folder with the same name as the target, flatten it so you don't get `Game/Game/...`. Reject entries that would escape the target (zip-slip protection). Or:
  - keep it as `<platform>/<name>.zip`.
- Record the size after install: the file size, or the total size of the folder.
- A failed or cancelled install leaves nothing behind (no `.part` files, no half-extracted folder).

### Manifest (`.romm-explorer.json`)

```json
{
  "version": 1,
  "roms": {
    "1234": {
      "rom_id": 1234,
      "name": "Super Mario World",
      "platform_id": 7,
      "platform_slug": "snes",
      "platform_fs_slug": "snes",
      "platform_name": "Super Nintendo Entertainment System",
      "rel_path": "snes/Super Mario World (USA).sfc",
      "size_bytes": 524288,
      "installed_at": 1730937600
    }
  }
}
```

- `roms` is keyed by the ROM id **as a string**. `installed_at` is unix seconds.
- `platform_slug`, `platform_fs_slug` and `platform_name` may be missing in older manifests; default them to `""`.
- **`rel_path` may use either `/` or `\` as the separator.** The Windows desktop build writes backslashes. Accept both when reading; always write `/`.
- Pretty-printed JSON. Write to `.romm-explorer.json.tmp`, then replace the real file, so a crash never leaves a corrupt manifest.
- Keep any fields you don't recognise when rewriting the file. Don't add fields the desktop app would need to understand.
- If the manifest can't be parsed, show an error. Never silently overwrite it with an empty one.

### Uninstall

Delete the file or folder at `rel_path`, remove the platform folder if it's now empty (never the library root), and drop the entry. Refuse to delete when `rel_path` is empty or points outside the library root.

### Detect existing files

For each server ROM that isn't installed, look for `<platform folder>/<name>`, plus `<platform folder>/<name>.zip` for multi-file ROMs. Add any match to the manifest.

### Reorganize

For each installed entry, compute the platform folder under the new naming setting from the entry's stored `platform_*` fields. Move it unless the destination already exists or the source is missing, and count moved and skipped entries.

## Storage

- Use the Storage Access Framework: `ACTION_OPEN_DOCUMENT_TREE`, and persist the URI permission with `takePersistableUriPermission`. Don't request `MANAGE_EXTERNAL_STORAGE`.
- Do all file operations through one `LibraryStorage` interface (create dir, open for write, rename, delete recursively, list, size, exists), backed by `DocumentsContract`/`DocumentFile`. This keeps the library logic testable on the JVM with a `java.io.File` implementation.
- `DocumentFile.findFile` and `listFiles` are slow (each is a content-provider query). Cache directory listings for the duration of an operation, and use `DocumentsContract` child queries directly for scans like "detect existing files".
- Covers and other caches go in the app's own cache directory, not the library.

## RomM API

The base URL is user-entered: trim it, drop trailing `/`, and prefix `http://` if no scheme is given. All endpoints live under `<base>/api/`.

| Call | Notes |
|---|---|
| `GET users/me` | Verifies login; read `username`. With no credentials, call `platforms` instead. |
| `GET platforms` | Either a JSON array or `{ "items": [...] }`. |
| `GET roms?limit=500&offset=N&order_by=name&order_dir=asc&with_rom_id_index=false&with_char_index=false&with_filter_values=false` | Paginated `{ "items": [...], "total": N }` on new servers, a plain array on old ones. Keep fetching until there are no more pages, fewer than `limit` items come back, or `offset >= total`. Show results as pages arrive. |
| `GET roms/{id}/content/{fs_name}` | Streams the file. Multi-file ROMs arrive as a zip. URL-encode `fs_name` as a path segment. Use `Content-Length` for progress, falling back to `fs_size_bytes`. |

**Auth:** HTTP Basic for username/password, `Authorization: Bearer <token>` for an API token. On 401/403, show "Authentication failed, check your username/password or API token". On other errors, show the status plus the first ~200 characters of the body.

**Parse leniently:** RomM changes between versions. Ignore unknown fields, treat `null` as missing, and skip any single item that fails to parse rather than failing the whole list. ROM fields used: `id`, `name`, `fs_name`, `fs_name_no_ext`, `fs_size_bytes`, `platform_id`, `platform_slug`, `platform_fs_slug`, `platform_display_name`, `platform_custom_name`, `platform_name`, `summary`, `regions`, `languages`, `revision`, `path_cover_small`, `path_cover_large`, `url_cover`, `has_multiple_files`, `multi`, `metadatum`, `igdb_metadata`.

- **Title:** the first non-blank of `name`, `fs_name_no_ext`, `fs_name`.
- **Genres, developers:** try `metadatum` first, then `igdb_metadata`. A list may hold strings or objects with a `name`. Developers fall back to `companies`.
- **Release date:** `first_release_date`, number or string. Values above 100,000,000,000 are milliseconds; otherwise seconds; `0` means unknown.
- **Rating:** `metadatum.average_rating` or `igdb_metadata.total_rating`. Values ≤ 10 are multiplied by 10, giving 0–100.
- **Cover URL:** prefer `path_cover_small` for the grid and `path_cover_large` for details, each falling back to the other.
  - If it's an absolute `http(s)` URL, use it as is.
  - If it starts with `/`, use `base + path`.
  - If it starts with `assets/`, use `base + "/" + path`.
  - Otherwise use `base + "/assets/romm/resources/" + path`.
  - With no path, use `url_cover`, adding `https:` if it starts with `//`.
- **Web link:** `<base>/rom/<id>`.

**Security:** send credentials **only** to requests whose URL starts with the configured server base. Cover URLs can point at external hosts such as IGDB; send those without auth. "Allow self-signed certificates" applies only to the configured server host, never to every host.

## Input and navigation

Design controller-first, then make sure touch works just as well.

**Controller mapping** (Android key codes):

| Button | Action |
|---|---|
| D-pad / left stick | Move focus |
| A (`BUTTON_A`, also `DPAD_CENTER` / `ENTER`) | Open / confirm |
| B (`BUTTON_B`, also `BACK`) | Back / close |
| X | Install or uninstall the focused game |
| Y | Search |
| L1 / R1 | Previous / next platform |
| L2 / R2 | Page up / page down in the grid |
| Start | Downloads / menu |
| Select | Sort and filter options |

- Add a "Swap A/B" setting for handhelds that use Nintendo-style layouts.
- **Focus must always be visible** when the user isn't touching the screen. Draw a clear focus ring or scale effect on cards, buttons and list rows: Compose doesn't do this for you outside Android TV.
- Focus must never get lost or land on something invisible. Restore focus when returning to a screen (for example with `focusRestorer`), and give each new screen a sensible initial focus.
- Grids scroll to keep the focused card in view. Holding a direction should repeat smoothly.
- Never rely on hover, double-tap, long-press or a keyboard shortcut as the **only** way to reach an action. On touch: tap opens details, long-press opens a context menu, and install/uninstall is always visible in details.
- Text fields (search, login) must work with the on-screen keyboard, and you must be able to leave them with B/back.

**Layout:**
- Handheld/landscape: platform rail on the left, cover grid filling the rest, details as a side pane or a full screen.
- Phone portrait: platform chips or a dropdown at the top, a 2–3 column grid, details as a full screen.
- Use window size classes, not device checks. Make tap targets at least 48dp and keep text readable on small screens at arm's length.
- Dark theme by default, matching the desktop app's palette: background `#0C0E14`, surface `#1A1E2B`, accent `#7C5CFF`, secondary accent `#38BDF8`, success `#22C55E`, danger `#EF4444`, text `#E8EAF2`.

## Downloads

- One foreground WorkManager worker per download (or a single queue worker), at most two running at once. Show progress in the notification, with a cancel action.
- Stream in chunks to the `.part` file, update progress at most a few times per second, and check for cancellation between chunks.
- The UI observes download state through a Flow from a single repository; the worker never touches the UI.
- After success, write the manifest entry. On failure or cancel, clean up as described in "Install procedure".
- Keep the screen awake while downloads run in the foreground (`FLAG_KEEP_SCREEN_ON`), optionally behind a setting.

## Testing

- **JVM unit tests** (the most important ones): folder naming, sanitising, manifest read/write (including backslash `rel_path`s and missing fields), install/uninstall/detect/reorganize against a `java.io.File`-backed `LibraryStorage` in a temp folder, zip extraction including flattening and zip-slip, and the API parsing rules above.
- **API tests:** OkHttp `MockWebServer` with JSON captured from a real RomM server: both paginated and array responses, and null-heavy ROM items.
- **Real-server smoke test:** opt-in only. Read the server URL and credentials from `local.properties` (git-ignored), never from committed files. Download the smallest single-file ROM into a temp folder and remove it again.
- **UI:** Compose UI tests for the main flows, including D-pad navigation (send key events) as well as clicks.

## Conventions

- Package layout by feature (`connect/`, `browse/`, `details/`, `downloads/`, `settings/`), with `data/` (API, library, storage, settings) kept free of Android UI code.
- Never log passwords, tokens or `Authorization` headers.
- Error messages tell the user what happened and what to do next, in plain language.
- Keep the README in sync with features and setup steps.
