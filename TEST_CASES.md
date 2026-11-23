# PiggyBank: primary test cases (for review before development)

Each case says what we set up, what the user or phone does, and what must happen.
Amounts and accounts are examples. "Spends" always means Needs + Wants only.

Mark each case ✅ agree, ✏️ change, or ❌ drop.

Cases added after M3 (E10–E12, R, P, L, S, I8–I9, J9–J10, K5–K6) describe what is already built
and covered by unit tests; they were written on 23 Nov 2026.

---

## A. Reading SMS

| ID | Situation | Expected result |
|---|---|---|
| A1 | A new SMS arrives: "Rs.320.00 spent on HDFC Bank Card x4421 at BLUE TOKAI on 21-11-26". | It appears on Today within a few seconds, even if the app was closed. |
| A2 | An SMS from an unknown sender contains "INR 1,250 debited". | It is still read. There's no list of supported banks; any SMS with an amount counts. |
| A3 | An OTP SMS: "OTP 482910 for txn of Rs 5,000". | The first time, it goes to review as undecided. After the user marks it "Not money", every later OTP in that format is skipped silently. |
| A4 | An SMS with no amount at all ("Your card statement is ready"). | Ignored. It never shows anywhere. |
| A5 | The phone was off overnight and 6 bank SMS arrived. | When the app next opens (or the nightly job runs), all 6 are picked up. None are missed. |
| A6 | The same SMS is delivered twice, or the inbox is scanned twice. | It is stored once. The transaction appears once. |
| A6a | Two *different* SMS describe the same payment (e.g. the bank SMS and the UPI app SMS for one ₹486 Uber ride: same amount, same account, a few minutes apart). | The app flags the second as a **possible duplicate** in review: "Same as Uber ₹486 at 10:05?", with the options Duplicate / Separate payment. |
| A6e | The same bank reports one payment twice in **two different SMS formats** (e.g. "PAYMENT ALERT" then "UPDATE: debited"; "AutoPay Success" then "Amount Spent"): same sender, amount and account, within 4 hours. | Marked as a duplicate **automatically**. The tagged copy, or the one naming the payee, or the earlier one is kept; the other is in the Duplicates list, where Undo works. An undone pair is never marked again. |
| A6f | Several payments the same day in the **same SMS format** with the same amount (e.g. 4 × ₹10,000 ATM withdrawals a minute apart). | Never a duplicate and never asked: a bank doesn't resend one alert (the balance drops each time), and exact re-deliveries are already dropped (A6). |
| A6b | The user marks a transaction as **Duplicate** (from review or from any list). | It's removed from every total right away, but kept in a "Duplicates" list where it can be undone. Its SMS format isn't blocked, because the format is still a valid payment format. |
| A6c | A duplicate is undone. | It returns to the totals and the day it belongs to. |
| A6d | The user taps "Separate payment" on a suggested duplicate. | Both stay. That pair is never suggested again. |
| A7 | First install, no backup. The user picks "import last 3 months". | All money SMS from the last 3 months are imported and grouped into past days. Past days are **not** forced through daily review; only undecided ones are listed once in a catch-up review. |
| A8 | The user denies SMS permission. | The app explains why it's needed and still works for manual entry and restore. It never crashes. |
| A9 | Dual-SIM phone, bank SMS on SIM 2. | Read the same as SIM 1. |

## B. Pulling details out of an SMS

