---
name: despia-contacts
description: Read device contacts in a Despia V3 web-view app with requestcontactpermission:// and readcontacts://: permission flow, the contacts object, the null answer when access is missing, iOS limited access and Android differences. V3 only.
---

# Despia Contact Access

Read the device contacts in a Despia V3 web-view app with `despia('requestcontactpermission://')` then `despia('readcontacts://', ['contacts'])` from `despia-native`. Needs the contacts feature in the build (`NSContactsUsageDescription` on iOS, the contacts permission on Android).

## The flow

1. Detect Despia from the user agent; a browser has no contacts.
2. `await despia('requestcontactpermission://')` once; it shows the system prompt the first time and does nothing after.
3. `const data = await despia('readcontacts://', ['contacts'])`.
4. If `data.contacts === null`, access is missing (denied, restricted, or a build without contacts): show a manual path and a hint to open Settings.
5. Otherwise `data.contacts` is `{ "Display Name": ["+1...", ...] }`; hash numbers before any server matching.

## Rules

- A read always answers: the object, or `null`. It never rejects and never stays unset; test the value, do not rely on `catch`.
- iOS: a read made while the prompt is on screen waits for the choice; limited access (iOS 18) returns only the contacts the user shared.
- Android: a read without permission answers `null` at once and shows no prompt; call the permission call first.
- After a denial the OS does not ask again; the user must change it in Settings.
- Never upload raw address books; hash numbers on the device and keep nothing without consent.
- Prove it in the installed app on a device.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/contact-access. Where this file and the guide differ, the guide is right; report it.
