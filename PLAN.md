# PiggyBank — Android app plan

An on-device spend tracker that reads bank/card/UPI SMS, tags each transaction
(Spends › Needs/Wants › category, Investments, Salary, or Hidden), and gives a
daily close-out review plus a monthly salary → spends → savings view.

Design mockups: https://claude.ai/artifact/EAqi7kjmDdoCq4Zn2ZxFR5 (Today, End-of-day
review, Monthly review, Tagging rules).

---

## 1. Non-negotiables

| Requirement | How the app guarantees it |
|---|---|
| No data leaves the device | Nothing leaves the device unless the user turns on Google Drive backup. The `INTERNET` permission exists **only** for that. The single network client is the Drive module, limited to the hidden `drive.appdata` folder. No analytics, crash reporting or ad SDKs; a CI check fails the build if any other library opens network connections. |
| Android auto-backup must not copy data to Google | `android:allowBackup="false"` and `dataExtractionRules` excluding everything. We run our own backup instead (§6). |
| Drive backup is possible | It works the way WhatsApp does: sign in with Google, then the backup goes to the app's hidden Drive folder, and a reinstall finds it automatically (§6). |
| Structured data | A single SQLite database with a versioned schema and explicit migrations (§4). Money is stored as integers in the currency’s smallest unit (paise, cents), never floats. The database is **not encrypted**; it lives in app-private storage on the user's own device. |
| Future iOS | Business logic and UI are shared with Kotlin Multiplatform (§2). Only SMS ingestion, background jobs and file access are platform-specific. |

## 2. Language and stack

**Kotlin Multiplatform (KMP) + Compose Multiplatform.**

Why this over Flutter or React Native:
- Reading SMS, `BroadcastReceiver`, `WorkManager` and the Storage Access Framework are
  native Android APIs. In Kotlin we call them directly, without plugin bridges.
- The parser, rules engine, budget maths, database and backup format live in
  `shared/` and compile for iOS unchanged.
- Compose Multiplatform lets the same screens run on iOS, so the UI is written once.

| Concern | Library |
|---|---|
| UI | Compose Multiplatform, Material 3 (custom theme from the mockups) |
| Database | **SQLDelight** (SQLite; type-safe queries; KMP; SQL-first schema with migration files) |
| Encryption | None for the DB. Drive backups optionally encrypted with AES-GCM and a passphrase (default on, because that copy leaves the phone) |
| Google Drive | Credential Manager / Google Sign-In for the account, Drive REST API v3 with the `drive.appdata` scope (Ktor client in `shared/`, so iOS can reuse it) |
| Background work | Android `WorkManager`; iOS `BGTaskScheduler` later |
| DI | Koin (KMP) |
| Dates/money | `kotlinx-datetime`; a `Money(paise: Long)` value class |
| Charts | Hand-drawn on Compose `Canvas` (burn-up, daily bars, splits). No chart library needed. |
| Tests | `kotlin.test` in `shared/` for parser/rules/budget; Compose UI tests on Android |

### iOS limitation to plan for now
iOS does not let any app read SMS. On iOS the ingestion layer will be different:
a Shortcuts automation ("When I get a message from HDFCBK…" → pass the text to the
app through an App Intent), plus share-sheet and paste. Everything after
ingestion is shared. That is why `SmsSource` is an interface in `shared/`.

## 3. Module layout

```
piggybank/
  shared/
    core/        domain models, Money, categories, rules engine, budget & savings maths
    parser/      SMS → ParsedTxn: bank templates, generic fallback, confidence score
    data/        SQLDelight schema + migrations, repositories
    backup/      backup format, writer, reader, restore-merge
    ui/          Compose Multiplatform screens: Today, Review, Monthly, Rules, Settings
  androidApp/    SMS receiver + inbox scanner, WorkManager jobs, SAF folder access,
                 notifications, permissions/onboarding
  iosApp/        (later) Shortcuts/App Intent ingestion, BGTask, Files access
```

