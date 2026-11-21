# Calling routes, answers and error codes

Source: the published V3 Extensions page, https://setup.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

## Calling a route with the Despia JS SDK

Install the SDK in your web project:

```bash
npm install despia-native
```

Import `despia` from `despia-native`. The examples use the published 1.0.28 API. A command without watched variables resolves when queued, not when the native operation finishes. Pass one answer variable when you need a result:

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

`recorderStatus` is the output variable declared by the Recorder manifest. Serialize calls that watch the same declared variable: await one before starting the next, including calls from other components. Sharing a watched variable across concurrent requests can mix responses.

The SDK resolves native error answers as well as successes. Check `answer.ok`; a native failure does not reject the SDK promise. A single-variable watch resolves to `undefined` after 30 seconds if no value arrives. That timeout does not cancel native work. Inspect the returned native envelope rather than assuming every command uses the same error shape.

### A helper for concurrent extension calls

Copy this app-owned helper to `despia-extension.js`. It uses the published SDK, a unique native callback function and a separate unique watched variable for each request. Native extension callbacks are functions; setting `callback` in the URL does not turn that callback name into an answer variable. The helper forwards the callback into the variable the SDK watches and removes both globals when finished.

```javascript
import despia from 'despia-native';

// App helper, not an export from despia-native. Call only in the browser.
export async function callExtension(command) {
  const suffix = crypto.randomUUID().replace(/-/g, '');
  const callbackKey = 'nxCallback' + suffix;
  const watchKey = 'nxReply' + suffix;
  const url = new URL(command);
  url.searchParams.set('callback', callbackKey);
  globalThis[callbackKey] = envelope => { globalThis[watchKey] = envelope; };
  try {
    const responses = await despia(url.toString(), [watchKey]);
    const envelope = responses[watchKey];
    if (envelope === undefined) throw new Error('No native reply within 30 seconds');
    if (!envelope.ok) {
      const error = new Error('Native extension request failed');
      error.response = envelope;
      throw error;
    }
    return envelope.data;
  } finally {
    delete globalThis[callbackKey];
    delete globalThis[watchKey];
  }
}
```

`callExtension` is a local helper, not a `despia-native` export. It returns the successful envelope's `data`. It throws when the SDK times out or native `ok` is false; native failures remain available on `error.response`. Each call watches one variable, so concurrent calls do not share reply state. The native route still has its own deadline and may continue after the SDK's 30-second wait.

Variable and callback names must be valid JavaScript names of up to 64 characters, must not start with `__`, and must not be browser or runtime globals. The helper's generated names fit these rules.

### React

Importing the SDK is safe during server rendering. Call it from a client event or lifecycle hook. In Next.js, put the component behind a client boundary.

```jsx
'use client';
import {useState} from 'react';
import {callExtension} from './despia-extension.js';

export function RecorderStatus() {
  const [message, setMessage] = useState('');
  async function readStatus() {
    try {
      const status = await callExtension('extension://recorder/status');
      setMessage(status.state);
    } catch (error) {
      setMessage(error.message);
    }
  }
  return <button onClick={readStatus}>{message || 'Read recording status'}</button>;
}
```

### Vue

The same helper works from a Vue click handler. For an automatic call, use `onMounted`, never server setup execution.

```vue
<script setup>
import {ref} from 'vue';
import {callExtension} from './despia-extension.js';
const message = ref('');
async function readStatus() {
  try {
    const status = await callExtension('extension://recorder/status');
    message.value = status.state;
  } catch (error) {
    message.value = error.message;
  }
}
</script>
<template>
  <button @click="readStatus">{{ message || 'Read recording status' }}</button>
</template>
```

### Events

Use the extension's state or journal routes to recover after page loads. Events are notifications, not a replay guarantee. Register the declared native callback before starting the operation, and remove your handler when its component unmounts:

```javascript
import despia from 'despia-native';

const previous = despia.onRecorderState;
const onState = recording => console.log(recording.recordingId, recording.state);
globalThis.onRecorderState = onState;

// Call this during component cleanup.
function stopListening() {
  if (globalThis.onRecorderState !== onState) return;
  if (previous === undefined) delete globalThis.onRecorderState;
  else globalThis.onRecorderState = previous;
}
```

The SDK proxy reads existing browser properties such as `despia.onRecorderState`. It does not register event handlers when you assign to that proxy. Event registration above uses the native callback global explicitly; commands still go through the SDK. Run this registration only in the browser. The extension manifest defines each event's callback and scope.

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

## Patterns the extensions use

The bridge answer is a JavaScript string with typed parameters. These conventions keep that workable.

- **Bytes.** A parameter named `hex` (hex digits, even length) or `b64` (standard or URL-safe base64), exactly one of them, and lower-case `hex` in answers.
- **UUIDs and lists.** UUIDs are `string` parameters in 16-bit, 32-bit or 128-bit form, and answers give lower-case 128-bit. Lists are comma-separated strings.
- **A table in one parameter.** One `string` parameter, with entries separated by `,` and fields by `~`. The extension parses it and refuses it whole.
- **Answers the platform needs at once.** When the platform asks for a value the page cannot give in time, the extension answers from a native value store that the page sets, and reports each write as an event.
- **Events a page may miss.** An event sent while no page is loaded is dropped. Keep each event in a native journal with a sequence number, offer a route that reads it (`events?after=<seq>`), and keep state readable through its own route.
- **Files.** File bytes never belong in a bridge answer. Serve the file from the extension on `127.0.0.1` with a random per-file token in the URL, and answer with that URL.
