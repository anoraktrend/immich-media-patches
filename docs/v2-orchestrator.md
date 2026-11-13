# v2 — the Immich app as orchestrator

Design and work plan for the next version. v1 is shipped and verified; see the
[README](../README.md) for it, including the trap table.

## Status of v1 (shipped)

| App | Picker | Preview | Send |
| --- | --- | --- | --- |
| `com.discord` 351.11 | Immich timeline | thumbnails (~13 KB/cell) | message, full-resolution original |
| `com.tumblr` 47.0.0.108 | Immich timeline | renders in composer | photo post + messenger image |

Config is baked into each APK at patch time (`immichBaseUrl` / `immichApiKey` as manifest
`<meta-data>`). Every patched app carries credentials and its own HTTP client.

## Requirements change (this revision)

- **Removed:** "Immich-only gallery; never merge device-local media." v2 must present the
  **full gallery** — local (on-device) and remote (server) assets interleaved exactly as
  the Immich app's own timeline shows them — and the orchestrator is what provides it.
- **Immich itself is built from source**, not Morphe-patched. The bridge is first-party
  Kotlin in the app: no Flutter unpick, no dex injection into a Flutter host, full control
  of the manifest (custom signature permission on our own provider).
- **Unchanged:** consumers keep a thin local provider and `_data` staging. An orchestrator
  cannot hand another app a real path into its sandbox — the v1 traps are all about `_data`
  needing to be a real file path, and that requirement does not move.

## Goals for v2

1. **Configure without rebuilding.** Point a published `.mpp` at a server through the app.
2. **Auto-discover from the Immich app.** A signed-in Immich mobile app is enough.
3. **One component reaches out.** Consumers ask the Immich app for bytes; credentials live
   in exactly one place.
4. **Gallery parity.** The picker shows what the Immich app shows, including local assets
   that are not backed up yet.

## Verified facts

### Credentials and session (device-verified)

| Fact | Evidence |
| --- | --- |
| The app writes the server endpoint and session token into **plaintext** `SharedPreferences` for its home-screen widget | `HomeWidgetPreferences.xml`: `widget_server_url`, `widget_auth_token`, `widget_custom_headers` |
| Keys are Dart-side constants written by the widget service | `mobile/lib/constants/constants.dart`, `mobile/lib/services/widget.service.dart` (`writeCredentials`/`clearCredentials`) |
| The token is the **live session token**, tracks auth state, blanked on logout | `writeCredentials` called from `mobile/lib/providers/auth.provider.dart`; `clearCredentials` clears the same keys |
| The Android host already ships the reader | `widget/ImmichAPI.kt` → `getServerConfig(context)`; also `buildRequestURL(endpoint, params)` |
| `?sessionKey=<token>` works | `GET /api/server/about?sessionKey=…` → 200 on v3.4.1 |
| Bearer also works; `?apiKey=` does not | `Authorization: Bearer …` → 200; `?apiKey=…` → 401 |
| `flutter_secure_storage` is not a barrier to a same-UID reader | its AES key in `FlutterSecureKeyStorage.xml` decodes to a known constant string |
| The Flutter layer is irrelevant to the bridge | all of the above lives in the Android host + `SharedPreferences` |

Consequence: **read per request** (never cache) gives a token that tracks the app,
including logout. No refresh logic of our own.

### The full gallery (source-verified at tag v3.4.1, executed against the device DB)

- The app's timeline is two drift **named queries** — `mergedAsset` and `mergedBucket` —
  in `mobile/lib/data/db/main/query/merged_asset.drift`. They are *not* SQL views: the
  database contains no `merged_asset` object (`sqlite_master` checked on the device DB);
  drift executes the SQL text with bound parameters.
- Runtime database: `app_flutter/immich.sqlite` (+`-wal`/`-shm`), WAL mode,
  `PRAGMA user_version = 35` on the test device.
- `mergedAsset` semantics (quoted from the file):
  - **remote rows**: not deleted, `visibility = 0` (timeline), owner in `:user_ids` (self
    plus partners with `in_timeline`), `group_date` set, stacks collapsed to the primary
    asset;
  - **local-only rows**: local assets (MediaStore) with **no remote asset of the same
    checksum**, and only those in an album with `backup_selection = 0` and not excluded
    (`= 2`);
  - **dedup**: a backed-up local asset appears exactly once, as its remote row — the
    checksum join also folds the `local_id` into that row;
  - **ordering**: `group_date DESC, created_at DESC`; paging appends
    `LIMIT <count> OFFSET <offset>`; buckets are day (`group_by = 0`) or month (`= 1`).
