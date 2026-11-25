---
name: despia-identity-vault
description: Store auth tokens and secrets encrypted in a Despia V3 app with identityvault:// (Keychain on iOS, Keystore on Android), with optional biometric or passcode lock, iCloud Keychain sync and Google Block Store backup. Use when a user wants to keep a session or refresh token safely in their Despia app or keep users signed in across reinstalls.
---

# Despia Identity Vault

Encrypted storage for tokens and secrets: the Keychain on iOS, the Android Keystore on Android. One `identityvault://` command per call; the answer lands on `window.identityVault`.

```javascript
import despia from 'despia-native'

await despia(`identityvault://set?key=session&value=${encodeURIComponent(token)}`, ['identityVault'])
const { identityVault: r } = await despia('identityvault://get?key=session', ['identityVault'])
if (r.ok) useToken(r.value)
else if (r.error.code === 'not_found') showLogin()
```

## Rules

- Commands: `set`, `get`, `remove`, `keys`, `clear`, `capabilities` (which `lock` a `set` can use), and the vault lock: `lock`, `unlock`, `state`, `configure?lockAfterBackgrounded=` (milliseconds, `0` or `never`). Keys are 1 to 64 characters of `A-Z a-z 0-9 . _ -`. Encode every value with `encodeURIComponent`.
- `set` options: `sync=device|cloud`, `lock=none|biometric|passcode`, `survivesReinstall=true|false`. Pass `id=` to match answers to calls.
- A locked value cannot sync or survive a reinstall on Android, and cannot sync on iOS: those combinations answer `platform_limit`. Read the guide's platform table before combining options.
- Values are at most 64 KB; Android Block Store (`sync=cloud` or `survivesReinstall=true`) keeps at most 16 values of 4094 bytes.
- A locked vault answers reads of locked values with `vault_locked` until `unlock`; lock changes also reach the page as `window.identityVaultLocked`, `window.onIdentityVaultEvent` and the `identityvaultlock` / `identityvaultunlock` events. A prompt cannot show while the app is in the background: that call answers `auth_cancelled` at once, so call again when the app is back.
- Handle `auth_cancelled`, `auth_failed`, `lock_unavailable`, `vault_locked` and `key_invalidated` (biometrics changed: sign in again).
- `survivesReinstall=true` on iOS is likely, not guaranteed: always keep a sign-in fallback.
- Use Storage Vault, not Identity Vault, for non-secret values that should follow the user.
- Do not claim it works until it ran in the installed rebuilt app on a device.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/identity-vault. Where this file and the guide differ, the guide is right; report it.