| ID | Situation | Expected result |
|---|---|---|
| B1 | "Rs.1,164.50 debited from A/c XX4521 to VPA bigbasket@hdfcbank". | Amount ₹1,164.50, debit, account SBI ••4521, payee `bigbasket@hdfcbank`. |
| B2 | Amount written as "INR 20000.00", "Rs 20,000" or "₹20,000/-". | All read as ₹20,000. |
| B3 | Indian grouping: "Rs 1,50,000.00 credited". | Read as ₹1,50,000 (not ₹150 or ₹1,50). |
| B4 | The SMS also contains the available balance: "…debited Rs 500. Avl Bal Rs 42,310". | The amount is ₹500. The balance is never taken as the amount. |
| B5 | Any credit SMS: "Rs 2,000 credited to A/c XX9910", including a salary credit. | **Not recorded.** No money in is ever taken from SMS. Income comes only from what the user sets up (see section D). The credit SMS may still be used quietly to recognise a self-transfer (D1). |
| B6 | Refund SMS: "Refund of Rs 612 from SWIGGY processed". | Not recorded automatically (it's money in). In the review the user can open the Swiggy spend and tap **"Mark refund"**, which lowers that spend by ₹612. A refund can be partial, and can never be more than the original spend. |
| B7 | The SMS has a date that differs from when it arrived (a delayed SMS). | The transaction uses the date written in the SMS. |
| B7a | The SMS has no date, or the date can't be read. | The time the SMS arrived is used. |
| B7b | A back-dated payment arrives today (e.g. an SMS dated 18 Nov arriving on 21 Nov) and isn't tagged yet. | It shows in **today's** review with a label "From 18 Nov". Once tagged, it counts on 18 Nov, so it updates 18 Nov's totals and November's budget, not today's spend. |
| B7c | A back-dated payment that already matches a rule. | Tagged automatically; it's listed under today's auto-tagged items with its "From 18 Nov" label so it's not missed. |

## C. Learning SMS formats (allowed / blocked)

| ID | Situation | Expected result |
|---|---|---|
| C1 | A brand-new format arrives and the app's guess is right. In review the user taps "Looks right". | The format is saved as **allowed**. The next SMS in that format is read automatically, with no review needed for the format. |
| C2 | A promo: "Get ₹500 cashback on your next order!". The user taps "Not money". | The format is saved as **blocked**. Future promos in that exact format are skipped silently. |
| C3 | Two SMS in the same format but with different amounts, merchants and dates. | They match the same stored format. |
| C4 | A blocked promo format and a real payment from the same sender. | The payment is **not** blocked. Formats are matched by structure, not by sender alone. |
| C5 | The app reads the wrong amount from a new format. The user taps the correct amount in the SMS. | The fix is saved. Next time that format gives the correct amount. |
| C6 | The bank changes its SMS wording. | It is treated as a new format and shows once in review, then it's learned. |
| C7 | The user opens Settings › SMS formats and changes a blocked format to allowed. | Matching past SMS from that format are re-read and appear as transactions. |

## D. Kinds of money movement

| ID | Situation | Expected result |
|---|---|---|
| D1 | ₹20,000 leaves SBI ••4521 and ₹20,000 arrives in HDFC ••9910 within an hour (both are your accounts). | **Suggested** as a self-transfer in review, with the reason ("Money arrived in your account ••9910"). It stays a counted spend until **you** mark it; once marked it's hidden, counted nowhere, and listed under "Not counted". The credit SMS only helps spot it and isn't recorded. |
| D2 | Only the debit SMS arrives, and it names HDFC A/c ••9910, which is your own account. | Also suggested as a self-transfer; only the user marks it. |
| D3 | A ₹20,000 debit to a friend's account (not yours). | **Not** a self-transfer. It goes to review. |
| D4 | You pay ₹18,400 towards the HDFC Regalia credit card bill. | Marked **card bill payment**. Hidden and not counted, because the card purchases were counted when you swiped. |
| D5 | You spend ₹14,300 on the card in November and pay the ₹18,200 bill on 5 Dec. | November spends include the ₹14,300 of purchases only. The 5 Dec bill payment adds nothing to December. |
| D6 | A Paytm wallet top-up of ₹2,000, then ₹258 spent from the wallet. | The top-up is hidden. Only the ₹258 spend is counted. |
| D7 | The user adds income: "Salary, ₹1,50,000, monthly, Apr 2026 – Mar 2027". | Each of those months gets ₹1,50,000 of salary. Months outside the range have none. |
| D7a | The user adds a second recurring income: "Rent received, ₹18,000, monthly, all months". | Both incomes are added for every month they apply to, and listed separately in the monthly review. |
| D7b | The user adds a one-off income: "Bonus, ₹60,000, March 2027 only". | Only March 2027's income goes up by ₹60,000. |
| D7c | The salary changes from April (a hike to ₹1,70,000). | The user edits it from April onward. Past months keep ₹1,50,000. |
| D7d | Applicable months picked individually (e.g. only Jun, Jul, Aug for a seasonal income). | Income counts only in those three months. |
| D8 | A Zerodha SIP auto-debit of ₹5,000. | Recorded as an **Investment**. Not counted in spends; shown as part of savings. |
| D9 | The user marks a hidden transfer as "Count it". | It moves into spends and the totals update immediately. |

## E. Tagging and rules

| ID | Situation | Expected result |
|---|---|---|
| E1 | You've tagged Swiggy as Food delivery · Want with "Always tag like this" on. A new Swiggy payment arrives. | It's tagged automatically and doesn't need review. |
| E2 | The same merchant is paid by card on one day and by UPI on another. | The same rule tags both. Rules match the merchant or UPI ID, not the payment method. |
| E3 | "Always tag like this" is off when confirming. | Only this one transaction is tagged. No rule is created. |
| E4 | Payment to a person's UPI ID (e.g. RAMESH K). | Always goes to review, because one tag can't fit every payment to a person. |
| E5 | Amazon is in "Always ask me". | Every Amazon payment goes to review, even after many tags. |
| E6 | The user changes a rule from Want to Need. | Future payments use the new tag. The app asks: "Also update the 23 past transactions?" Past ones change only on yes. |
| E7 | Two rules match one payment. | The more specific rule wins (an exact UPI ID beats a name match). |
| E8 | A payment is tagged Investment. | No Need/Want choice is shown; investments sit outside spends. |
| E9 | The user deletes a category that has transactions. | The app asks where to move them. Nothing is lost silently. |
| E10 | The category the user wants isn't listed on a review card. | "+ New category" opens a short form: name, and Need/Want for a spend (investments don't ask). "Add" saves it and selects it on that card; it then appears on every card and in budgets. |
| E11 | The typed name already exists in any capitals ("pets" vs "Pets"). | The existing category is used; no copy is made. An archived one comes back. |
| E12 | The name is already used under another group (e.g. "Self-transfer" for a spend). | The form says where it's used and "Add" stays off. Names are trimmed and limited to 30 characters. |

