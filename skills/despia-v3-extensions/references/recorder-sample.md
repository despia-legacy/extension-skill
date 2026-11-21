# Background Recorder extension notes

Documentation: https://setup.despia.com/experiments/samples/recorder . The files are in examples/recorder/ (copy each to public/despia/recorder/<path>, except extensions.json which goes in public/despia/, and recorder.policy.json which belongs to the trusted build inputs and is not served).

Source: [Background Recorder repository](https://github.com/despia-legacy/recorder). Read its [LICENSE](https://github.com/despia-legacy/recorder/blob/main/LICENSE) before using or modifying the extension.

This extension records audio from the microphone, keeps recording when the app goes to the background, and uploads the file to your server when recording stops. It shows its state in a Lock Screen widget on iOS, and in a foreground service notification and a home screen widget on Android.

The page only ever sees a recording id and its state. The audio never crosses the bridge.

**Experimental. V3 only.** You own and maintain this ready-made extension once it is in your app. First-party V4 support comes through the new package registry. Despia does not support customised code. Read [V3 Extensions](/experiments/v3-extensions) first.

## Build prerequisite

Extension files must be compiled into a rebuilt native app. Uploading files does not change an installed binary. Automatic discovery of hosted `/despia/extensions.json` during a normal rebuild is under review and is not yet confirmed in deployed builds. See `troubleshooting.md`.

## Files

Copy every file to the path in its title. Paths are under `public/despia/recorder/` in your web app, except `extensions.json`, which goes in `public/despia/`.

Before you build, change these values to your own:

- the upload host `upload.example.com` in both `hosts` lists in `manifest.json`.

The build policy is part of the trusted build inputs and is not served from your site. Signed-source checks verify the exact files and dependency graph; artifact checks verify the build before signing. You own the behavior and maintenance of your native code.

## Permissions and capabilities

The manifest declares everything the recorder needs. The build adds them to your app.

- **Microphone.** `NSMicrophoneUsageDescription`, declared under `requires.usage_descriptions`. Set the purpose text in Despia.
- **Background modes.** `audio` (keep recording in the background) and `processing` (finish uploads in a background processing task named `upload`).
- **App Group.** The logical group `nativeext`, shared by the app and the `RecorderStatusWidget` target. It becomes `group.<bundle id>.nx.nativeext`. Register it in the Apple Developer portal and add it to both bundle IDs, as for [Home Widgets](/native-features/home-widgets).
- **Widget.** `RecorderStatusWidget`, a WidgetKit target with a deployment target of iOS 16.6.
- **Microphone.** `android.permission.RECORD_AUDIO`, declared under `requires.permissions`. Turn on the microphone permission for your app in Despia. The recorder does not ask for it on Android: it must be granted before `start`, otherwise `start` answers `permission_denied`. See [Asking for the microphone on Android](#asking-for-the-microphone-on-android).
- **Foreground service.** `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE`, with the service type `microphone`. Google Play asks you to declare this use.
- **Notifications.** `POST_NOTIFICATIONS`, for the recording notification and its stop action.
- **Widget.** `StatusWidgetProvider`, an exported app widget receiver.
- **Libraries.** `androidx.core:core:1.18.0` and `androidx.work:work-runtime:2.11.2`.

## Using it from your web app

The upload URL receives an HTTP `PUT`, with `Content-Type: audio/mp4` and an `X-Recording-Id` header. Its host must be in the route's approved `hosts` list.

Install the SDK with `npm install despia-native`. Import `despia` from `despia-native` and watch the route's declared output variable. Run this function from a client event after obtaining any required microphone permission:

```javascript
import despia from 'despia-native';

async function startRecording(uploadUrl) {
  const reply = await despia('extension://recorder/start?upload=' + encodeURIComponent(uploadUrl), ['recorderStart']);
  const answer = reply.recorderStart;
  if (answer === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!answer.ok) { console.error(answer.code, answer.error); return; }
  return answer.data.recordingId;
}

async function stopRecording(id) {
  const stoppedReply = await despia('extension://recorder/stop?id=' + encodeURIComponent(id), ['recorderStop']);
  const stopped = stoppedReply.recorderStop;
  if (stopped === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!stopped.ok) { console.error(stopped.code, stopped.error); return; }
  const statusReply = await despia('extension://recorder/status?id=' + encodeURIComponent(id), ['recorderStatus']);
  const status = statusReply.recorderStatus;
  if (status === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!status.ok) { console.error(status.code, status.error); return; }
  console.log(status.data.state);
}
```

Register `onRecorderState` as described in `references/routes-and-answers.md` before starting if you need live changes. Read `status` after a page reload; event delivery is not durable.

### Asking for the microphone on Android

On iOS, `start` shows the microphone prompt itself. On Android it does not. Ask for the microphone from the page first with the standard web API. Despia's Android app turns a web microphone request into the system `RECORD_AUDIO` prompt, when the microphone permission is on for your app:

```javascript
import despia from 'despia-native';

async function startWithMicrophone(uploadUrl) {
  const stream = await navigator.mediaDevices.getUserMedia({audio: true});
  stream.getTracks().forEach(track => track.stop());
  const reply = await despia('extension://recorder/start?upload=' + encodeURIComponent(uploadUrl), ['recorderStart']);
  const answer = reply.recorderStart;
  if (answer === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!answer.ok) { console.error(answer.code, answer.error); return; }
  return answer.data.recordingId;
}
```

If the user denies it, `getUserMedia` throws and `start` answers `permission_denied`.

### Routes

| Route     | Parameters                 | Answer variable and callback          | What it does                                                                                       |
| --------- | -------------------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------- |
| `start`   | `upload` (required)        | `recorderStart`, `onRecorderStart`    | Starts a recording and answers with its `recordingId`                                               |
| `stop`    | `id` (required)            | `recorderStop`, `onRecorderStop`      | Stops the recording and starts the upload                                                           |
| `status`  | `id` (optional)            | `recorderStatus`, `onRecorderStatus`  | The state of a recording, or of the latest one without `id`; `idle` when there is none              |
| `discard` | `id` (required)            | `recorderDiscard`, `onRecorderDiscard`| Deletes a stopped recording and its file                                                            |
| `upload`  | `id`, `upload` (required)  | `recorderUpload`, `onRecorderUpload`  | Sends a saved recording again to a fresh URL, for example after a presigned URL expired             |

The `state` event calls the declared `onRecorderState(data)` handler on changes.

### States

| State         | Meaning                                                                                   |
| ------------- | ----------------------------------------------------------------------------------------- |
| `starting`    | The recording is being set up                                                             |
| `recording`   | Recording                                                                                 |
| `interrupted` | Paused by the system                                                                      |
| `uploading`   | Stopped, and the file is being uploaded                                                   |
| `uploaded`    | The upload finished                                                                       |
| `failed`      | The upload failed. `reason` says why. Call `upload` with a fresh URL to try again          |
| `terminated`  | The app was closed while recording. The file was never finished and is never uploaded      |
| `discarding`  | The file is being deleted                                                                 |
| `discarded`   | The recording is gone                                                                     |

Answers and events carry `recordingId`, `state`, `durationMs` and `bytes`, plus `httpStatus` after an upload attempt and `reason` when the state is `failed`. While the recorder could not save its index, they also carry `durable: false` and `storage`.

### Error codes

Besides the [standard codes](/experiments/v3-extensions#error-codes), the recorder answers:

| Code                  | When                                                                                       |
| --------------------- | ------------------------------------------------------------------------------------------ |
| `busy`                | A recording is already running, or the recording is not in a state that allows this route  |
| `permission_denied`   | Microphone access is not granted                                                           |
| `not_found`           | No recording has this id                                                                   |
| `record_failed`       | The recorder could not start, or the recording was never finished, so there is nothing to upload |
| `storage_unavailable` | The recorder's index cannot be read yet. Try again later                                   |
| `storage_failed`      | The index could not be saved, or the upload could not be scheduled. Nothing changed        |
| `index_recovered`     | The index was unreadable and was replaced, so an older recording may be missing            |
| `discard_failed`      | The file could not be deleted yet. It stays `discarding` and is deleted on the next `discard` or launch |

## Platform limits

- **iOS.** Recording must start while the app is in the foreground. It continues in the background while the audio session stays active. A force-quit ends it. Uploads run while the app runs, in the limited background time iOS may give, and in a background processing task if iOS grants one. iOS guarantees neither. An upload that is cut off starts again from the beginning the next time the app runs.
- **Android.** Android 14 and later only start a microphone foreground service while the app is visible, so call `start` from a page the user is looking at. Lock Screen widgets depend on the device; the foreground service notification is always shown. Uploads run through WorkManager.
