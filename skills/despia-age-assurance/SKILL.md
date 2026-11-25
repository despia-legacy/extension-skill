---
name: despia-age-assurance
description: Add, gate on or debug Age Assurance in a Despia V3 web-view app with ageassurance://: the OS age band (Apple Declared Age Range, Google Play Age Signals), the three-state gates map, assurance levels, which age obligations apply (applicable), Android verification (resolve), last, forget, and the build and store setup. V3 only.
---

# Despia Age Assurance

Needs the Age Assurance addon in the Despia V3 app, the Declared Age Range capability (iOS) and the Play Age Signals terms and bands (Android), and a new build with an increased version code. Calls are `despia('ageassurance://check?...')`, `ageassurance://applicable`, `ageassurance://resolve`, `ageassurance://last` and `ageassurance://forget` from `despia-native`, from the top document only.

## The flow

1. Read `despia.ageAssuranceAvailable`, `ageAssuranceProvider`, `ageAssuranceReason` and `ageAssuranceFeatures`; they never prompt.
2. Optional: where `ageAssuranceFeatures` includes `applicable`, define `window.onAgeAssuranceApplicable` and call `ageassurance://applicable?id=...` to learn which obligations apply (`mandatory_sharing` and, on iOS, the significant-change obligations) before asking for an age.
3. Define `window.onAgeAssuranceResult(verdict)` and call `ageassurance://check?id=...&gates=...` with up to three ages you gate on.
4. Branch on `verdict.status` first (`shared`, `declined`, `verification_required`, `not_applicable`, `unavailable`, `error`), then on `verdict.gates["18"] === true`.
5. On Android `verification_required`, call `ageassurance://resolve` only when `ageAssuranceFeatures` includes `resolve`; guard on `status` so the callback does not loop.
6. On logout or an account switch, call `ageassurance://forget`.

## Rules

- A gate is `true`, `false` or `null`. Unlock only on `=== true`; never read `null` as `false` or `true`.
- Test `ageAssuranceFeatures`, never the OS version or the user agent. `applicable` is listed on Android with a provider and on iOS 26.4 and later; iOS 26.2 and 26.3 answer `unavailable` with `applicable_unsupported`.
- The `applicable` answer has its own global (`ageAssuranceApplicable`) and callback; it never changes `ageAssuranceVerdict`, and `last` does not replay it.
- Use the `despia(..., ['ageAssuranceVerdict'])` await only for `last` and `forget`; a `check` can outlast its 30-second watch. Fire one command at a time.
- Frames get no globals and their calls are refused; pass the verdict down with `postMessage`.
- The verdict is a client-side signal. For a legal, payment or content duty, send it to your server with App Attest or Play Integrity.
- Prove it in the installed app on a device: the iOS Simulator always answers `unavailable`, and a browser preview has no provider.

The full guide is `references/guide.md`, converted from https://setup.despia.com/native-features/age-assurance. Where this file and the guide differ, the guide is right; report it.