- Evidence: the SQL extracted from the tag was executed against the database pulled from
  the test device — 13,616 rows, all remote, because all 56 local assets are already
  checksum-matched (backed up). Local-only rows surface as soon as an unbacked asset
  exists, which is exactly when a user wants to send it.
- Consequence: **the bridge does not re-implement merging.** It ships the same SQL text
  (frozen with the app build — the bridge is compiled into the same binary), binds
  `:user_ids` from `auth_user_entity` / `partner_entity`, and reads the app's database
  read-only.

### Build from source (verified locally)

Tag `v3.4.1` pins (all now installed on this machine):

| Tool | Pin | Local state |
| --- | --- | --- |
| Flutter | `3.47.2` (exact, in `pubspec.yaml` and `mobile/mise.toml`) | fvm 4.3.2 → `~/fvm/versions/3.47.2` (Dart 3.13.2) |
| Rust | `1.99.1` (native crate `rust-toolchain.toml`), `1.98.0` (mobile mise pin) | rustup has both, with the Android targets (arm64/armv7/x64) |
| Node | `24.22.1` | mise 2026.11.5 installed it |
| openapi-generator-cli | `2.42.0` | mise installed it (generator jar pinned in `open-api/openapitools.json`) |
| Java | `21` | system JDK 21.0.12 |
| Android SDK | — | `/opt/android-sdk`, NDK `28.2.13676358` |

- **Checkout:** sparse clone of the tag. Required sparse paths:
  `mobile native i18n open-api .mise .github`. `.mise` is mandatory (dependency
  sidecars) — without it npm-tool installs fail with "run `mise lock`". Monorepo warnings
  about config roots that are not checked out are benign.
- **CI's canonical steps** (`.github/workflows/build-mobile.yml`):
  `mise //mobile:install:ci` → `mise //mobile:codegen` → `flutter build apk --release`.
  Locally the umbrella `codegen` task needs the workarounds recorded under "Operational
  notes" — use `./build-immich.sh`, which encodes the verified order.
- The Dart OpenAPI client is generated from the **checked-in spec**
  (`open-api/immich-openapi-specs.json`); no server build involved.
- **Native core:** `native/native_core/hook/build.dart` (via `native_toolchain_rust`)
  builds the Rust crate per ABI during `flutter build` and rustup-adds the target itself.
  Comment in the hook worth remembering: *mise sets `RUSTUP_TOOLCHAIN`, which makes rustup
  ignore `rust-toolchain.toml` targets, so the hook adds what the build needs.*
- **Signing:** `mobile/android/key.jks` plus `ALIAS` / `ANDROID_KEY_PASSWORD` /
  `ANDROID_STORE_PASSWORD` env (or `key.properties`). Without a keystore the release build
  silently falls back to debug signing — never leave that to chance for v2 builds.

### Signing and the signature permission (verified from the patcher)

- Morphe signs patched APKs with a built-in signer ("Morphe") by default; the v1 installs
  on the device carry its certificate (SHA-256 `6B:9B:2A:1A:C3:92:91:AA:…`).
- The patcher accepts explicit material: `--keystore`, `--keystore-entry-alias`,
  `--keystore-entry-password`, `--keystore-password`.
- **v2 plan (implemented):** one project-owned keystore signs **both** the custom Immich
  build (Gradle, via `key.properties` written by `build-immich.sh`) and the patched
  consumers (via those flags in `patch.sh`). The `signature`-level permission then grants
  between exactly these apps, nothing else. v1 consumers get re-patched anyway for v2, so
  switching keys costs nothing extra.
- The keystore exists: `~/.config/immich-bridge/immich-bridge.keystore` (PKCS12, alias
  `immich-bridge`, 4096-bit RSA); its password is in the gitignored `.env`
  (`KEYSTORE_PATH` / `KEYSTORE_ALIAS` / `KEYSTORE_PASSWORD`). Losing it costs an
  uninstall/restore cycle on every app — keep a backup copy.

## Where the Immich modifications live (the fork)

The modifications are kept as a **fork**: `anoraktrend/immich-android-bridge` (a true
GitHub fork of `immich-app/immich`, public like upstream) with a working branch
**`android-bridge`** cut from release tag `v3.4.1`.

