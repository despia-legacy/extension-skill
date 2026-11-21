# The Android handler

Source: the published V3 Extensions page, https://docs.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

## The Android handler

The manifest names the entry class in `android.entry_class`. It implements `com.despia.nativeext.NativeExtension`. From the recorder sample:

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
