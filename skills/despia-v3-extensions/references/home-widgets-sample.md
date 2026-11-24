# Home Widgets extension

Documentation: https://setup.despia.com/experiments/samples/home-widgets

Source: [Home Widgets repository](https://github.com/despia-legacy/home-widgets). Read its [LICENSE](https://github.com/despia-legacy/home-widgets/blob/main/LICENSE) before using or modifying the extension.

Bundled files: `examples/home-widgets/`, copied from the template repository. Copy `manifest.json`, `ios/` and `android/` to `public/despia/<id>/`, and put `extensions.json` in `public/despia/` or merge its name into your own; `home-widgets.policy.json` is build verification metadata and is not served.

This extension puts data from your web app into native home-screen widgets. It includes a stat card and a list with buttons and checkboxes. The page sets and reads the widget data, reloads the extension's widgets, lists installed widgets and reads queued taps.

The first-party Home Widget setting (`widget://`, page https://setup.despia.com/native-features/home-widgets) is separate and needs no extension. Choose it when one SVG from your backend, refreshed in the background, is enough. Choose this extension when the page must fill native stat and list widgets and read taps on their buttons and checkboxes.

It is for the Despia V3 runtime. You own and maintain the copied native code. First-party V4 support comes through the new package registry. Read [V3 Extensions](https://setup.despia.com/experiments/v3-extensions) for build verification and rebuild requirements.

## Install

1. Copy the files from the linked extension repository into `public/despia/widgets/` in your web app, keeping their `ios/` and `android/` paths.
2. Add `"widgets"` to the `extensions` array in `public/despia/extensions.json`, keeping any other extension names.
3. For iOS, register the App Group `group.<your bundle id>.nx.widgets` and enable it on both the app and the widget extension App IDs. The widget extension bundle ID is `<your bundle id>.NXWidgets`. Regenerate both provisioning profiles. See [Set up iOS App Groups](manifest.md#set-up-ios-app-groups) for the steps and signing errors.
4. Rebuild and install your native app with the extension compiled in. Changing native files requires fresh build verification and a new build.

The manifest adds the `NXWidgets` WidgetKit target on iOS and a custom Glance module on Android. The build policy stays with the trusted build inputs, outside your served `public/despia/` folder. The Android module declares the widget receivers and its RemoteViews service.

## Call it from your web app

Install the SDK with `npm install despia-native`. Import `despia` from `despia-native` and watch the route's declared output variable. Call these functions from client events.

```javascript
import despia from 'despia-native';

async function updateWidget() {
  const widget = {
    title: "Today's orders", value: '12', caption: 'Ready to dispatch',
    progress: 0.6, link: '/orders'
  };
  const reply = await despia('extension://widgets/set?template=stat&data=' + encodeURIComponent(JSON.stringify(widget)), ['widgetsSet']);
  const answer = reply.widgetsSet;
  if (answer === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!answer.ok) { console.error(answer.code, answer.error); return; }
  console.log(answer.data.template, answer.data.stored);
}

async function readWidget() {
  const widgetReply = await despia('extension://widgets/get?template=stat', ['widgetsGet']);
  const widget = widgetReply.widgetsGet;
  if (widget === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!widget.ok) { console.error(widget.code, widget.error); return; }
  const queuedReply = await despia('extension://widgets/taps', ['widgetsTaps']);
  const queued = queuedReply.widgetsTaps;
  if (queued === undefined) { console.error('No native reply within 30 seconds'); return; }
  if (!queued.ok) { console.error(queued.code, queued.error); return; }
  console.log(widget.data.data, queued.data.taps);
}
```

For live taps, register the declared `onWidgetTap` callback with browser lifecycle cleanup as described in `references/routes-and-answers.md`. Read `taps` after loading the page to recover queued taps.

Setting data does not place a widget on the screen. The person adds it from the widget gallery, or accepts an Android `pin` request.

## Routes and answers

Each success answer uses `{ok: true, data: ...}`. Failures use `{ok: false, code, error}`. The table lists the fields inside `data`.

| Route | Parameters | Answer variable and callback | Answer |
| --- | --- | --- | --- |
| `set` | Required: `template` (`stat` or `list`), `data` (JSON text) | `widgetsSet`, `onWidgetsSet` | `template`, `stored: true` |
| `get` | Required: `template` | `widgetsGet`, `onWidgetsGet` | `template`, `data` (canonical object, or `null` when empty) |
| `clear` | Required: `template` | `widgetsClear`, `onWidgetsClear` | `template`, `cleared: true` |
| `reload` | Optional: `template` | `widgetsReload`, `onWidgetsReload` | `reloaded`, an array of template names |
| `installed` | None | `widgetsInstalled`, `onWidgetsInstalled` | `widgets`, an array of placed widgets |
| `taps` | Optional: `ack` (integer from 0 to 9007199254740991) | `widgetsTaps`, `onWidgetsTaps` | `taps`, `dropped`, `next` |
| `pin` | Required: `template` | `widgetsPin`, `onWidgetsPin` | Android: `requested: true` if the request was shown. iOS: `platform_limit` |

`reload` touches only this extension's widget kinds. For `installed`, each iOS row contains `template` and `family`; each Android row contains `template`, `width` and `height` in dp.

### Widget data

The JSON text must fit in 4096 UTF-8 bytes. Unknown keys, duplicate keys and invalid types return `bad_data` with the field path and reason.

| Template | Required fields | Optional fields |
| --- | --- | --- |
| `stat` | `title` (1 to 40 characters), `value` (1 to 16 characters) | `unit` (1 to 12), `caption` (1 to 60), `progress` (0 to 1), `trend` (`up`, `down`, `flat`), `tint`, `link`, `actions` (up to 2 buttons) |
| `list` | `title` (1 to 40 characters), `items` (up to 8) | `empty` (1 to 60), `tint`, `link`, `action` (one button) |

Each list item requires `id` and `title` (1 to 60 characters). It may have `subtitle` (1 to 60), `checked` (boolean) and `link`. Each button requires `id` and `label` (1 to 20). IDs contain 1 to 32 lowercase letters, digits, underscores or hyphens. IDs must be unique within their list or button array. `tint` is `#RRGGBB`. A `link` is an app path starting with one `/`, with no backslash or spaces, up to 512 characters.

### Taps

`onWidgetTap` receives `{seq, template, kind, id, at}`, plus `link` for an open and `checked` for a toggle. `kind` is `open`, `action` or `toggle`. Its event scope is the document: pages that have called this extension receive it.

Taps are stored before they are emitted. Call `taps` after a page loads, then pass the last consumed `seq` as `ack` on the next read to remove acknowledged taps. The queue keeps the newest 32 taps and counts older discarded taps in `dropped`.

### Errors

Besides the [standard bridge errors](https://setup.despia.com/experiments/v3-extensions#error-codes), the extension can return `bad_data` or `storage_failed`. On iOS, missing App Group access returns `group_unavailable` for the storage routes. Android `pin` can return `pin_unavailable` when the launcher does not support pinning. iOS `pin` returns `platform_limit`.

## Platform limits

- The iOS widget extension requires iOS 17. The user places widgets through the widget gallery; iOS has no app API for placing one. App Group access is required to share the widget data and taps.
- Android pinning needs Android 8 or later and a launcher that supports it. `requested: true` confirms the request was shown, not that a widget was placed. Check `installed` afterwards.
- Widget size, placement and update timing are controlled by the OS and launcher. The extension requests updates of its own widget kinds only.
- Android widget taps may fail to open a link when the app is already running.

The [extension skill](https://github.com/despia-legacy/extension-skill) includes a reference for this extension.
