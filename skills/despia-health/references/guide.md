# despia-health

Generated from the docs pages below; do not edit by hand. Change the page and regenerate.


## Apple Health

Page: https://setup.despia.com/health-data/apple-health

Access Apple HealthKit from your web app through the Despia native bridge. Despia gives you three ways to work with health data: read historical records on demand, write new samples back to HealthKit, and subscribe to live updates via server webhooks. All three work from JavaScript with no native code required.

> **Info:** HealthKit is iOS only. Always gate calls behind an `isDespiaIOS` check so your app degrades gracefully in a browser or on Android.

---

### Installation


**Bundle**

<CodeGroup>

```bash npm
npm install despia-native
```

```bash pnpm
pnpm add despia-native
```

```bash yarn
yarn add despia-native
```

</CodeGroup>

```javascript
import despia from 'despia-native';
```

**CDN**

<CodeGroup>

```html UMD
<script src="https://cdn.jsdelivr.net/npm/despia-native/index.min.js"></script>
```

```html ESM
<script type="module">
    import despia from 'https://cdn.jsdelivr.net/npm/despia-native/+esm'
</script>
```

</CodeGroup>



---

### Apple Developer and Despia setup

HealthKit requires the HealthKit capability on your core app bundle ID before any read or write call works. Configure Apple Developer first, then enable Health Data in the Despia Editor and rebuild.


**Sign in to Apple Developer**