- `origin` = the fork, `upstream` = `immich-app/immich` (blob-filtered) for rebases.
- Upgrades: fetch the new release tag from `upstream`, rebase `android-bridge`, re-copy
  `merged_asset.drift` and diff it, rebuild.
- The local checkout `/home/lucy/media/Android/immich-app` is a sparse copy of the same
  repo and doubles as the build tree.
- Licensing falls out of the fork: the modified app stays AGPL-3.0 with its source, and
  nothing AGPL needs to live in this Unlicense repository.

## Architecture

```
Immich app (custom build)                     Discord / Tumblr (patched)
  bridge provider                               thin local provider
    config   <- widget prefs (live session)       proxy rows + stage _data
    timeline <- immich.sqlite, mergedAsset SQL    (existing code; new upstream)
    media    <- server API (remote rows)
              + MediaStore (local rows)
        ^
        | signature-level permission; same keystore both sides
        +-- patched consumers and this build are mutually trusted, nothing else
```

## The bridge (contract draft)

- Authority `app.alextran.immich.bridge`, guarded by
  `app.alextran.immich.permission.BRIDGE` (`protectionLevel="signature"`), declared and
  required by the provider. Read-only.
- `config` → server endpoint + session key via `ImmichAPI.getServerConfig(context)`, or a
  clean "not signed in" signal (blanked prefs).
- `timeline?count&offset` → rows exactly as `mergedAsset` yields them: kind (remote /
  local-only), name, type, created/updated, width/height, duration, checksum, favorite,
  owner; remote rows also carry `thumb_hash`.
- `asset/<key>/thumb` → preview. Remote rows: `/assets/{id}/thumbnail` (WebP — report
  `image/webp` exactly as v1 learned to). Local rows: MediaStore thumbnail.
- `asset/<key>/original` → bytes. Remote rows: `/assets/{id}/original`. Local rows: the
  local file streamed. Sizes: remote via cached `HEAD` (v1 behaviour), local from
  MediaStore.
- Keys: `r/<remoteId>` and `l/<mediaStoreId>`, stable; consumers keep mapping to their own
  numeric ids, so the consumer side never changes shape.
- Degradation: if the database cannot be read (e.g. first launch before migration), serve
  the remote-only timeline and say so; logout of Immich disables the bridge naturally.

### Bridge verification (2026-11-13, first working build)

- The bridge skeleton + `config` endpoint are **verified on device**: provider registered,
  permission declared, and a call returns `{protocol=1, configured=true, serverUrl=…,
  sessionKey=…}` (token masked in every log). Read from `HomeWidgetPreferences` per call.
