---
name: despia-quick-actions
description: Add long-press app icon shortcuts to a Despia V3 web-view app: iOS Home Screen quick actions and Android launcher app shortcuts from the editor's Quick Actions, with the global function pattern, Quick Link and JavaScript fields, limits and Android link checks. V3 only.
---

# Despia Quick Actions

Add long-press shortcuts on the app icon of a Despia V3 web-view app: Home Screen quick actions on iOS, launcher app shortcuts on Android. They are configured in the Despia Editor (Add-ons, Hard Coded, Quick Actions) and compiled into the build, so a rebuild is needed; there is no SDK call.

## The flow

1. In the page, attach the function the shortcut should run to `window` (a top-level effect in a framework app), before any user action.
2. In the editor add a shortcut: a short label, an icon, a Quick Link (the page that defines the function), and the JavaScript, for example `window.openCategory('food')`.
3. Save, rebuild, install the new build. Up to four shortcuts show, in the editor's order.
4. Tap one: the app opens the Quick Link, then runs the JavaScript once the page has loaded (on Android cold start, once the first page has loaded).

## Rules

- The function name in the editor must match the global exactly; a typo gives `function is not defined`.
- Android publishes only shortcuts that have a label and a Quick Link; give every shortcut a link.
- Android never trusts the launching intent: only a shortcut of the app's own list opens, and `javascript:`, `file:`, `data:`, `content:`, `blob:` and `vbscript:` links do nothing.
- More than four shortcuts: the first four win, and never more than the launcher's own limit.
- A tap runs once; it is not replayed when the app is recreated or reopened from Recents.
- Prove it in the installed app on a device or emulator; a browser has no shortcuts.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/quick-actions. Where this file and the guide differ, the guide is right; report it.
