---
name: despia-background-upload
description: Upload a picked file from a Despia V3 app to the user's own server as a native background transfer with extension://backgroundupload (pick, selection, start, upload, uploads, cancel, discard), with retries and an Idempotency-Key. Use when a user wants large or reliable uploads that keep going when the app is in the background.
---

# Despia Background Upload

Built into the Despia V3 app; nothing to install. Calls are `extension://backgroundupload/<route>?...` through `despia()` from `despia-native`. The answer variable is `despiaBackgroundUpload` + the route (`despiaBackgroundUploadStart`).

## The flow

1. `status`: offer uploads only when `background_upload_available` is `true`.
2. `pick` (optional `kind`: any, photo, video, audio, file) while the app is visible: it answers a `selection_id` at once.
3. `selection` until `state` is `ready` (or `cancelled`, `failed`); take `files[0].file_id`.
4. `start` with `file_id`, `url` (https), optional `method`, `body` (`multipart` or `raw`), `headers`, `fields`, `metadata_json`, `file_field`, `metadata_field`. All JSON options are JSON text, encoded.
5. Follow `window.onDespiaBackgroundUploadEvent(upload)`, or read `upload` / `uploads` after a relaunch.
6. `discard` the file when done.

## Rules

- Authenticate with `headers`; the browser's cookies are not sent. Framing headers (`Content-Length`, `Host`, `Transfer-Encoding`, ...) are refused.
- The server answers 2xx only after storing the file, and deduplicates by the `Idempotency-Key` header (the upload id). Retries (408, 425, 429, 5xx, network) and resends after a lost transfer reuse the key; `outcome_unknown: true` means an earlier attempt may be stored.
- Point `url` at the final address: redirects are reported (`redirected`, `final_host`), and on Android 301/302/303 are final.
- Files are at most 250 MiB; the newest eight copies are kept, older than a day are removed.
- Android's default mode restarts a transfer longer than 10 minutes from the first byte; the long-running mode is a build setting with a Play Console foreground service declaration.
- Do not claim it works until a transfer was proven in the installed app on a device, with the app backgrounded.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/background-upload. Where this file and the guide differ, the guide is right; report it.
