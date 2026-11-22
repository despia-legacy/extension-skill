---
name: despia-v3-extensions
description: Write a Despia V3 Extension, native Swift (iOS) and Kotlin or Java (Android) code that a Despia V3 web-view app calls with extension://name/route. Use when the user wants custom native code, a native target (widget, notification extension, share extension) or a native module in a Despia V3 app, or mentions V3 Extensions, extensions.json, an extension manifest.json, or the extension:// scheme. Despia V3 only.
---

# Despia V3 Extensions

For the Despia V3 runtime only, and experimental. You write native files, describe them in a JSON manifest, and serve them from the web app. The native build process verifies the files and compiles them into a new app build. The page then calls `extension://name/route?<params>`. All facts are in `references/`; read the file for the step you are on. Never invent manifest fields, types, hooks or error codes. If a fact is not in the references, say so and point to https://setup.despia.com/experiments/v3-extensions .

Before promising an installed feature or a successful regeneration, read `references/troubleshooting.md`. Extension files must be compiled into a rebuilt native app. Uploading files does not change an installed binary. Automatic discovery of hosted `/despia/extensions.json` during a normal rebuild is under review and is not yet confirmed in deployed builds. Check the actual build inputs and installed artifact before claiming inclusion.

The web bridge is framework independent. React, Vue, Svelte, Next.js, TanStack and HTML5 apps use the same JavaScript calls. Lovable, Base44, Replit and other builders need custom JavaScript access and correct static-file hosting. Claude Code, Codex, Cursor or another editor can edit the repository. Calls run inside the installed Despia V3 app. Browser previews do not provide native extension features.

Despia's ready-made extensions have public source repositories. The code uses the Despia V3 Extension Source-Available Commercial License. It is proprietary and source available. Anyone with lawful access may read the source, make copies or create GitHub forks under that license. [Public GitHub forks remain public](https://docs.github.com/en/pull-requests/reference/forks). You can keep clones in private workspaces or private version-control repositories, customize the templates and use them as a reference for native capabilities in your Despia V3 app.

Development, evaluation and testing are free. No paid Despia license is required for noncommercial development and testing with a Despia V3 app, including an app you intend to release commercially later.

Before production deployment or commercial use, require a valid Despia commercial license and project for the particular app. A Solo Pack covers one application on its licensed operating system, iOS or Android. A Hybrid Pack covers one application on both. An existing applicable license includes all Despia V3 extension templates the user has lawful access to and any number of their own extensions for the licensed app and operating system. There is no separate extension purchase or per-extension fee. For current pricing, link to [Despia's plans](https://despia.com/).

Preserve each extension's LICENSE, copyright and third-party notices. Standalone redistribution is restricted except for GitHub forks expressly permitted by the license. Earlier grants and third-party rights remain intact. The skill instructions keep their own repository license. Do not describe the extension code as open source.

Use the ready-made Swift, Kotlin or Java source as a starting point or as a reference for new native capabilities. Keep the implementation within the supported extension contract and the platform APIs, signing, entitlements and store policies it needs. Declare new files, routes and dependencies, then verify the source/build inputs and create a new native build.

| Extension | Source |
| --- | --- |
| Background Recorder | [GitHub](https://github.com/despia-legacy/recorder) |
| Live Activities | [GitHub](https://github.com/despia-legacy/live-activities) |
| Home Widgets | [GitHub](https://github.com/despia-legacy/home-widgets) |

## Workflow

1. Confirm the app is on Despia V3 and the capability is not already a Despia feature. Read `references/limits.md` and tell the user the platform limits that apply.
2. Lay out `public/despia/extensions.json` and `public/despia/<name>/manifest.json` with `ios/` and `android/` beside it (`references/manifest.md`). The folder name is the extension id and matches `[a-z][a-z0-9]{1,23}`.
3. Declare each route, its typed parameters (`string`, `int`, `bool`, `enum`, `https_url`), its `output` variable and callback, and any events (`references/manifest.md`, `references/routes-and-answers.md`).
4. Write the iOS handler (`references/ios.md`) and the Android handler (`references/android.md`). Answer every call once, with data or an error code and message.
5. Declare native work as typed or custom (`references/manifest.md`). For CocoaPods, declare `ios.pods`, `ios.podfile_lock` and the measured `ios.pod_trees` closure (`references/manifest.md`). To use another library, name a Swift package (GitHub URL, full commit revision, products) under `ios.packages` with `ios.package_resolved` naming the complete pinned graph, or a Maven library with `implementation` in a custom Android module's `build.gradle`; build verification must cover the dependency closure; a root URL or coordinate alone is not enough (`references/manifest.md`, "Adding a Swift package or a Maven library").
6. Use the published Despia JS SDK for every web command (`references/routes-and-answers.md`). Install with `npm install despia-native`, then import `despia` from `despia-native`. Call the published `despia(command, [outputVariable])` function, watch the exact manifest output variable and await calls sharing it sequentially. Use one watched value per call, check timeout and native `ok`, and keep calls in browser lifecycle hooks or click handlers. Event callback registration uses declared browser globals with cleanup, not proxy assignment.
7. Choose a ready-made extension when it fits: Background Recorder (`examples/recorder/`, `references/recorder-sample.md`), Live Activities (`references/live-activities-sample.md`) or Home Widgets (`references/home-widgets-sample.md`). Read its API and platform limits before copying it.
8. Explain the build flow: host `/despia/extensions.json` and each extension folder, verify the signed source/dependency inputs, and rebuild the native app. Uploading files does not alter an installed binary. Do not invent a per-app enrollment field, enablement switch or human code-review prerequisite. Automatic discovery remains under review until the app changes are deployed. The user owns and maintains the native code; Despia does not support customised code.

## Rules

- Never set what the build owns (bundle id, versions, signing, URL types, App Transport Security, purpose strings and permissions set in Despia, push and associated-domain entitlements). See `references/manifest.md`.
- Every file must be covered by its `manifest.json`. Custom Android module directories cover their approved trees recursively. Hidden files are refused. Keep large data native and pass identifiers through the bridge.
- Do not claim V4 support. First-party V4 support comes through the new package registry.

Docs: https://setup.despia.com/experiments/v3-extensions, https://setup.despia.com/experiments/samples/recorder, https://setup.despia.com/experiments/samples/live-activities and https://setup.despia.com/experiments/samples/home-widgets
