---
name: despia-passkeys
description: Make an existing WebAuthn passkey sign-in work inside a Despia V3 iOS and Android app, sharing passkeys with the website. Use when a user wants passkeys in their Despia app, passkey calls fail in the app, or they need apple-app-site-association or assetlinks.json for passkeys.
---

# Despia passkeys

The page code does not change. Keep the standard WebAuthn calls (`navigator.credentials.create` and `get`). The work is setup.

## Steps

1. Turn on the Passkeys add-on for the app in Despia and list every relying party ID the web app uses (the `rp.id` / `rpId`).
2. On each listed domain, host `/.well-known/apple-app-site-association` with a `webcredentials` entry `<Team ID>.<bundle ID>` (https, valid certificate, no redirect) and `/.well-known/assetlinks.json` with `delegate_permission/common.get_login_creds` and the app signing certificate's SHA-256 (HTTP 200, `Content-Type: application/json`, no redirect).
3. Add `android:apk-key-hash:<base64url SHA-256 of the signing certificate, no padding>` to the server's allowed origins. iOS sends the page's own origin.
4. Ship a new build. The build log warns about a missing or wrong association file; fix it on the website.

## Rules

- Start passkey calls from a tap.
- Check `isConditionalMediationAvailable()` before `mediation: 'conditional'`. Android's WebView does not support conditional requests: offer a "Sign in with a passkey" button there.
- Android 9 or later has passkeys; keep another sign-in method for older devices.
- Errors are the standard WebAuthn `DOMException`s (`NotAllowedError`, `SecurityError`). There is no Despia call for passkeys.
- A third-party identity provider's domain cannot be associated: use the OAuth flow for it.
- Do not claim it works until it ran in the installed rebuilt app on a device, after Apple's network fetched the file (up to 24 hours).

## Common mistakes

- An `apple-app-site-association` with `applinks` only.
- The upload key fingerprint in `assetlinks.json` instead of the Play app signing key.
- A server that rejects the `android:apk-key-hash:` origin.
- `robots.txt` blocking `/.well-known/`.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/passkeys. Where this file and the guide differ, the guide is right; report it.