## 4. Data model (SQLite)

```
account         id, kind(BANK|CARD|WALLET), institution, last4, nickname, is_own
sms_raw         id, android_sms_id, sender, body, received_at, body_hash UNIQUE,
                template_id→sms_template,
                status(PARSED|CANDIDATE|IGNORED|USER_PICKED|NOT_MONEY)
sms_template    id, sender_key (e.g. "HDFCBK", the prefix is stripped), signature UNIQUE
                (body with amounts/dates/refs/names/last4 masked to placeholders),
                verdict(WHITELIST|BLACKLIST|PENDING), field_map (where amount, account,
                merchant/VPA and direction sit), example_sms_id, seen_count,
                decided_by(USER|AUTO), decided_at
txn             id, sms_id→sms_raw, occurred_at, date_source(SMS|ARRIVAL),
                orig_currency (ISO 4217), orig_amount_minor,       -- as written in the SMS
                home_amount_minor, fx_rate, fx_source(SMS|USER_RATE|SAME),
                refund_minor (user-marked refunds, home currency),
                account_id→account, counterparty_raw, vpa, merchant_key,
                kind(SPEND|INVESTMENT|SELF_TRANSFER|CC_BILL|WALLET_TOPUP),   -- debits only
                category_id→category, need_want(NEED|WANT|NULL),
                tag_source(RULE|USER|GUESS), rule_id→rule, reviewed_at, note,
                duplicate_of→txn (NULL = not a duplicate), duplicate_by(USER|AUTO)
dup_dismissed   txn_a, txn_b          (pairs the user said are separate payments)
fx_rate         currency, rate_to_home, set_at          (entered by the user, never fetched)
income          id, name ("Salary", "Rent received", "Bonus"), amount_minor (home),
                schedule(MONTHLY|ONE_OFF), from_month, to_month (NULL = open-ended),
                months_mask (12 bits, for picking individual months), active
category        id, name, top_level(SPENDS|INVESTMENTS|EXCLUDED),
                default_need_want, icon, sort, archived
rule            id, field(VPA|MERCHANT|SENDER|ACCOUNT_PAIR|BODY),
                match_type(EXACT|PREFIX|CONTAINS|REGEX), pattern,
                result_kind, result_category_id, result_need_want,
                always_ask BOOL, priority, hits, last_hit_at, created_from_txn_id
budget          month(YYYY-MM), category_id, amount_paise
settings        home_currency (chosen at first launch), review_time, …
day_review      date PK, closed_at, txn_count, streak
backup_log      id, created_at, file_name, location_uri, size, sha256, schema_version, ok
meta            key, value        (schema_version, install_id, last_sms_scan_at, …)
```

Rules:
- Only `kind = SPEND` counts toward spends. `SELF_TRANSFER`, `CC_BILL` and
  `WALLET_TOPUP` are **excluded everywhere** but stay visible in a "Not counted" list.
- **Savings = Salary − (Needs + Wants)** for the month. Investments are shown as the part
  of savings that was invested; they don't reduce savings.
- **No money in is read from SMS.** Credit SMS are never stored as transactions (they
  may be used only to spot a self-transfer). Income comes solely from the `income`
  table the user fills in: salary or any recurring income with a schedule and the months
  it applies to, plus one-off entries. Months are calendar months (1st to last day).
- Money is stored in **minor units** per ISO 4217 (paise, cents; JPY has 0 decimals,
  KWD has 3). Every total, budget and income figure is in the home currency. Foreign
  amounts keep their original value plus the home value and the rate used.
- A transaction with `duplicate_of` set is excluded from every total but kept for undo.

## 5. SMS pipeline

