# Adding support for another app

How to extend this patch set to a host that isn't Discord or Tumblr — including apps you
cannot test yourself. Read the [README](../README.md) first for the architecture and the
per-app trap table; this document is the workflow.

## Step 0 — check whether the app needs a patch at all

Three kinds of photo pickers exist, and usually only one needs work:

| Picker | What to do |
| --- | --- |
| System photo picker (`ACTION_PICK_IMAGES`, `MediaStore.ACTION_PICK`) | **Nothing.** A CloudMediaProvider exposes Immich to every app using the system picker — see [immich-cloud-media](https://github.com/Dreaming-Codes/immich-cloud-media). |
| Intent-based (`ACTION_GET_CONTENT`, `ACTION_OPEN_DOCUMENT`) | Usually lands in the system picker/document UI → no patch. Confirm which chooser actually appears. |
| In-app custom gallery (Discord, Tumblr, most social apps) | Needs a patch. Continue. |

Quick triage: decompile and search for `MediaStore` / `EXTERNAL_CONTENT_URI`. An app that
never references MediaStore is almost certainly handing the job to the system.

## Step 1 — what a consumer patch contains

Two pieces, both in this repo:

1. **The extension** (`extension/src/main/java/dev/immich/media/`) — a `ContentProvider`
   presenting the Immich timeline as MediaStore content; a client that talks to the Immich
   app's bridge (v2) or the server directly (v1 fallback); staging, cursor coercion, hook
   helpers. **Host-agnostic** — you normally do not touch it.
2. **The patch module** (`patches/src/main/kotlin/dev/immich/patches/Patches.kt`) — per
   host: a `resourcePatch` (provider + permissions + `<queries>` + optional baked config)
   and a `bytecodePatch` (the seams).

`installProvider()` is ready-made: give it the package name and an authority like
`<pkg>.immich.media` and it registers the provider, adds the bridge `<queries>` entry and
the signature permission, strips split requirements, and writes baked config only when the
patch was built with it.

## Step 2 — find the seams

The extension serves `/thumb` (listing), `/thumb/<id>` (preview), `/media/<id>`
(original), `/image`, `/video`, plus `_data` staging for code that insists on a real path
(see the contract table at the bottom).

Search the decompiled app for these strings, roughly in order of yield:

- `content://media/external`, `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` — the
  gallery's listing query.
- `_data` — anything reading the file path column; the upload path lives near here.
- `getRealPathFromUri` / `UriUtil` — Fresco's helper; almost always the upload resolver.
- `BitmapFactory.decodeFile`, `FileInputStream`, `new File(` around media code — checks a
  content URI can never pass.
- `openInputStream` / `openFileDescriptor` — how bytes actually flow.
- Gallery class names, from the host's picker Activity/Fragment or strings like
  "Gallery", "PhotoPicker", "MediaPicker".

Then map the chain end to end — five roles, each with a seam strategy:

| Role | What it does | Seam strategy |
| --- | --- | --- |
| Enumeration | Queries MediaStore for the grid | Point the URI (or the whole query call) at your provider |
| Bucketing | Lists albums/folders | Same, for bucket queries |
| Preview | Loads grid cells | Let it read `/thumb/<id>` (WebP) |
| Resolve | Turns a content URI into a file for upload | Redirect `getRealPathFromUri`-style code to the provider's `_data` |
| Size/type checks | Validates media before upload | Provider returns `_size` / `mime_type`; keep `openFileDescriptor` lengths `UNKNOWN_LENGTH` |

Discord and Tumblr are the worked examples — their seams in `Patches.kt` are commented
with class and method names; copy the shape.

## Step 3 — the trap checklist

Generalised from the [trap table](../README.md#traps-worth-keeping). Assume every one of
these until proven otherwise:

1. `MatrixCursor`-shaped cursors: getters are hard casts — use `CoercingCursor`.
2. `_data` must be a real file for upload paths → stage originals into the app's cache.
3. `File` collapses `content://` to `content:/` — match the scheme, not the string.
4. `File(_data).exists()` filters: redirect in the row converters only — and never where a
   fallback to `openInputStream` depends on `false`.
5. `BitmapFactory.decodeFile(_data)` for dimension probes — redirect.
6. URIs built by string concatenation — intercept the builder.
7. `openFileDescriptor` callers reject declared lengths — return `UNKNOWN_LENGTH`.
8. `_size`-based validation — resolve sizes lazily for single-item queries.
9. Mime sniffing: report `image/webp` for previews; sniff originals after caching.
10. Upload queues that report success on failure — verify by the artifact landing, not by
    the worker result.

If you hit a behaviour that isn't on this list, add it — that table is the most valuable
output of this repo.

## Step 4 — write the patch

Copy a block from `Patches.kt` and adjust:

```kotlin
private val acmeImmichResources = resourcePatch(
    name = "Immich media provider",
    description = "Registers the Immich backed media provider inside Acme's manifest.",
) {
    compatibleWith(ACME_PACKAGE to setOf(ACME_VERSIONS))
    execute { installProvider(this, "$ACME_PACKAGE.immich.media") }
}

val acmeImmichMediaPatch = bytecodePatch(
    name = "Immich photo picker",
    description = "Replaces Acme's in-app gallery with a browser for an Immich server.",
) {
    compatibleWith(ACME_PACKAGE to setOf(ACME_VERSIONS))
    dependsOn(acmeImmichResources)
    immichBaseUrl()
    immichApiKey()
    extendWith(EXTENSION_PATH)
    execute {
        // seams, each commented with the host class/method it targets
    }
}
```

Pin **exact versions** with `compatibleWith` — obfuscated names move between releases, and
an unverified version's seams are unknown territory.

## Step 5 — build, install, iterate

- Use a **merged** APK (splits folded in) or the installed build lacks its native
  libraries; `installProvider` strips `requiredSplitTypes` so a single APK installs.
- Sign with your own keystore. To use the **bridge** (auto-config from your Immich app)
  the key must match the one your custom Immich build is signed with — the bridge
  permission is `signature` level. Without that, build the patch with baked
  `immichBaseUrl` / `immichApiKey` and the direct mode works.
- Replacing a store-installed app with a differently-signed build needs a full uninstall:
  tar-back `/data/data/<pkg>` first, restore after, `chown` to the new uid and **re-grant
  runtime permissions explicitly** — Android keeps grants in system state, not app data.
- Patch your own copy. This project does not distribute patched APKs.

## Step 6 — verify, and be honest about what you didn't

The extension traces everything to `files/immich-debug.log` inside the host app (and to
logcat, tag `ImmichMedia`): every query, open, staged path, `exists()` decision, size
resolution and `getType`. A new host's first run is about reading that trace — it shows
exactly which columns and URIs the app asks for, and where it goes wrong.

Without the app's UI you can still exercise the provider headlessly (root, or the
[bridge probe](../tools/bridge-probe/) for the bridge side):

```
adb shell su -c 'content query --uri content://<pkg>.immich.media/thumb' | head
adb shell su -c 'content read --uri content://<pkg>.immich.media/media/<id> | wc -c'
```

That proves listing and bytes. Only the host's own upload chain needs the UI.

**For apps you cannot test:** ship the patch anyway, but say so out loud:

- Name it **"(untested on device)"** in the patch description.
- Keep the version pin to the single version you analysed.
- Ask testers to collect `files/immich-debug.log` and report — the trace file was designed
  to be handed over.
- Never mark an app as verified in the README until someone has actually sent a photo from
  it; "compiles and installs" is not verification.

## Reference — the provider contract

| URI | Returns |
| --- | --- |
| `/thumb`, `/image`, `/video` | Listing rows: `_id`, `_display_name`, `mime_type`, dimensions, dates, `_size`, `_data`, bucket columns |
| `/thumb/<id>` | Preview bytes (WebP; `getType` → `image/webp`) |
| `/media/<id>` | Original bytes as a real cached file |
| `_data` on single items | Staged cache path (the upload trap) |

v2 adds the bridge — the Immich app as orchestrator. Its contract
(`call("config"|"timeline"|"size")`, `openFile("/thumb|/original" + "/r/<uuid>|l/<mediaId>")`,
signature-guarded) is documented in [`v2-orchestrator.md`](v2-orchestrator.md) if you want
to implement the same front-end in another host.
