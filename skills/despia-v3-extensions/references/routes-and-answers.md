# Calling routes, answers and error codes

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

## Calling a route

**Promise**
    With the `despia-native` package, pass the route's answer variable to `despia()` and await it:

    ```javascript
    import despia from 'despia-native';

    const r = await despia(
      'extension://recorder/start?upload=' + encodeURIComponent(uploadUrl),
      ['recorderStart']
    );

    if (r.recorderStart.ok) {
      console.log(r.recorderStart.data.recordingId);
    } else {
      console.error(r.recorderStart.code, r.recorderStart.error);
    }
    ```
**Callback**
    Define the route's callback, then set `window.despia`:

    ```javascript
    window.onRecorderStart = (result) => {
      if (result.ok) {
        console.log(result.data.recordingId);
      } else {
        console.error(result.code, result.error);
      }
    };

    window.despia = "extension://recorder/start?upload=" + encodeURIComponent(uploadUrl);
    ```
**Per-call callback**
    Add `callback=<name>` to replace the route's callback for that one call:

    ```javascript
    window.onStatusOnce = (result) => console.log(result.data.state);

    window.despia = "extension://recorder/status?id=" + id + "&callback=onStatusOnce";
    ```

Variable and callback names must be valid JavaScript names of up to 64 characters, must not start with `__`, and must not be a browser or runtime global.

## The result

Every answer has the same shape:

| Field       | Meaning                                                           |
| ----------- | ----------------------------------------------------------------- |
| `ok`        | `true` on success, `false` on error                               |
| `provider`  | The provider of the answer |
| `platform`  | The platform that answered                                        |
| `runtime`   | `3`                                                               |
| `bridge`    | `1`                                                               |
| `extension` | The extension id                                                  |
| `route`     | The route name                                                    |
| `data`      | Your handler's data on success, `null` on error                   |
| `error`     | The error message on error                                        |
| `code`      | The error code on error                                           |

An error carries a code and a message, and nothing else. If the page must tell failures apart, give each one its own code.

### Error codes

| Code               | When                                                                       |
| ------------------ | -------------------------------------------------------------------------- |
| `bad_request`      | An undeclared parameter, a missing required one, or a value of the wrong type |
| `not_registered`   | This build does not contain the extension or route                         |
| `forbidden_frame`  | The call did not come from the top-level page                              |
| `forbidden_origin` | The call came from an origin that is not allowed                           |
| `rate_limited`     | More than 20 calls per second to one extension                             |
| `timeout`          | The handler did not answer within the route's `timeout_ms`                 |
| `unavailable`      | The capability is not available right now                                 |
| `platform_limit`   | This platform or OS version has no such capability. Every route of an Android extension answers it on a device below the extension's `android.min_sdk` |
| `handler_error`    | Your handler failed                                                        |
| Your own codes     | Any code your handler returns, for example `busy` or `permission_denied`   |

In an extension-enabled build, an unregistered extension or route answers an explicit `callback`; otherwise it fires `despia-feature-not-included`. A build without the runtime cannot answer. For missing responses, see `troubleshooting.md`.

## Patterns the samples use

The bridge answer is a JavaScript string with typed parameters. These conventions keep that workable.

- **Bytes.** A parameter named `hex` (hex digits, even length) or `b64` (standard or URL-safe base64), exactly one of them, and lower-case `hex` in answers.
- **UUIDs and lists.** UUIDs are `string` parameters in 16-bit, 32-bit or 128-bit form, and answers give lower-case 128-bit. Lists are comma-separated strings.
- **A table in one parameter.** One `string` parameter, with entries separated by `,` and fields by `~`. The extension parses it and refuses it whole.
- **Answers the platform needs at once.** When the platform asks for a value the page cannot give in time, the extension answers from a native value store that the page sets, and reports each write as an event.
- **Events a page may miss.** An event sent while no page is loaded is dropped. Keep each event in a native journal with a sequence number, offer a route that reads it (`events?after=<seq>`), and keep state readable through its own route.
- **Files.** File bytes never belong in a bridge answer. Serve the file from the extension on `127.0.0.1` with a random per-file token in the URL, and answer with that URL.