1. **Ingest**
   - Live: a `BroadcastReceiver` for `SMS_RECEIVED` stores the raw SMS within milliseconds.
   - Catch-up: on app open and in the nightly job, query the SMS inbox through the
     `ContentResolver` for anything newer than `last_sms_scan_at`. This covers missed
     broadcasts and reboots.
   - First run: import past inbox history (the user chooses 3/6/12 months).
   - Dedupe by `body_hash` + `android_sms_id`.
2. **Filter**: from **any sender**, keep every SMS containing a money amount in any
   currency (`Rs|INR|₹|USD|US$|$|EUR|€|AED|…` + number). There's no fixed list of
   banks, so every bank, card and wallet is covered from day one. Credits are dropped
   after transfer matching (§4).
3. **Template lookup**: turn the SMS into a *signature* by masking the variable parts
   (amount → `{AMT}`, last4 → `{ACC}`, dates, refs, names/VPAs) and look it up in
   `sms_template`:
   - **WHITELIST**: a known payment format. Use its `field_map` to extract the fields → classify (step 4).
   - **BLACKLIST**: a known non-payment format (OTP, promo "get ₹500 cashback",
     balance alert, bill reminder). Mark it `IGNORED` silently.
   - **Unknown or PENDING**: a generic parser tries to extract the fields (amount +
     debited/credited/spent/paid/sent + account/VPA). If it does, the SMS goes into the
     daily review as a guess; if not, it goes to "Missed a payment?" as a `CANDIDATE`.
     Either way the template is stored as `PENDING`.
   - **Date**: the date written in the SMS; if there is none or it can't be read, the
     SMS arrival time (`date_source = ARRIVAL`).
   - **Currency**: the original currency and amount. If the SMS also gives an INR amount,
     that is the home value; otherwise the user's last rate for that currency fills it in,
     and the transaction asks for confirmation in review.
   - **Duplicates**: an identical SMS (same hash) is dropped. A *different* SMS with the
     same amount, account and time within 15 minutes (e.g. bank SMS + UPI app SMS)
     becomes a "possible duplicate" in review, unless that pair is in `dup_dismissed`.
4. **Classify**, in this order:
   1. Own-account pair, **suggested only** (the user marks every self-transfer): debit from own A, credit to own B, same amount, ±60 min, or a
      counterparty last4 that is an own account) → `SELF_TRANSFER`. Both halves are linked.
   2. Credit card bill payment (debit SMS mentioning card payment/BillDesk/CRED to own
      card, or a card SMS "payment received") → `CC_BILL`. Card spends are counted once,
      at swipe.
   3. User rules by priority (VPA → merchant → sender → body).
   4. Otherwise: a best guess from merchant-name keywords, flagged `GUESS` → goes into review.

   **Back-dated payments**: any untagged transaction dated before today (a delayed SMS,
   one that arrived after midnight, an allowed format re-read) shows up in **today's**
   review labelled "From <date>". Once tagged, it counts on its own date.
5. **End-of-day review grows the whitelist and blacklist.** Every PENDING template
   that showed up today is settled by what the user does:
   - Confirms a guessed payment, or picks "It's a payment" on a candidate → the template
     becomes **WHITELIST**. Where the generic parser missed a field, the user taps the
     amount/merchant in the SMS once, and that position is saved in `field_map`.
   - Taps "Not money" → **BLACKLIST**.
   - Over time almost every SMS matches a stored template, so review shrinks to new
     merchants only. Templates and verdicts are part of the backup, so this learning is
     never lost. Settings has a Templates list where any verdict can be reversed.
   - A searchable list of all money-like SMS lets the user pick anything that was missed.

### Learning loop
In review, the switch "Always tag `<vpa/merchant>` like this" creates or updates a rule.
Rules track `hits`. A merchant whose tags keep flip-flopping is suggested as
**always ask** (e.g. Amazon, person-to-person UPI). The weekly auto-tag rate drives
the chart on the Rules screen.

## 6. Backup and restore

