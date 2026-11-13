# Immich media picker patches (Morphe)

Morphe patches that make an app's built-in photo picker browse an
[Immich](https://immich.app) server instead of local `MediaStore` content. Nothing is
synced to the device: the picker lists the Immich timeline and uploads the originals.

Verified end to end on Android 13 (API 33), arm64:

| App | Picker | Preview | Send |
| --- | --- | --- | --- |
| `com.discord` 351.11 - Stable | Immich library | thumbnails (~13 KB/cell) | messages, full-resolution original |
| `com.tumblr` 47.0.0.108 | Immich library | renders in composer | photo posts and messenger images |

## How it works

Each patched app gets a private, read-only `ContentProvider`:

| URI | Serves |
| --- | --- |
| `content://<pkg>.immich.media/thumb` | grid listing (images + videos, newest first) |
| `content://<pkg>.immich.media/thumb/<id>` | preview thumbnail (`/thumbnail?size=thumbnail`) |
| `content://<pkg>.immich.media/media/<id>` | original asset (`/original`), used for uploads |
| `content://<pkg>.immich.media/image`, `/video` | type filtered listings |

Numeric ids handed to the app are synthetic and persisted in a small SQLite database
(`immich_media.db`) so drafts that store content URIs keep working across restarts.

Two behaviours matter beyond plain streaming:

- **Size.** Immich's search API reports no file size, but both apps validate media by
  `_size`. Single-item queries resolve it with a `HEAD` on `/original` and cache it.
- **Staging.** Both apps eventually resolve media through Fresco's
  `UriUtil.getRealPathFromUri`, which reads the `_data` column and wraps the result in
  `new File(...)`. Single-item queries that ask for `_data` therefore download the
  original into `cache/immich/staged/<uuid>-<filename>` and return that real path.
  Staged files older than six hours are swept at provider start.

Everything is baked in at patch time: the server URL and API key are written into the
patched `AndroidManifest.xml` as `<meta-data>` and read back by the provider.

## Layout

```
build.sh                     builds build/immich-media-patches.mpp
build-immich.sh              builds the custom Immich APK (the orchestrator) from source
tools/bridge-probe/          tiny APK exercising the v2 bridge (trusted + untrusted builds)
patches/src/main/kotlin/     Morphe patch module (Kotlin)
extension/src/main/java/     runtime injected into the APK (Java, dexed to extensions/*.mpe)
```

`build.sh` needs the Morphe desktop jar, an `android.jar` and Android build-tools
(`d8`). Override with `MORPHE_JAR`, `ANDROID_JAR` and `BUILD_TOOLS`.

```bash
./build.sh                  # -> build/immich-media-patches.mpp
./patch.sh discord <apk>    # options come from the gitignored .env
```

## Configuration

Create a gitignored `.env` next to this file (see `.env.example`) or pass the options
directly:

```bash
java -jar morphe-desktop-*.jar patch \
  -p build/immich-media-patches.mpp \
  -e "Immich photo picker" \
  -O immichBaseUrl=https://immich.example.com \
  -O immichApiKey=xxxxxxxxxxxxxxxxxxxxxxx \
  -o out/app.apk \
  app.apk
```

The API key is never stored in this repository.

## Host apps

| App | Seams patched |
| --- | --- |
| `com.discord` 351.11 | `ContentResolverMedia$Companion.queryMedia` (collection URI), `MediaResolver.resolveToUri$media_release` (preview URI → original before upload), provider + split requirement removal |
| `com.tumblr` 47.0.0.108 | `PostGalleryFragment.A5()` (collection URI), `plc.j` / `plc$a.g` (grid thumbnails), `plc.o` / `v6` (`File.exists()` row filter), `GalleryMedia.<init>` and `.d()`, `ImageData$a.b(J)` (post payload URI), `BitmapFactory.decodeFile` across `com/tumblr/**`, provider + split requirement removal |

Use a **merged** APK (splits for `base__abi` / `base__density` folded in), otherwise
the installed build is missing its native libraries. Both manifests carry
`requiredSplitTypes`; the patch removes it so a single APK installs.

## Traps worth keeping

Both apps are hostile to `content://` media in ways that only show up at runtime.
Every row below cost a debugging cycle:

| Where | Behaviour | What the patch does |
| --- | --- | --- |
| `ContentResolver.openFileDescriptor` callers | Throws `FileNotFoundException: Not a whole file` when the provider's `AssetFileDescriptor` declares a length — it cannot tell a whole file from a slice | Always return `UNKNOWN_LENGTH`; size comes from the fd's stat size |
| `UriUtil.getRealPathFromUri` (Fresco, both apps) | Reads `_data` and wraps it in `new File(...)`; a content URI can never be uploaded | Serve a real staged path for single-item `_data` queries |
| Tumblr `sld.g` | Checks `File(_data).exists()`; when false it correctly falls back to `ContentResolver.openInputStream` | Do **not** force `exists()` true here, or it takes the `FileInputStream` path instead |
| Tumblr `plc.o`, `v6`, `E5` | Drop every row whose `_data` "file" does not exist | Redirect `File.exists()` in the gallery converters only |
| Tumblr `GalleryMedia.<init>` | Rewrites its `_data` argument to `"file://" + Uri.parse(data).getPath()`, destroying provider URIs | Keep `content://` values verbatim |
| Tumblr `ImageData$a.b(J)` | Builds post attachment URIs by **string concatenation** off `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` | Return the provider URI for our ids |
| Tumblr `postform.helpers.a.e`, `plc.o`, `v6`, Kanvas, GIF encoder | Call `BitmapFactory.decodeFile(_data)` when an item reports no dimensions; fatal for content URIs and non-raster formats like SVG | Redirect every `decodeFile` in `com/tumblr/**` to a hook that decodes the staged file or the stream |
| Any provider cursor | `MatrixCursor` getters are hard casts, so `getInt()` on a `Long` column throws `ClassCastException` | Return a `CoercingCursor` with window-style numeric coercion |
| Any provider URI string | `java.io.File` collapses `content://` to `content:/`, so scheme checks on a `File` path must match `"content:"`, not `"content://"` | Always match the scheme only |
| Tumblr upload queue | `PostTaskWorker` reports WorkManager `SUCCESS` even when the post failed | Judge success by the post landing, never by the worker result |
| Immich metadata | `originalMimeType` does not always match the stored bytes (a PNG uploaded as `.jpg`) | Sniff cached originals and report the detected type |
| Immich thumbnails | `/thumbnail` returns WebP regardless of the source format | Report `image/webp` for preview URIs so image loaders pick the right decoder |

## Debugging

The extension traces to `ImmichMedia` in logcat **and** to
`files/immich-debug.log` inside the app (some ROMs keep log buffers too small to
trust). Every query, open, staged path, `exists()` decision and `getType` result is
recorded there, and both apps' own error loggers are routed into the same file for the
packages that matter. That trace is what isolated every trap above.

## Notes

* `MediaStore` queries are replaced wholesale; the app never sees local media while the
  Immich backend is reachable. Listing pages are cached for two minutes.
* Thumbnails and originals are cached under the app's `cache/immich/` directory.
* Downloads happen off the main thread even when the caller is a UI component.
* No release/publishing setup: this builds locally only.

## Next version

[`docs/v2-orchestrator.md`](docs/v2-orchestrator.md) plans turning the Immich mobile app
(**built from source**) into the orchestrator: it lends its own session credentials to the
patched pickers (auto-discovery, no rebuild per user), serves the **full gallery** —
device-local and server assets interleaved exactly as the Immich app's own timeline shows
them — and becomes the only component that talks to the server. The Immich modifications are
developed as a fork (`anoraktrend/immich-android-bridge`, branch `android-bridge`), so the
AGPL app and its changes ship together. The doc carries the verified merge SQL, the build
recipe, the signature-permission security model, and the phase plan.

**Status (2026-11-13):** the bridge is implemented and both surfaces are verified on
device — the Immich build serves `config` / `timeline` / `size` / media, and re-patched
Discord and Tumblr list and fetch through it (byte-identical to the probe), with the
baked meta-data retained as the fallback. Discord's send test passed on device;
remaining: the in-picker settings entry and Tumblr's post confirmation.

## License

Released into the public domain under [The Unlicense](LICENSE) — do whatever you
like with it, with no warranty.
