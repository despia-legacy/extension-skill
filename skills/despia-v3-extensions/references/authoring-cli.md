# Build an extension with the despia-extensions CLI

Generated from the docs pages below; do not edit by hand. Change the page and regenerate.


## Create an extension

Page: https://setup.despia.com/extensions/build/create

```bash
npx despia-extensions create weather \
  --route "current:lat=string!,units=enum(metric|imperial)" \
  --event changed \
  --config "apiKey:string:public!"
cd weather && npx despia-extensions next
```

`create` writes a complete extension repository that already compiles and answers. `next` then tells you the one next step, every time you run it.

### Every input is a flag, or one file

No command ever asks a question. Give everything as flags, or the same fields in one spec file with `--spec weather.spec.json`.

| Flag | Meaning |
| --- | --- |
| `--route "name:param=type,..."` | A route. Types: `string`, `int`, `int(0..10)`, `bool`, `enum(a\|b)`, `https_url(api.example.com)`. `!` after a type marks a required parameter. Repeatable. |
| `--event name` or `--event name:app` | An event the extension can send. `:app` delivers it to the app's top-level page instead of the pages that called the extension. Repeatable. |
| `--config "key:type:public!"` | A setting. `public` ships in the app; `secret` is never stored and is refused at install. `!` marks it required. Repeatable. |
| `--platform ios,android` | The platforms to write. Both by default. |
| `--android-lang kotlin\|java` | The Android language. iOS is Swift. |
| `--dir <dir>` | Where to write. Inside a web project that has `despia.extensions.json`, the default is `extensions/<id>/`. |
| `--title`, `--description` | Shown by `info` and in the generated README |
| `--license <SPDX id>` | Your licence |
| `--json` | One JSON object on stdout |

A folder that is not empty is refused (exit 5, `DX-A003`) unless every file in it is what `create` would write. `create` never overwrites your files.

### What you get

```text
weather/
├── despia.extension.json            descriptor: title, settings, docs (read by the CLI, never served)
├── extension/                       the part your app serves, copied to public/despia/weather/
│   ├── manifest.json                routes, parameters, answers, events, native files
│   ├── ios/app/WeatherExtension.swift   entry type: one case per route
│   ├── ios/app/WeatherCore.swift        your logic: Foundation only
│   ├── ios/app/WeatherPlatform.swift    the only file with Apple frameworks
│   ├── ios/app/WeatherRoutes.swift      generated route table, typed parameters, events, settings
│   └── android/weather/...              the same parts in Kotlin or Java, plus build.gradle and resources
├── web/weather.js, web/weather.d.ts generated page helper and types
├── mock/weather.mock.js             answers for the browser mock bridge
├── tests/contract/current.json      one contract file per route: parameters in, answer out
├── tests/ios/, tests/android/       XCTest and JVM tests for Core
├── Package.swift                    compiles Core and Entry with Swift on Linux or macOS
├── weather.policy.json              the native capabilities the extension uses
├── .github/workflows/despia-extension.yml   Linux and macOS jobs
├── .devcontainer/                   JDK 17, Android SDK and Swift for Linux
└── AGENTS.md                        the loop for coding agents (CLAUDE.md and .cursor/rules point to it)
```

### Why the code is split in three

The split decides how much you can check without a Mac.

| File | Holds | Compiles where |
| --- | --- | --- |
| `<Id>Core` | Your logic. Foundation only in Swift, no `android.*` in Kotlin or Java. | Linux and macOS. Its tests run on Linux. |
| `<Id>Extension` | The entry type. It switches over the generated `<Id>Call` with no default case. | Linux and macOS |
| `<Id>Platform` | The only file with Apple frameworks or Android APIs (`#if canImport(UIKit)` in Swift) | The iOS SDK on a Mac; Android on Linux |
| `<Id>Routes` (generated) | The route table, typed parameters, events, error codes and typed settings | Everywhere |

A route in `manifest.json` without its handler does not compile: an exhaustive Swift `switch`, a Kotlin `when` over a sealed interface, a Java `Handler` interface. `check` also refuses a Core or Entry file that imports an Apple UI framework or `android.*` (`DX-C150`).

### Change it later

```bash
npx despia-extensions route add "forecast:days=int(1..7)!" --timeout 15000
npx despia-extensions event add alert:app
npx despia-extensions config add region:string:public
npx despia-extensions gen --check
```

`route add` also writes a contract file for the new route and tells you which handler to add; until you add it, the native code does not compile. Every command regenerates. A setting is read as `WeatherConfig.region` in Swift and `WeatherConfig.region(context)` in Kotlin. `gen` rewrites the generated files from `manifest.json` and `despia.extension.json`; `gen --check` fails when one is stale. Never edit a file marked `GENERATED`.

### Next

