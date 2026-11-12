# v2 — the Immich app as orchestrator

Design and work plan for the next version. v1 is shipped and verified; see the
[README](../README.md) for the current design, the per-app seam list and the trap table.

## Status of v1 (shipped)

| App | Picker | Preview | Send |
| --- | --- | --- | --- |
| `com.discord` 351.11 | Immich timeline | thumbnails (~13 KB/cell) | message, full-resolution original |
| `com.tumblr` 47.0.0.108 | Immich timeline | renders in composer | photo post + messenger image |

Config is baked into each APK at patch time (`immich.baseUrl` / `immich.apiKey` as
manifest `<meta-data>`). Every patched app carries credentials and its own HTTP client.

## Goals for v2

1. **Configure without rebuilding.** A user should point a published `.mpp` at their own
   server through the app, not through patch options.
2. **Auto-discover from the Immich app.** If the user is already signed into Immich
   mobile, no configuration at all should be needed.
3. **One component reaches out.** Discord and Tumblr should ask the *Immich app* for
   bytes rather than talking to the server themselves — credentials stay in one place and
   the data path is transparent.

## Verified facts (Immich mobile `main`, server v3.4.1, Nov 2026)

Everything below was confirmed on a real device; no inference remains in this section.

| Fact | Evidence |
| --- | --- |
| The app writes the server endpoint and a session token into **plaintext** `SharedPreferences` for its home-screen widget | `HomeWidgetPreferences.xml` contains `widget_server_url`, `widget_auth_token`, `widget_custom_headers` |
| Those keys are defined in Dart and written by the widget service | `mobile/lib/constants/constants.dart`, `mobile/lib/services/widget.service.dart` (`writeCredentials` / `clearCredentials`) |
| The token is the **live session token**, refreshed with auth state and **blanked on logout** | `writeCredentials` called from `mobile/lib/providers/auth.provider.dart`; `clearCredentials` clears the same keys |
| The Android host already ships a Kotlin reader for exactly these values | `ImmichAPI.getServerConfig(context)` → `ServerConfig(serverURL, sessionKey, customHeaders)`, reading via `HomeWidgetPlugin.getData()` |
| The widget's request style is `?sessionKey=<token>` and **still works** | `api/server/about?sessionKey=…` → HTTP 200 on v3.4.1 |
| Bearer auth also works | `Authorization: Bearer <token>` → HTTP 200; `?apiKey=…` → 401 (query-param API keys are not a thing) |
| The token is a session token, not a JWT | 43 characters |
| `flutter_secure_storage` is not a barrier for a same-UID reader | its AES key in `FlutterSecureKeyStorage.xml` decodes to a known constant string |
| The Flutter layer is irrelevant to us | all of the above lives in the Android host + `SharedPreferences`; `libapp.so` is untouched |

Consequence: **reading per request** (never caching) gives a token that tracks the app's
session, including logout. No refresh logic of our own is needed.

## Architecture

```
Immich app (patched)                    Discord / Tumblr (patched)
  bridge provider                         thin local provider
    config   -> getServerConfig()            proxy rows + stage _data locally
    media/** -> sessionKey requests          (exists today; only its upstream changes)
    own cache + paging
        ^
        | signature-level permission
        +-- every app Morphe signs shares one keystore, so our patched apps
            trust each other and nothing else does
```

### Why consumers keep a provider (and staging)

An orchestrator **cannot** satisfy the traps that v1 fought through. `UriUtil.
getRealPathFromUri` and Tumblr's `foc.b` require `_data` to be a **real file path**, and
a path inside the Immich app's sandbox is unreadable by another app. Cross-app file
sharing has no clean solution on modern Android, so:

- the **bridge** owns credentials, HTTP, caching and paging;
- each **consumer** keeps a thin local provider that proxies rows and materialises
  originals into its own cache (`cache/immich/staged/`), exactly as v1 does.

That staging code is already written and verified. v2 changes only where bytes come from.

### Config resolution order

1. In-app settings (below).
2. Bridge provider — automatic when the Immich app is installed and signed in.
3. Patch-time `<meta-data>` — keeps existing personal builds working.

Resolution 3 also covers users who patch without the Immich app installed, and any
consumer where the bridge is unreachable.

## Security model

The bridge exposes a live session token, so it must not be world-readable.

- Define a **custom permission with `android:protectionLevel="signature"`** in the
  Immich app's manifest, and declare it on the provider.
- Consumers request it. Because Morphe signs everything it patches with the same
  keystore, our patched apps share a signature and the permission is granted; unpatched
  apps are refused. Third-party users get the same property with their own keystore.
- Do **not** use `android:exported="true"` without the permission, and do not rely on
  obscurity of the authority.

Fallback if signature matching proves awkward (e.g. per-app keystores): expose `config`
only to the *user's own* entered settings and serve only `media/**` from the bridge,
since media bytes are not credentials.

## Work plan

### Phase 0 — map the Android host

- Decode `app.alextran.immich` and read its manifest: activities, providers, permissions,
  `requiredSplitTypes`, and where the Flutter engine is initialised.
- Confirm a Morphe `resourcePatch` can add a `<provider>` and a `<permission>`, and that
  a `bytecodePatch` can inject an extension dex into the host (the app is Flutter, so the
  host has few classes — verify the extension loads before the engine starts).
- Check whether the app is split (`base__abi` / `base__density`) like the other two.

