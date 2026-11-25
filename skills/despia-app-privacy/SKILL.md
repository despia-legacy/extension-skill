---
name: despia-app-privacy
description: Keep a Despia V3 app consistent with App Store and Play privacy rules: tracking consent gating with despia.trackingDisabled, the iOS privacy manifest Despia generates from compiled features (ITMS-91053), and matching privacy labels and policy. V3 only.
---

# Despia App Store Privacy

Keep a Despia V3 app consistent with Apple's and Google's privacy rules: gate analytics and ad SDKs on the tracking consent flag (`despia.trackingDisabled`), and know what the iOS build declares on its own. Needs no SDK call for the manifest; it is generated at build time.

## The flow

1. Read `despia.trackingDisabled` before starting any analytics or ad SDK; do not start them when it is `true`.
2. Write the privacy policy and the App Store Connect App Privacy answers from what your app really collects, using the table in the guide of what the iOS build declares per feature.
3. For Android fill the Data safety form in the Play Console; Android has no manifest file.
4. Rebuild after adding or removing a feature (location, Health, motion, Stream): the generated manifest follows the compiled features.

## Rules

- iOS: required-reason APIs and collected data types are read from `PrivacyInfo.xcprivacy` only; an `Info.plist` privacy block has no effect (ITMS-91053).
- The runtime's manifest says `NSPrivacyTracking` false; SDKs you add (AppsFlyer, Audience Network) declare their own.
- The manifest does not replace the privacy policy or the App Privacy labels; they must tell the same story.
- Do not ship your own `PrivacyInfo.xcprivacy` as an app resource inside an extension; it collides with the generated file.
- Check the real archive for the file before submitting; a simulator build proves nothing about review.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/app-privacy, https://setup.despia.com/store-rejections/common-rejection/privacy-policy. Where this file and the guide differ, the guide is right; report it.