Run the [local loop](https://setup.despia.com/extensions/build/local-loop). At any point, `npx despia-extensions next` prints the one next step.


## Local loop without a Mac

Page: https://setup.despia.com/extensions/build/local-loop

```bash
npx despia-extensions validate
npx despia-extensions check
npx despia-extensions next
```

These run on any machine with Node 18.17 or later. `validate` also needs Python 3.9 or later for the build's own validator. They take seconds.

### What each step proves

| Step | Proves | Does not prove |
| --- | --- | --- |
| `gen --check` | The generated route tables, web helper and types match `manifest.json` and `despia.extension.json` | Anything about your code |
| `validate` | Installed into a scratch web app with the example values, the extension passes `doctor` and the same validator the build uses, for iOS and Android | That your code compiles or works |
| `check` | Generated files are current, every route has a contract file, the mock loads, and Core and Entry import no UI framework | That routes behave correctly |
| Mock bridge | Your page calls the contract correctly and handles answers, errors and timeouts | Anything native |
| `compile`, `test` | See [Compile and test](https://setup.despia.com/extensions/build/compile-and-test) | |

`next` reads what has passed for the current files (kept in `.despia/state.json`) and prints the one next step. After you change a file, steps that depend on it count as not done again.

### Contract checks

| Code | Means | Fix |
| --- | --- | --- |
| `DX-C101` | A generated file is stale or missing | Run `gen`. Never edit a file that says `GENERATED`. |
| `DX-C110` | A route has no contract file | Add `tests/contract/<route>.json` with at least one case |
| `DX-C111` | A contract file names a route `manifest.json` does not declare | Rename it, or declare the route with `route add` |
| `DX-C112` | A contract file is malformed | Write `{"route", "cases": [{"name", "params", "expect": {"ok": true, "data"?} \| {"code"}}]}` |
| `DX-C120` | `despia.extension.json` describes a route or event `manifest.json` does not declare | Remove the entry, or declare the route or event |
| `DX-C140` | The mock handlers file does not load, or answers an undeclared route | Fix `mock/<id>.mock.js`; it exports an object of route functions |
| `DX-C150` | A Core or Entry file imports an Apple UI framework or `android.*` | Move that code into the Platform file |

### Try your page in a browser

The mock bridge answers `extension://` calls in any browser. It checks each call, delivers answers and events, and times out exactly as the app's bridge does. Your page keeps calling the real `despia-native` SDK. `npx despia-extensions mock` prints the snippet for your extension:

```js
// Development only: the mock answers extension:// calls the way the app's bridge does.
import { installMockBridge } from 'despia-extensions/mock';
import manifest from './extension/manifest.json' with { type: 'json' };
import descriptor from './despia.extension.json' with { type: 'json' };
import handlers from './mock/weather.mock.js';

if (import.meta.env?.DEV) {
  installMockBridge({ manifests: { weather: manifest }, descriptors: { weather: descriptor }, handlers: { weather: handlers }, platform: 'ios' });
}
```

Handlers live in `mock/<id>.mock.js`, one function per route: `(params, ctx) => data`, or `ctx.error(code, message)`. Send an event with `ctx.emit(event, data)`. A route with no function answers from its `returns` example in `despia.extension.json`.

Switch scenarios from the console or a test. Every method returns the control, so calls chain:

```js
window.__despiaMock.platform('android');
window.__despiaMock.latency(800);
window.__despiaMock.fail('weather/current', 'unavailable');
window.__despiaMock.notIncluded('weather');   // an app built without the extension
window.__despiaMock.belowMinSdk('weather');   // a device below the extension's minimum
window.__despiaMock.emit('weather', 'changed', { temp: 21 });
window.__despiaMock.reset();
```

The mock refuses to start where a real Despia bridge is present, so it can never hide the native one.

> **Warning:** A page that works against the mock says nothing about your native code. It proves only that the page calls the contract correctly.

### Next

[Compile and test](https://setup.despia.com/extensions/build/compile-and-test).


## Compile and test

Page: https://setup.despia.com/extensions/build/compile-and-test

```bash
npx despia-extensions setup              # what this machine can run
npx despia-extensions compile android
npx despia-extensions compile ios
npx despia-extensions test               # every tier this machine can run
```

> **Warning:** `compile` and `test` run the repository's own build files and test code (Gradle scripts, `Package.swift`, tests) with your rights. Run them only on a repository you trust.

### What this machine can run

`setup` reports each tier and what is missing, with the way to get it. It never installs anything and never accepts a licence for you. The `.devcontainer/` that `create` writes has every Linux tier.

| Tier | Needs |
| --- | --- |
| `contract` | Node only |
| `android` | JDK 17 or later, Gradle 8.14 or later (or `DESPIA_EXT_GRADLE`), and the Android SDK (`ANDROID_HOME`) with platform `android-36`. Installing the platform asks you to accept Google's SDK licence. |
| `swift` | Swift on this machine, or Docker with `DESPIA_EXT_SWIFT_IMAGE` (for example `swift:6.2-noble`) |
| `ios-sim` and the iOS SDK compile | macOS with Xcode |

### Compile

Each file in the result is reported with the check it actually got:

| Signal | Means |
| --- | --- |
| `android-sdk` | Compiled by the Android Gradle plugin against `android.jar` (compileSdk 36) |
| `swift-host` | Compiled by Swift on this machine without Apple UI frameworks (Linux, or SwiftPM on macOS). Code inside `#if canImport(UIKit)` is not compiled, and the result says so. |
| `ios-sdk` | Compiled by `xcodebuild` against the iOS Simulator SDK |
| `none` | Not compiled here, with the reason |

`compile ios` uses the iOS SDK on a Mac with Xcode, and Swift without UI frameworks elsewhere. A compile error is reported with its file and line (`DX-K201` Kotlin or Java, `DX-K202` Swift). A missing route branch, or a non-exhaustive `switch`, means a route has no handler yet.

`--via github` does not start a hosted run: the CLI exits 8 (`DX-K104`) and tells you to push. The repository's workflow, `.github/workflows/despia-extension.yml`, runs a Linux job (`validate`, `check`, `compile android`, Android tests), a Swift job, and a macOS job (`compile ios` with the iOS SDK and Simulator tests).

### Test

```bash
npx despia-extensions test --tier contract,android,swift,ios-sim
```

| Tier | Runs |
| --- | --- |
| `contract` | Every case in `tests/contract/` against the declared parameters and answers |
| `android` | The JVM tests in `tests/android/` |
| `swift` | The XCTest tests in `tests/ios/` with Swift on this machine |
| `ios-sim` | The same tests on an iOS Simulator (macOS with Xcode) |

A tier this machine cannot run is reported as skipped with the reason, never as passed. When a tier you asked for was skipped and nothing failed, the exit code is 7. A failed test is `DX-T001`; a tier with no tests is `DX-T002`. Results are written to `.despia/results/`.

### Exit codes for these steps

| Code | Meaning |
| --- | --- |
| 0 | ok |
| 3 | a compile error or a failed test |
| 7 | a toolchain this step needs is missing here; `setup` names it |
| 8 | a hosted run was asked for; push instead |

### What still needs your app

A compile and tests prove your extension's code. They do not prove it inside your app. Install it into your web project, deploy, rebuild the native app, and run the feature in the installed app on a device. See [Publishing and sharing](https://setup.despia.com/extensions/build/publishing).


## Publishing and sharing

Page: https://setup.despia.com/extensions/build/publishing

```bash
npx despia-extensions add github:your-org/weather#v1.2.0
```

A git repository, public or private, is how you share an extension. Whoever installs it runs one command.

### Share a repository

- Tag a version and give people the `add github:owner/repo#tag` command. A branch or tag is resolved to a commit, and the installed files are locked by their tree hash in `despia.extensions.lock.json`.
- A private repository needs the installer's own read access: `GH_TOKEN` in their environment. The CLI never stores the token.
- A folder works too: `npx despia-extensions add ./extensions/weather`. Inside a web project, `create` writes to `extensions/<id>/` by default.
- `info` shows installers your descriptor: title, description, settings, routes, permissions, SDK pins and your docs link.

> **Warning:** An installed extension is served from the app's own website at `/despia/<id>/`. Anyone can read those files there, even when the repository is private. Never put a secret in an extension. Declare a server secret as a `secret` setting: the CLI refuses to store it and tells the installer which server variable to use.

### Before you ship it in an app

`validate`, `check`, `compile` and `test` prove your extension's code. Before a release, prove it in the app:

1. Install it into the web project with `add`, run `npx despia-extensions doctor --strict`, and deploy.
2. Run `npx despia-extensions doctor --hosted https://your.app` to check what your site really serves.
3. Start a native rebuild. The build fetches and verifies every file and pinned dependency again, and stops with an `NX-` code if anything differs.
4. Install the rebuilt app and run every route, the permission prompts and their denial, backgrounding and relaunch on a real device.
5. Record the source commit, the build and what you ran. A validator, a mock, a compile, a simulator and a device are different levels of evidence; say which one you have.

Store review is separate. A permission or capability your extension adds must be justified in the app's store listing.

### Licence

Pick a licence when you create the extension (`create --license <SPDX id>`). Your own extension code is yours, under the licence you choose.

The ready-made Despia extensions have their own source-available licence. If you start from one of them, your copy stays under that licence: keep its `LICENSE`, copyright and third-party notices with it.
