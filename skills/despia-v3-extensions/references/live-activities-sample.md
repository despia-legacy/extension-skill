# Live Activities extension

Documentation: https://setup.despia.com/experiments/samples/live-activities

Source: [Live Activities repository](https://github.com/despia-legacy/live-activities). Read its [LICENSE](https://github.com/despia-legacy/live-activities/blob/main/LICENSE) before using or modifying the extension.

This extension starts, updates, ends and lists native activities from your web app. It includes delivery, score and timer templates. On iOS it uses ActivityKit for the Lock Screen and Dynamic Island. On Android it uses ongoing notifications, with Live Updates on Android 16 and later when the system allows promotion.

It is for the Despia V3 runtime. You own and maintain the copied native code. First-party V4 support comes through the new package registry. Read [V3 Extensions](https://setup.despia.com/experiments/v3-extensions) for approval and rebuild requirements.

## Install

1. Copy the files from the linked extension repository into `public/despia/liveactivity/` in your web app, keeping their `ios/` and `android/` paths.
2. Add `"liveactivity"` to the `extensions` array in `public/despia/extensions.json`, keeping any other extension names.
3. Send the extension for approval and rebuild your native app. Changing the native files needs a new approval and build.

The manifest adds the `NXLiveActivity` WidgetKit target on iOS. The Android custom module adds its notification permissions, notification service extension and dismiss receiver. Local calls work without OneSignal; server updates require the base app's push integration and your server configuration. Do not put server credentials in your web app.

## Call it from your web app

Use the local `callExtension` helper from `references/routes-and-answers.md`. Call these functions from client events. Timer times are Unix seconds.

```javascript
import {callExtension} from './despia-extension.js';

async function startTimer() {
  const startedAt = Date.now() / 1000;
  const params = new URLSearchParams({
    template: 'timer',
    activity_id: 'oven-timer',
    attributes: JSON.stringify({title: 'Pizza oven'}),
    state: JSON.stringify({startedAt, endsAt: startedAt + 900, label: 'Baking'})
  });
  const activity = await callExtension('extension://liveactivity/start?'
    + params.toString().replace(/\+/g, '%20'));
  console.log(activity.activityId, activity.state);
}

async function endTimer() {
  return callExtension('extension://liveactivity/end?activity_id=oven-timer&dismiss=immediate');
}
```

## Routes and answers

Each success answer uses `{ok: true, data: ...}`. Failures use `{ok: false, code, error}`. The table lists the fields inside `data`.

| Route | Parameters | Answer variable and callback | Answer |
| --- | --- | --- | --- |
| `start` | Required: `template` (string), `attributes` and `state` (JSON text). Optional: `activity_id`, `stale_minutes`, `relevance` | `liveActivityStart`, `onLiveActivityStart` | `activityId`, `template`, `state` |
| `update` | Required: `activity_id`, `state`. Optional: `stale_minutes`, `relevance`, `alert_title`, `alert_body` | `liveActivityUpdate`, `onLiveActivityUpdate` | `activityId`, `template`, `state` |
| `end` | Required: `activity_id`. Optional: `state`, `dismiss` (`default`, `immediate`, `after`), `dismiss_minutes` | `liveActivityEnd`, `onLiveActivityEnd` | `activityId`, `template`, `state: "ended"` |
| `list` | None | `liveActivityList`, `onLiveActivityList` | Authorization fields, `templates`, `activities`, `pushToStart` |
| `permission` | None | `liveActivityPermission`, `onLiveActivityPermission` | Authorization fields. Android can ask for notification permission; iOS reads the current setting |
| `settings` | None | `liveActivitySettings`, `onLiveActivitySettings` | `opened` |

`stale_minutes` is 0 to 720. `relevance` is 0 to 100. `dismiss=after` requires `dismiss_minutes`, from 0 to 240. IDs contain 1 to 64 letters, digits, dots, underscores or hyphens. The combined attributes and state, including the injected `activityId`, must fit in 4096 UTF-8 bytes.

Each row in `activities` has `activityId`, `template`, `state`, `origin`, `pushToken`, `attributes` and `content`. On Android `pushToken` is `null` and `pushToStart` is empty. iOS authorization fields are `enabled`, `frequentPushes`, `onesignal` and `platform`. Android fields are `enabled`, `permission`, `promoted`, `liveUpdates`, `onesignal` and `platform`. `onesignal` is `linked` or `not_linked`.

### Templates

| Template | Attributes | State |
| --- | --- | --- |
| `delivery` | `orderNumber`, `merchant` | `status`, `step`, `stepCount`; optional `etaEpoch` (Unix seconds), `courier` |
| `score` | `league`, `homeTeam`, `awayTeam`; optional `homeShort`, `awayShort` | `homeScore`, `awayScore`, `period`; optional `clock`, `final` |
| `timer` | `title` | `startedAt`, `endsAt` (Unix seconds), `label`; optional `pausedRemaining` (seconds) |

The template source files define these fields. Edit the Swift template, Android template and registries together when adding your own template.

### Events

Events have app scope and reach the trusted top-level page.

| Event callback | Fields |
| --- | --- |
| `onLiveActivityState` | `activityId`, `template`, `state`, `origin` |
| `onLiveActivityDismissed` | `activityId`, `template`, `by` (`user` or `end`) |
| `onLiveActivityToken` | `kind` (`update` or `push_to_start`), `template`, `token`; `activityId` for update tokens |

Read `list` to recover the current state after a page loads. Do not rely only on events.

### Errors

Besides the [standard bridge errors](https://setup.despia.com/experiments/v3-extensions#error-codes), the extension can return `unknown_template`, `activities_disabled`, `exists`, `bad_value`, `too_large` and `unknown_activity`. iOS can also return `activity_failed`, `not_foreground`, `too_many` or `unsupported`. Android can return `storage_failed` or `busy` while a notification permission prompt is open. `settings` can return `unavailable`.

## Platform limits

- iOS local starts require the app to be in the foreground. The extension widget target declares iOS 16.6. Push-to-start requires iOS 17.2 or later. ActivityKit and the user's settings control visibility, lifetime and push delivery.
- Android 16 and later can promote an eligible ongoing notification to a Live Update. Earlier versions use an ordinary ongoing notification. Promotion is not guaranteed.
- Android has no ActivityKit relevance score. When you pass `relevance`, Android reports this in `platformDifferences.relevance` instead of applying it.
- On Android 7, ended notifications are removed immediately because notification timeouts are unavailable. On later Android versions the extension uses a timeout for `default` and `after` dismissal.

The [extension skill](https://github.com/despia-legacy/extension-skill) includes a reference for this extension.
