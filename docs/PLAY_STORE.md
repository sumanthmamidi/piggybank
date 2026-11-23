# Google Play: internal testing

How to get PiggyBank onto testers' phones through Google Play, so it installs without Play Protect blocking it.
Everything below is ready except the steps marked **you**, which need your Google account.

## What's ready

| Item | Where |
|---|---|
| App bundle to upload (`.aab`) | GitHub → Actions → latest "Build APK" run → Artifacts, or `./gradlew :androidApp:bundleRelease` → `androidApp/build/outputs/bundle/release/` |
| Target API 36 (required for Play since 31 Aug 2026) | `gradle/libs.versions.toml` |
| Privacy policy page | [docs/privacy-policy.html](privacy-policy.html) (needs a public URL, see step 2) |
| App icon 512×512 | [branding/logo-512.png](../branding/logo-512.png) |
| Feature graphic 1024×500 | [branding/play-feature-graphic.png](../branding/play-feature-graphic.png) |
| Listing text, form answers | below |

Still missing: **phone screenshots** (at least 2). Take them from your phone, ideally from a fresh install or with
amounts that aren't your real ones, since they become public on the listing.

## 1. Developer account (you)

1. Go to play.google.com/console and sign up with your **personal** Google account.
2. Choose a **Personal** account. Pay the one-time US$25 fee and complete identity verification (government ID).
   This can take a few days.

## 2. Publish the privacy policy (you)

Play needs a public URL. Replace `CONTACT_EMAIL` in `docs/privacy-policy.html` with the address you want shown publicly
(the same one as your Play developer contact), then host it anywhere public, for example a public GitHub Gist or
Google Sites page. GitHub Pages won't work from this repo while it's private on a free plan.

## 3. Create the app (you)

Play Console → **Create app**:
- App name: `PiggyBank`
- Default language: English (United States) or English (India)
- App or game: **App**; Free or paid: **Free**
- Accept the declarations.

## 4. App signing: keep your own key

When you first upload the bundle, Play asks how to sign the app. Pick **Use a different key / export and upload a key
from Java keystore** and upload the key in `~/.piggybank-release/piggybank-release.jks` with Google's PEPK tool
(the Console shows the exact command). This makes Play-installed copies and your shared APKs the same app, so one can
update the other.

If you take Google's default (Google creates a new signing key), that still works, but:
- APKs from GitHub can't install over a Play install or the other way round, and
- Google Drive backup needs a second Android OAuth client with the SHA-1 from Console → Setup → App signing.

## 5. Google Drive for testers

Add an **Android OAuth client** in Google Cloud (project piggybank-515604 → Credentials → Create credentials →
OAuth client ID → Android): package `app.piggybank`, SHA-1 `01:A2:91:BD:23:73:F1:07:A8:0B:E4:FE:72:08:6A:E9:19:02:E4:EE`
(the release key). While the consent screen is in **Testing**, add each tester's Gmail under OAuth consent screen →
Test users (up to 100), or they'll see "Access blocked" when connecting Drive.

## 6. App content (Policy → App content)

**Privacy policy:** the URL from step 2.

**Ads:** No, my app does not contain ads.

**App access:** All functionality is available without special access. (No login.)

**Content rating:** fill the questionnaire. Category: *Utility, productivity, communication or other*. Answer **No** to
violence, sexual content, language, drugs, gambling, user interaction/sharing, and location sharing with other users.
Expected result: Everyone / 3+.

**Target audience:** 18 and over. (Financial data; avoids the Families policy.)

**News app:** No.

**Government app:** No.