## F. End-of-day review

| ID | Situation | Expected result |
|---|---|---|
| F1 | 7 transactions today: 4 matched rules, 3 didn't. | The review shows the 3 cards. The 4 are listed as auto-tagged, and you can open and change them. |
| F2 | The user confirms all 3. | The "Day closed" screen appears and the streak goes up by 1. |
| F3 | No transactions needed review today. | "Close your day" is still offered (one tap) and the streak still counts. |
| F4 | The user skips review one day. | The streak resets to 0 the next day. Unreviewed items carry into the next review, labelled with their date. |
| F5 | The user picks an SMS under "Missed a payment?". | It becomes a transaction and is added to today's review. Its format is saved as allowed. |
| F6 | A payment at 23:58 whose SMS arrives at 00:03 after that day was closed. | It counts on the earlier day (the date in the SMS). If untagged, it appears in the next review labelled "From <date>" (same as B7b). |
| F7 | Review reminder set for 21:30. | A notification at 21:30, only if the day isn't closed yet. |
| F8 | The user goes back and edits a tag on a closed day. | Allowed. All totals update and the streak is unaffected. |

## G. Today screen numbers

Setup: November has a ₹90,000 spends budget, of which ₹25,000 is fixed rent. Today is the 21st.

| ID | Situation | Expected result |
|---|---|---|
| G1 | ₹2,840 spent today (Needs ₹1,650, Wants ₹932, untagged ₹258), plus a ₹5,000 SIP. | "Spent today" = ₹2,840. Invested = ₹5,000, shown separately and not inside ₹2,840. |
| G2 | A ₹20,000 self-transfer and an ₹18,200 card bill today. | Neither changes any number. They show only as "2 hidden". |
| G3 | Today's allowance. | (Remaining variable budget at the start of today) ÷ (days left including today). For example (₹31,400 + ₹2,840) ÷ 10 = ₹3,424. |
| G4 | Today's spend goes over the allowance. | The ring shows over 100% in a warning colour. Nothing breaks. |

