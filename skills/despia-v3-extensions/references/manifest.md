# Layout, manifest and native work

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

**Native rebuild required.** Extension files must be compiled into a rebuilt native app. Uploading files does not change an installed binary. Automatic discovery of hosted `/despia/extensions.json` during a normal rebuild is under review and is not yet confirmed in deployed builds. Read `troubleshooting.md` when a route does not answer.

V3 Extensions let you add your own native code to a Despia V3 app. You write the Swift (iOS) and Kotlin or Java (Android) files, describe them in a JSON manifest, and serve them from your web app. Despia builds them into your app. Your web code calls them with the Despia JS SDK from a client event:

```javascript
import despia from 'despia-native';

// Run from a browser click handler or client lifecycle hook.
async function readRecorderStatus() {
  const reply = await despia('extension://recorder/status', ['recorderStatus']);
  const answer = reply.recorderStatus;
  if (answer === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!answer.ok) console.error(answer.code, answer.error);
  return answer;
}
```

Use V3 Extensions when you need a native capability that Despia does not offer as a feature yet, or when you need full control over the native code. The [ready-made extensions](/experiments/samples/recorder) include source you can copy and customize.

## How it works

- **The manifest declares the extension.**
    Each extension has a `manifest.json`. Its folder supplies the extension id. The manifest declares its routes, the typed parameters of each route, the answer variable and callback of each route, its events, and the native files for each platform.
- **Despia builds it into your app.**
    Your files are served from your app's own HTTPS origin. The extension build process fetches and verifies the index, source files and pinned dependencies, then compiles them into a new native build. Signed inputs bind the build to your app. Changed source needs fresh build verification and a native rebuild; it cannot be applied over-the-air.
- **Your web app calls a route.**
    The page sends `extension://<id>/<route>?<params>`. The runtime first checks the call: the extension and route exist, only declared parameters are present, and each value has the declared type. Only then does it call your native handler.
- **The answer comes back once.**
    Every admitted call gets one terminal answer in its calling document while that document remains eligible to receive it. Missing runtimes and rejected callers do not provide that guarantee. The runtime sets the route's answer variable on `window` and then calls the route's callback with it.

## Source and build verification

The build verifies source hashes, the complete pinned dependency graph, app identity and the signed build lock. Signing checks also compare the compiled artifact with those inputs. These checks protect build hosts, signing keys and app boundaries. You remain responsible for the behavior and maintenance of your extension code. Apple-managed capabilities, provisioning and store requirements still apply.

## Folder layout in your web app

Put the files in your web app's `public` folder, so they are served from your app's own origin:

```text
public/despia/
├── extensions.json              # your app identity and the list of extension folders
└── recorder/                    # one folder per extension; the folder name is the extension id
    ├── manifest.json            # routes, parameters, outputs, events and native files
    ├── ios/
    │   └── ...                  # Swift sources and targets
    └── android/
        └── ...                  # Java or Kotlin sources and resources
```

`extensions.json` names your app and lists the extension folders:

```json public/despia/extensions.json
{
  "extensions": [
    "recorder"
  ]
}
```

`extensions.json` lists the extension folders.

Despia takes app identity from the existing build configuration. You do not copy it into this file. A legacy `app` block remains optional and must match the build if present. The signed build lock must still match the app being built.

`extensions.json` lists 1 to 8 folders, and each folder name matches `[a-z][a-z0-9]{1,23}`. Any other key is refused, including `manifest_version`. Each extension folder has its own `manifest.json` with a `version` field. A folder listed twice is also refused.

The folder name is the id in the scheme. `public/despia/recorder/` is called as `extension://recorder/<route>`. File paths in `manifest.json` are relative to the extension's folder and start with `ios/` or `android/`, for example `ios/app/RecorderExtension.swift`. An extension can only name files inside its own folder. A `manifest.json` that also sets `"id"` is refused, because the folder name is the id.

To add an extension to your app, copy its folder under `public/despia/` and add its name to `extensions.json`. Build verification covers `extensions.json`, every `manifest.json` and each referenced file. A change to any of them needs a fresh verified snapshot and native rebuild.

  Calls are accepted only from the top-level page of your app's own HTTPS origin, or from origins you list in `bridge.allowed_origins`. Iframes, other origins, popups and page sheets get no answer.

## Declaring routes, outputs and events

Each route in `manifest.json` declares its parameters and its output. The output names the `window` variable that receives the answer and the callback that is called with it:

```json public/despia/recorder/manifest.json (excerpt)
{
  "name": "start",
  "params": {
    "upload": {
      "type": "https_url",
      "hosts": ["upload.example.com"],
      "required": true
    }
  },
  "output": {
    "variable": "recorderStart",
    "callback": "onRecorderStart"
  }
}
```

For this route the runtime runs `window.recorderStart = <result>` and then `window.onRecorderStart(window.recorderStart)`.

If you leave `output` out, the defaults are `<id><Route>Result` for the variable and `on<Id><Route>Result` for the callback. For the route above that would be `recorderStartResult` and `onRecorderStartResult`.

