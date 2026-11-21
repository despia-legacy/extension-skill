# The iOS handler

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

## The iOS handler

The manifest names the entry type in `ios.app.entry_type`. It is a class marked `@MainActor` that conforms to `NativeExtension`. From the [recorder extension](/experiments/samples/recorder):

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

### Lifecycle hooks

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