### Phase 1 — bridge skeleton

- Provider authority, e.g. `app.alextran.immich.immich.bridge`.
- Signature permission, declared and required.
- `call()` or plain URIs; keep it read-only.

### Phase 2 — config endpoint

- Reuse `ImmichAPI.getServerConfig(context)`; return the endpoint + session token, or a
  clear "not signed in" signal when the app is logged out (prefs blank).

### Phase 3 — media endpoints

- `media` listing: paged timeline search, same 500-per-page contract v1 uses so offset
  arithmetic stays stable.
- `media/<id>/thumb` → `/thumbnail?size=thumbnail` (WebP, as v1 reports).
- `media/<id>/original` → `/original`, with `Content-Length` available for the `_size`
  contract consumers rely on.
- Own cache inside the Immich app's cache dir; reuse the bundled Gson and
  `ImmichAPI.buildRequestURL` rather than adding a client.

### Phase 4 — consumers

- Point the existing client upstream at the bridge when available; keep the manifest
  meta-data path as fallback.
- Settings Activity in the extension: server URL + token/API key, "Test connection"
  hitting `/api/server/about` and printing the version, stored in the consumer's
  `SharedPreferences`.
- Entry point: a synthetic album row (`Immich settings`) at the top of the picker's
  folder list, launching the settings Activity.

### Phase 5 — polish

- Surface which source is active (bridge / manual / baked) in the settings screen.
- Sweep staged files, as v1 does.
- Document the bridge contract so a future host can implement it.

## Operational notes (needed to resume)

- **Repos.** Patch source: `/home/lucy/media/Android/immich-media-patches` (public,
  The Unlicense). RE notes: `/home/lucy/media/Android/apk-re` (private).
- **Build.** `./build.sh` → `build/immich-media-patches.mpp`; needs
  `MORPHE_JAR` (`~/Downloads/morphe-desktop-1.15.0-all.jar`), `ANDROID_JAR`
  (`/opt/android-sdk/platforms/android-36/android.jar`), `BUILD_TOOLS`
  (`/opt/android-sdk/build-tools/37.0.0`). Kotlin compiler jars are cached in
  `~/.cache/immich-media-patches`.
- **Patch and install.** `./patch.sh <app> <apk>` with options from the gitignored
  `.env`. Use **merged** APKs (splits folded in) — the base alone lacks native libraries.
  Patching the base also requires removing `requiredSplitTypes` (the patch does it).
- **Device.** TCL T607DL, Android 13 (API 33), arm64, KernelSU root, ADB over USB
  (serial `NJZLI7DEB6SSWKEQ`). Wake before tests, screen off after.
- **Install dance** (root, both apps): `pm uninstall` → `pm install -r` → restore data
  from a `tar` of `/data/data/<pkg>` and `/data/user_de/0/<pkg>` →
  `chown -R <new uid>` → **`chcon -R u:object_r:app_data_file:s0:c<uid-10000>,c256,c512,c768`**.
  `restorecon` does not follow the `/data/data` symlink and Discord hard-crashes without
  the relabel. Pull data backups before wiping.
- **Debugging.** The extension traces to logcat and to `files/immich-debug.log` in each
  app; both apps' own loggers are routed into it for the packages that matter. Frida
  17.26.2 on the host and `frida-ksud` on the device are set up and were decisive for the
  last v1 bug — reach for it when a trace is ambiguous.
- **RE tooling.** `apk-re/bin/apk-decode|apk-info|apk-pull|apk-sign`, jadx 1.5.6,
  apktool 3.0.3, and the REA Android tools (`inspect_android_method`,
  `trace_android_references`, …). REA indexes only classes inside the APK, so framework
  xrefs still need a dex scan.

## Risks and open questions

- **Immich app version pinning.** The bridge reuses app internals
  (`ImmichAPI.getServerConfig`, `HomeWidgetPlugin.getData`). Pin the patch to a tested
  version and fail loudly when the target is absent, as the other patches do.
- **Session token lifetime.** Reading per request tracks the app, but if the token is
  ever short-lived while the app is backgrounded, the bridge must surface that rather
  than fail silently. Decide behaviour after observing real p95 usage.
- **`sessionKey` deprecation.** It works today; Bearer also works. Prefer Bearer in new
  code and keep `sessionKey` only if mirroring the widget exactly buys simplicity.
- **Server API drift.** The user's server moved v3.3.5 → v3.4.1 during v1. Re-check the
  search/thumbnail/original shapes whenever the bridge starts talking to a new version.
- **Provider injection into a Flutter host.** Unverified: the host is small, so if dex
  injection is awkward, an alternative is an exported service in the same app or (last
  resort) resolving config only, leaving HTTP in the consumers.
- **Split requirement.** Confirm the Immich app's manifest state and whether the patch
  must strip `requiredSplitTypes` like the other two.

## References

- Patch source and v1 trap table: `../README.md`.
- RE notes (private): `../../apk-re/notes/immich-patch-verification.md`,
  `tumblr-runtime-traps.md`, `discord-media-protocol.md`.
- Immich upstream, paths relied on: `mobile/lib/constants/constants.dart`,
  `mobile/lib/services/widget.service.dart`, `mobile/lib/providers/auth.provider.dart`,
  `mobile/android/app/src/main/kotlin/app/alextran/immich/widget/ImmichAPI.kt`.