**How WhatsApp does it, and what we copy**
WhatsApp never asks you for a folder. Its Google Drive backup goes to a hidden,
app-only folder in your Drive (the Drive API's `appDataFolder`). The backup is tied
to your Google account and phone number. On reinstall, WhatsApp asks you to sign in
with Google, lists that hidden folder, and offers the newest backup. Its local backup
is written to a fixed path the app always uses.

**Nightly rolling backup (both destinations)**
- `PeriodicWorkRequest` every 24 h, aiming for about 02:00. Constraint: battery not low.
- Steps: `VACUUM INTO` a consistent DB snapshot → zip with `manifest.json`
  (schema_version, app_version, created_at, row counts, sha256) → write
  `piggybank-YYYY-MM-DD.pbk`.
- Rolling retention: keep the last **7 daily + 4 weekly + 3 monthly** files and delete older ones.
  Each run is logged in `backup_log`. If a run fails, the app shows a gentle banner the next day.
- Manual "Back up now" in Settings.

**Option A: Google Drive (WhatsApp method)**
- In Settings, the user picks a Google account (Credential Manager / Google Sign-In)
  and grants only the `drive.appdata` scope. The app can see nothing else in their Drive,
  and the backup doesn't show up among their normal Drive files.
- The nightly job uploads the `.pbk`, encrypted with the user's passphrase if they set
  one, into `appDataFolder` and applies the same retention there. A copy is also kept
  locally (Option B path).
- **Reinstall**: the first setup screen offers "Restore from Google Drive", then the account
  picker. The app lists `appDataFolder`, picks the **newest valid** backup, checks its
  checksum, asks for the passphrase if there is one, and restores the full history.
- Setup work: a Google Cloud project with an Android OAuth client (package name + SHA-1)
  and the Drive API enabled. `drive.appdata` is a non-restricted scope, so a personal
  build needs no Google security review.

**Option B: local folder (auto-suggested path)**
- The app writes backups to a fixed public path: **`Documents/PiggyBank/`**, through
  MediaStore. Files there are kept when the app is uninstalled. The app's private
  folders, including `Android/media/<package>`, are wiped on uninstall, so that is not
  used.
- **Reinstall**: Android 11+ won't let a newly installed app read files left by the old
  install. So the first setup screen opens the system folder picker **already pointing at
  `Documents/PiggyBank`** (`EXTRA_INITIAL_URI`). The user taps "Use this folder" once,
  then the app finds the newest valid backup, restores it and keeps the folder
  permission for future nightly backups.
- The user can still pick another folder or a single file ("Restore from file…").

**After any restore**
- A schema migration runs if the backup came from an older app version.
- The SMS scan resumes from the backup's newest `received_at`. Only newer SMS are
  parsed, and dedupe stops any double-import. Templates, rules, tags, budgets and
  streaks all come back with the history.

## 7. Screens (matching the mockups)

1. **Today**: allowance ring, Needs/Wants/Invested tiles, today's list, hidden items
   folded into one line, "Close your day" button, "SMS not read? Pick" entry.
2. **End-of-day review**: card stack (category, Need/Want, remember switch,
   self-transfer), "Missed a payment?" candidates, a "Day closed" screen with streak.
   A local notification at a time the user sets (default 21:30) reminds them.
3. **Monthly review**: salary → needs / wants / invested / still in bank, savings %
   and projection; burn-up vs pace; daily vs allowance; Needs/Wants/Investments bars
   with a pace marker; categories sorted by risk; budget move suggestion; "Not counted" list.
4. **Rules**: auto-tag rate, filters (Spends / Investments / Salary / Hidden), edit or
   delete rules, "always ask" list, own accounts.
5. **Settings**: **Income** (salary and other recurring or one-off incomes, each with an
   amount, schedule and applicable months), home currency (at first launch) and saved
   exchange rates, budgets per category, Duplicates list, review reminder time, backup
   folder and passphrase, backup history, import past SMS, app lock (biometric).

## 8. Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | KMP skeleton, theme, nav, SQLDelight schema v1 | App runs on a device; the network-access check passes in CI |
| M1 | SMS ingest (live + inbox catch-up + history import), amount filter, template signatures, generic parser, raw + txn storage | Your last 3 months import; every money SMS is either parsed or listed as a candidate |
| M2 | Today + End-of-day review + rules learning + self-transfer / CC-bill detection + candidate picker | You can close a full day in under a minute |
| M3 | Monthly review: budgets, income schedules, savings, multi-currency, charts | Numbers reconcile against your bank statement for one month |
| M4 | Nightly rolling backup to `Documents/PiggyBank` + Google Drive `appDataFolder`; restore on reinstall | Uninstall → reinstall → sign in with Google (or confirm the suggested folder) → full history back |
| M5 | Polish: reminder notification, streaks, app lock, home-screen widget | Daily use for 2 weeks without friction |
| M6 | iOS target: Shortcuts/App Intent ingestion, Files-based backup | Same DB and backup restores on iPhone |

## 9. Testing

- **Parser golden tests**: a corpus of real SMS formats per bank. Use your own messages
  with account numbers and names masked, kept in a git-ignored local folder. Commit only
  synthetic samples.
- Rules engine and classification order (transfer, then CC bill, then rules, then salary,
  then guess), covered by unit tests in `shared/`.
- Budget, pace, projection and savings maths, tested with fixed-date fixtures.
- Backup round-trip: back up, wipe, restore, compare row counts and checksums, then do
  it again across a schema migration.
- A CI check that fails the build if any module other than `backup/drive` uses a network
  client, or if a new network-capable dependency is added.
- **Template learning**: tests that run real-world variants through masking (same format,
  different amounts or merchants) and check they map to one signature, while promos with
  "₹" never match a payment template.

## 10. Risks and open questions

- **Play Store**: `READ_SMS`/`RECEIVE_SMS` are restricted permissions. Installing it
  yourself (sideload) is fine. Publishing needs Google's permission declaration
  (an SMS-based money-management exception) and a review.
- **Bank SMS formats drift.** A changed format becomes a new PENDING template, shows up in
  review once, and is learned again from the user's answer.
- **Masking quality decides template matching.** If the signature is too strict, every SMS
  looks new; if it's too loose, a promo can match a payment template. This needs golden tests.
- **Some banks send only email or app notifications for some transactions.** We could add
  `NotificationListenerService` (still on-device) later if needed.
- **If the Drive backup passphrase is lost, those backups can't be read.** Offer an
  optional printed recovery key, or let the user turn the passphrase off.
- **Decided (21 Nov 2026):** read every SMS that has an amount, with no bank list; learn
  a whitelist and blacklist of templates from the daily review; months run from the 1st;
  no DB encryption; backup to Google Drive (WhatsApp method) or a local folder.
- **Decided (later, 21 Nov 2026):** no money in is read from SMS, and income is entered by
  the user with a schedule; duplicates can be marked by the user and are suggested
  automatically; a missing or unreadable date falls back to the arrival time; back-dated
  untagged payments show in today's review; multiple currencies are supported from v1
  (home currency + rates entered by the user, never fetched).
- **Decided:** refunds are not read from SMS. The user marks a refund (full or partial,
  never more than the original) on the original spend with "Mark refund" (B6).
- **Decided (23 Nov 2026):** self-transfers are never marked automatically. A detected one is
  suggested in review (pre-selected, with the reason) and keeps counting as a spend until the user
  marks it. A rule the user creates for a payee can still apply their own choice.
- **Decided (23 Nov 2026):** duplicates are decided by SMS format. Same bank, same amount and account,
  two different formats within 4 hours → marked automatically. Same format → never a duplicate, never
  asked (checked on real SMS: 4 ATM withdrawals a minute apart are real). Different senders within
  15 minutes → asked in review. Undoing one records the pair as separate so it is never re-marked.
