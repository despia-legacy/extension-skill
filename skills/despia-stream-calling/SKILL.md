---
name: despia-stream-calling
description: Add, set up or debug the Stream Calling extension in a Despia V3 web-view app: Stream Video audio calls with CallKit and VoIP push on iOS, Telecom and FCM on Android, configure, join, tokens, permissions including Android full-screen ring, events and status. V3 only.
---

# Despia Stream Calling

A Despia V3 extension (`npx despia-extensions add stream-calling`), then a new build. Calls are `extension://streamcall/<route>?...` through `despia()` from `despia-native`, each with its answer variable (`streamcallConfigure`, `streamcallJoin`); events arrive on `onStreamcall*` callbacks.

## The flow

1. Set up Stream (VoIP and Firebase push configurations, ringing on), Apple push, the app's microphone and Android push settings, and the token endpoint on your server.
2. After sign-in, fetch the API key, user id and token from your server and call `configure` (with `token_url` for refreshes).
3. Check `permissions` (`request=true` to ask); on Android 14 and later read `full_screen`.
4. Start or answer calls with `join` (`call_type`, `call_id`, `create`, `ring`, `members`); `leave`, `set_muted`, `set_speaker`.
5. Handle `onStreamcallIncoming`, `onStreamcallJoined`, `onStreamcallEnded` and `onStreamcallTokenNeeded` (answer with `set_token` and the same `request_id`).
6. After the app opens from a call, read `status`; it is the truth.
7. On sign-out call `logout`.

## Rules

- The Stream API secret stays on your server; the page gets only the user token.
- `token_url` must be on a host listed in the `tokenHosts` setting.
- Audio calls only; do not promise video or screen share.
- One VoIP owner per app: no other PushKit code in the base app.
- Remove China from the iOS app's territories (CallKit).
- Branch on `onStreamcallEnded`'s `reason` from the guide's table; `answered_elsewhere` comes only on iOS, and only Android ends an outgoing call with `rejected` (iOS sends a `rejected` error per callee and keeps the call).
- Prove ringing on real devices with the app closed; a simulator or browser does not ring.

The full guide is `references/guide.md`, generated from https://setup.despia.com/extensions/catalog/stream-calling. Where this file and the guide differ, the guide is right; report it.