## H. Monthly review

| ID | Situation | Expected result |
|---|---|---|
| H1 | Income ₹1,50,000 (set up by the user); Needs ₹38,200; Wants ₹20,400; invested ₹25,000. | Saved = ₹91,400 (61%). Invested ₹25,000 is shown as part of that, and still in bank = ₹66,400. |
| H2 | No income set up for this month. | Savings shows "Add your income for November" with a button, instead of a negative number. |
| H3 | Salary ₹1,50,000 plus rent received ₹18,000 in the same month. | Income = ₹1,68,000, listed as two lines. Savings uses the total. |
| H4 | Spends go over income in a month. | Savings shows as a negative number in a warning colour ("Overspent ₹4,200"). |
| H5 | The pace marker on day 21 of 30. | 70% marker on every bar. A category at 82% of budget shows "Over pace"; one at 34% shows "Way under". |
| H6 | Rent ₹25,000 paid in full on the 1st. | Shown as "Paid · fixed" and left out of the pace check. |
| H7 | Month-end projection. | (Variable spend so far ÷ days so far) × days left, plus spend so far. Shown as a dotted line and a number. |
| H8 | Months of different lengths (February has 28 or 29 days). | Pace and allowance use the real number of days in the month (28/29/30/31). |
| H9 | Browsing to October with the previous-month arrow. | Shows October's final numbers. It's read-only unless the user edits a transaction. |
| H10 | The user marks a ₹612 refund on a Swiggy spend (B6). | Food delivery spend goes down by ₹612, but never below ₹0. |

## R. Catch-up (imported history)

Setup: SMS history was imported on first run; those payments wait in Catch-up, never in a daily review.

| ID | Situation | Expected result |
|---|---|---|
| R1 | First run: "How far back should PiggyBank read?" | Choices are 1, 3, 6 and 12 months. |
| R2 | More than 10 payments in Catch-up, some with a category already guessed. | A "Tag all N identified" card appears. After a confirm, each guessed payment is tagged as shown. No rules are saved, so new payments to those payees are still asked. |
| R3 | Tag all identified, with possible self-transfers, possible duplicates, payments needing a rate and payments to people in Catch-up. | All of those are left in Catch-up for the user. |
| R4 | Filter → "Tag everything under ₹300" with Cafés · Want picked. | The sheet shows the live count and total under ₹300 (strictly under). After a confirm, they're tagged Cafés · Want; no rules are saved. The slider steps are ₹50 to ₹5,000. |
| R5 | Same as R4, with possible transfers, duplicates and foreign payments without a rate under ₹300. | Those are left out. |
| R6 | Filter → period changed from 3 months to 6 months. | Older SMS are read from the inbox; new payments join Catch-up; nothing is removed or doubled. |
| R7 | Filter → period changed from 3 months to 1 month. | Before applying, it says how many untagged payments older than 1 month will be removed. Only untagged ones go (with their SMS); tagged payments and today's review are never touched. |
| R8 | Filter → Clear. | All untagged Catch-up payments are removed; Catch-up says "Catch-up cleared" and the Filter offers a period again. |
| R9 | After R7 or R8, a longer period is picked. | The removed payments are read back from the inbox, untagged, as long as the SMS are still on the phone. |
| R10 | An SMS format's example message was among the removed SMS. | The Rules screen still opens (this crashed once); the format points at another of its SMS, or none. |

## P. Saved places ("Save where I paid")

| ID | Situation | Expected result |
|---|---|---|
| P1 | Fresh install. | Off. Nothing about location is asked until the user turns it on (Rules → "Save where I paid"). |
| P2 | The user turns it on. | Android asks for location while in use, then "Allow all the time". With only "while in use", the card says it works only while the app is open and offers "Allow all the time". |
| P3 | A card swipe at a café; the SMS arrives within seconds. | The phone's location is saved with that payment. Today shows a 📍; tapping shows the coordinates and accuracy, "Open in Maps" and "Remove". |
| P4 | Autopay, e-mandate, subscription, bill payment, EMI, insurance or scheduled payment SMS. | No location is saved. |
| P5 | A foreign-currency charge. | No location is saved. |
| P6 | Imported history, or an SMS that arrived more than 3 minutes ago. | No location is saved. |
| P7 | The phone's last location is more than 3 minutes old and no fresh one comes. | No location is saved, rather than a wrong one. |
| P8 | "Open in Maps". | The point is handed to the phone's maps app; PiggyBank itself makes no network call and shows no place names. |
| P9 | "Remove" on a location, or the payment itself is deleted ("Not a payment", catch-up removal). | The location is gone too. |

