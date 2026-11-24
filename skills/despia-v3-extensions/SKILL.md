---
name: despia-v3-extensions
description: Create, adapt or debug experimental Despia V3 native extensions, including extensions.json, per-folder manifest.json, extension:// routes, Swift handlers, Android Java or Kotlin modules, widgets and pinned native dependencies. Use for extension build failures, missing SDK answers, permissions, App Groups and background work in a Despia V3 web-view app. V3 only.
---

# Despia V3 Extensions

An extension is native source compiled into a Despia V3 app, with a manifest-defined web API. Hosting source supplies build inputs. It does not install native code in a running app. This system is experimental; do not turn a local proof or a proposed runtime change into a release claim.

## Start with the right contract

1. Confirm Despia V3, the target platforms and the actual native build/runtime version. Read `references/build-and-release.md` before promising inclusion or regeneration. Automatic discovery of hosted `/despia/extensions.json` during normal rebuilds is under review and is not confirmed deployed. Do not invent a per-app enablement switch, enrollment field or manual source-review prerequisite.
2. Describe the native capability, target OS versions, web inputs and outputs, backend wire format if any, and what must survive page reload, backgrounding or process death. Read `references/limits.md`. If a system prompt or long operation is involved, design a prompt acknowledgement plus state/event routes rather than keeping a bridge call open indefinitely.
3. Read `references/manifest.md` before writing JSON. The current index requires `app` identity and 1 to 8 extension folder names. A folder's `manifest.json` has `version`, not `id` or `manifest_version`. Optional-identity and other held rollout changes are not the current contract.
4. Read `references/routes-and-answers.md` before writing web code. Use the published npm SDK directly. Watch the exact declared output variable, serialize calls sharing it, and check both no-answer timeout and native `ok`. Do not invent SDK methods, callback-value fallback or event subscription APIs.

## Implement and verify

| Work | Read first | Result to produce |
| --- | --- | --- |
| Layout, typed parameters, paths, targets and dependency pins | `references/manifest.md` | A declared source tree and valid manifest for the actual runtime |
| Swift, lifecycle and background callbacks | `references/ios.md` | A handler using the public native context and declared hooks |
| Java or Kotlin, services, workers and activity permissions | `references/android.md` | A handler and modules compatible with the host toolchain |
| Missing replies, build or signing failures | `references/troubleshooting.md` | The failing stage and a focused reproduction, without guessing |
| Build inclusion and release evidence | `references/build-and-release.md` | Exact source, selected build, installed artifact and behavioral scope |

Native handlers answer each admitted active call once. A route timeout does not undo native work. After asynchronous waits, check call activity before starting work for that call. Preserve host delegate ownership and keep files and long-lived state native. Never invent context methods, lifecycle hooks, manifest fields or build-service routes.

Declare complete pinned dependency graphs, not just a package URL or Maven coordinate. Follow the existing Swift package, CocoaPods and custom Android module examples in `references/manifest.md`. Verify real vendor SDK signatures against the selected version; mocks and standalone type checks do not prove integration into the host app.

For web code, install `despia-native`, import `despia` from it and call `await despia(command, ['declaredOutputVariable'])` from a browser event or client lifecycle hook. The SDK import is SSR-safe; native calls require the installed app. Native errors resolve as envelopes, not rejected promises. Do not replace the SDK with a local command wrapper in the recommended recipe.

## Adapt a ready-made extension

| Extension | Reference | Source |
| --- | --- | --- |
| Background Recorder | `references/recorder-sample.md`, bundled `examples/recorder/` | https://github.com/despia-legacy/recorder |
| Live Activities | `references/live-activities-sample.md` | https://github.com/despia-legacy/live-activities |
| Home Widgets | `references/home-widgets-sample.md` | https://github.com/despia-legacy/home-widgets |
| Meta Ads (source preview) | `references/meta-ads-sample.md` | https://github.com/despia-legacy/meta-ads |
| TikTok Ads (source preview) | `references/tiktok-ads-sample.md` | https://github.com/despia-legacy/tiktok-ads |
| Google Ads (source preview) | `references/google-ads-sample.md` | https://github.com/despia-legacy/google-ads |
| Calendar (source preview) | `references/calendar-sample.md` | https://github.com/despia-legacy/calendar |
| Alarms (source preview) | `references/alarms.md` | https://github.com/despia-legacy/alarms |
| App Lock (source preview) | `references/app-lock.md` | https://github.com/despia-legacy/app-lock |

For iOS App Tracking Transparency requests and status, read `references/tracking-consent.md`. For authoring a new extension from scratch, read `references/authoring.md`. The Home Widgets extension is separate from the first-party Home Widget setting (`widget://`), which needs no extension.

Read the extension's actual routes, platform requirements and recorded test scope before adapting it. Keep stable identifiers and existing extension names when changing the index. Recorder's own raw audio PUT uploader is separate from a general file-picker or multipart background-upload capability. Do not promise an untested combination, indefinite background execution or recovery after every kind of termination.

## Ownership and licensing

Despia's ready-made extensions have public source repositories. The code uses the Despia V3 Extension Source-Available Commercial License. It is proprietary and source available. Anyone with lawful access may read the source, make copies or create GitHub forks under that license. [Public GitHub forks remain public](https://docs.github.com/en/pull-requests/reference/forks). You can keep clones in private workspaces or private version-control repositories, customize the templates and use them as a reference for native capabilities in your Despia V3 app.

Development, evaluation and testing are free. No paid Despia license is required for noncommercial development and testing with a Despia V3 app, including an app you intend to release commercially later.

Before production deployment or commercial use, require a valid Despia commercial license and project for the particular app. A Solo Pack covers one application on its licensed operating system, iOS or Android. A Hybrid Pack covers one application on both. An existing applicable license includes all Despia V3 extension templates the user has lawful access to and any number of their own extensions for the licensed app and operating system. There is no separate extension purchase or per-extension fee. For current pricing, link to [Despia's plans](https://despia.com/).

Preserve each extension's LICENSE, copyright and third-party notices. Standalone redistribution is restricted except for GitHub forks expressly permitted by the license. Earlier grants and third-party rights remain intact. The skill instructions keep their own repository license. Do not describe the extension code as open source.

## Delivery boundaries

- Preserve build-owned app identity, signing, host permissions and entitlements. A manifest declaration is neither runtime user consent nor an Apple capability grant. Use the currently supported configuration path, not a field from a held proposal.
- Files must stay inside the extension tree and be declared. Custom Android modules cover their approved directory trees recursively. Apply the exact path rules in `references/manifest.md`; do not copy editor caches, secrets or arbitrary hidden files.
- Keep credentials, private URLs and user content out of examples and diagnostic logs. Use sanitized error codes and source/build identifiers when reporting failures.
- Report what ran, on which source/artifact and with what limits. A default build, simulated handler, sample test or successful signing step does not prove a selected feature is ready for production.
- V3 extensions do not establish V4 support. First-party V4 support comes through the new package registry.

Public guide: https://setup.despia.com/experiments/v3-extensions . Troubleshooting: https://setup.despia.com/experiments/extension-troubleshooting .