Events are declared next to the routes, each with its own callback:

```json
"events": [
  { "name": "state", "callback": "onRecorderState", "scope": "document" }
]
```

Your native code sends an event with `emit("state", data)`, and the page's `window.onRecorderState(data)` runs. With `scope: "document"` (the default), only pages that have called the extension receive it. With `scope: "app"`, the trusted top-level page that is loaded receives it. Events are notifications only. Always read the current state through a route.

### Parameter types

Parameters are declared per route. Anything not declared is refused with `bad_request`.

| Type        | Value                                                    |
| ----------- | -------------------------------------------------------- |
| `string`    | Text, with an optional `max` length                      |
| `int`       | Whole number                                             |
| `bool`      | `true` or `false`                                        |
| `enum`      | One of a declared list of values                         |
| `https_url` | An `https` URL whose host is in the route's `hosts` list |

An `int` may declare `min` and `max` anywhere in the range -(2^53-1) to 2^53-1 (the default is -2^31 to 2^31-1), and a value outside it is refused. A `string`'s `max` is at most 4096.

Keep large data on the native side and pass identifiers through the bridge. A call URL is at most 8192 characters, and each extension accepts up to 20 calls per second.

## Validate the contract before native work

JSON parsing is only the first check. Use the runtime's manifest validator, then the selected native build. Do not add fields just because they sound useful.

| Item | Current contract |
| --- | --- |
| Index | Only `app` and `extensions`; 1 to 8 distinct extension folder names |
| Folder manifest | `version`; the folder supplies `id`, so an explicit `id` is rejected |
| Route names | Lowercase letter followed by lowercase letters, digits or `_`, up to 32 characters |
| Parameters | Only declared names and type-specific fields; `callback` is reserved for the bridge |
| Outputs | Valid JavaScript names; a route's output variable and callback must differ |
| Timeout | `timeout_ms` on the route, default 20000, range 1000 to 25000 milliseconds |
| Paths | Relative to the extension folder, in the platform tree, no traversal or symlinks |

Declare a string for encoded JSON only when the handler actually parses and validates that JSON. The bridge does not infer object types, file references or new routes from JavaScript. `https_url` host approval is a destination constraint, not authentication or a guarantee that a later redirect is safe. Recheck stored destinations before delayed network work.

Source paths and custom directory coverage must agree with the fetched tree. Names and case matter. Do not put credentials, build policy, signing material, package caches or editor directories in the hosted source tree. The only supported hidden resource member is `.xccurrentversion` directly inside a non-hidden `.xcdatamodeld` bundle; this does not allow arbitrary dotfiles.

## Declaring native work

Both ways are declared in an extension's `manifest.json`.

- **Typed.** On iOS, `targets[].kind` is a preset: `widgetkit`, `notification-service`, `notification-content`, `share` or `intents`. On Android, a module with `kind: "typed"` lists sources, resources, permissions and components (`service`, `receiver`, `provider`, `activity`) from a fixed vocabulary, and the build writes the Gradle file and manifest.
- **Custom.** On iOS, `kind: "custom"` takes a product type, build settings, script phases, a full `info_plist`, `entitlements`, link targets and packages. On Android, `kind: "custom"` points `dir` at your own module with its own `build.gradle`, `AndroidManifest.xml`, sources and resources.

## Adding a Swift package or a Maven library

Declare dependencies and include their required graph files. Build verification covers the complete pinned dependency closure. A package URL or Maven coordinate alone does not describe that graph or prove it was compiled into the app. Manually copying library binaries is not required.

**iOS: a Swift package from GitHub.** List it under `ios.packages` in `manifest.json` with its GitHub URL, the full commit `revision` of the release you want, and the products you use. Then link the product in your app target under `ios.app.packages`. Custom module example:

```json
{
  "ios": {
    "packages": [
      {
        "name": "stream-video-swift",
        "remote": {
          "url": "https://github.com/GetStream/stream-video-swift",
          "revision": "5fda355f0a682ebbf1e131d80d38386acd4c4406"
        },
        "products": ["StreamVideo"]
      }
    ],
    "package_resolved": "ios/Package.resolved",
    "app": {
      "packages": ["StreamVideo"]
    }
  }
}
```

Swift packages come from `github.com` over https. Remote packages require `ios.package_resolved` pointing to the complete pinned `Package.resolved` graph, including transitive packages. The snippet is an excerpt, not a graph file. Missing the reference is refused with `NX-M100`.

**iOS: a CocoaPod.** The manifest also accepts CocoaPods. Declare exact pod names and versions in `ios.pods`, point `ios.podfile_lock` at the lockfile under your extension's `ios/` folder, and put the installed-tree SHA-256 for every root pod in that lockfile in `ios.pod_trees`:

```json
{
  "ios": {
    "pods": [{ "name": "Lottie", "version": "4.5.0" }],
    "podfile_lock": "ios/Podfile.lock",
    "pod_trees": {
      "Lottie": "<installed-tree-sha256>"
    }
  }
}
```

