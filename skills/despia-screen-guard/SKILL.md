---
name: despia-screen-guard
description: Hide private pages of a Despia V3 web-view app in the app switcher and Recents with screenguard://, hold the splash cover until the page's own lock (Face ID) is ready, and switch screenshot blocking per page with screenshotguard://. For banking, health, journals and anything behind a lock. V3 only.
---

# Despia Screen Guard

Two switches from `despia-native`, both off until the page calls them, both kept across navigations and reloads, both gone after the app is closed:

- `despia('screenguard://on')`: the app's splash covers the window whenever the app is not in front, so the app-switcher or Recents picture never shows the page.
- `despia('screenshotguard://on')`: blocks screenshots and screen recording while the page is shown.

## The flow

1. Detect Despia from the user agent; a browser has neither guard.
2. Call `screenguard://on` from an inline script in `<head>` on every private page, and `screenguard://off` on public pages. A call made while the page loads is kept.
3. Need a lock on return? Use `screenguard://on?hold=true` (optional `&timeout=<seconds>`, default 10). Define `window.onDespiaScreenGuard` before the call. On `event === 'returned'`, run `bioauth://` with all three callbacks (`onBioAuthSuccess`, `onBioAuthFailure`, `onBioAuthUnavailable`); in each one show content or your own lock screen, then call `screenguard://ready`.
4. Screenshot blocking on some pages only: `screenshotguard://on` and `off`; `window.onScreenCaptureAttempt()` fires on an attempt while it is on.

## Rules

- In hold mode only the page lifts the cover (`ready` or `off`), every time it went up, including Control Center, permission prompts and the Face ID sheet.
- If the page does not call `ready` within the limit while the app is in front, the app reloads the page under the cover and lifts it when the new page loads (`event: 'reloaded'`). It never lifts the cover over the old page.
- Answers and events set `window.despiaScreenGuard` / `window.despiaScreenshotGuard`, then call `window.onDespiaScreenGuard` / `window.onDespiaScreenshotGuard`. Events: `state`, `covered`, `returned`, `uncovered`, `reloaded`. Refusals: `unknown_command`, `bad_request`.
- Events are best effort (the page may be suspended); the cover does not depend on them. Never hide private content yourself from `focusout`.
- The page's `screenshotguard://` call wins over the editor's screenshot switch; the switch only sets the state at launch.
- Platforms: iOS shows the splash in the switcher and blocks captures best effort (no public API). Android 13+ shows a plain card in the splash colour and allows screenshots. Android 7 to 12L has no Recents-only API, so the guard also blocks screenshots there.
- Only the top-level page can call them; iframe, popup and in-app-browser calls are ignored.

## Mistakes to refuse

- Calling `ready` while locked without first showing your own lock screen.
- Expecting either guard to survive an app relaunch; re-arm in `<head>`.
- Calling from an iframe.
- Promising a splash image on the Android Recents card (Android allows only a colour).

Prove it in the installed app on a device. The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/screen-guard. Where this file and the guide differ, the guide is right; report it.