Go to [developer.apple.com](https://developer.apple.com) and sign in with the Apple Developer account that owns your app's primary bundle ID. Navigate to **Certificates, Identifiers & Profiles \> Identifiers**.

**Add HealthKit to your core app bundle ID**

Find your core app (e.g. `com.despia.myapp`), click into it, and check **HealthKit** under Capabilities. Click **Save**. Without this, HealthKit reads will return empty arrays and writes will fail silently, since iOS rejects HealthKit access for any app whose bundle ID does not declare the capability.

**Enable Health Data in the Despia Editor**

Open the Despia Editor and go to **App \> Addons \> Health Data**. Toggle the addon on. This signals Despia to compile the HealthKit framework and the matching usage descriptions into your next build.

**Rebuild your app**

Trigger a fresh build from the Despia Editor. HealthKit linking happens at the binary level, so this cannot be applied over-the-air. After the rebuild, calls to `readhealthkit://`, `writehealthkit://`, `healthkit://workouts`, and `healthkit://observe` will function in production.



> **Warning:** Skipping the HealthKit capability on the core bundle ID, or skipping the rebuild after enabling the Despia addon, leaves the integration inactive even when both toggles read enabled. `readhealthkit://` returns empty arrays, `healthkit://workouts` resolves to `[]`, `writehealthkit://` resolves silently without saving, and `healthkit://observe` never fires a webhook. If health data stops working after editing settings, verify the capability is on in Apple Developer and rebuild before opening a support ticket.

---

### Choosing the right approach

HealthKit data can be accessed in three ways. Which one to use depends on whether you need data right now, want to save data, or need to react to changes continuously.

| Approach | Use when | How it works |
| :-- | :-- | :-- |
| **Read** | You need historical data at a specific moment, on page load, on a button tap, or when building a report | Call `readhealthkit://` or `healthkit://workouts` and await the result. Returns records for the last N days |
| **Write** | You want to save a value back to HealthKit, for example logging a weight entry or a step count from your own sensor | Call `writehealthkit://` with a value. Adds a new sample without replacing existing data |
| **Observer** | You need your server to stay in sync with the user's health data automatically, without requiring the user to open the app | Register a webhook with `healthkit://observe`. Despia posts new data to your server whenever HealthKit updates, even in the background |

A fitness app that shows a weekly steps chart uses **read**, fetching data when the user opens the dashboard. A coaching platform that tracks whether a user hit their daily goal uses **observer**, the server receives updates automatically and can send a push notification without any user action. An app that lets users log body weight from a form uses **write** to save the entry back to Apple Health.

You can combine all three. Most apps that use observer also use read to populate the initial UI before the first webhook fires.

---

### How it works

Define the `isDespiaIOS` check once at the top of your app and reuse it throughout. Pass one or more identifiers as a comma-separated list on the URL host, Despia routes the request through the native bridge in a single dispatch and returns one response keyed by identifier.

```javascript
const isDespiaIOS = navigator.userAgent.toLowerCase().includes('despia') && (
    navigator.userAgent.toLowerCase().includes('iphone') ||
    navigator.userAgent.toLowerCase().includes('ipad')
)

if (isDespiaIOS) {
    const data  = await despia(
        'readhealthkit://HKQuantityTypeIdentifierStepCount,HKQuantityTypeIdentifierHeartRate?days=7',
        ['healthkitResponse']
    )

    const steps = data.healthkitResponse.HKQuantityTypeIdentifierStepCount
    const hr    = data.healthkitResponse.HKQuantityTypeIdentifierHeartRate
}
```

Despia requests HealthKit permission on the first call for each identifier, then fetches and returns the data. A single multi-identifier call prompts the user once for the whole set rather than once per type.

#### Answers and errors

Every read and every workouts call answers once, on both iOS and [Android](https://setup.despia.com/health-data/google-health#answers-and-errors), in the same shape:

- Every identifier you asked for is a key whose value is an array: the samples read, or `[]` when there is nothing to show (no data, or a read the user declined; HealthKit does not tell an app which).
- A call that failed adds `error` (a code) and `message` (a sentence), always together. An answer that did not fail has neither, so `if (response.error)` is the whole check.
- If the page defines `window.onHealthkitResponse(response)`, it is called once per answer with the same object, so you can wait for the result instead of polling `window.healthkitResponse`.

```javascript
window.onHealthkitResponse = (response) => {
    if (response.error) {
        console.warn(response.error, response.message)
        return
    }
    const steps = response.HKQuantityTypeIdentifierStepCount // always an array
}
despia('readhealthkit://HKQuantityTypeIdentifierStepCount?days=7')
```

| `error` | When |
| --- | --- |
| `healthkit_unavailable` | Health data is not available on this device |
| `healthkit_authorization_failed` | The permission request itself failed (not a decline) |
| `healthkit_timeout` | The reads did not finish in time; the answer keeps what was read so far and `[]` for the rest |
| `healthkit_read_failed` | A query failed, or the view went away before the answer |

Every call answers within 15 seconds of starting. While HealthKit's permission sheet is on screen, that clock waits for the sheet (your call's own sheet, or another call's sheet ahead of it in line) and a 300 second safety limit applies instead; when the call goes on it gets a fresh 15 seconds. A call that is stuck therefore always ends with `healthkit_timeout`.

A `healthkit://workouts` call also merges its list into `window.healthkitResponse.HKWorkoutTypeIdentifier` and calls `onHealthkitResponse`. It clears any `error` and `message` left by an earlier read, and sets its own pair only when the workouts call itself failed.

---

### Read

Use `readhealthkit://` to fetch historical data on demand. This is the right approach when your UI needs to display health data at a point in time, on load, after a user action, or when generating a report. You control exactly when the fetch happens and how many days of history to retrieve.

Pass any valid `HKQuantityTypeIdentifier`, `HKCategoryTypeIdentifier`, `HKWorkoutTypeIdentifier`, or `HKCharacteristicTypeIdentifier` as the URL host, plus an optional `days` parameter. See [Identifier reference](#identifier-reference) for the full list.

#### Reading multiple identifiers

Most real apps need more than one metric at a time, a dashboard typically wants steps, heart rate, calories, and sleep all at once. Comma-separate the identifiers on the URL host and Despia returns them in a single response, keyed by identifier name.

```javascript
if (isDespiaIOS) {
    const data = await despia(
        'readhealthkit://HKQuantityTypeIdentifierStepCount,HKQuantityTypeIdentifierActiveEnergyBurned,HKCategoryTypeIdentifierSleepAnalysis?days=7',
        ['healthkitResponse']
    )

    const steps  = data.healthkitResponse.HKQuantityTypeIdentifierStepCount
    const energy = data.healthkitResponse.HKQuantityTypeIdentifierActiveEnergyBurned
    const sleep  = data.healthkitResponse.HKCategoryTypeIdentifierSleepAnalysis
}
```

Each key on `healthkitResponse` holds the same array shape that a single-identifier read would return for that type. Quantity types return daily values, sleep returns stage intervals, workouts return session records. The single `days` parameter applies to every identifier in the call.

You can mix categories freely in one call as long as they all accept a `days` window:

```javascript
if (isDespiaIOS) {
    const data = await despia(
        'readhealthkit://HKQuantityTypeIdentifierStepCount,HKQuantityTypeIdentifierHeartRate,HKWorkoutTypeIdentifier?days=14',
        ['healthkitResponse']
    )
}
```

Characteristics (`HKCharacteristicTypeIdentifierDateOfBirth`, biological sex, blood type, skin type) are static values and do not accept `days`, so fetch them with their own dedicated call.

> **Warning:** Always batch related reads into one comma-separated call. Firing several `despia()` reads in parallel with `Promise.all` queues multiple independent requests against the native bridge, which can race during permission prompts and on slower devices, causing one or more reads to return stale or empty arrays without throwing. A single multi-identifier call goes through one native dispatch, hits HealthKit's authorization layer once, and resolves all identifiers atomically.

#### Quantity types

Quantity types cover numeric metrics: steps, heart rate, distance, body mass, calories, and more. Each record represents one day's value for the requested identifier. For individual samples with timestamps instead of a daily value, see [Raw samples](#raw-samples).

```javascript
if (isDespiaIOS) {
    const data      = await despia('readhealthkit://HKQuantityTypeIdentifierHeartRate?days=30', ['healthkitResponse'])
    const heartRate = data.healthkitResponse.HKQuantityTypeIdentifierHeartRate
}
```

```json
[
  { "date": "2025-11-16", "value": 68, "unit": "count/min" },
  { "date": "2025-11-17", "value": 72, "unit": "count/min" },
  { "date": "2025-11-18", "value": 70, "unit": "count/min" }
]
```

<ResponseField name="date" type="string">
  Start of day in ISO 8601 format
</ResponseField>

<ResponseField name="value" type="number">
  The health metric value for that day
</ResponseField>

<ResponseField name="unit" type="string">
  Unit of measurement, e.g. `count`, `count/min`, `kg`, `m`. Determined automatically by the identifier.
</ResponseField>

---

#### Raw samples

By default a quantity read returns one averaged value per day. Add `raw=true` to get every individual sample with its own timestamp instead. The daily average is computed on-device, so without `raw` the underlying samples never reach your app. This is the mode to use for intra-day or overnight analysis, for example tracking how HRV moves across a night of sleep.

```javascript
if (isDespiaIOS) {
    const data    = await despia('readhealthkit://HKQuantityTypeIdentifierHeartRateVariabilitySDNN?days=1&raw=true', ['healthkitResponse'])
    const samples = data.healthkitResponse.HKQuantityTypeIdentifierHeartRateVariabilitySDNN
}
```

```json
[
  { "startDate": "2026-06-03T01:14:23Z", "endDate": "2026-06-03T01:14:23Z", "value": 52.3, "unit": "ms" },
  { "startDate": "2026-06-03T01:19:41Z", "endDate": "2026-06-03T01:19:41Z", "value": 48.7, "unit": "ms" },
  { "startDate": "2026-06-03T01:24:58Z", "endDate": "2026-06-03T01:24:58Z", "value": 61.2, "unit": "ms" }
]
```

<ResponseField name="startDate" type="string">
  ISO 8601 start time of the sample
</ResponseField>

<ResponseField name="endDate" type="string">
  ISO 8601 end time of the sample
</ResponseField>

<ResponseField name="value" type="number">
  The measured value for that sample
</ResponseField>

<ResponseField name="unit" type="string">
  Unit of measurement, determined automatically by the identifier
</ResponseField>

`raw=true` works with comma-separated identifiers and multi-day windows, and applies to quantity types only. Samples come back sorted oldest to newest. It has no effect on `HKCategoryTypeIdentifierSleepAnalysis` or workout reads, which already return per-sample data. Calls without `raw=true` return the same daily aggregate as before, so adding it is non-breaking, and on an app build that predates this feature `raw=true` is ignored and you get the aggregate.

---

#### Sleep data

Sleep analysis uses `HKCategoryTypeIdentifierSleepAnalysis`. Unlike quantity types, sleep returns individual stage intervals rather than daily aggregates, each record covers a contiguous block of one sleep stage with its own start and end time.

```javascript
if (isDespiaIOS) {
    const data  = await despia('readhealthkit://HKCategoryTypeIdentifierSleepAnalysis?days=7', ['healthkitResponse'])
    const sleep = data.healthkitResponse.HKCategoryTypeIdentifierSleepAnalysis
}
```

```json
[
  { "startDate": "2025-11-17T22:15:00Z", "endDate": "2025-11-17T22:45:00Z", "value": 0, "label": "inBed" },
  { "startDate": "2025-11-17T22:45:00Z", "endDate": "2025-11-18T00:30:00Z", "value": 3, "label": "core" },
  { "startDate": "2025-11-18T00:30:00Z", "endDate": "2025-11-18T02:15:00Z", "value": 4, "label": "deep" },
  { "startDate": "2025-11-18T02:15:00Z", "endDate": "2025-11-18T03:45:00Z", "value": 5, "label": "rem"  },
  { "startDate": "2025-11-18T03:45:00Z", "endDate": "2025-11-18T06:00:00Z", "value": 3, "label": "core" },
  { "startDate": "2025-11-18T06:00:00Z", "endDate": "2025-11-18T06:20:00Z", "value": 2, "label": "awake" }
]
```

<ResponseField name="startDate" type="string">
  ISO 8601 start time of the sleep stage interval
</ResponseField>

<ResponseField name="endDate" type="string">
  ISO 8601 end time of the sleep stage interval
</ResponseField>

<ResponseField name="value" type="number">
  Raw integer from `HKCategoryValueSleepAnalysis`
</ResponseField>

<ResponseField name="label" type="string">
  Human-readable stage: `inBed`, `awake`, `asleep`, `core`, `deep`, or `rem`
</ResponseField>

---

#### Workouts

Use the dedicated `healthkit://workouts` scheme to fetch workout sessions, optionally enriched with per-workout statistics like average heart rate or summed active energy. The response lands on `window.healthkitWorkouts` as a flat array, distinct from the multi-type `healthkitResponse` object used by `readhealthkit://`.

```javascript
if (isDespiaIOS) {
    const data     = await despia('healthkit://workouts?days=14', ['healthkitWorkouts'])
    const workouts = data.healthkitWorkouts
}
```

```json
[
  {
    "date": "2026-05-07T07:32:11Z",
    "activityType": "Running",
    "duration": 1820.4,
    "calories": 312.7,
    "distance": 4521.3,
    "value": 4521.3,
    "unit": "m"
  },
  {
    "date": "2026-05-05T18:02:00Z",
    "activityType": "FunctionalStrengthTraining",
    "duration": 2700,
    "calories": 220.5,
    "distance": 0,
    "value": 220.5,
    "unit": "kcal"
  }
]
```

<ParamField path="days" type="number">
  Number of days back to look. Defaults to `1`.
</ParamField>

<ParamField path="included" type="string">
  Comma-separated list of per-workout quantity statistics. Each entry is a real `HKQuantityTypeIdentifier` followed by an aggregation suffix. Omit to skip per-workout stats. See [Per-workout statistics](#per-workout-statistics).
</ParamField>

<ResponseField name="date" type="string">
  ISO 8601 start time of the workout
</ResponseField>

<ResponseField name="activityType" type="string">
  Apple's activity enum name in PascalCase, for example `Running`, `Cycling`, `FunctionalStrengthTraining`. See [HKWorkoutActivityType](https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype) for the complete list.
</ResponseField>

<ResponseField name="duration" type="number">
  Duration in seconds
</ResponseField>

<ResponseField name="calories" type="number">
  Kilocalories burned. `0` if the source did not record this.
</ResponseField>

<ResponseField name="distance" type="number">
  Distance in meters. `0` if not applicable, for example strength training.
</ResponseField>

<ResponseField name="value" type="number">
  Primary metric, picked dynamically: distance if greater than `0`, otherwise calories, otherwise duration in seconds
</ResponseField>

<ResponseField name="unit" type="string">
  Unit of `value`: `m`, `kcal`, or `s`
</ResponseField>

<ResponseField name="samples" type="array">
  Per-workout quantity statistics, present only when `included=` is non-empty. Each entry has `key`, `value`, and `unit`.
</ResponseField>

##### Per-workout statistics

Pass `included=` to compute aggregated quantity values scoped to each workout's time range. Each entry is an `HKQuantityTypeIdentifier` plus an aggregation suffix. The native side strips the suffix to request authorization for the underlying type, then runs the aggregation across that workout's `startDate` to `endDate` window. The result is the avg, max, min, or sum of that signal during the workout, not over the whole day.

| Suffix | Aggregation | Example |
| :-- | :-- | :-- |
| `Average` | Discrete average | `HKQuantityTypeIdentifierHeartRateAverage` |
| `Max` | Discrete maximum | `HKQuantityTypeIdentifierHeartRateMax` |
| `Min` | Discrete minimum | `HKQuantityTypeIdentifierHeartRateMin` |
| `Sum` | Cumulative sum | `HKQuantityTypeIdentifierActiveEnergyBurnedSum` |
| _(none)_ | Discrete average | `HKQuantityTypeIdentifierHeartRate` |

Stack multiple stats for the same identifier by repeating it with different suffixes. The example below pulls average, max, and min heart rate for every workout in the last 14 days.

```javascript
if (isDespiaIOS) {
    const data     = await despia(
        'healthkit://workouts?days=14&included=HKQuantityTypeIdentifierHeartRateAverage,HKQuantityTypeIdentifierHeartRateMax,HKQuantityTypeIdentifierHeartRateMin',
        ['healthkitWorkouts']
    )
    const workouts = data.healthkitWorkouts
}
```

```json
[
  {
    "date": "2026-05-07T07:32:11Z",
    "activityType": "Running",
    "duration": 1820.4,
    "calories": 312.7,
    "distance": 4521.3,
    "value": 4521.3,
    "unit": "m",
    "samples": [
      { "key": "HKQuantityTypeIdentifierHeartRateAverage", "value": "148.2", "unit": "count/min" },
      { "key": "HKQuantityTypeIdentifierHeartRateMax",     "value": "176.0", "unit": "count/min" },
      { "key": "HKQuantityTypeIdentifierHeartRateMin",     "value": "62.0",  "unit": "count/min" }
    ]
  }
]
```

Mix multiple identifiers and aggregations in a single call. Heart rate stats and summed active energy together:

```javascript
if (isDespiaIOS) {
    const data     = await despia(
        'healthkit://workouts?days=7&included=HKQuantityTypeIdentifierHeartRateAverage,HKQuantityTypeIdentifierHeartRateMax,HKQuantityTypeIdentifierActiveEnergyBurnedSum',
        ['healthkitWorkouts']
    )
    const workouts = data.healthkitWorkouts
}
```

```json
{
  "date": "2026-05-04T06:10:00Z",
  "activityType": "Cycling",
  "duration": 3600,
  "calories": 540.2,
  "distance": 18234.0,
  "value": 18234.0,
  "unit": "m",
  "samples": [
    { "key": "HKQuantityTypeIdentifierHeartRateAverage",      "value": "138.4", "unit": "count/min" },
    { "key": "HKQuantityTypeIdentifierHeartRateMax",          "value": "171.0", "unit": "count/min" },
    { "key": "HKQuantityTypeIdentifierActiveEnergyBurnedSum", "value": "540.2", "unit": "kcal" }
  ]
}
```

Common combinations that work well:

| Goal | `included=` value |
| :-- | :-- |
| Heart rate range per workout | `HKQuantityTypeIdentifierHeartRateAverage,HKQuantityTypeIdentifierHeartRateMax,HKQuantityTypeIdentifierHeartRateMin` |
| Total energy per workout | `HKQuantityTypeIdentifierActiveEnergyBurnedSum` |
| Pace and top speed for runs | `HKQuantityTypeIdentifierRunningSpeedAverage,HKQuantityTypeIdentifierRunningSpeedMax` |
| Average and peak power for rides | `HKQuantityTypeIdentifierCyclingPowerAverage,HKQuantityTypeIdentifierCyclingPowerMax` |

<ResponseField name="samples[].key" type="string">
  Exact identifier you passed in `included=`, including the aggregation suffix
</ResponseField>

<ResponseField name="samples[].value" type="string">
  Stringified numeric result. Wrap with `Number()` before doing math. A value of `"0"` means the source did not record that signal during the workout, treat as missing rather than literal zero.
</ResponseField>

<ResponseField name="samples[].unit" type="string">
  HealthKit unit string, for example `count/min`, `kcal`, `m`
</ResponseField>

> **Info:** Authorization for every base quantity type referenced in `included=` is requested automatically on the first call, the suffix is stripped before the auth request. Invalid identifiers in `included=` are silently skipped, valid ones in the same call still come through. If the user denies access or the build does not have HealthKit linked, `window.healthkitWorkouts` resolves to `[]`, so always check `Array.isArray()` before reading.

The legacy `readhealthkit://HKWorkoutTypeIdentifier` endpoint continues to work and is the right choice when you need workouts in the same call as other unrelated identifiers, for example pulling steps, heart rate, and workouts together. Its response shape is slightly different: it lands on `healthkitResponse.HKWorkoutTypeIdentifier` rather than `healthkitWorkouts`, returns lowercase `activityType` values, and does not support `included=`. Use `healthkit://workouts` for any workouts-only read or any read that needs per-workout statistics.

---

#### Characteristics

Characteristics such as date of birth, biological sex, and blood type are static values that do not change over time. They return a plain string or raw integer and do not accept a `days` parameter, so they cannot be batched into the same call as quantity, category, or workout reads.

```javascript
if (isDespiaIOS) {
    const data = await despia('readhealthkit://HKCharacteristicTypeIdentifierDateOfBirth', ['healthkitResponse'])
    const dob  = data.healthkitResponse.HKCharacteristicTypeIdentifierDateOfBirth
}
```

```json
"1990-06-15T00:00:00Z"
```

| Identifier | Returns |
| :-- | :-- |
| `HKCharacteristicTypeIdentifierDateOfBirth` | ISO 8601 date string |
| `HKCharacteristicTypeIdentifierBiologicalSex` | Raw integer from `HKBiologicalSex` |
| `HKCharacteristicTypeIdentifierBloodType` | Raw integer from `HKBloodType` |
| `HKCharacteristicTypeIdentifierFitzpatrickSkinType` | Raw integer from `HKFitzpatrickSkinType` |

---

### Write

Use `writehealthkit://` to save a value back to HealthKit. This is useful when your app collects health data that should also live in Apple Health, for example a weight logging form, a manual step entry, or a calorie tracker. Writing adds a new sample to HealthKit without removing or replacing any existing records.

The URL format is `writehealthkit://IdentifierString//Value`. The unit is resolved automatically from the identifier.

```javascript
if (isDespiaIOS) {
    despia('writehealthkit://HKQuantityTypeIdentifierBodyMass//74.5')
    despia('writehealthkit://HKQuantityTypeIdentifierStepCount//10000')
}
```

<ParamField path="identifier" type="string" required>
  Any writable `HKQuantityTypeIdentifier`, passed as the URL host (e.g. `HKQuantityTypeIdentifierBodyMass`)
</ParamField>

<ParamField path="value" type="number" required>
  Numeric value to write. The unit is determined automatically from the identifier, see the [Identifier reference](#identifier-reference).
</ParamField>

> **Info:** Write is one-directional. It adds samples to HealthKit but does not delete or modify existing ones. If you need to correct a previously written value, write a new sample, HealthKit stores the history and surfaces the most recent reading.

---

### Observer

Use observers when you need your server to stay current with a user's health data automatically, without requiring the user to open your app. Observers register a background listener on the device that watches one or more HealthKit types. When new data arrives, from Apple Watch, a connected sensor, or another app, Despia posts a webhook to your server with the latest records.

This is fundamentally different from read. Read is a point-in-time fetch that your code initiates. An observer is a persistent subscription that fires on Despia's side whenever HealthKit tells it something changed. The user does not need to be in the app for a webhook to fire.

Common uses for observers include: tracking whether a user hit a daily step goal, alerting a coach when a client logs a workout, syncing sleep data nightly to a backend, and triggering push notifications based on health events.

#### Registering an observer

```javascript
if (isDespiaIOS) {
    despia('healthkit://observe?types=HKQuantityTypeIdentifierStepCount,HKCategoryTypeIdentifierSleepAnalysis&frequency=hourly&server=https://your-server.com/webhook')
}
```

Observers persist across app restarts automatically. You only need to register them once, typically on app load or after the user enables health tracking in your app.

<ParamField path="types" type="string" required>
  Comma-separated list of HealthKit identifiers to observe
</ParamField>

<ParamField path="frequency" type="string">
  How often Despia delivers batched updates to your server: `immediate`, `hourly`, `daily`, or `weekly`. Defaults to `immediate`. Use `hourly` or `daily` for high-volume types like steps to avoid unnecessary webhook traffic.
</ParamField>

<ParamField path="server" type="string" required>
  Full URL of the endpoint that receives webhook POSTs. Append your own query parameters here to identify the user on your server.
</ParamField>

#### Identifying users in webhooks

Webhooks include a `userId` field containing the Despia device ID. If you need to map webhook events to your own user records, append your user identifier as a query parameter on the `server` URL when registering the observer. Your server receives it as part of the request URL.

```javascript
const myUserId = 'user_abc123'

if (isDespiaIOS) {
    despia(`healthkit://observe?types=HKQuantityTypeIdentifierStepCount&frequency=hourly&server=https://your-server.com/webhook?user=${myUserId}`)
}
```

Your server receives `POST /webhook?user=user_abc123` and can route the event directly to the right user record without any device ID mapping.

#### Webhook payload

Despia sends a `POST` with `Content-Type: application/json` each time an observed type updates. The `data` object contains one key per observed type, each holding an array of the most recent records (last 1 day).

```json
{
  "event": "update",
  "userId": "abc123-device-id",
  "timestamp": "2025-11-18T08:00:00Z",
  "data": {
    "HKQuantityTypeIdentifierStepCount": [
      { "date": "2025-11-18", "value": 4210, "unit": "count" }
    ]
  }
}
```

<ResponseField name="event" type="string">
  Always `update`
</ResponseField>

<ResponseField name="userId" type="string">
  Despia device ID, consistent across all events from the same device. For your own user IDs, use a query parameter on the `server` URL instead.
</ResponseField>

<ResponseField name="timestamp" type="string">
  ISO 8601 timestamp of when the webhook was sent
</ResponseField>

<ResponseField name="data" type="object">
  One key per observed type, each containing an array of records matching the read response shape for that type
</ResponseField>

#### Checking active observers

`despia.observingHealthKit` holds an array of currently active identifier strings. It is `undefined` before any `observe` or `unobserve` call has been made, and `[]` once all observers have been stopped. Use this to check whether an observer is already registered before calling `observe` again.

```javascript
if (despia.observingHealthKit?.length > 0) {
    console.log('Active observers:', despia.observingHealthKit)
    // ["HKQuantityTypeIdentifierStepCount", "HKCategoryTypeIdentifierSleepAnalysis"]
}
```

#### Stopping observers

Pass `all` to stop every active observer, or a comma-separated list of identifiers to stop specific types. The response contains the updated list of remaining active observers.

```javascript
if (isDespiaIOS) {
    // Stop all observers
    const data = await despia('healthkit://unobserve?types=all', ['observingHealthKit'])
    console.log(data.observingHealthKit) // []

    // Stop a specific type
    const data = await despia('healthkit://unobserve?types=HKQuantityTypeIdentifierStepCount', ['observingHealthKit'])
    console.log(data.observingHealthKit) // ["HKCategoryTypeIdentifierSleepAnalysis"]
}
```

---

### Identifier reference

Despia supports all four HealthKit identifier categories. Pass any valid identifier string directly to `readhealthkit://` or `writehealthkit://`, or as part of `included=` on `healthkit://workouts`. Despia resolves the type and unit automatically.

#### Constructing identifiers

Every identifier follows a predictable pattern based on its category:

| Type | Prefix | Example |
| :-- | :-- | :-- |
| Quantity | `HKQuantityTypeIdentifier` | `HKQuantityTypeIdentifierStepCount` |
| Category | `HKCategoryTypeIdentifier` | `HKCategoryTypeIdentifierSleepAnalysis` |
| Workout | `HKWorkoutTypeIdentifier` | `HKWorkoutTypeIdentifier` |
| Characteristic | `HKCharacteristicTypeIdentifier` | `HKCharacteristicTypeIdentifierDateOfBirth` |

Take the type name from Apple's documentation and prepend the matching prefix. For example, Apple lists `StepCount` under `HKQuantityTypeIdentifier`, the full identifier string is `HKQuantityTypeIdentifierStepCount`.

When using a quantity identifier inside `included=` on `healthkit://workouts`, append one of the aggregation suffixes (`Average`, `Max`, `Min`, `Sum`) to control how the value is computed across the workout window. Identifiers used as the URL host on `readhealthkit://` never take a suffix.

#### Apple Documentation


  - [HKQuantityTypeIdentifier](https://developer.apple.com/documentation/healthkit/hkquantitytypeidentifier): Steps, heart rate, distance, body mass, nutrition, and all other numeric types

  - [HKCategoryTypeIdentifier](https://developer.apple.com/documentation/healthkit/hkcategorytypeidentifier): Sleep analysis, mindfulness, and other category-based types

  - [HKWorkoutActivityType](https://developer.apple.com/documentation/healthkit/hkworkoutactivitytype): All workout activity types returned in the `activityType` field

  - [HKCharacteristicTypeIdentifier](https://developer.apple.com/documentation/healthkit/hkcharacteristictypeidentifier): Date of birth, biological sex, blood type, and skin type


---

### Resources


  - [NPM Package](https://www.npmjs.com/package/despia-native): despia-native

  - [Support](mailto:support@despia.com): [support@despia.com](mailto:support@despia.com)


## Google Health

Page: https://setup.despia.com/health-data/google-health

Despia connects your web app to [Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect), Android's on-device store for health and fitness data. You use the **same calls as [Apple Health](https://setup.despia.com/health-data/apple-health)**: `readhealthkit://`, `writehealthkit://`, `healthkit://workouts`, `healthkit://observe` and `healthkit://unobserve`, with the same identifiers and the same result shapes. One code path serves iPhone and Android.

On top of that, `healthconnect://` gives you what Health Connect offers beyond HealthKit: an availability check, the install flow, the Health Connect settings screen, explicit permission requests, time ranges and hourly buckets, deletes of your own records, and change tokens.

> **Info:** Android support is new. The type mapping, the request handling and the result shapes are covered by automated tests. Testing on devices is in progress, so check your flows on a real phone before you release.

---

### Installation

Install `despia-native` as described on the [Apple Health](https://setup.despia.com/health-data/apple-health#installation) page. Nothing else is needed in your web app.

---

### Setup

Health Connect is built into your Android app only when you turn it on, because Google Play reviews every app that asks for health permissions.


**Choose the data types**

List the types your app reads and writes. Use the HealthKit identifiers you already use on iOS (for example `HKQuantityTypeIdentifierStepCount`), Health Connect record names (for example `StepsRecord`), or Health Connect permission names (for example `android.permission.health.READ_STEPS`). Only these types are declared in your app, and Health Connect only asks the user for declared types.

**Publish a privacy policy**

Health Connect shows your privacy policy from its permission screen, and Google Play requires one for health permissions. Without a privacy policy URL, Health Connect is left out of the build.

**Turn it on for your app**

Contact [support@despia.com](mailto:support@despia.com) with your app, the read and write lists, the privacy policy URL, and whether you need background reads (observers while the app is closed) or history older than 30 days. These become the build settings below.

**Rebuild and complete the Play Console declaration**

Rebuild the Android app. In Google Play Console, complete the Health apps declaration for the same data types. Health Connect access is part of the binary, so it cannot be changed over-the-air.



| Build setting | Value |
| :-- | :-- |
| `HEALTH_CONNECT_ENABLED` | `true` |
| `HEALTH_CONNECT_READ` | Comma-separated identifiers, record names or permission names, or `all` |
| `HEALTH_CONNECT_WRITE` | The same, for writes |
| `HEALTH_CONNECT_BACKGROUND` | `true` to let observers read while the app is closed |
| `HEALTH_CONNECT_HISTORY` | `true` to read data older than 30 days before the user granted access |
| `HEALTH_CONNECT_PRIVACY_URL` | Your privacy policy URL |

An identifier that Health Connect has no type for stops the build with its name, so a typo never ships silently.

---

### Detecting the platform

Check for Android the same way you check for iOS, and send the same calls.

```javascript
const ua = navigator.userAgent.toLowerCase()
const isDespia = ua.includes('despia')
const isDespiaIOS = isDespia && (ua.includes('iphone') || ua.includes('ipad'))
const isDespiaAndroid = isDespia && ua.includes('android')

if (isDespiaIOS || isDespiaAndroid) {
    const data = await despia(
        'readhealthkit://HKQuantityTypeIdentifierStepCount,HKQuantityTypeIdentifierHeartRate?days=7',
        ['healthkitResponse']
    )
    const steps = data.healthkitResponse.HKQuantityTypeIdentifierStepCount
}
```

---

### Availability and install

Health Connect runs on Android 9 and later. On Android 14 and later it is part of the system. On Android 13 and lower it is an app from Google Play, which the user may need to install or update.

```javascript
if (isDespiaAndroid) {
    const { healthConnectStatus } = await despia('healthconnect://status', ['healthConnectStatus'])

    if (healthConnectStatus.status === 'update_required' || healthConnectStatus.status === 'unavailable') {
        // Opens Health Connect in Google Play (Android 13 and lower)
        await despia('healthconnect://install', ['healthConnectInstall'])
    }
}
```

<ResponseField name="healthConnectStatus.status" type="string">
  `available`, `update_required` (the Health Connect app must be updated), `unavailable` (not installed, or Android 8), `requires_android_8` (Android 7), or `not_included` (the app was built without Health Connect)
</ResponseField>

<ResponseField name="healthConnectStatus.available" type="boolean">
  `true` when calls can read and write
</ResponseField>

<ResponseField name="healthConnectStatus.builtIn" type="boolean">
  `true` on Android 14 and later, where Health Connect is part of the system
</ResponseField>

<ResponseField name="healthConnectStatus.declared" type="string[]">
  The Health Connect permissions your build declares
</ResponseField>

<ResponseField name="healthConnectStatus.granted" type="string[]">
  The permissions the user has granted (only when available)
</ResponseField>

<ResponseField name="healthConnectStatus.backgroundRead" type="boolean">
  Whether background reads are granted
</ResponseField>

<ResponseField name="healthConnectStatus.historyRead" type="boolean">
  Whether reads older than 30 days are granted
</ResponseField>

On Android 7 (API 24 and 25), which the Health Connect library does not support, every health call answers at once with the same shapes as a build without Health Connect, using the code `health_connect_requires_android_8` (status `requires_android_8`). Android 8 answers `unavailable`.

`healthConnectInstall` is `{ opened, status }`. When Health Connect is not usable, reads answer in `healthkitResponse` with `error` set to `health_connect_unavailable` or `health_connect_update_required` and a `message` (see [Answers and errors](#answers-and-errors)).

---

### Permissions

As on iOS, the first read, write or observe of a type shows the Health Connect permission screen for the types in that call. Each type is asked at most once this way; after the user answers, later calls do not show the screen again. A type the user did not allow reads as an empty array, as on iOS.

To ask again, for example from a settings page in your app, call `healthconnect://permissions`. It asks for every listed type that is not granted.

```javascript
const { healthConnectPermissions } = await despia(
    'healthconnect://permissions?read=HKQuantityTypeIdentifierStepCount,HKCategoryTypeIdentifierSleepAnalysis&write=HKQuantityTypeIdentifierBodyMass&background=true',
    ['healthConnectPermissions']
)
// { granted: [...], denied: [...], notDeclared: [...] }
```

<ParamField query="read" type="string">
  Types to read. Without `read` and `write`, every type your build declares is asked.
</ParamField>

<ParamField query="write" type="string">
  Types to write
</ParamField>

<ParamField query="background" type="boolean">
  Also ask for background reads
</ParamField>

<ParamField query="history" type="boolean">
  Also ask for data older than 30 days
</ParamField>

`notDeclared` lists permissions your build does not declare. Health Connect cannot ask for them; add them to the build settings and rebuild.

| Call | Result |
| :-- | :-- |
| `healthconnect://settings` | Opens the Health Connect screen where the user manages your app's access and data. Result `healthConnectSettings`: `{ opened, status }` |
| `healthconnect://revoke` | Removes every Health Connect permission your app holds. Result `healthConnectPermissions` with empty lists |

The user can open your privacy policy from the Health Connect permission screen, and on Android 14 and later from the system's permission usage screen.

---

### Read

Reads work exactly as on [Apple Health](https://setup.despia.com/health-data/apple-health#read): `readhealthkit://` with identifiers on the host and `days`, `raw=true` and `write` in the query, or `healthkit://read?types=...`. The result lands in `healthkitResponse`, keyed by the identifier you passed.

```javascript
const data = await despia(
    'readhealthkit://HKQuantityTypeIdentifierStepCount,HKCategoryTypeIdentifierSleepAnalysis?days=7',
    ['healthkitResponse']
)
```

| Shape | Returned for |
| :-- | :-- |
| `[{ date, value, unit }]`, one entry per local day, `0` for a day without data | Quantity types. Cumulative types (steps, distance, calories) are summed, other types averaged per day |
| `[{ startDate, endDate, value, unit }]` | Quantity types with `raw=true`, one entry per sample |
| `[{ startDate, endDate, value, label }]` | Sleep and the other category types |
| Workout list, see [Workouts](#workouts) | `HKWorkoutTypeIdentifier` |
| `[{ value: "" }]` | Characteristic types (Health Connect has no profile data) |

Units and scales match iOS for the same identifier: kg for body mass, cm for height, a fraction from 0 to 1 for body fat and oxygen saturation, mg or mcg for nutrients, `count/min` for heart rate. Daily totals of cumulative types come from Health Connect's own aggregation, which removes the overlap when several apps record the same activity.

Sleep sessions return an `inBed` entry (value 0) for the whole session, followed by one entry per stage: `awake` 2, `core` 3 (Health Connect light sleep), `deep` 4, `rem` 5, and `asleep` 1 for sleep without a stage.

#### Answers and errors

Answers have the same shape as on [iOS](https://setup.despia.com/health-data/apple-health#answers-and-errors):

- Every type you asked for is a key whose value is an array: the records read, or `[]` when there is nothing to show.
- A call that failed adds `error` (a code) and `message` (a sentence), always together. An answer that did not fail has neither.
- If the page defines `window.onHealthkitResponse(response)`, it is called once per answer with the same object.
- `healthkit://workouts` merges its list into `healthkitResponse.HKWorkoutTypeIdentifier`, clears an earlier read's `error` and `message` first, and sets its own pair only when the workouts call failed.

| `error` | When |
| --- | --- |
| `health_connect_unavailable` | Health Connect is not installed, or the device is Android 8 |
| `health_connect_update_required` | The Health Connect app must be updated |
| `health_connect_authorization_failed` | The permission request itself failed (not a decline, which reads as `[]`) |
| `health_connect_timeout` | The reads did not finish within 15 seconds of the permission step; the answer keeps what was read |
| `health_connect_read_failed` | An unexpected failure while reading; every requested type is `[]` |
| `health_connect_invalid_request` | The call cannot be read as given, for example `bucket=week` (`bucket` must be `day` or `hour`); the `message` says what is wrong |
| `health_connect_not_included` | The build has no Health Connect (see below) |

#### Time ranges and hourly buckets

`healthconnect://read` takes the same `types`, `days`, `raw` and `write` parameters, plus:

<ParamField query="start" type="string">
  ISO 8601 start, for example `2026-11-20T00:00:00Z` or `2026-11-20T00:00:00+04:00`. Replaces `days`.
</ParamField>

<ParamField query="end" type="string">
  ISO 8601 end. Defaults to now.
</ParamField>

<ParamField query="bucket" type="string">
  `day` (default) or `hour`
</ParamField>

```javascript
const data = await despia(
    'healthconnect://read?types=StepsRecord&start=2026-11-20T06:00:00Z&end=2026-11-20T18:00:00Z&bucket=hour',
    ['healthkitResponse']
)
const hourlySteps = data.healthkitResponse.StepsRecord
```

Types can be HealthKit identifiers or Health Connect record names. When a read includes identifiers that Health Connect has no type for, they return an empty array and `window.healthConnectLimits` names each one with the reason. The same happens for skin temperature and mindfulness sessions when the Health Connect version on the device does not offer them yet (updating Health Connect adds them), and for a type whose read fails, with the error message as the reason.

---

### Workouts

`healthkit://workouts?days=30` returns Health Connect exercise sessions in the iOS workout shape, newest first, in `healthkitWorkouts` and in `healthkitResponse.HKWorkoutTypeIdentifier`. Workouts need only the exercise permission; when the user has not granted calories or distance, that field is `0`, as on iOS.

```json
{
  "date": "2026-11-20T06:30:00Z",
  "activityType": "running",
  "duration": 1800,
  "calories": 312,
  "distance": 5010,
  "value": 5010,
  "unit": "m",
  "endDate": "2026-11-20T07:00:00Z",
  "exerciseType": "RUNNING",
  "title": "Morning run"
}
```

`activityType` uses the iOS names (`running`, `cycling`, `hiit`, `traditionalStrengthTraining`, ...). Health Connect types without an iOS twin use their own name in camelCase. `exerciseType` is the Health Connect type and `title` the session title when the recording app set one. Calories and distance are the ones recorded by the same app during the session. `included=` adds statistics per workout exactly as on iOS, for example `included=HKQuantityTypeIdentifierHeartRateMax`.

---

### Write

`writehealthkit://Identifier//Value` saves a value in the same unit as on iOS and answers in `healthkitWriteResponse` (`{ success, identifier, value, error }`), calling `window.onHealthKitWrite` when you define it.

```javascript
despia('writehealthkit://HKQuantityTypeIdentifierBodyMass//74.5')
```

`healthconnect://write` writes by Health Connect record name, with the value in the same unit a read of that type returns (for example kg for `WeightRecord`, a 0 to 1 fraction for `BodyFatRecord`), and an optional time range:

```javascript
despia('healthconnect://write?type=HydrationRecord&value=0.25&start=2026-11-20T08:00:00Z&end=2026-11-20T08:05:00Z')
despia('healthconnect://write?type=BloodPressureRecord&systolic=120&diastolic=80')
```

Single-moment types are written at `end` (default now). Types that cover a period are written from `start` to `end` (default the last second). Blood pressure is one reading with both values. Category types (sleep, cycle tracking) are not written through these calls, as on iOS.

Delete your own app's records of one type in a range with `healthconnect://delete?type=WeightRecord&days=7` (or `start` and `end`). Result in `healthConnectDelete`. Health Connect never lets an app delete another app's data this way.

---

### Observer

`healthkit://observe` and `healthkit://unobserve` take the same parameters as on iOS, keep observers across restarts, and keep `observingHealthKit` up to date.

```javascript
despia('healthkit://observe?types=HKQuantityTypeIdentifierStepCount&frequency=hourly&server=https://your-server.com/webhook?user=user_abc123')
```

Health Connect does not notify apps when data changes. Despia checks for changes with Health Connect change tokens when your app comes to the foreground and on a schedule set by `frequency`: `hourly`, `daily`, `weekly`, and every 15 minutes for `immediate` (the shortest interval Android allows). Checks while the app is closed need background reads (`HEALTH_CONNECT_BACKGROUND` and the user's grant); without them, checks run when the app is opened. A new observer reports once when it starts.

The webhook body matches iOS, with a `source` field:

```json
{
  "event": "healthkit:update",
  "userId": "device-id",
  "timestamp": "2026-11-20T08:00:00Z",
  "data": {
    "HKQuantityTypeIdentifierStepCount": [
      { "date": "2026-11-20T00:00:00Z", "value": 4210, "unit": "count" }
    ]
  },
  "source": "healthconnect"
}
```

`userId` is the Android device ID. Use a query parameter on `server` to identify your own users.

A change counts as sent only when your server answers with a 2xx status. If the request fails, the same changes are sent again on the next check.

#### Change tokens

To sync changes yourself, take a token and read changes since it later:

```javascript
const first = await despia('healthconnect://changes?types=StepsRecord,WeightRecord', ['healthConnectChanges'])
// { token, upserts: [], deletions: [], hasMore: false, tokenExpired: false }

const later = await despia(`healthconnect://changes?token=${encodeURIComponent(first.healthConnectChanges.token)}`, ['healthConnectChanges'])
// { token, upserts: [{ recordType, id, startDate, endDate, source }], deletions: [ids], tokenExpired }
```

Tokens expire after 30 days. When `tokenExpired` is `true`, read the data again and take a new token.

---

### Type reference

Every HealthKit identifier below works on Android through Health Connect.

| Area | HealthKit identifiers | Health Connect type |
| :-- | :-- | :-- |
| Activity | `StepCount`, `DistanceWalkingRunning`, `FlightsClimbed`, `ActiveEnergyBurned`, `BasalEnergyBurned`, `AppleExerciseTime`, `PushCount` | Steps, Distance, FloorsClimbed, ActiveCaloriesBurned, BasalMetabolicRate, ExerciseSession, WheelchairPushes |
| Activity distance and speed | `DistanceCycling`, `DistanceSwimming`, `DistanceWheelchair`, `DistanceDownhillSnowSports`, `DistanceRowing`, `DistancePaddleSports`, `DistanceSkatingSports`, `RunningSpeed`, `WalkingSpeed`, `CyclingSpeed`, `RowingSpeed`, `PaddleSportsSpeed`, `RunningPower`, `CyclingPower`, `CyclingCadence` | Distance, Speed and Power within exercise sessions of that activity; CyclingPedalingCadence |
| Vitals | `HeartRate`, `RestingHeartRate`, `BloodPressureSystolic`, `BloodPressureDiastolic`, `BloodGlucose`, `OxygenSaturation`, `RespiratoryRate`, `BodyTemperature`, `BasalBodyTemperature`, `VO2Max` | HeartRate, RestingHeartRate, BloodPressure, BloodGlucose, OxygenSaturation, RespiratoryRate, BodyTemperature, BasalBodyTemperature, Vo2Max |
| Body | `BodyMass`, `Height`, `BodyFatPercentage`, `LeanBodyMass` | Weight, Height, BodyFat, LeanBodyMass |
| Nutrition | `DietaryEnergyConsumed`, `DietaryWater` and every `Dietary...` nutrient | Nutrition (one field per nutrient), Hydration |
| Categories | `SleepAnalysis`, `MindfulSession`, `MenstrualFlow`, `IntermenstrualBleeding`, `OvulationTestResult`, `CervicalMucusQuality`, `SexualActivity` | SleepSession, MindfulnessSession, MenstruationFlow, IntermenstrualBleeding, OvulationTest, CervicalMucus, SexualActivity |
| Workouts | `HKWorkoutTypeIdentifier` | ExerciseSession |

Quantity identifiers take the `HKQuantityTypeIdentifier` prefix, categories `HKCategoryTypeIdentifier`.

Health Connect types beyond HealthKit, by record name: `ElevationGainedRecord`, `TotalCaloriesBurnedRecord`, `BasalMetabolicRateRecord` (kcal/day), `HeartRateVariabilityRmssdRecord`, `BodyWaterMassRecord`, `BoneMassRecord`, `SkinTemperatureRecord`, `SpeedRecord`, `PowerRecord`, `StepsCadenceRecord`, `MenstruationPeriodRecord`, and `NutritionRecord.<field>` for one nutrient, including `transFat`, `unsaturatedFat`, `folicAcid` and `energyFromFat`. Blood pressure reads use `BloodPressureRecord.systolic` and `BloodPressureRecord.diastolic`.

#### HealthKit types without a Health Connect equivalent

These return an empty array on Android (characteristics return `[{ value: "" }]`), and `healthConnectLimits` names them:

- Heart rate variability SDNN: Health Connect stores RMSSD (`HeartRateVariabilityRmssdRecord`).
- Characteristics: date of birth, biological sex, blood type, skin type, wheelchair use, move mode.
- Apple scores and metrics: stand time and stand hours, move time, walking steadiness, walking heart rate average, sleeping wrist temperature, sleeping breathing disturbances, AFib burden, workout effort, Nike Fuel.
- Mobility and environment: walking step length, double support, asymmetry, stair speed, six-minute walk, running stride and form, audio exposure, UV exposure, time in daylight, underwater depth, water temperature.
- Lung function, inhaler use, insulin delivery, alcohol, falls, electrodermal activity, perfusion index, body mass index.
- Cross-country skiing distance and speed: Health Connect has one skiing type.
- Symptom categories and the health event categories (heart rate notifications, pregnancy and cycle events, handwashing, toothbrushing).

These cannot be written on Android: the activity-specific distance, speed and power types (Health Connect records one value per time range and takes the activity from the exercise session), `AppleExerciseTime`, `BasalEnergyBurned` (write `BasalMetabolicRateRecord` instead), `SkinTemperatureRecord`, and the single blood pressure values (write `BloodPressureRecord` with both).

---

### Builds without Health Connect

When an Android build does not include Health Connect, every health call still answers right away, so your code never waits: reads give `{ "error": "health_connect_not_included" }` and call `onHealthkitResponse`, writes give `success: false` and call `onHealthKitWrite`, workouts `[]`, observers `[]`, and the status, permissions, install, settings, delete and changes calls answer their own globals with the not-included status. The `despia-feature-not-included` event fires on each call, with `detail.scheme` set to the scheme you called.

---

### Resources

- [Health Connect developer guide](https://developer.android.com/health-and-fitness/guides/health-connect)
- [Health Connect data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)
- [Google Play health apps policy](https://support.google.com/googleplay/android-developer/answer/12991134)
- [Apple Health](https://setup.despia.com/health-data/apple-health), the same calls on iOS