**Financial features:** pick the option for budgeting / personal finance management if listed; otherwise
"My app doesn't provide any financial features" (it doesn't move money, lend, or trade).

**Health:** none.

### Data safety

- Does your app collect or share any of the required user data types? **Yes**
- Is all user data encrypted in transit? **Yes** (only the Drive upload goes over the network, via HTTPS)
- Do you provide a way for users to request that their data be deleted? **Yes** (uninstall deletes it; Drive backup
  is deleted from Drive's Manage apps; explained in the privacy policy)

Data types — declare these as **collected**, **not shared**, **optional**, purpose **App functionality** (and
**Backup** wording in the notes if asked):

| Data type | Why it's declared |
|---|---|
| Financial info → Purchase history | Payments parsed from SMS, included in the optional Drive backup |
| Financial info → Other financial info | Account/card last digits, budgets, in the backup |
| Messages → SMS or MMS | Payment SMS text is kept with each payment and is in the backup |
| Location → Approximate / Precise location | Only if "Save where I paid" is on; in the backup |

Data processed only on the phone doesn't count as collected; these are declared because the optional Drive backup
uploads them to the user's own Drive. Play's form allows marking them **optional** ("users can choose").

### Sensitive permissions

**SMS (READ_SMS, RECEIVE_SMS)** → Permissions declaration form:
- Core functionality: **SMS-based money management**.
- Description to paste:

  > PiggyBank is a personal spending tracker. Its core feature is reading bank, card and UPI transaction SMS to build
  > the user's list of payments, which they review, categorize and budget. READ_SMS imports past transaction messages
  > when the user first sets up the app; RECEIVE_SMS records new transaction messages as they arrive, so the daily
  > review is complete without the user opening the app. All processing happens on the device. SMS content is never
  > sent to the developer or any third party; the only network use is an optional, user-enabled backup to the user's
  > own Google Drive app folder. Without SMS access the app has no way to find transactions and can't work.

- Video: a short screen recording (30–60 s): open the app → grant SMS permission → payments appear → review and tag
  one → Monthly view. Upload it to YouTube as **Unlisted** and paste the link. Use a test phone or blur amounts.

**Background location (ACCESS_BACKGROUND_LOCATION)** → Play reviews this separately and approves it only when it's
core to the app, which "Save where I paid" isn't. Expect a rejection if the bundle asks for it. Decide before uploading:
either drop background location from the app (Save where I paid then works only while PiggyBank is open), or declare it
and risk the review holding up the release.

## 7. Store listing (Grow → Store presence → Main store listing)

**App name** (30): `PiggyBank`

**Short description** (80):
`Track spending from your bank SMS. Private: stays on your phone, no accounts.`

**Full description:**

```
PiggyBank turns the payment SMS your bank already sends you into a clear picture of where your money goes.

• Reads bank, card and UPI transaction SMS — no bank login, no linking accounts
• A quick daily review: tag each payment as a need, a want or an investment
• Learns your payees, so most payments tag themselves after a few days
• Monthly view with budgets per category, and drill down to every payment
• Spots self-transfers, card bill payments and duplicate SMS so totals stay right
• Foreign currency spends converted to rupees
• Optional: remember where you paid, shown on a map
• Nightly backup to your phone, and optionally to your own Google Drive

Private by design
Everything stays on your phone. There's no sign-up, no ads, no analytics and no servers.
The only thing that ever leaves your phone is the optional backup to your own Google Drive,
in a hidden folder only PiggyBank can open.
```

**Graphics:** icon `branding/logo-512.png`, feature graphic `branding/play-feature-graphic.png`, 2–8 phone screenshots.

**Category:** Finance. **Contact email:** your public contact address.

## 8. Internal testing release

1. Testing → **Internal testing** → Testers tab → create an email list with your testers' Gmail addresses (up to 100).
2. Releases → **Create new release** → upload `PiggyBank-0.4.N.aab` → release name e.g. `0.4.N` → notes → **Save** →
   **Review release** → **Start rollout to Internal testing**.
3. Copy the **opt-in link** from the Testers tab and send it to testers. They open it, accept, and install from Play.

The first release waits for Google's review (SMS declaration included); that can take from a day to a week or more.

Each later build: upload the new `.aab` from GitHub Actions as a new internal release. The version number goes up with
every commit, so Play accepts it.

## Later: public release

New personal developer accounts must run a **closed test with at least 12 testers for 14 days in a row** before they
can apply for production access.
