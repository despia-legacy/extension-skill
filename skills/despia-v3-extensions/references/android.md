# The Android handler

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

## The Android handler

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

## Host and platform integration

Use only the public context and declared lifecycle hooks. An application context is not an Activity and cannot silently supply a permission dialog. Declare `activity_lifecycle` when you need `onActivityEvent`, handle Activity recreation and namespace Activity Result Registry keys with `ctx.resultKey`. A permission in the compiled manifest does not grant runtime user consent.

Services, receivers and workers can start a process without a page. Persist the operation state they need natively, and expose status when the page returns. Check `call.isActive()` after asynchronous waits before starting new work for a waiting call. Reply once; do not treat the SDK's missing-answer timeout as cancellation.

Compile custom modules against the host's actual Gradle, Android SDK and minimum-device requirements. The current extension `min_sdk` route guard is not an app minimum-SDK upgrade. Real SDK dependencies, manifest merging, foreground-service declarations and artifact checks must pass in the selected host build.

Choose scheduling for the operation rather than assuming all background work is equivalent. Recorder's regular WorkManager uploader does not establish a general user-initiated transfer service. Document retry, interruption and server idempotency; avoid private content, credentials and full authenticated URLs in logs.