## L. Payments list

| ID | Situation | Expected result |
|---|---|---|
| L1 | Open the Payments tab. | Every tagged payment (by the user or a rule), newest first, under day headings ("Today", "Yesterday", "Fri, 21 Nov"). Untagged payments aren't listed; they wait in Review. Duplicates are never listed. |
| L2 | "Highest". | Biggest first by ₹ amount after refunds; no day headings. Foreign payments still waiting for a rate go last. |
| L3 | Filters: month (‹ Nov 2026 › or All time), type (All, Spends, Needs, Wants, Investments, Hidden), category. | The list, count and total follow every filter together; a category filter shows with ✕ to clear it. |
| L4 | Tap a payment → "Change tag". | A form with kind, category and Need/Want edits just this payment; the list and Monthly totals update. No rule is created or changed. |
| L5 | Monthly → tap a category in Budgets. | Payments opens filtered to that month and category, with the same total as the budget row. Back returns to Monthly. |
| L6 | Monthly → tap Needs, Wants or Invested in the savings card. | Payments opens with that month and type. |

## S. Settings

| ID | Situation | Expected result |
|---|---|---|
| S1 | The gear at the top right of Today, Review, Payments or Monthly. | Settings opens with Rules, Backup & restore and Save where I paid, each with a one-line status. |
| S2 | Open an entry, then press Back (arrow or system gesture). | Back goes to the Settings list, then out to the tab. Tapping a bottom-bar tab also leaves Settings. |
| S3 | Any switch that is off (Always tag, Always ask, nightly backup, Save where I paid). | Clearly visible on a white card: grey track, darker grey thumb and outline. On stays dark. |

## M. Multiple currencies

Setup: home currency is INR, chosen at first launch. All budgets, income and totals are in the home currency.

| ID | Situation | Expected result |
|---|---|---|
| M1 | "USD 25.00 spent on HDFC Card x4421 at AWS. INR equivalent 2,113.40". | Stored as USD 25.00 **and** ₹2,113.40. Totals use ₹2,113.40. The list shows "$25.00 · ₹2,113". |
| M2 | "EUR 40.00 spent on card x4421 at ZARA PARIS" with no INR amount. | Stored as EUR 40.00. The review card shows "€40.00" (never ₹0) and asks "1 EUR = ₹ ___", pre-filled from the last EUR rate, with a live ₹ preview. Confirm stays off until a rate is entered; confirming saves the rate and fills every waiting EUR payment. Until then it is left out of totals, and rate cards sort first. |
| M3 | The user enters a rate for EUR once ("1 EUR = ₹90.20"). | The next EUR payment is filled in with that rate automatically, and it can still be changed. No exchange rates are ever fetched from the internet. |
| M4 | Currencies with different decimals: JPY 1,200 (no decimals), KWD 3.500 (3 decimals). | Stored and shown correctly: ¥1,200 and KWD 3.500, not ¥12.00 or KWD 3.50. |
| M5 | Symbols and codes: "$", "US$", "USD", "€", "EUR", "AED", "SGD". | All recognised as the right currency. A bare "$" with no other clue is treated as USD and flagged in review to confirm. |
| M6 | A separate forex markup / foreign transaction fee SMS (₹63.40). | Recorded as its own spend, auto-tagged "Bank charges". |
| M7 | An investment in a foreign currency (e.g. a US stock purchase in USD). | Same rules: stored in USD with the ₹ value, counted under Investments. |
| M8 | Monthly review with foreign spends. | All totals in ₹. A small "Includes $25 + €40 in foreign spends" line, tappable to see them. |
| M9 | Backup and restore. | Original currency, amount, ₹ amount and the rate used all come back exactly. |

