---
name: despia-v3-extensions
description: Write a Despia V3 Extension, native Swift (iOS) and Kotlin or Java (Android) code that a Despia V3 web-view app calls with extension://name/route. Use when the user wants custom native code, a native target (widget, notification extension, share extension) or a native module in a Despia V3 app, or mentions V3 Extensions, extensions.json, an extension manifest.json, or the extension:// scheme. Despia V3 only.
---

# Despia V3 Extensions

For the Despia V3 runtime only, and experimental. You write native files, describe them in a JSON manifest, and serve them from the web app. Despia approves the files and compiles them into a new native build. The page then calls `extension://name/route?<params>`. All facts are in `references/`; read the file for the step you are on. Never invent manifest fields, types, hooks or error codes. If a fact is not in the references, say so and point to https://setup.despia.com/experiments/v3-extensions .

Before promising an installed feature or a successful regeneration, read `references/troubleshooting.md`. Hosting extension files and completing a regular Despia build do not enable the extension runtime. Confirm an approved extension build for the app; if availability is unknown, prepare the code and report the deployment requirement without claiming it works.

The web bridge is framework independent. React, Vue, Svelte, Next.js, TanStack and HTML5 apps use the same JavaScript calls. Lovable, Base44, Replit and other builders need custom JavaScript access and correct static-file hosting. Claude Code, Codex, Cursor or another editor can edit the repository. Calls run inside the installed Despia V3 app. Browser previews do not provide native extension features.

Despia's ready-made extensions are source available under a commercial license. Using or modifying them requires a valid Despia commercial license for the particular app and operating system, plus a valid project for that app. Standard price: USD 249 per application per operating system. Preserve each extension's LICENSE and third-party notices. The skill instructions keep their own repository license; do not describe the extension code as open source.

## Workflow

1. Confirm the app is on Despia V3 and the capability is not already a Despia feature. Read `references/limits.md` and tell the user the platform limits that apply.
2. Lay out `public/despia/extensions.json` and `public/despia/<name>/manifest.json` with `ios/` and `android/` beside it (`references/manifest.md`). The folder name is the extension id and matches `[a-z][a-z0-9]{1,23}`.
3. Declare each route, its typed parameters (`string`, `int`, `bool`, `enum`, `https_url`), its `output` variable and callback, and any events (`references/manifest.md`, `references/routes-and-answers.md`).
4. Write the iOS handler (`references/ios.md`) and the Android handler (`references/android.md`). Answer every call once, with data or an error code and message.
5. Declare native work as typed or custom (`references/manifest.md`). For CocoaPods, declare `ios.pods`, `ios.podfile_lock` and the measured `ios.pod_trees` closure (`references/manifest.md`). To use another library, name a Swift package (GitHub URL, full commit revision, products) under `ios.packages` with `ios.package_resolved` naming the complete pinned graph, or a Maven library with `implementation` in a custom Android module's `build.gradle`; approval must cover the dependency closure; a root URL or coordinate alone is not enough (`references/manifest.md`, "Adding a Swift package or a Maven library").
6. Use the published Despia JS SDK for every web command (`references/routes-and-answers.md`). Import `despia` from `despia-native`. Watch the real manifest output variable and serialize calls sharing it, or copy the app-local concurrent helper from `examples/web/despia-extension.js`. Use one watched value per call, check timeout and native `ok`, and keep calls in browser lifecycle hooks or click handlers. Event callback registration uses declared browser globals with cleanup, not proxy assignment.
7. Choose a ready-made extension when it fits: Background Recorder (`examples/recorder/`, `references/recorder-sample.md`), Live Activities (`references/live-activities-sample.md`) or Home Widgets (`references/home-widgets-sample.md`). Read its API and platform limits before copying it.
8. Tell the user the approval flow: Despia approves `extensions.json` and every referenced file, each `manifest.json` included. Any change needs a new approval and a new native build, never over-the-air. Extensions are enabled per app by Despia. The user owns and maintains the native code; Despia does not support customised code.

For advertiser SDK source previews, read `references/meta-ads-sample.md`, `references/tiktok-ads-sample.md` and `references/google-ads-sample.md`. For device calendars and platform methods, read `references/calendar-sample.md`. For iOS ATT request and status callbacks, read `references/tracking-consent.md`.

## Rules

- Never set what the build owns (bundle id, versions, signing, URL types, App Transport Security, purpose strings and permissions set in Despia, push and associated-domain entitlements). See `references/manifest.md`.
- Every file must be covered by its `manifest.json`. Custom Android module directories cover their approved trees recursively. Hidden files are refused. Keep large data native and pass identifiers through the bridge.
- Do not claim V4 support. First-party V4 support comes through the new package registry.

Docs: https://setup.despia.com/experiments/v3-extensions, https://setup.despia.com/experiments/samples/recorder, https://setup.despia.com/experiments/samples/live-activities and https://setup.despia.com/experiments/samples/home-widgets