This is an illustrative manifest excerpt. Replace `<installed-tree-sha256>` with the measured 64-character lowercase hexadecimal digest for each root pod in the complete lockfile closure. Include the declared versions and spec checksums. The build checks the installed trees and preserves the base app's existing pods.

**Android: a Maven library.** Add a `custom` module in `manifest.json` that points at your own module folder, and list the libraries in that module's `build.gradle` with `implementation`, the same way as in Android Studio:

```json
{
  "android": {
    "modules": [
      { "kind": "custom", "name": "streamcall", "dir": "android/streamcall", "link": "implementation" }
    ]
  }
}
```

```groovy
// public/despia/streamcall/android/streamcall/build.gradle
dependencies {
    implementation project(':nativeext-api')
    implementation 'io.getstream:stream-video-android-core:1.35.0'
}
```

Maven libraries come from Google's Maven repository and Maven Central. A library published only on a vendor's own Maven server is not accepted yet.

Both ways may also declare:

- **Android `min_sdk`** (21 to 36, typed modules). The module builds at that level. On a device below it, the extension is never created and every route answers `platform_limit`.
- **Android `meta_data`.** Application `<meta-data>` entries, each a `name` and exactly one of `value` or `resource`. A name the app already declares, or another extension declares, is refused. Keep account values in your config, not in a public repository.
- **iOS `packages[].weak: true`.** Products are weak-linked. This is not a general bypass for a package's compiler or deployment requirements. Check the real package graph and guard supported runtime use with `#available`.

Files are accepted by type: source and text formats, assets and binaries. Every file must be covered by its `manifest.json`, and an unreferenced file is refused. A custom Android module `dir` covers its declared directory tree recursively. Hidden files are refused except for the specific `.xcdatamodeld/.xccurrentversion` resource member described above. Binaries must be declared and covered by the verified build inputs.

## What the build owns

The build owns bundle id and package name, versions, team and signing, URL types, push and associated-domain entitlements. Existing app permissions and capabilities remain part of the app configuration. App Groups are logical names: `nativeext` becomes `group.<bundle id>.nx.nativeext`. Approved extension purpose text can supply a fallback as described below.


### Approved iOS purpose text

Prepared runtime change, pending deployment: an extension may supply a nonempty `NS...UsageDescription` string in `ios.app.info_plist` with `ios.app.custom_keys: true`. For example, add these fields to the existing `ios.app` declaration:

```json
{
  "custom_keys": true,
  "info_plist": {
    "NSAlarmKitUsageDescription": "Schedule the alarms and timers you create in this app."
  },
  "requires": {
    "usage_descriptions": ["NSAlarmKitUsageDescription"]
  }
}
```

Nonempty purpose text from the app's build configuration wins. An absent key or a blank string uses the approved extension fallback. A required or declared purpose value must be a nonempty string; an invalid non-string app value is not silently replaced. If several extensions supply the same fallback, the first in the signed `extensions.json` order wins.

This uses the existing manifest fields and needs no new dashboard or Xano field for each Apple framework. It does not grant system authorization, enable a disabled native feature or change signing. Signed verification still binds the exact source, app identity, manifest and dependency contract; final artifact checks remain exact.

Build errors in your files are reported by their served path, `<extension>/<ios|android>/<your path>:<line>:<column>`, for example `recorder/ios/app/RecorderExtension.swift:42:17`. Build diagnostics have redaction handling. Do not assume every native or WebView runtime log is redacted, and do not log raw commands, credentials or authenticated URLs.

## Set up iOS App Groups

Use an App Group when your iOS app and an embedded target, such as a widget, need to read and write the same data.

1. Put the same logical group name in `com.apple.security.application-groups` under `ios.app.entitlements` and under `entitlements` on each participating target in `ios.targets`. Keep the short name, such as `widgets`, in the manifest.
2. In Apple Developer, register `group.<app bundle ID>.nx.<logical name>`. For Home Widgets in `com.example.myapp`, register `group.com.example.myapp.nx.widgets`.
3. Assign the group to the main App ID and the widget extension App ID on the same team. The Home Widgets target is `NXWidgets`, so its generated bundle ID is `com.example.myapp.NXWidgets`. Register that separate App ID before assigning the group. Keep these existing target IDs when configuring the current V3 runtime.
4. Regenerate the provisioning profiles for the app and the widget target. Use those profiles for the next native build, then check that both processes can access the shared data on a device.

The manifest requests the entitlement. Apple provisioning grants it. `NX-S010` means the build needs a profile for the reported bundle ID. For `NX-S011` on `com.apple.security.application-groups`, check that the reported bundle's profile grants the exact group ID.

See [Set up iOS App Groups](https://setup.despia.com/experiments/v3-extensions#set-up-ios-app-groups) for the public guide. These instructions apply to targets declared in the V3 manifest; built-in Home Widgets and Share Extension have their own setup.
