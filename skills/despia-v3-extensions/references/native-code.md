# Native code: iOS and Android handlers, native work, dependencies

Generated from the docs pages below; do not edit by hand. Change the page and regenerate.


## Native code

Page: https://setup.despia.com/extensions/build/native-code

Your extension's native code runs inside the app. On iOS it is Swift; on Android it is Kotlin or Java. If you used `create`, the files and the generated route tables are already there, and the entry type switches over every route, so a missing handler does not compile. See [Create an extension](https://setup.despia.com/extensions/build/create).

What follows is the full native contract.

### The iOS handler

The manifest names the entry type in `ios.app.entry_type`. It is a class marked `@MainActor` that conforms to `NativeExtension`. From the [recorder extension](https://setup.despia.com/extensions/catalog/recorder):

```swift ios/app/RecorderExtension.swift (excerpt)
@MainActor
final class RecorderExtension: NSObject, NativeExtension {
    required override init() { super.init() }

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        // ...
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        // ...
        switch call.route {
        case "start": return await start(call)
        case "stop": return stop(call.params["id"] as? String ?? "")
        case "status": return status(call.params["id"] as? String)
        case "discard": return discard(call.params["id"] as? String ?? "")
        case "upload": return upload(call.params["id"] as? String ?? "", call.params["upload"] as? String ?? "")
        default: return .error(code: "not_registered", message: call.route)
        }
    }
}
```

- `handle(_:)` runs on the main actor for every checked call. Return `.ok([...])` with your data, or `.error(code:message:)`.
- `didLaunch(_:)` runs once at every launch, before any web view exists, also when iOS launches the app in the background.
- After any `await` in `handle`, check `call.isActive` before you start something new. Once the route's `timeout_ms` has passed, the page already has its `timeout` answer.
- Do not mark `handle` `nonisolated` or `@concurrent`. Do background work explicitly and return to the main actor before you touch extension state.

`NativeExtContext` gives you `dataDirectory`, `appGroupIdentifier(_:)`, `appGroupURL(_:)`, `backgroundSessionIdentifier(_:)`, `isApprovedURL(_:route:param:)`, `onExpiration(of:_:)`, `emit(_:_:)` and `log(_:)`. Only these types are supported API.

#### Lifecycle hooks

Each hook is called only when its name is listed in `ios.app.lifecycle`. The runtime stays the app's only system delegate.

| `lifecycle` name         | Hook                                                                          |
| ------------------------ | ----------------------------------------------------------------------------- |
| `launch`                 | `didLaunch(_:)`                                                               |
| `scene`                  | `sceneDidChange(_:)`: foreground and background changes                       |
| `background_task`        | `handleBackgroundTask(name:task:)` for a name in `background_tasks`           |
| `open_url`               | `openURL(_:)` for a URL scheme your extension claims                          |
| `user_activity`          | `continueUserActivity(_:)`                                                    |
| `push_token`             | `didRegisterPushToken(_:)`                                                    |
| `remote_notification`    | `didReceiveRemoteNotification(_:completion:)`                                 |
| `notification`           | `willPresentNotification(_:)` and `didReceiveNotificationResponse(_:completion:)` |
| `bluetooth_restoration`  | `restoreBluetoothCentrals(_:)`                                                |
| `terminate`              | `willTerminate()`                                                             |

`handleBackgroundURLSession(name:completion:)` receives events for a session declared in `background_url_sessions`. Set a background task's expiration handler with `context.onExpiration(of:_:)`, never `task.expirationHandler`.

---

### The Android handler

The manifest names the entry class in `android.entry_class`. It implements `com.despia.nativeext.NativeExtension`. From the recorder extension:

```java android/recorder/java/despia/ext/recorder/RecorderExtension.java (excerpt)
public final class RecorderExtension implements NativeExtension {
    private static volatile ExtContext policy;
    private ExtContext ctx;

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        policy = context;
        RecorderStore.setListener(snapshot -> ctx.emit("state", snapshot));
        // ...
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        Context app = ctx.app();
        try {
            switch (call.route) {
                case "start": {
                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        reply.reply(ExtResult.error("permission_denied", "RECORD_AUDIO is not granted; request it from the page first"));
                        return;
                    }
                    // ...
                }
                // ...
                default:
                    reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (RecorderStore.StorageException e) {
            reply.reply(storageFailed(e, "read"));
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", e.getMessage()));
        }
    }
}
```

- `handle` gets the call and a reply callback. Call `reply.reply(ExtResult.ok(json))` or `reply.reply(ExtResult.error(code, message))` once.
- `onCreate` runs in `Application.onCreate` of the main process, before any activity or WebView. It also runs when WorkManager or a receiver starts the process. List `application_create` in `android.lifecycle`.

`ExtContext` gives you `app()`, `dataDir()`, `emit(event, json)`, `log(msg)`, `resultKey(name)` and `isApprovedUrl(url, route, param)`. Use `isApprovedUrl` to check a stored URL again before delayed work, such as a `Worker` or a service, sends anything to it.

---

### Patterns the extensions use

The command is a URL string; the native answer is a structured envelope. These are implementation conventions, not extra manifest types or built-in SDK services.

- **Bytes.** A parameter named `hex` (hex digits, even length) or `b64` (standard or URL-safe base64), exactly one of them, and lower-case `hex` in answers.
- **UUIDs and lists.** UUIDs are `string` parameters in 16-bit, 32-bit or 128-bit form, and answers give lower-case 128-bit. Lists are comma-separated strings.
- **A table in one parameter.** One `string` parameter, with entries separated by `,` and fields by `~`. The extension parses it and refuses it whole.
- **Answers the platform needs at once.** When the platform asks for a value the page cannot give in time, the extension answers from a native value store that the page sets, and reports each write as an event.
- **Events a page may miss.** An event sent while no page is loaded is dropped. Keep each event in a native journal with a sequence number, offer a route that reads it (`events?after=<seq>`), and keep state readable through its own route.
- **Files.** File bytes never belong in a bridge answer. Return a native file identifier. If your implementation provides a local file server, confine access and use unguessable, short-lived tokens. There is no universal file-server API in the extension context.

---

### Validate the contract before native work

JSON parsing is only the first check. Use the runtime's manifest validator, then the selected native build. Schema validation rejects unknown fields.

| Item | Current contract |
| --- | --- |
| Index | `extensions` (required): 0 to 8 unique folder names; optional legacy `app`, accepted only when it matches the build |
| Folder manifest | `version`; the folder supplies `id`, so an explicit `id` is rejected |
| Route names | Lowercase letter followed by lowercase letters, digits or `_`, up to 32 characters |
| Parameters | Only declared names and type-specific fields; `callback` is reserved for the bridge |
| Outputs | Valid JavaScript names; a route's output variable and callback must differ |
| Timeout | `timeout_ms` on the route, default 20000, range 1000 to 25000 milliseconds |
| Paths | Relative to the extension folder, in the platform tree, no traversal or symlinks |

Declare a string for encoded JSON only when the handler actually parses and validates that JSON. The bridge does not infer object types, file references or new routes from JavaScript. `https_url` host approval is a destination constraint, not authentication or a guarantee that a later redirect is safe. Recheck stored destinations before delayed network work.

Source paths and custom directory coverage must agree with the fetched tree. Names and case matter. Do not put credentials, build policy, signing material, package caches or editor directories in the hosted source tree. The only supported hidden resource member is `.xccurrentversion` directly inside a non-hidden `.xcdatamodeld` bundle; this does not allow arbitrary dotfiles.

### Declaring native work

Both ways are declared in an extension's `manifest.json`.

- **Typed.** On iOS, `targets[].kind` is a preset: `widgetkit`, `notification-service`, `notification-content`, `share` or `intents`. On Android, a module with `kind: "typed"` lists sources, resources, permissions and components (`service`, `receiver`, `provider`, `activity`) from a fixed vocabulary, and the build writes the Gradle file and manifest.
- **Custom.** On iOS, `kind: "custom"` takes a product type, build settings, script phases, a full `info_plist`, `entitlements`, link targets and packages. On Android, `kind: "custom"` points `dir` at your own module with its own `build.gradle`, `AndroidManifest.xml`, sources and resources.

Dependencies on a Swift package or a Maven library are on [Dependencies](https://setup.despia.com/extensions/build/dependencies).

---

### What the build owns

The build owns bundle id and package name, versions, team and signing, URL types, App Transport Security, push and associated-domain entitlements. Existing app permissions and capabilities remain part of the app configuration. App Groups are logical names: `nativeext` becomes `group.<bundle id>.nx.nativeext`. Approved extension purpose text can supply a fallback as described below.


#### Approved iOS purpose text

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

This uses the existing manifest fields. It does not grant system authorization, enable a disabled native feature or change signing. Signed verification still binds the exact source, manifest and dependency contract; final artifact checks remain exact.

Build errors in your files are reported by their served path, `<extension>/<ios|android>/<your path>:<line>:<column>`, for example `recorder/ios/app/RecorderExtension.swift:42:17`. Secrets, tokens and build machine paths are redacted.

### Set up iOS App Groups

Use an App Group when your iOS app and an embedded target, such as a widget, need to read and write the same data.

1. In the extension's `manifest.json`, put the same logical name in `com.apple.security.application-groups` under `ios.app.entitlements` and under `entitlements` on each target in `ios.targets` that needs the shared data. Keep the short name, such as `widgets`, in the manifest.
2. In Apple Developer, register `group.<app bundle ID>.nx.<logical name>`. For the [Home Widgets extension](https://setup.despia.com/extensions/catalog/home-widgets) with an app bundle ID of `com.example.myapp`, register `group.com.example.myapp.nx.widgets`.
3. Assign the group to the main App ID and to every participating extension App ID on the same Apple Developer team. A generated target's bundle ID is the main bundle ID followed by a dot and the target's `bundle_suffix`. If the manifest omits `bundle_suffix`, the build uses the target name. In this example, assign the group to `com.example.myapp` and `com.example.myapp.NXWidgets`. Register a separate App ID for a new target before assigning its group.
4. Regenerate the provisioning profiles for the app and all participating targets. Use those profiles for the next native build, then install it on an iPhone to check that both processes can access the shared data.

For a new iOS target, prefer `"bundle_suffix": "extension.widgets"` so the full bundle ID is
`com.example.myapp.extension.widgets`. Set the suffix inside the target in `ios.targets`.
Register that full ID in Apple Developer. For a target you've already provisioned, use its
existing bundle ID. The App Group for this example is still `group.com.example.myapp.nx.widgets`.

The manifest requests the entitlement. Apple provisioning grants it. If you see `NX-S010`, check that the build has a profile for the reported bundle ID. If you see `NX-S011` for `com.apple.security.application-groups`, check that the reported bundle's profile grants the exact group ID.

These steps are for extensions declared in your V3 manifest. Use the separate setup guides for the built-in [Home Widgets](https://setup.despia.com/native-features/home-widgets) and [Share Extension](https://setup.despia.com/native-features/share-extension).

[Register an App Group in Apple Developer](https://developer.apple.com/help/account/identifiers/register-an-app-group/) and [assign it to an App ID](https://developer.apple.com/help/account/identifiers/enable-app-capabilities/).

---

### Platform limits to plan for

- **iOS.** Background audio continues only while the audio session stays active. Recording must start in the foreground, and a force-quit ends it. Lock Screen widget families need iOS 16 and update on WidgetKit's budget. App Groups and new App IDs are manual steps in the Apple Developer portal.
- **Android.** Android 14 and later only start a microphone foreground service while the app is visible. Lock Screen widgets depend on the device, so the foreground service notification is the guaranteed Lock Screen presence. Google Play requires foreground service type declarations.
- **Files.** Android WorkManager stops a regular worker after 10 minutes, and an upload restarts from its first byte.
- **Both.** Apple managed capabilities and restricted Android permissions need approval from Apple or Google and pass the store's own review.

---

### Compiler settings

The current V3 iOS project uses Swift language mode **5.0**. Generated extension targets inherit the host setting unless a permitted custom target setting changes it. The compiler version and language mode are different: a Swift 6 compiler can compile Swift 5 language mode.

The current V3 Android app uses **compileSdk 36**. A custom module supplies its own Gradle configuration and must be compatible with the host app. These are build settings, not promises that every API is available on every supported device. Check the OS version before using a newer API.

### Android permissions and the Activity

There are three separate steps:

1. Declare the required permission in the built Android manifest.
2. For a dashboard-owned permission, list it in `android.requires.permissions` so the build verifies that the base app enables it.
3. Request runtime permission from the user where Android requires it.

`android.requires.permissions` is an assertion about the base app. It does not grant runtime consent. In a custom module, put Android permission declarations and their attributes in the module's own `AndroidManifest.xml`. For example, `usesPermissionFlags="neverForLocation"` belongs on the `BLUETOOTH_SCAN` declaration. Use that assertion only if the extension does not derive physical location from scan results. Android can filter some BLE beacons when it is set.

`ExtContext.app()` returns the Application context. It is not an Activity. To receive the host Activity, declare the lifecycle hook:

```json
{
  "android": {
    "lifecycle": ["application_create", "activity_lifecycle"],
    "requires": {
      "permissions": ["android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"]
    }
  }
}
```

This is an excerpt: keep your entry class and module declarations alongside it. Implement `NativeExtension.onActivityEvent(Activity, ActivityEvent)`. For an AndroidX `ComponentActivity`, register an `ActivityResultContracts.RequestMultiplePermissions` launcher through its Activity Result Registry during `CREATED`, using `ctx.resultKey("permissions")`. Unregister it during `DESTROYED`. Preserve outstanding request state across Activity recreation and finish it if the Activity is finishing. Do not keep a destroyed Activity reference.

Request the permissions required by the OS version and operation. Android 12 and later use `BLUETOOTH_SCAN` for scanning and `BLUETOOTH_CONNECT` for connecting. Older Android versions have different scan permission requirements. The base build must also enable any dashboard-owned permission listed under `requires`.

### Custom module directories

An Android custom module's `dir` covers the declared directory tree recursively, including its Gradle file, manifest, sources and resources. You do not list each Java file separately. Files must remain inside that module directory and satisfy the extension file rules. Signed build inputs record the individual files and their hashes, so adding or changing a file requires a fresh verified snapshot.

### Routes that wait for a user

Put `timeout_ms` on the route, beside `name`, `params` and `output`:

```json
{
  "name": "request_permission",
  "params": {},
  "timeout_ms": 25000,
  "output": {
    "variable": "laserPermissionResult",
    "callback": "onLaserPermissionResult"
  }
}
```

The default is **20000 milliseconds**. The accepted range is **1000 to 25000 milliseconds**. Both `"params": {}` and omitting `params` are accepted for a route without parameters.

A user can leave a permission dialog open longer than the bridge deadline. For that workflow, answer promptly with your own pending state, then emit a declared event when the user answers. Also expose a status route so the page can recover state after navigation. Any pending result shape or event is part of your extension's API, not a built-in permission result. For a handler that waits, check `call.isActive()` on Android or `call.isActive` on iOS before starting work after an asynchronous answer. A late Allow must not start an operation for a call that already timed out.


## Dependencies

Page: https://setup.despia.com/extensions/build/dependencies

### Vendor SDKs

Declare dependencies and include their required graph files. Build verification covers the complete pinned dependency closure. A package URL or Maven coordinate alone does not describe that graph or prove it was compiled into the app. You do not need to manually copy library binaries into the app.

**iOS: a Swift package from GitHub.** List it under `ios.packages` in `manifest.json` with its GitHub URL, the full commit `revision` of the release you want, and the products you use. Then link the product in your app target under `ios.app.packages`. For example:

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

Swift packages come from `github.com` over https. `ios.package_resolved` is required for remote packages and names the complete pinned SwiftPM graph, including transitive packages. Include that file in the extension folder. The snippet above is an excerpt; use the extension's graph file or generate the graph for your own dependency set. A build without the graph is refused.

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

Files are accepted by type: source and text formats, assets and binaries. Every file must be covered by its `manifest.json`, and an unreferenced file is refused. A custom Android module `dir` covers its declared module tree recursively. Hidden files (names that start with a dot) are always refused. Binaries must be declared and covered by the verified build inputs.

---

### Extensions that need other extensions

Declare it in your descriptor, `despia.extension.json`:

```json despia.extension.json
{
  "requires": {
    "extensions": [{ "id": "sqlite", "version": "^1.0.0" }],
    "capabilities": [{ "name": "push.transport", "version": "^1.0.0", "optional": true }]
  },
  "provides": [{ "capability": "push.transport", "version": "1.0.0" }]
}
```

- **By id** when your code needs that exact extension. **By capability** when it needs a role any provider can fill. Capability names are free strings that you choose; names starting with `despia.` belong to the app runtime.
- `"optional": true` lets the app build without it.
- Versions use npm-style ranges on `x.y.z` versions: `1.2.3`, `^`, `~`, comparisons and `||`.

What the CLI does with them when someone installs your extension:

- `add` installs every extension required by id, at the highest version every requirer accepts. One version per id per app. Ranges that do not overlap stop the install (`DX-G001`) and nothing is written.
- A required capability is never filled unasked. With no provider installed, `add` stops (`DX-G002`) and lists the candidates; `--with <name>` adds one. With several installed providers, `--bind <capability>=<id>` chooses (`DX-G003` until then), and the choice is kept in `despia.extensions.json` under `bindings`.
- `public/despia/extensions.json` is written in dependency order: a provider before every extension that requires it. The app registers and starts extensions in that order.
- `remove` refuses an extension others require (exit 5, `DX-G006`). `--with-dependents` removes them too; `--force` leaves them broken. Extensions installed only as dependencies stay until `remove --prune`.
- `update` refuses a version outside a dependent's range (exit 5, `DX-G007`) and names the newest version they accept; `--force` takes it anyway.
- A cycle is refused (`DX-G005`). `doctor` fails when the installed extensions and the recorded graph disagree (`DX-G004`); `sync` rewrites both.

| Code | Means |
| --- | --- |
| `DX-G001` | No single version of an id satisfies every requirer |
| `DX-G002` | A required capability has no installed provider |
| `DX-G003` | Several installed providers and no binding |
| `DX-G004` | The index order or the lock's graph differs from the installed extensions |
| `DX-G005` | The requirements form a cycle |
| `DX-G006` | `remove` of an extension others require |
| `DX-G007` | `update` to a version outside a dependent's range |
| `DX-G008` | A capability version that no installed provider offers |
| `DX-G009` | A malformed `requires` or `provides` |
| `DX-G010` | The registry's copy of the declarations differs from the fetched source |
| `DX-G011` | A required extension is not installed and not in the registry |
| `DX-G012` | A `--bind` value is malformed or names an extension that does not provide the capability |

Extensions do not call each other's routes. Two extensions cooperate through a shared vendor SDK or through your page.
