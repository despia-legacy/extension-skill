# Limits and warnings

Source: the published V3 Extensions page, https://docs.despia.com/experiments/v3-extensions (Experimental, Despia V3 only).

**Experimental. V3 only.**

  - V3 Extensions work only in Despia V3. They are never migrated to V4. V4 has its own first-party plugins and plugin system instead. You can stay on V3 as long as you want.
  - You own and maintain the native code. That includes the code you copy from a sample.
  - Despia provides the templates and the extension system. Despia does not support code you have customised.
  Calls are accepted only from the top-level page of your app's own HTTPS origin, or from origins you list in `bridge.allowed_origins`. Iframes, other origins, popups and page sheets get no answer.
## Platform limits to plan for

- **iOS.** Background audio continues only while the audio session stays active. Recording must start in the foreground, and a force-quit ends it. Lock Screen widget families need iOS 16 and update on WidgetKit's budget. App Groups and new App IDs are manual steps in the Apple Developer portal.
- **Android.** Android 14 and later only start a microphone foreground service while the app is visible. Lock Screen widgets depend on the device, so the foreground service notification is the guaranteed Lock Screen presence. Google Play requires foreground service type declarations.
- **Files.** Android WorkManager stops a regular worker after 10 minutes, and an upload restarts from its first byte.
- **Both.** Apple managed capabilities and restricted Android permissions need approval from Apple or Google and pass the store's own review.