## I. Nightly backup

| ID | Situation | Expected result |
|---|---|---|
| I1 | The phone is on overnight with normal battery. | One backup file is created around 2 AM in `Documents/PiggyBank/` (and on Drive, if turned on). |
| I2 | Battery is very low at 2 AM. | The backup waits and runs once the battery is OK. It runs at most once per day. |
| I3 | Months of nightly backups. | Kept: the newest 7 daily files, then one per week for the 4 weeks before them, then one per month for the 3 months before those (weeks and months already covered aren't counted twice). Older ones are deleted automatically, on the phone and on Drive. |
| I4 | The Drive upload fails (no internet). | The local backup still succeeds. Drive retries the next night, and a small banner says "Drive backup pending". |
| I5 | Backup fails 2 nights in a row. | A visible warning on Today. |
| I6 | "Back up now" in Settings. | A backup is made immediately, with the time shown. |
| I7 | The backup contents. | Every transaction, raw SMS, SMS format, rule, category, budget, income schedule, duplicate mark, currency rate, review history, streak and saved place. |
| I8 | "Back up now" twice on the same day. | Still one file for that day (`piggybank-YYYY-MM-DD.pbk`), holding the latest data. |
| I9 | A backup file is opened. | It carries a manifest (format, schema and app version, date, row counts, SHA-256) and the database; a changed byte, a cut-short file or a file from a newer app version is rejected. |

## J. Restore after reinstall

| ID | Situation | Expected result |
|---|---|---|
| J1 | Uninstall, reinstall, choose "Restore from Google Drive", pick the same Google account. | The newest backup is found automatically and the full history is restored: everything looks as it did before. |
| J2 | Reinstall, choose local restore. | The folder picker opens already at `Documents/PiggyBank`. After one tap on "Use this folder", the newest backup is restored. |
| J3 | The newest backup file is damaged. | The app skips it, restores the next-newest valid one and says which date it restored. |
| J4 | A Drive backup with a passphrase, and the wrong passphrase is entered. | A clear error. Retrying is allowed. Nothing is overwritten. **Not built yet:** Drive copies are not encrypted for now. |
| J5 | A backup from an older app version. | It's upgraded to the current format and restored without losing data. |
| J6 | SMS that arrived after the backup's date. | Read in after restore, with no duplicates of ones already in the backup. |
| J7 | Restoring on a different, new phone. | Works the same, through Drive or by copying the `PiggyBank` folder. |
| J8 | No backup found anywhere. | The app says so and offers a fresh start with an SMS history import. |
| J9 | A valid backup is found. | Before anything changes, it shows the backup's date and how many payments, SMS and rules it holds. Cancel leaves the phone as it was; Restore replaces everything and restarts the app. |
| J10 | After a reinstall, the user restored from `Documents/PiggyBank` with "Use this folder". | Nightly backups keep writing to and pruning that same folder (through the granted access), with no "(1)" copies. |

## K. Privacy

| ID | Situation | Expected result |
|---|---|---|
| K1 | Drive backup is off. | The app makes zero network connections, checked with a network monitor during a full day of use. Today says "Offline · all data stays on this phone". |
| K2 | Drive backup is on. | The only network traffic goes to Google Drive, and only to the app's hidden folder (`drive.appdata` scope). Today says only the nightly backup goes online. |
| K3 | Android's own system backup. | Doesn't copy PiggyBank's data anywhere. |
| K4 | Another app on the phone. | Can't read PiggyBank's database. Only the user-visible backup folder is readable, by apps the user allows. |
| K5 | The build. | Fails if any source file other than `backup/DriveClient.kt` uses a network API (`verifyNetworkUse`). |
| K6 | The build. | Fails if the merged manifest asks for a permission not on the allowlist (`verifyPermissions`). WorkManager's network-state and foreground-service permissions are removed. |

---

### Out of scope for v1 (agree?)
- Bank transactions that send no SMS (email-only or app-notification-only).
- Changing the home currency after setup.
- Shared or family budgets.
- iOS (planned for M6).
