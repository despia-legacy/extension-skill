---
name: despia-local-server
description: Serve a Despia V3 web build from the device with the local server: the despia/local.json manifest, deployed_at updates, all-or-nothing deployment swaps, what survives an update, and how to test a failing update. V3 only.
---

# Despia Local Server

Serve a Despia V3 web build from the device (`http://localhost`) so it boots fast and works offline. The build produces `despia/local.json` (`entry`, `deployed_at`, sorted `assets`); the app downloads a new deployment when `deployed_at` changes. Needs a new build with the local server on; there is no SDK call.

## The flow

1. Generate the manifest in your build with the Despia plugin; check that `assets` lists every deployed file and `entry` exists.
2. Deploy the files and the manifest together; change `deployed_at` on every deployment.
3. The app compares `deployed_at` at start, downloads the whole new version in the background, and serves it only when every file arrived.
4. Test an update by deploying a version, then one with a missing listed file: the app must stay on the first version.

## Rules

- Updates are all or nothing: one failed or non-2xx file drops the download, the previous complete deployment keeps being served online and offline, and the update is retried on a later launch.
- A killed update is finished at the next launch when it was complete, else dropped.
- Never list a file in `assets` that you do not deploy; it blocks the whole update.
- Local CDN files, local uploads and shared files survive every deployment; they are not part of it.
- Only web content updates this way (HTML, CSS, JS, images, fonts); native code, permissions and new native capabilities need a store release.
- Prove updates in the installed app on a device or emulator, with a deployment that fails midway.

The full guide is `references/guide.md`, generated from https://setup.despia.com/local-server/introduction, https://setup.despia.com/local-server/reference. Where this file and the guide differ, the guide is right; report it.
