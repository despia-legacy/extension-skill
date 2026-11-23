# Capability and platform limits

V3 Extensions are experimental and developer maintained. First-party V4 support comes through the new package registry; V3 source does not establish that support.

## The bridge is a control API

Calls require an allowed top-level app page. Untrusted frames and origins can receive no answer. Use small typed values and native identifiers, not audio, images or documents in query strings. Events can be missed during navigation or while no page exists; keep durable state native and expose a route to recover it.

Native code can use supported platform APIs within the extension contract. This is not a promise that arbitrary Xcode projects, Gradle plugins, SDKs or delegate replacements work unchanged. Check the actual host toolchain, dependencies, available hooks and signing requirements before choosing an implementation.

## Permission and lifecycle are part of the design

A declared permission does not grant runtime consent. An entitlement declaration does not obtain an Apple capability approval or provisioning profile. Store policy is separate from both the compiler and the extension validator. Report build-policy restrictions as build-policy restrictions; do not label every refusal an OS or store rule.

Do not promise uninterrupted execution after suspension, force-quit, reboot or permission revocation. Read the specific platform API and the implementation's recovery path. Background tasks are opportunities controlled by the OS, not timers with guaranteed delivery.

## Recorder's implemented boundaries

- Start recording from the foreground with microphone permission. The audio session on iOS and microphone foreground service on Android support the implemented background recording flow; interruptions and termination still matter.
- Android background service starts are subject to foreground-service and while-in-use microphone restrictions. Plan the start while the app is visible; do not promise that a worker can silently start recording later.
- Recorder's iOS upload uses an in-process ephemeral URLSession plus scheduled background processing, not a background URLSession daemon transfer. Suspension or process loss can interrupt it; retry starts at byte zero.
- Recorder's Android upload uses a regular WorkManager worker, not the separate user-initiated transfer implementation. Regular workers have a limited execution window; a retry is not a resumable byte-range upload.
- Its server contract is raw `PUT` with `Content-Type: audio/mp4`, not multipart. A general file-picker/background-upload capability and a Recorder-plus-uploader combination require their own integration and verification.

## Widgets and shared data

Widget timelines, Live Activities, notification presentation and refresh frequency are controlled by their platform APIs and device settings. Do not promise an exact refresh interval or universal Lock Screen layout. App Groups require the real group, app/target identifiers and matching profiles. A successful signing step does not prove widget behavior, and a simulated pass does not establish every device or lifecycle case.

Read each ready-made extension's documented results as that exact test scope. Keep failures and untested combinations explicit rather than converting a source preview, build pass or partial result into a production-ready claim.
