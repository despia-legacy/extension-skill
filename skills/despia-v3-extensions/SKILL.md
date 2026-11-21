---
name: despia-v3-extensions
description: Write a Despia V3 Extension, native Swift (iOS) and Kotlin or Java (Android) code that a Despia V3 web-view app calls with extension://<name>/<route>. Use when the user wants custom native code, a native target (widget, notification extension, share extension) or a native module in a Despia V3 app, or mentions V3 Extensions, extensions.json, an extension manifest.json, or the extension:// scheme. Despia V3 only.
---

# Despia V3 Extensions

For the Despia V3 runtime only, and experimental. You write native files, describe them in a JSON manifest, and serve them from the web app. Despia approves the files and compiles them into a new native build. The page then calls `extension://<name>/<route>?<params>`. All facts are in `references/`; read the file for the step you are on. Never invent manifest fields, types, hooks or error codes. If a fact is not in the references, say so and point to https://docs.despia.com/experiments/v3-extensions .

## Workflow

1. Confirm the app is on Despia V3 and the capability is not already a Despia feature. Read `references/limits.md` and tell the user the platform limits that apply.
2. Lay out `public/despia/extensions.json` and `public/despia/<name>/manifest.json` with `ios/` and `android/` beside it (`references/manifest.md`). The folder name is the extension id and matches `[a-z][a-z0-9]{1,23}`.
3. Declare each route, its typed parameters (`string`, `int`, `bool`, `enum`, `https_url`), its `output` variable and callback, and any events (`references/manifest.md`, `references/routes-and-answers.md`).
4. Write the iOS handler (`references/ios.md`) and the Android handler (`references/android.md`). Answer every call once, with data or an error code and message.
5. Declare native work as typed or custom (`references/manifest.md`). To use a library, name a Swift package (GitHub URL, full commit revision, products) under `ios.packages`, or a Maven library with `implementation` in a custom Android module's `build.gradle`; Despia fetches and pins it at approval, and the developer never downloads anything (`references/manifest.md`, "Adding a Swift package or a Maven library").
6. Write the web call and its answer handling (`references/routes-and-answers.md`).
7. Copy the pattern from `examples/recorder/` (the Background Recorder sample, notes in `references/recorder-sample.md`) when a complete template helps.
8. Tell the user the approval flow: Despia approves `extensions.json` and every referenced file, each `manifest.json` included. Any change needs a new approval and a new native build, never over-the-air. Extensions are enabled per app by Despia. The user owns and maintains the native code; Despia does not support customised code.

## Rules

- Never set what the build owns (bundle id, versions, signing, URL types, App Transport Security, purpose strings and permissions set in Despia, push and associated-domain entitlements). See `references/manifest.md`.
- Every file must be named in its `manifest.json`; hidden files are refused; keep large data native and pass identifiers through the bridge.
- Do not claim V4 support. First-party V4 support comes through the new package registry.

Docs: https://docs.despia.com/experiments/v3-extensions and https://docs.despia.com/experiments/samples/recorder
