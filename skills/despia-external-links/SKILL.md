---
name: despia-external-links
description: Route links in a Despia V3 app: window.open to the in-app browser or system browser (whitelist for payments and OAuth), and tel:, sms:, geo:, market:, mailto:, whatsapp: and intent: links that open the installed app on Android 11 and later. V3 only.
---

# Despia External Links

How a Despia V3 app routes links. `window.open(url, '_blank')` opens in the in-app browser, or in the system browser when the host is on the External Links whitelist (payments and sign-in belong there). Plain links to `tel:`, `sms:`, `geo:`, `market:`, `mailto:`, `whatsapp:` and `intent:` open the installed app on Android.

## The flow

1. Use `window.open(url, '_blank')` from a button handler for web pages; do not rely on a bare `<a target="_blank">` under a client-side router.
2. Whitelist the bare domain (no `https://`, no path; exact host) of every payment and OAuth page in the Despia Editor so it opens in Safari or Chrome.
3. For phone, text, map, mail and store links use a normal `<a href="tel:...">` (or `sms:`, `geo:`, `mailto:`, `market://details?id=...`); no SDK call is needed.
4. Test links in the installed app on a device.

## Rules

- Payments and sign-in must open in the system browser (store rules); never leave their domains off the whitelist.
- Android 11 and later: the runtime starts the link and shows "No application can handle this request" only when no app can open it; do not add your own check for installed apps.
- Do not describe iOS behaviour for these schemes beyond the guide; the Android fix does not change iOS.
- Quick actions with one of these links take the same route.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/external-links. Where this file and the guide differ, the guide is right; report it.
