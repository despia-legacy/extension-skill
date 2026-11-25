# despia-app-privacy

Generated from the docs pages below; do not edit by hand. Change the page and regenerate.


## App Privacy

Page: https://setup.despia.com/native-features/app-privacy

<iframe src="https://www.youtube.com/embed/F3t0gIbY26k" title="YouTube video player" frameborder="0" className="w-full aspect-video rounded-xl" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen />

> **Note:** The video uses a specific AI coding tool to demonstrate the setup, but the configuration works 1:1 with Cursor, Claude Code, or any other tool. Despia is web framework and tooling agnostic, so the only thing that matters is the SDK call.

`despia.trackingDisabled` is a synchronous boolean reflecting the user's response to the App Tracking Transparency (ATT) prompt on iOS and the equivalent consent flow on Android. Read it on app load, gate analytics and ad SDKs accordingly, and optionally sync it to your backend for compliance auditing.

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

### How it works

Read `despia.trackingDisabled` directly. The value is injected by the native runtime before your JavaScript executes, so it is available on first render with no loading state. The value is `true` when the user has denied tracking consent, `false` when they have allowed it.

```javascript
import despia from 'despia-native'

const isDespia = navigator.userAgent.toLowerCase().includes('despia')
const trackingDisabled = isDespia ? despia.trackingDisabled : true
```

The `true` fallback in the browser is the conservative choice. If you cannot determine consent, assume the user has denied tracking and behave accordingly. This keeps your analytics and ad SDKs from firing during web preview where no consent prompt has happened.

---

### Gate analytics and ad SDKs

Wrap any tracking-dependent initialization behind the consent check. Most analytics SDKs expose either an `enable`/`disable` toggle or a "do not track" mode, use those rather than skipping initialization entirely so you can flip the switch later if the user changes their mind in iOS Settings.

```javascript
import despia from 'despia-native'

const isDespia = navigator.userAgent.toLowerCase().includes('despia')
const trackingDisabled = isDespia ? despia.trackingDisabled : true

if (!trackingDisabled) {
    // user consented, run full analytics
    initAnalytics({ idfa: true, behavioral: true })
} else {
    // user denied, run privacy-preserving analytics only
    initAnalytics({ idfa: false, behavioral: false, anonymous: true })
}
```

Do not gate out _all_ analytics behind this check. Aggregated, non-identifying telemetry (crash reports, performance metrics, anonymized error logs) is generally allowed under both ATT and GDPR. The check is for _user-level_ tracking, not for everything.

---

### Sync consent status to your backend

For compliance audits and to keep server-side behavior aligned with the user's choice, post the consent status when the user logs in or on every app start. Store it keyed by user ID, with a timestamp so you have an audit trail of when the user's consent was last verified.

```javascript
import despia from 'despia-native'

const isDespia = navigator.userAgent.toLowerCase().includes('despia')

async function syncPrivacy(user) {
    if (!isDespia) return

    await fetch('/api/sync-privacy', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            userId: user.id,
            trackingDisabled: despia.trackingDisabled,
            timestamp: new Date().toISOString(),
        }),
    })
}
```

```javascript
// /api/sync-privacy
export default async function handler(req, res) {
    const { userId, trackingDisabled, timestamp } = req.body

    if (!userId) return res.status(400).json({ error: 'Missing userId' })

    try {
        await db.collection('user_privacy').doc(userId).set({
            userId,
            trackingDisabled,
            lastChecked: timestamp,
            createdAt: timestamp,
        }, { merge: true })

        res.status(200).json({ success: true })
    } catch (error) {
        res.status(500).json({ error: 'Failed to sync privacy status' })
    }
}
```

`merge: true` (or your database equivalent) preserves the original `createdAt` while updating `trackingDisabled` and `lastChecked` on every sync, so you can see when consent was first recorded and when it was last verified.

---

### Disabling the ATT prompt entirely

If your app does not need tracking and you want to skip the ATT prompt altogether, declare that intent in the Despia Editor under **App \> Settings \> Disable Tracking Declaration** and toggle it on. After enabling, rebuild the app and resubmit to the App Store. The new build will not display the ATT prompt at launch, and `despia.trackingDisabled` will always return `true` for those users.

This is the right choice for apps that do not run ad SDKs and do not pass user identifiers across third-party services. Disabling the declaration improves the first-launch experience by removing one extra system prompt, and it reduces the surface area for App Store review questions about tracking practices.

> **Warning:** Toggling the Disable Tracking Declaration setting requires a fresh build and a new App Store submission. The change cannot be applied over-the-air, and existing installs will continue using the previous behavior until they update. Plan the toggle around your normal release cadence.

---

