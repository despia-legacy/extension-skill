---
name: despia-widget-json
description: Build, update or debug JSON-described home screen widgets, lock screen widgets, Live Activities and Android Live Updates in a Despia V3 web-view app with widgetjson:// calls: documents, layouts per family, styles, formulas, actions, timelines, refresh URLs, push envelopes and tokens. V3 only.
---

# Despia Widget JSON

Built into the Despia V3 app; nothing to install (iOS needs the Home Widgets widget setup once). Calls are `despia('widgetjson://<route>?...')` from `despia-native`; every call answers on `window.onWidgetJsonResult({ rid, call, ok, data | code, error, path })`.

## The flow

1. Read `widgetjson://capabilities` and branch on `families`, `activities`, `interactive` and `push`.
2. Write the document: `widgetJson: 1`, `kind`, `id`, `state`, `layouts` (one per family group, a last one without `for`), `styles`, `actions`.
3. Run `validate` and fix every error by its `path`; read the notes for each platform.
4. `widget/set` with `id` and `doc`; then change only state with `widget/state` (`patch` merges).
5. For time-based changes use `widget/timeline`; for server data use `widget/refresh` or a push envelope under `widgetjson`.
6. Handle taps on `window.onWidgetJsonAction`, and read `events` (then `ack`) after the app opens.
7. Activities: a `kind: "activity"` document with `summary`; `activity/start`, `activity/update`, `activity/end`; send push tokens with `token-sink`.

## Rules

- Only elements, styles, operators and families listed in the guide exist; never invent one.
- Write every formula from the guide's operator tables (arguments, result, example); never guess a signature. A one-key options object needs `lit`: `{"lit": {"decimals": 2}}`.
- No value is converted: `{"+": ["1", 2]}` is an error; join text with `cat`. Only `false` and `null` count as false.
- Send state, not documents, for updates; keep an activity's attributes and state under 4 KB.
- A `list` needs `max`; single-tap families use the layout's `widgetUrl`, not a `link`.
- Give `image.symbol` both `ios` and `android` names, or use `svg`.
- Remote pictures must be `https://`; the app downloads them (and rasterises `svg`) when a document, state or push arrives, scales them to 1200 px and skips anything over 16 MB. A summary `icon` or `largeIcon` takes the same `symbol`, `src`, `svg` or `asset` forms.
- Encode every value with `encodeURIComponent`; JSON values are JSON text.
- A `stale` or `duplicate` answer means an older update; pass a higher `seq`.
- Prove it in the installed app on a device; a browser preview has no widget.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/widget-json. Where this file and the guide differ, the guide is right; report it.
