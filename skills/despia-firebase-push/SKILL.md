---
name: despia-firebase-push
description: Add or debug Firebase Cloud Messaging push in a Despia V3 app with the firebase-push extension: configure, permission, token, topics, message and tap events, take_opened, events log, errors and sending from a server. V3 only.
---

# Despia Firebase Push

A Despia V3 extension (`npx despia-extensions add firebase-push`), then a new build. It replaces the app's built-in push provider, so the app has one Firebase Messaging owner. Calls are `extension://firebasepush/<route>` through `despia()` from `despia-native`, each with its answer variable (`firebasePushConfigure`, `firebasePushToken`); events arrive on `onFirebasePushMessage`, `onFirebasePushOpened` and `onFirebasePushTokenChanged`.

## The flow

1. In Firebase register the real iOS bundle ID and Android application ID; give the CLI `GoogleService-Info.plist` and `google-services.json`. On iOS turn on Push Notifications for the App ID and upload the APNs key in Firebase. Rebuild.
2. Call `configure` once the app wants push (the choice is remembered); stop on `ok: false` and read `code`.
3. After the user agrees, call `request_permission`, then read `status`.
4. Read `token` (iOS answers `not_ready` until APNs registration is done) and send `data.token` to your server for the signed-in account.
5. On start call `take_opened` for taps that opened the app, and `events?after=<last seq>` for what the page missed; set `onFirebasePush*` handlers for live ones.
6. `subscribe` and `unsubscribe` topics (`[a-zA-Z0-9-_.~%]`, 1 to 900 characters). On sign-out call `delete_token` and unlink the token.
7. Send from your own server with the Admin SDK or FCM HTTP v1.

## Rules

- The service account and the APNs key never go in the app or in `public/`.
- iOS routes a message to the extension only when `data` holds `"nativeext": "firebasepush"`; a silent message also needs `content-available: 1`.
- Branch on `code`: `not_configured`, `configuration_invalid`, `configuration_mismatch`, `sdk_conflict`, `not_ready`, `token_unavailable`, `bad_request`, `activity_unavailable`, `sdk_error`.
- Keep tokens out of logs, URLs and analytics.
- Page events arrive only while the page is loaded; promise no background JavaScript. Read `take_opened` and `events` on start and route in the page.
- Prove delivery with a real Firebase project on a real device; a simulator or browser does not receive FCM.

The full guide is `references/guide.md`, generated from https://setup.despia.com/extensions/catalog/firebase-push. Where this file and the guide differ, the guide is right; report it.