- Install notes for replacing the stock app (all verified):
  - `pm uninstall -k` is a trap — kept data remembers the old signature and the next
    install of a differently-signed build fails `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
    Full `pm uninstall` is required; restore the data from a tar afterwards.
  - The stock build's versionCode (3042099) is *higher* than the v3.4.1 tag's
    (3040199), so install with `adb install -d` (allow downgrade).
  - The uid changes on reinstall (10243 → 10248 here): `chown -R <new-uid>` both
    `/data/data/<pkg>` and `/data/user_de/0/<pkg>`, then `chcon -R
    u:object_r:app_data_file:s0:c<uid-10000>,c256,c512,c768`.
  - Data (prefs incl. the live login, `app_flutter/immich.sqlite`) survives the tar
    round trip; the app starts normally on the restored state.
- Caller checks observed: root and shell can call the provider on this ROM (KernelSU
  runs `su` in its own SELinux domain; shell's uid checks are privileged). The case that
  must be refused is a differently-signed *app* — the provider sets
  `android:readPermission` (framework-enforced) and re-checks in `call()`. A direct
  denial test needs the probe app (open item, Phase 3 tooling).
- Frida 17.26.2 is back online (`frida-ps -U` lists device processes) — handy for the
  media-endpoint work.

## Config resolution order

1. In-app settings (Phase 4): server URL + token, "Test connection" hitting
   `/server/about`, stored in the consumer's `SharedPreferences`.
2. Bridge provider — automatic when the Immich app is installed, patched and signed in.
3. Patch-time `<meta-data>` — keeps personal builds working with neither of the above.

## Work plan

- **Phase 0 — host, toolchain, data source, baseline build: done (2026-11-13).**
  Manifest/host mapped, Kotlin seams located, the merge query executed against the device
  DB, toolchain installed, fork established, and the unmodified baseline built end to end
  (arm64 release APK, 102.7 MB, debug-signed). The caveats that cost a few cycles are
  recorded under "Build from source".
- **Phase 1 — bridge skeleton: done (fork commit `d786cc7e`, 2026-11-13).** Provider +
  signature permission in the manifest, read-only; `call("config")` verified on device.
- **Phase 2 — config endpoint: done.** `ImmichAPI.getServerConfig` read per call;
  logged out → `configured=false`.
- **Phase 3 — timeline and media.** `mergedAsset` SQL read-only against
  `app_flutter/immich.sqlite`; remote via the app's HTTP stack (OkHttp + coroutines
  already on the classpath); local via MediaStore (projection constants already exist in
  `sync/MessagesImplBase.kt`).
- **Phase 4 — consumers.** Point the existing client upstream at the bridge (keep the
  meta-data fallback), settings Activity + synthetic album row (`Immich settings`),
  re-patch consumers with the project keystore.
- **Phase 5 — polish.** Active source visible in settings; staged-file sweep (already in
  v1); document the bridge so other hosts can implement it; licence layout for the Immich
  modifications (below).

## Operational notes

- **Checkout:** `/home/lucy/media/Android/immich-app` — sparse clone of the fork
  (`origin` = `anoraktrend/immich-android-bridge`, `upstream` = `immich-app/immich`),
  currently on branch `android-bridge` at tag `v3.4.1`. Sparse paths:
  `mobile native i18n open-api .mise .github` (~113 MB).
- **Build (each time):**

  `./build-immich.sh` is the verified path; it runs exactly this:

  ```bash
  cd /home/lucy/media/Android/immich-app
  export PATH="$HOME/fvm/versions/3.47.2/bin:$PATH"
  export ANDROID_HOME=/opt/android-sdk
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
  mise //mobile:install:ci            # dart client from open-api/ + pub get --enforce-lockfile
  mise //mobile:codegen:native        # cargo + ffigen (see caveats)
  mise //mobile:codegen:dart          # build_runner (301 outputs)
  mise //mobile:codegen:drift:migration
  mise //mobile:codegen:pigeon
  mise //mobile:i18n:loader && mise //mobile:i18n:keys
  cd mobile && flutter build apk --release --target-platform android-arm64
  # -> mobile/build/app/outputs/flutter-apk/app-release.apk (102.7 MB baseline)
  ```

  Arm64 only: the device is arm64 (CI additionally builds universal + split-per-abi).

  Caveats recorded from the first successful build (2026-11-13):

  - The umbrella `mise //mobile:codegen` aborts: `codegen:native` needs the ffigen
    workaround below, and `codegen:translation` pulls `//:i18n:format-fix`
    (`pnpm format:fix`), which needs the pnpm workspace — not installed. The individual
    task order above is the working sequence.
  - ffigen fails with `'stdarg.h' file not found` unless `native/native_core/ffigen.yaml`
    gains `compiler-opts: -resource-dir=/usr/lib/clang/23 -I/usr/lib/clang/23/include`.
    `build-immich.sh` appends it, runs the task, then restores the file.
  - Gradle needs `build-tools;35.0.0` and `platforms;android-33/34/35`; `/opt/android-sdk`
    was chowned to the build user so Gradle can install components itself.
  - `i18n:keys` must complete, or every `context.t.*` getter is missing and kernel
    compilation fails.
  - Signing: release silently debug-signs unless `mobile/android/key.jks` exists — the
    Gradle logic switches on that exact path. `build-immich.sh` symlinks the project
    keystore there and writes `mobile/android/key.properties` from `.env`.
