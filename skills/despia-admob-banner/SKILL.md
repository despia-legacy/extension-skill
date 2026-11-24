---
name: despia-admob-banner
description: Add, place or debug a native AdMob banner in a Despia V3 web-view app with admob://banner: ad units, adaptive, fixed and collapsible sizes, resize or overlay layout, CSS insets, banner events and paid revenue, consent and privacy options. V3 only.
---

# Despia AdMob Banner Ads

Needs AdMob in the Despia V3 app (the AdMob integration with App IDs, and a new build). Calls are `despia('admob://banner?...')`, `admob://banner/hide`, `admob://banner/reload` and `admob://privacyoptions` from `despia-native`, from the top document only.

## The flow

1. Create a Banner ad unit per platform in AdMob; while testing, use Google's sample unit IDs.
2. Define `window.onAdBannerEvent(e)` for `loaded`, `failed`, `impression`, `clicked`, `opened`, `closed` and `paid`.
3. Call `admob://banner` with `adunit` (chosen by platform) unless the app's settings carry it, plus `position`, `size` or `collapsible` if needed.
4. Keep `layout=resize` unless the page reserves the space itself; then use `overlay` with `--ad-banner-inset-bottom` or `--safe-area-bottom`.
5. Hide on screens that should show no ad with `admob://banner/hide`; show again with `admob://banner`.
6. If `window.despiaAds?.privacyOptionsRequired`, offer a link that calls `admob://privacyoptions`.

## Rules

- Only the parameters and values in the guide exist; others are ignored with a console warning.
- Encode values with `encodeURIComponent` or `URLSearchParams`.
- Feature-test with `window.despiaAdBanner?.supported`; a build without AdMob fires `despia-feature-not-included`.
- Handle `failed` codes (`no-ad-unit`, `consent`, `removed`, `att-denied`, or Google's number); never retry in a loop.
- A collapsible banner needs `admob://banner/reload` for another collapsible ad.
- Follow Google's banner policies: no floating over content, keep a `gap` from your own controls.
- Prove it in the installed app on a device; a browser preview has no native banner.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/admob/banner-ads. Where this file and the guide differ, the guide is right; report it.
