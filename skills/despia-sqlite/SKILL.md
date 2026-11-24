---
name: despia-sqlite
description: Use the SQLite database built into a Despia V3 app with extension://sqlite routes: SQL with bound parameters, transactions, migrations, several named databases, and a small record store with revisions. Use when a user wants offline or on-device data, tables or queries in their Despia app.
---

# Despia SQLite

SQLite is built into the Despia V3 app; nothing to install. Every call is `extension://sqlite/<route>?...` through `despia()` from `despia-native`, waiting for the route's answer variable (`sqlite` + the route in camel case: `sqliteQuery`, `sqliteExecuteScript`).

```javascript
import despia from 'despia-native'
const q = (s) => encodeURIComponent(typeof s === 'string' ? s : JSON.stringify(s))

await despia(`extension://sqlite/migrate?migrations=${q([{ version: 1, sql: 'CREATE TABLE notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL)' }])}`, ['sqliteMigrate'])
const { sqliteQuery: a } = await despia(`extension://sqlite/query?sql=${q('SELECT * FROM notes WHERE id = ?')}&params=${q([1])}`, ['sqliteQuery'])
if (a === undefined) { /* no answer within 30 s */ } else if (!a.ok) console.error(a.code, a.error); else use(a.data.rows)
```

## Rules

- Always bind values with `params` (a JSON array for `?`, an object for `:name`). Never build SQL from user input.
- `execute` and `query` take exactly one statement. Several statements: `execute_script` (no parameters) or `transaction` (all or none).
- Keep the schema in one `migrate` list with ascending versions and call it on every start; it applies only versions above `PRAGMA user_version`.
- BLOBs are `{ "$blob": base64 }` and integers beyond JavaScript's safe range are `{ "$bigint": "decimal" }`, both ways.
- `db` names a database (default `main`). Each database runs one call at a time.
- Handle `bad_request`, `sql_error`, `constraint`, `busy`, `storage_full`, `timeout`, `storage_unavailable`. On no answer, read before retrying a write.
- The record routes (`get`, `put`, `delete`, `list`, `batch`, `status`) are a separate 512-record key-value store with revisions (`if_revision`); use them for small optimistic-concurrency records.
- Data is on this device only, not encrypted by SQLite. Tokens and secrets go in Identity Vault.
- Prove it in the installed app on a device; a browser preview has no native SQLite.

The full guide is `references/guide.md`, generated from https://setup.despia.com/native-features/sqlite. Where this file and the guide differ, the guide is right; report it.