### Resources


  - [NPM Package](https://www.npmjs.com/package/despia-native): despia-native

  - [Support](mailto:support@despia.com): [support@despia.com](mailto:support@despia.com)


## Privacy Policy

Page: https://setup.despia.com/store-rejections/common-rejection/privacy-policy

### What are privacy policy rejections?

**Every app that collects data needs a privacy policy**

Apple and Google require a privacy policy for any app that collects, transmits, or shares user data. This includes most apps, even simple ones.

Common rejection messages:

- "Your app does not include a privacy policy"
- "The privacy policy URL is not valid or accessible"
- "The privacy policy does not adequately describe data collection practices"
- "Your app collects user data but the privacy policy does not disclose this"

No privacy policy means no approval.

---

### Why this happens

**Privacy policies are legally required in most regions**

GDPR, CCPA, and other regulations require disclosure of data practices. App stores enforce this for all apps distributed globally.

Common mistakes:

- No privacy policy at all
- Privacy policy URL returns 404
- Privacy policy hosted on localhost or staging
- Generic template that doesn't match your app
- Missing disclosures for specific data types (location, contacts, health)
- Privacy policy doesn't mention third-party SDKs
- Different privacy policy in app vs App Store listing

---

### How to fix it

#### Create a privacy policy

**Every app needs one, even if you think you don't collect data**

If your app uses analytics, crash reporting, push notifications, or any third-party SDK, you're collecting data. You need a privacy policy.

**What must be included:**

- What data you collect
- How you collect it
- Why you collect it
- How you use it
- Who you share it with
- How users can delete their data
- How to contact you

**Data types to disclose:**

| Data Type      | Examples                                      |
| :------------- | :-------------------------------------------- |
| Personal info  | Name, email, phone number                     |
| Device info    | Device ID, IP address, OS version             |
| Location       | GPS, IP-based location                        |
| Usage data     | Pages viewed, features used, session duration |
| Contacts       | Address book access                           |
| Health data    | HealthKit, fitness data                       |
| Financial data | Payment info, purchase history                |
| User content   | Photos, files, messages                       |

If your app accesses any of these, your privacy policy must mention it.

---

#### Disclose third-party SDKs

**You're responsible for data collected by SDKs you include**

Most apps include SDKs that collect data. Your privacy policy must disclose these.

**Common SDKs that collect data:**

- **Analytics:** Google Analytics, Mixpanel, Amplitude, Firebase Analytics
- **Crash reporting:** Crashlytics, Sentry, Bugsnag
- **Push notifications:** OneSignal, Firebase Cloud Messaging
- **Payments:** RevenueCat, Stripe
- **Advertising:** AdMob, Facebook Ads, AppLovin
- **Authentication:** Google Sign-In, Facebook Login, Auth0

**For each SDK, disclose:**

- What data it collects
- Link to the SDK's privacy policy
- Why you use it

**Example disclosure:**

> "We use OneSignal for push notifications. OneSignal collects device identifiers and push tokens to deliver notifications. See OneSignal's privacy policy at https://onesignal.com/privacy_policy"

---

#### Host your privacy policy properly

**The URL must be accessible and stable**

Reviewers will check that your privacy policy URL works.

**Requirements:**

- Publicly accessible (no login required)
- Loads over HTTPS
- Returns a real page (not 404 or redirect loop)
- Available 24/7 (not localhost or staging server)
- Same URL used in app and App Store listing

**Good hosting options:**

- Your company website (`https://yourapp.com/privacy`)
- Notion public page
- GitHub Pages
- Google Docs (published to web)
- Dedicated legal page services

**What causes rejection:**

- URL returns 404 or error
- URL requires login to view
- URL redirects to homepage
- URL is localhost or internal IP
- Different URLs in app vs store listing

---

#### Add privacy policy to your app

**Users must be able to access it from within the app**

The privacy policy can't only be on the App Store. Users need to find it in your app too.

**Where to place it:**

- Settings screen (most common)
- Account or profile screen
- Onboarding flow
- Login/signup screen
- App footer or menu

**Implementation:**

```javascript
// Link to privacy policy
const openPrivacyPolicy = () => {
  window.open('https://yourapp.com/privacy', '_blank');
};
```

Make sure the link opens in a browser, not in the WebView where it might fail.

---

#### Match App Store privacy labels

**Your privacy policy must match your App Store declarations**

Apple requires privacy nutrition labels. Google requires a data safety section. These must match your privacy policy.

**If your App Store listing says you collect:**

- Location data → Privacy policy must mention location collection
- Contact info → Privacy policy must mention contact collection
- Usage data → Privacy policy must mention analytics

**Mismatches cause rejection:**

- App Store says "no data collected" but you use analytics
- Privacy policy mentions email collection but App Store doesn't list it
- App accesses contacts but neither discloses it

Review your App Store privacy labels and privacy policy together. They must tell the same story.

See: [App Privacy Details](https://setup.despia.com/lovable/native-features/app-privacy)

---

#### The privacy manifest is generated for you (iOS)

**Apple refuses an upload that uses a required-reason API without a privacy manifest (ITMS-91053)**

Since 1 May 2024, App Store Connect reads required-reason API use and collected data types from a privacy manifest (`PrivacyInfo.xcprivacy`) inside the app, never from `Info.plist`. A privacy block in `Info.plist` has no effect. Despia writes the manifest for your iOS build, from the features that are compiled into it, so an app declares only what it uses.

| Declared | When |
| --- | --- |
| Required-reason APIs: UserDefaults (`CA92.1`, `1C8F.1`), file timestamps (`C617.1`), system boot time (`35F9.1`) | Every build |
| Collected data: Product interaction | Every build (audio player webhooks you point at your own endpoint) |
| Collected data: Precise location | Unless location is left out of the build |
| Collected data: Health, Device ID | When Apple Health is in the build and reads are not disabled |
| Collected data: Fitness | Unless motion is left out of the build |
| Collected data: User ID | When Stream calling is in the build |

All collected types are linked to the user, not used for tracking, and have the purpose App Functionality. `NSPrivacyTracking` is `false` for Despia's own code; SDKs such as AppsFlyer and Audience Network ship and declare their own manifests. The image widget and Share Extension targets carry their own manifests for the app group they use.

You still fill in the App Privacy answers in App Store Connect and keep your privacy policy in step with them (see above): the manifest tells Apple what the binary does, the labels and policy tell users what you collect. Android has no manifest file; Google Play uses the Data safety form in the Play Console. Source: Apple, "Describing use of required reason API" and "Describing data use in privacy manifests" (developer.apple.com/documentation/bundleresources), read 25 Nov 2026.

An extension that ships its own `PrivacyInfo.xcprivacy` as an app resource would collide with the generated file; none does today.

---

#### Include account deletion instructions

**Required by Apple since 2022**

If your app supports account creation, users must be able to delete their account and data.

**Requirements:**

- Clear instructions for account deletion in privacy policy
- Working deletion mechanism in app or via request
- Data actually gets deleted (not just deactivated)
- Deletion available without contacting support (preferred)

**What to include in privacy policy:**

> "You can delete your account and all associated data at any time from Settings \> Account \> Delete Account. Alternatively, email [privacy@yourapp.com](mailto:privacy@yourapp.com) to request deletion. We will process deletion requests within 30 days."

If you don't offer account creation, you don't need this. But if you do, account deletion is mandatory.

---

#### Use a proper template

**Don't write from scratch unless you're a lawyer**

Privacy policy generators and templates help you cover required elements.

**Free generators:**

- Termly (https://termly.io)
- FreePrivacyPolicy (https://freeprivacypolicy.com)
- PrivacyPolicies.com (https://privacypolicies.com)

**What to customize:**

- Your company/app name
- Contact information
- Specific data types you collect
- Third-party SDKs you use
- Account deletion process
- Data retention periods

Don't use a template without customizing it. Reviewers can tell when the privacy policy doesn't match the app.

---

#### Update when you add features

**Privacy policy must reflect current app behavior**

When you add new features that collect data, update your privacy policy.

**Triggers for update:**

- Adding analytics SDK
- Adding push notifications
- Adding location features
- Adding contacts access
- Adding health/fitness features
- Adding camera/photo access
- Adding payment processing
- Adding social login

Update the privacy policy before submitting the app update. Reviewers check that disclosures match current functionality.

---

### Quick checklist

**Privacy policy content:**

1. Lists all data types collected
2. Explains how data is used
3. Discloses third-party SDKs
4. Includes contact information
5. Explains account deletion process
6. States data retention period

**Accessibility:**

 7. Hosted at public HTTPS URL
 8. URL works (not 404)
 9. No login required to view
10. Link included in app (Settings screen)
11. Same URL in app and App Store listing

**Consistency:**

12. Matches App Store privacy labels
13. Matches Google Play data safety section
14. Reflects current app functionality
15. Updated when features change

---

### Common rejection reasons

| Rejection                | Fix                                                       |
| :----------------------- | :-------------------------------------------------------- |
| "No privacy policy"      | Create one and add URL to app and store listing           |
| "URL not accessible"     | Fix hosting, ensure HTTPS, test from outside your network |
| "Doesn't match app"      | Update policy to reflect actual data collection           |
| "Missing SDK disclosure" | Add section for each third-party SDK                      |
| "No account deletion"    | Add deletion instructions and mechanism                   |
| "Doesn't match labels"   | Align App Store privacy labels with policy                |

---

### Still stuck?

If you keep getting rejected for privacy policy issues:

1. Open your privacy policy URL in an incognito browser window
2. Compare your privacy policy to your App Store privacy labels line by line
3. List every SDK in your app and verify each is mentioned
4. Contact support: [support@despia.com](mailto:support@despia.com) with:
   - Your privacy policy URL
   - Your rejection notice in full
   - List of SDKs your app uses
   - Screenshot of your App Store privacy labels