- **Replacing the device's Immich app** (custom build keeps `applicationId
  app.alextran.immich` but has a different signer): back up
  `/data/data/app.alextran.immich` and `/data/user_de/0/app.alextran.immich` **before
  uninstalling** — they carry the login session
  (`shared_prefs/HomeWidgetPreferences.xml`) and the timeline DB (`app_flutter/immich.sqlite`).
  A full uninstall is required (`-k` breaks differently-signed installs), `adb install -d`
  is needed (stock versionCode is higher than the tag's), and the uid changes: restore
  the tar, `chown -R <new-uid>`, `chcon` as in v1. Verified end to end on 2026-11-13 —
  see "Bridge verification". Logging in again also works but re-downloads sync state.
- **Pull the DB for local SQL iteration** (used for the evidence above):
  `app_flutter/immich.sqlite{,-wal,-shm}`; a copy lives at `/tmp/opencode/immich-db/`
  (ephemeral). Bind `:user_ids` with `(SELECT id FROM auth_user_entity)` for quick checks.
- **Device:** TCL T607DL, Android 13 (API 33), arm64, KernelSU root, ADB serial
  `NJZLI7DEB6SSWKEQ`. Wake before tests, screen off after.
- **Patcher:** `MORPHE_JAR=~/Downloads/morphe-desktop-1.15.0-all.jar`; v2 `patch.sh` gets
  explicit keystore flags.
- **Debugging:** extension traces to logcat and `files/immich-debug.log`; Frida 17.26.2
  with `frida-ksud` on device. RE tooling: `apk-re/bin/*`, jadx 1.5.6, apktool 3.0.3.
- **Repos:** patches `/home/lucy/media/Android/immich-media-patches` (public, The
  Unlicense); RE notes `/home/lucy/media/Android/apk-re` (private).

## Licensing

Immich mobile is **AGPL-3.0**, and the fork (`anoraktrend/immich-android-bridge`) carries
that license with its source — so if the custom APK is ever distributed, the
corresponding source is already published. This repository (the Morphe patches and the
provider extension) stays **The Unlicense**; no AGPL code is copied into it.

## Relationship to `immich-cloud-media`

`Dreaming-Codes/immich-cloud-media` (checked out at
`/home/lucy/media/Android/immich-cloud-media`) is the **system photo picker** route: a
`CloudMediaProvider` enabled via `device_config allowed_cloud_providers`, so any app using
Android's default picker gets Immich as a cloud source, and the OS interleaves it with
local media. It is complementary, not a dependency: Discord and Tumblr use their own
in-app pickers, which is why they need Morphe patches. Its README is also a handy map of
which Immich endpoints and API-key permissions a cloud-media integration needs.

## Risks and open questions

- **Build pipeline weight.** First build is long (Flutter + Rust native assets + codegen).
  Flutter 3.47 native-assets support may need a `flutter config` flag — verify on the
  baseline build.
- **Toolchain pin churn.** Pins differ per tag (this tag: Flutter 3.47.2 / Rust pins
  1.98.0 + 1.99.1; `main` had 3.47.9 / 1.99.1). Re-read the pins at every tag bump.
- **SQL coupling is versioned with the app** — the bridge ships inside the same binary, so
  drift is a non-issue for our builds; care only when bumping the app tag (re-copy
  `merged_asset.drift`, diff).
- **Database not yet migrated** (first launch) → bridge falls back to remote-only.
- **Session token lifetime**: read per request; if backgrounded life turns out to be
  short, surface it rather than fail silently.
- **`sessionKey` deprecation**: works on v3.4.1; Bearer also works — prefer Bearer in new
  code.
- **Server API drift**: re-check search/thumbnail/original shapes on server bumps
  (v3.3.5 → v3.4.1 happened during v1).
- **Keystore hygiene**: one key for Immich + consumers, stored off-repo; loss = reinstall
  cycle everywhere.
- **Probe app (Phase 3 tooling)**: a tiny bridge client built once, signed with the
  project key for positive tests and with a throwaway key to *prove* the denial path —
  shell/root on this ROM are privileged, so they cannot stand in for "an unsigned app".
- **App version pinning** (updated): upgrading = checkout new tag, re-apply the bridge
  patch, re-copy the SQL, rebuild, re-run the install dance.
- **Gallery semantics worth knowing**: local-only rows are limited to assets in
  backup-selected albums, by the app's own query. We mirror that deliberately — "the
  orchestrator provides what the app's gallery shows".

## References

- Upstream (in the checkout): `mobile/lib/data/db/main/query/merged_asset.drift` (merge
  SQL), `mobile/lib/data/db/main/database.dart` (DB path/open, schema 35),
  `mobile/android/app/src/main/kotlin/app/alextran/immich/` (Kotlin host),
  `native/native_core/hook/build.dart` (Rust build hook),
  `.github/workflows/build-mobile.yml` (CI recipe).
- RE notes (private): `apk-re/notes/immich-app-android-host.md`,
  `apk-re/notes/immich-patch-verification.md`, `apk-re/notes/tumblr-runtime-traps.md`.
- v1 design + trap table: `../README.md`.
