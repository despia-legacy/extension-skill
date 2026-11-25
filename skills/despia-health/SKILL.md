---
name: despia-health
description: Read Apple Health and Android Health Connect data in a Despia V3 web-view app with readhealthkit:// and healthkit://workouts: one answer shape, onHealthkitResponse, error and message codes for both platforms, timeouts, and builds without Health Connect. V3 only.
---

# Despia Health

Reads and workouts from Apple Health (iOS) and Health Connect (Android) in a Despia V3 web-view app, with one answer shape on both. Calls are `despia('readhealthkit://<identifiers>?days=7')` and `despia('healthkit://workouts?days=30')` from `despia-native`. Needs the Health feature on in the app and a new build (Android: Health Connect build settings and the Play Console health declaration).

## The flow

1. Detect the platform from the user agent; use the Health Connect status call (`healthconnect://status`) on Android before reading.
2. Define `window.onHealthkitResponse` before the call; it runs once per answer with the same object as `window.healthkitResponse`.
3. Call `readhealthkit://` with one or more identifiers (one permission prompt for the set) or `healthkit://workouts`.
4. In the callback, test `response.error` first; then index each requested identifier, which is always an array.
5. Handle `error` by code: `healthkit_*` on iOS, `health_connect_*` on Android (unavailable, update_required, authorization_failed, timeout, read_failed, invalid_request, not_included).

## Rules

- An answer has `error` and `message` together or neither; never read `message` without `error`.
- A declined read is `[]`, not an error; HealthKit does not reveal a decline.
- A timeout keeps what was read; other types are `[]`. Every call ends within 15 s of starting (iOS waits for the permission sheet, 300 s safety line; Android counts from after the permission step).
- A workouts call clears an earlier read's `error`/`message` and sets its own only if it failed.
- A build without Health Connect still answers at once (`health_connect_not_included`, `despia-feature-not-included` fires); never wait without a callback or timeout.
- Prove it in the installed app on a device; a browser preview has no Health data.

The full guide is `references/guide.md`, generated from https://setup.despia.com/health-data/apple-health, https://setup.despia.com/health-data/google-health. Where this file and the guide differ, the guide is right; report it.
