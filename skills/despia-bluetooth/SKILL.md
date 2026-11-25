---
name: despia-bluetooth
description: Scan, connect and talk to Bluetooth Low Energy devices, publish GATT services, answer reads and writes from the page, open L2CAP channels and advertise, in a Despia V3 web-view app with the bluetooth extension (extension://ble). Also the move from the retired bluetooth:// scheme. V3 only.
---

# Despia Bluetooth

A Despia V3 extension (`npx despia-extensions add bluetooth`), then a new build with the app's Bluetooth (`bluetooth_always`) and Location (`location_when_in_use`) permissions on. Calls are `extension://ble/<route>?...` through `despia()` from `despia-native`; each route answers in `ble<Route>` (for example `bleScanStart`) and events arrive on `onBle*` callbacks, which you set before the call.

## The flow

1. Call `state?prompt=true` before the first scan, connect or server route; branch on its `state` and `authorization`.
2. Central: `scan_start` (services, seconds), wait for `onBleScanEnd`, read `devices`, `connect`, `discover`, then `read`, `write`, `subscribe`; `disconnect` when done.
3. Peripheral: `service_add` (characteristics as `uuid~flags`), wait for `services`, `value_set`, `advertise_start`; handle `onBleRequest`; for flag `a`, answer `onBleAttRequest` with `respond`.
4. L2CAP: peripheral `l2cap_publish` and shares the `psm`; central `l2cap_open` (on iOS `connect` first); data arrives in `onBleL2CapData`.
5. To read what happened while the page was not loaded, call `events` (and `events?run=<previousRun>` for the last launch).
6. Android background links: `keep_alive?enabled=true` while the app is visible.

## Rules

- Encode every value with `encodeURIComponent`; bytes go as `hex` or `b64`.
- Check `ok` on every answer; a failed call carries `code` and `error`.
- A route that answers `platform_limit` is a real platform limit (iOS: bonding, PHY, connection priority, advertising sets, dropping a central); do not retry it, read the guide's limits.
- Only one GATT operation runs per link at a time; writes are at most 512 bytes; a write without response is not a delivery receipt.
- On iOS a background scan needs a service filter (`filter_required`).
- The old `bluetooth://` scheme is gone; map its calls with the guide's table.
- A real radio needs two devices; the simulator and emulator prove only that the routes answer.

The full guide is `references/guide.md`, generated from https://setup.despia.com/extensions/catalog/bluetooth. Where this file and the guide differ, the guide is right; report it.
