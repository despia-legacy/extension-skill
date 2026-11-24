---
name: despia-media-picker
description: Pick photos, videos, audio and files in a Despia V3 app with extension://mediapicker (the platform's own pickers and camera), show them from local URLs, transcode video natively and upload in the background. Use when a user wants a media or file picker, camera capture, video compression or reliable uploads in their Despia app.
---

# Despia Media Picker

Built into the Despia V3 app; nothing to install. Calls are `extension://mediapicker/<route>?...` through `despia()` from `despia-native`; the answer variable is `mediaPicker` + the route (`mediaPickerPick`). Answers use camelCase (`pickId`, `fileId`).

## The flow

1. Define `window.onMediaPickerFileEvent(file)` and `window.onMediaPickerPickEvent(pick)` before calling `pick`.
2. `pick` with `types`, `source`, `multiple`, `limit`, size limits: it answers at once (`state: presenting`).
3. Files arrive as events: `loading`, then `ready` with a local `url` the page shows or plays directly, or `failed` with a `reason`. `result?pick=` and `files` read the same state again.
4. Optional: `transcode` a ready video (preset, codec, bitrate); follow `onMediaPickerJobEvent`.
5. Optional: `upload` a ready file to the user's https endpoint (`PUT` raw, or `POST` multipart with `form_field`), with `headers` for auth; it keeps going in the background.
6. `release` files when done, or `release_after=true` on the upload.

## Rules

- Never send file bytes through the bridge or read them into JavaScript to upload; use `upload`.
- Encode every value with `encodeURIComponent`; `fields` and `headers` are JSON text.
- The server deduplicates by `Idempotency-Key`; retries reuse it.
- File URLs follow the local server: on `onMediaPickerServerEvent` read `files` again.
- Check the per-platform notes (`limitLowered`, `preselectedApplied`, `tabApplied`) instead of assuming an option applied.
- The camera depends on the app's camera and microphone settings (`camera_unavailable`).
- Prove it in the installed app on a device; a browser preview has no native picker.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/media-picker. Where this file and the guide differ, the guide is right; report it.
