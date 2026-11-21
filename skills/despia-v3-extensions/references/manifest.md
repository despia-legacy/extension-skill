# Layout, manifest and native work

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

**Requires an extension-enabled build.** Files hosted under /despia/ are preparation only. A regular app build does not fetch or compile them automatically. Despia must confirm app availability and approve the files and dependency closure. A completed normal build is not an extension code check. Read `troubleshooting.md` before promising a regeneration will include the code.

V3 Extensions let you add your own native code to a Despia V3 app. You write the Swift (iOS) and Kotlin or Java (Android) files, describe them in a JSON manifest, and serve them from your web app. Despia builds them into your app. Your web code calls them with the Despia JS SDK from a client event:

```javascript
import despia from 'despia-native';

// Run from a browser click handler or client lifecycle hook.
async function readRecorderStatus() {
  const responses = await despia('extension://recorder/status', ['recorderStatus']);
  const answer = responses.recorderStatus;
  if (answer === undefined) throw new Error('No native reply within 30 seconds');
  if (!answer.ok) throw new Error(JSON.stringify(answer.error ?? answer));
  return answer.data;
}
```

Use V3 Extensions when you need a native capability that Despia does not offer as a feature yet, or when you need full control over the native code. The [ready-made extensions](/experiments/samples/recorder) include source you can copy and customize.

## How it works

- **The manifest declares the extension.**
    Each extension has a `manifest.json`. It declares the extension id, its routes, the typed parameters of each route, the answer variable and callback of each route, its events, and the native files for each platform.
- **Despia builds it into your app.**
    Your files are served from your app's own HTTPS origin. Despia reads them and compiles them into a new native build of your app. Extensions are enabled per app by Despia. Any change to the manifest or to a native file needs to be approved again and needs a new native build. It cannot be applied over-the-air.
- **Your web app calls a route.**
    The page sends `extension://<id>/<route>?<params>`. The runtime first checks the call: the extension and route exist, only declared parameters are present, and each value has the declared type. Only then does it call your native handler.
- **The answer comes back once.**
    Every call answers exactly once, in the page that made it. The runtime sets the route's answer variable on `window` and then calls the route's callback with it.

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

`extensions.json` holds the app identity and lists the extension folders. The three identity values:

Despia takes app identity from the existing build configuration. You do not copy it into this file. A legacy `app` block remains optional and must match the build if present. The signed approval lock must still match the app being built.

`extensions.json` lists 1 to 8 folders, and each folder name matches `[a-z][a-z0-9]{1,23}`. Any other key is refused, including `manifest_version`. Each extension folder has its own `manifest.json` with a `version` field. A folder listed twice is also refused.

The folder name is the id in the scheme. `public/despia/recorder/` is called as `extension://recorder/<route>`. File paths in `manifest.json` are relative to the extension's folder and start with `ios/` or `android/`, for example `ios/app/RecorderExtension.swift`. An extension can only name files inside its own folder. A `manifest.json` that also sets `"id"` is refused, because the folder name is the id.

To add an extension to your app, copy its folder under `public/despia/` and add its name to `extensions.json`. Despia approves `extensions.json` and every other file, each `manifest.json` included. A change to any of them needs a new approval.

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

## Declaring native work

Both ways are declared in an extension's `manifest.json`.

- **Typed.** On iOS, `targets[].kind` is a preset: `widgetkit`, `notification-service`, `notification-content`, `share` or `intents`. On Android, a module with `kind: "typed"` lists sources, resources, permissions and components (`service`, `receiver`, `provider`, `activity`) from a fixed vocabulary, and the build writes the Gradle file and manifest.
- **Custom.** On iOS, `kind: "custom"` takes a product type, build settings, script phases, a full `info_plist`, `entitlements`, link targets and packages. On Android, `kind: "custom"` points `dir` at your own module with its own `build.gradle`, `AndroidManifest.xml`, sources and resources.

## Adding a Swift package or a Maven library

Declare dependencies and include their required graph files. Approval covers the pinned dependency closure as part of an extension build. A package URL or Maven coordinate alone does not enable the extension or complete its approval. Manually copying library binaries is not required.

**iOS: a Swift package from GitHub.** List it under `ios.packages` in `manifest.json` with its GitHub URL, the full commit `revision` of the release you want, and the products you use. Then link the product in your app target under `ios.app.packages`. From the Stream calling template:

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
- **iOS `packages[].weak: true`.** Every product of that package is linked as optional, so an app whose deployment target is below the package's minimum still launches. Guard its use with `#available`.

Files are accepted by type: source and text formats, assets and binaries. Every file must be covered by its `manifest.json`, and an unreferenced file is refused. A custom Android module `dir` covers its approved directory tree recursively. Hidden files (names that start with a dot) are always refused. Binaries need approval.

## What the build owns

You cannot set these. The build sets them for you: bundle id and package name, versions, team and signing, URL types, App Transport Security, purpose strings and permissions your app already sets in Despia (declare them under `requires`), and push and associated-domain entitlements. App Groups are logical names. A group named `nativeext` becomes `group.<bundle id>.nx.nativeext`.

Build errors in your files are reported by their served path, `<extension>/<ios|android>/<your path>:<line>:<column>`, for example `recorder/ios/app/RecorderExtension.swift:42:17`. Secrets, tokens and build machine paths are redacted.
