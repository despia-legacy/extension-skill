---
name: despia-native-navigation
description: Add, configure or debug the native navigation bar, tab bar and back gesture in a Despia V3 web-view app with the despia-navigation meta tags and navigation:// calls: titles, large titles, buttons, menus, tabs with badges, events, layout insets and page-change readiness. V3 only.
---

# Despia Native Navigation

Built into the Despia V3 app; nothing to install. The page declares a native navigation bar and tab bar with `<meta name="despia-navigation" content="key=value&...">` and `<meta name="despia-navigation-tabs" content="...">`, and changes them with `despia('navigation://bar?...')` and `despia('navigation://tabs?...')` from `despia-native`.

## The flow

1. Add the meta tags to the `<head>` of every page that should show the chrome; a document that declares nothing turns it off, unless the bar has `persist=true`.
2. Pick the bar keys (`title`, `style`, `back`, `items`, colours) and the tab keys (`items`, `selected`); `items` is a JSON array of `{ id, title, icon: { sfSymbol, svg }, ... }`.
3. Define `window.onNavigationEvent(e)` for `item`, `tab` and (with `backaction=event`) `back` events.
4. Pad the content with `--navigation-inset-top` and `--navigation-inset-bottom` so nothing hides under the bars.
5. For pages that render late, set `ready=manual` and call `navigation://ready` when the content is on screen.
6. Change keys at run time with a call; it changes only the keys it names, and an empty value removes one.

## Rules

- Only keys and values in the guide exist; anything else is ignored with a console warning. Do not invent keys.
- Encode every call value with `encodeURIComponent`; `items` is JSON text.
- Give every icon both `sfSymbol` (iOS) and `svg` or `src` (Android).
- Check `window.despiaNavigation?.supported` before relying on it; older builds do not have it.
- With `backaction=event`, go back with `history.back()`; `navigation://back` sends the event again.
- Check the platform table before promising a key's effect on Android.
- Prove it in the installed app on a device; a browser preview has no native chrome.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/navigation. Where this file and the guide differ, the guide is right; report it.
