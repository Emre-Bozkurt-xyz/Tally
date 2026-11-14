# Phase 7: the everyday number, splits, receipts by email

Planning doc for the next round of work, written 2026-11-13 after three weeks of real use.
`TALLY.md` stays the overall plan and status; this file holds the detail for Phase 7 and is
ticked off as steps land. Work goes straight to `main` (pushes deploy the server).

## 1. Why

Three weeks of real notifications (Oct 18 to Nov 7) showed four problems:

1. **Wrong totals.** A credit card payment counted as $200 of spending: RBC sends the chequing
   withdrawal and "A payment of $200.00 to RBC credit card 0800" as two alerts, and only the
   withdrawal was understood. Deposits weren't understood at all.
2. **The headline number is noisy.** Rent ($750, automatic, early on the 3rd) makes that week's
   widget number useless. The number on Home and the widget should be "everyday" spending:
   the purchases you choose to make, not fixed and automatic ones.
3. **Splits are slow and settling is rigid.** Splitting a meal 50/50 takes several taps.
   e-Transfers back often cover several things at once, or a little more or less than owed,
   and today an e-Transfer can only settle one part with exactly the same amount.
4. **Online card purchases are invisible.** RBC sends no alert for credit card purchases, and
   wallets only see taps, so a Walmart order on the Visa shows up only on the monthly
   statement.

## 2. Decisions made

| Question | Decision |
|---|---|
| The $750 withdrawal on the 3rd | Rent. File it automatically; don't count it in the everyday number. |
| The $20.00 withdrawals | Presto top-ups (Transport › Transit). Already labelled by hand. |
| Categories that start as "fixed" (not counted) | Rent and Bills. Changeable any time. |
| e-Transfer slightly over or under what's owed | Offer **Call it even**. The difference becomes your own spending (short) or money back (over), in the category of what was owed, so totals still match the bank. |
| Receipt emails | Read on the server; receipt-like emails (only those) go to Claude to pull out store, total, card and order number. Accepted: their text includes names and addresses. |
| Email access | IMAP with a Google app password (needs 2-Step Verification). Fallback if the account can't make app passwords: a Gmail filter forwarding receipts to an address on ems-place.com through Cloudflare Email Routing. |
| Interac e-Transfer notices | Arrive by text message, not email. Handled on the phone (done, step 0). |

## 3. Order of work

Ordered so each step stands on its own, the database changes once, and the step that needs
setup from you (email) comes last, giving you time to make the app password.

| Step | What | Needs from you | Status |
|---|---|---|---|
| 0 | Capture fixes: deposits, card payments, noise, late re-posts, Interac texts | Nothing | **Done** (`31bf494`, `26b1433`) |
| 1 | One database migration (v8) for steps 2 and 3, synced everywhere | Nothing | **Done** |
| 2 | Rules without a merchant (rent) and "What counts" | Nothing | **Done** |
| 3 | Quick splits, balances per person, settling e-Transfers | Nothing | **Done** |
| 4 | Receipts from email on the server | App password in `.env.production` | **Done** (waiting for the password) |
| 5 | Notifications on the phone for inbox items the server adds | Nothing | To do |

Steps 2 and 3 are independent once step 1 is in; 2 first because it fixes the number you look
at every day.

## 4. Step 0: capture fixes (done)

- [x] RBC deposits ("A deposit of $70.00 was made to your RBC account ****8805") are money in,
      waiting in the inbox.
- [x] A credit card payment pairs with the chequing withdrawal RBC sends seconds before or
      after it, and the two become one transfer: never spending, nothing to review
      (`CaptureDeduper.isPaymentPair`).
- [x] Ignored: RBC low-credit alerts and budgeting nudges, Google Wallet passes and tickets,
      and anything from a wallet without an amount ("Installing…").
- [x] A notification re-posted hours later with its original post time is the same
      notification (Google Wallet did this once and made a duplicate $6.98 purchase).
- [x] Transactions without a merchant read "Withdrawal", "Deposit" or "Credit card payment",
      on the phone and the dashboard.
- [x] Interac texts ("INTERAC e-Transfer: $70.00 (CAD) from … has been deposited to your
      account") are read from Google Messages and Samsung Messages. Nothing else from a
      messaging app is ever stored, even with "log all packages" on; older ones are deleted at
      start-up. The text merges with RBC's deposit alert, so the deposit is named after the
      sender.

Still to do by hand once (on the phone):

- [ ] More → Raw events → re-parse, so the stored deposits and the Nov 2 payment are read.
      The payment should turn the existing $200 withdrawal into a transfer; if not, delete it.
- [ ] Check Oct 26 for two $6.98 Neighbours Market entries and delete the second.

Not seen yet, so not handled: the text Interac sends for an e-Transfer you send, and a tap with
the physical card (it should arrive as an RBC withdrawal with no wallet alert).

## 5. Step 1: database v8

One Room migration and one server change, so steps 2 and 3 ship without another.

- [x] `categories.counted` (boolean, default true). Categories named Rent, Bills or Housing
  (any case, with their subcategories) set to false by the migration, with a fresh `updatedAt`
  so the change syncs. New installs seed Bills uncounted.
- [x] `transactions.counted` (nullable boolean): null follows the category, true or false
  overrides it for this one transaction.
- [x] New table `settlements`: `id, person, amountCents, transactionId (the e-Transfer), differenceCents,
  occurredAt, updatedAt, deleted`. `differenceCents` is what **Call it even** absorbed:
  positive when they paid less than owed, negative when more.
- [x] Synced like every other table (`SyncTables`, server `Rows`/`RowType`, `RowsSchemaTest`
  against `app/schemas/.../8.json`). Older phones ignore the new columns (merge over stored
  JSON already handles this).
- [x] Tests: `TallyDatabaseMigrationTest` 7 → 8 with data in place; the rule that Rent and Bills
  start uncounted.

## 6. Step 2: rent rules and "What counts"

### Rules without a merchant

Rent arrives as a bare chequing withdrawal, so no merchant pattern can match it.

- [x] A rule may leave the merchant blank if it has an amount condition: it then matches only
  money out **without** a merchant. Blank merchant and no amount stays an error (it would
  catch everything).
- [x] If a wallet names the store after a no-merchant rule filed the bank alert ($20.00 at a
  shop, not Presto), the capture is filed again by the store.
- [x] `RulesEngine.match` checks these rules when `merchant == null`; patterned rules work as
  today.
- [x] TOML: `merchant = ""` with `min_amount`/`max_amount`. Editor: "No merchant (bank alert
  only)" chip under the merchant field. Not done: a match count for bare transactions in the
  editor's preview.
- [x] Seed nothing automatically; offer it: when you file a bare withdrawal (inbox or "Always
  do this…"), "Always file $750.00 withdrawals from RBC Chequing here" (exact amount, that
  account; one rule per amount and account).
- Re-run on what's waiting with the existing Apply.

### What counts

- [x] Effective rule (`Counting` in core): a transaction counts toward the everyday number if it's spending (EXPENSE)
  and its own `counted` override says so, otherwise its category's (a subcategory is uncounted
  when its parent is). Transfers never count; income is shown separately as today.
- [x] `LedgerEntry` gains `counted`; `MonthSummary`, `WeekSpend` and the running-total chart take
  counted spending for the headline and keep the full total alongside.
- [x] **Home hero:** "$612.40 everyday" with "+ $750.00 fixed · $1,362.40 in all" underneath.
  Budget bar and pace tick use counted spending against budgets of counted categories.
- [x] **Tap the line under the number → "What counts" sheet:** each top-level category with this month's amount and
  a switch; subcategories inside. Changes apply everywhere at once (Home, widget, chart).
- [x] **Per transaction:** a "Counts toward everyday" switch in the detail screen, for one-offs
  (a laptop, a flight). Not done: a long-press action in Activity.
- [x] **Widget:** "This week" uses counted spending (and budgets of counted categories).
- [x] **Month screen** keeps everything, with uncounted categories in their own "Fixed" group.
- [x] **Dashboard:** same number on the Overview hero (its "fixed" line links to the switches),
  and a "Counts" column in the Rules page's categories.
- [x] Tests: report maths in core (counted vs all, overrides both ways, parent off), app repository
  tests, dashboard test for the hero text.

## 7. Step 3: splits and settling

### Quick split

- One-tap chips on inbox cards and the transaction screen: "½ with Sam" for the people you
  split with most recently (from open and recent owed parts). One tap splits off half as owed;
  undo from the snackbar.
- Long-press the chip (or "Split…") for a sheet: person, share (½, ⅓, ¼, custom amount),
  and "someone else".
- Halves of odd cents: you keep the extra cent.
- The capture notification gets a "½ Sam" action for the most recent person.

### Balances per person

- A person's balance = their open owed parts − their settlements. Bulk and partial payments
  then just work: the balance is what matters, not which item was paid.
- "Owed to me" becomes a list of people with balances; tap one for their history (parts and
  settlements, newest first) and **Settle up** (records a settlement for the whole balance, for
  cash or anything Tally didn't see).
- Which parts are "paid" is derived oldest first (`Balance.open`); nothing is written back to
  the parts. `settledAt` only marks parts paid the old per-item way before v8, and they stay
  paid. Removing a payment brings the balance back, with no bookkeeping to undo.
- Names: "Sam" and "Sam Lee" are the same person (first-name or full-name agreement, as
  `OwedMatch` did; it's now `People` and `Balances.forSender` in `core/report/Balances.kt`).

### Settling an e-Transfer

- A deposit named after someone who owes you shows their balance on the inbox card:
  "Sam owes $68.40 (3 items)". **Settle** records a settlement for the amount received.
- When it's within $5 or 10% of the balance (whichever is larger): **Call it even** (settle
  the whole balance; the difference goes to `differenceCents`) or **Keep $1.50 owing** /
  **Keep $3.50 as credit**.
- Further off than that: it settles that much and leaves the rest, or you file it as income
  or money back instead.
- A deposit with no name (RBC alert alone): the card asks "From someone?" with chips for
  people who owe you.
- The deposit itself becomes a transfer (not income, not money back), as settling does today.
- **Call it even accounting:** a shortfall is counted as your spending in the category of the
  most recent owed part; an overpayment as money back in it. Reports read `differenceCents`
  from settlements.
- Dashboard: the same settle action in the web inbox; balances on the Overview.
- Tests: balance maths (bulk, partial, over, under), call-it-even effect on month totals, name
  matching, inbox flows.

### Done (step 3)

- [x] `core/report/Balances.kt`: balances per person, oldest-first coverage, credit, the
  call-it-even slack (`Offer`), name matching (`People`), and call-it-even differences as
  report entries (shortfall spending, overpayment money back, in the category of the most
  recent thing owed for).
- [x] Every report (Home, Month, Insights, widget, dashboard) reads one feed that includes
  those differences (`observeLedgerEntries`, `Snapshot.entries`).
- [x] "½ with Sam" chips on inbox purchases and the transaction screen (undo from the inbox
  snackbar; tap an owed chip to take it back), long-press or "Split…" for ½ ⅓ ¼, an amount,
  or someone else. Their share rounds down: you keep the odd cent.
- [x] Capture notification: "½ Sam" for the most recent person in place of "Split"; after it,
  the notification says "Sam owes $6.49" and still waits for a yes.
- [x] Inbox deposits: "Sam owes $68.40 (3 items)", Settle / Call it even / Keep $1.40 owing
  (or as credit); undo restores the deposit as it was. Unnamed deposits ask "From someone?".
- [x] "Owed to me": one section per person with what's open, Settle up, and history
  (payments, with Remove, and paid items).
- [x] Dashboard: the same settle choices in the web inbox, "From someone?" for unnamed
  deposits, and "Owed to you" on the Overview.
- [x] Tests: core balance maths, app repository flows, dashboard settle flows.

## 8. Step 4: receipts by email (server)

### Setup (you)

1. Turn on 2-Step Verification on the Google account, then make an app password
   (myaccount.google.com → Security → App passwords). If the option isn't there, say so and
   we switch to the forwarding fallback (section 2).
2. Add to `.env.production` on the mini-PC: `GMAIL_ADDRESS`, `GMAIL_APP_PASSWORD`. Optional:
   `EMAIL_POLL_MINUTES` (default 5), `EMAIL_DAILY_LIMIT` (default 100 Claude calls).

### Design

- **Poller:** IMAP (Jakarta/Angus Mail) to `imap.gmail.com:993`, read-only (`EXAMINE`), every
  few minutes, picking up from the last seen UID (kept in Postgres). Starts from 7 days back
  the first time.
- **Candidate filter, before anything leaves the server:** subject or sender looks like an
  order or receipt ("Thank you for shopping with us", "order confirmation", "your order",
  "receipt", "purchase"), minus shipping, delivery, review and marketing mail. Walmart's
  delivery updates and review requests never get read further.
- **Extraction:** Claude Haiku gets the plain text of a candidate and answers with JSON:
  is it a new charge, store, total, currency, date, card brand and last 4, order number,
  up to five item names. Answers that fail checks (no total, not CAD, total out of range) are
  dropped. Daily cap like the categorizer's.
- **One transaction per order:** keyed by store and order number (Postgres table `email_orders`:
  order key → transaction id, last total). A later receipt for the same order with a different
  total updates the amount (Walmart's $3.30 temporary hold disappears from the final charge).
- **Not twice:** before adding, look for a transaction with the same amount on the same (or
  unknown) account within 36 hours; if one exists (a tap the wallet saw, an earlier statement
  import), add the store name and items to it instead.
- **Account:** card last 4 through `account_identifiers` (0800 → RBC Visa, learned the first
  time you file one), else the card brand against account names (`AccountNames`).
- **What it writes:** a transaction through `SyncServer.write` with source `EMAIL`, status
  CAPTURED (always to the inbox), merchant from the store name (aliases and rules apply, then
  the categorizer), and the first item names as the note. The email body is not stored.
- **Statement import later:** matches the email transaction like any capture and takes the
  statement's final amount.
- Tests: candidate filter on real subject lines (made up where they'd name people), extraction
  parse and checks with a fake model, order-key updates, dedupe against a tap, account mapping.
  The poller itself behind an interface, tested with a fake mailbox.

### Done (step 4)

- [x] `core/email/Receipts.kt`: the subject filter (`ReceiptFilter`), HTML to text, and
  `ReceiptReader` with its checks. The model answers through structured output (a JSON
  schema with every field required), at low effort.
- [x] `server/EmailPoller.kt`: Gmail over IMAP, read-only, from the last UID (Postgres
  `email_state`, migration V4); 7 days back the first time; the daily cap stops before the next
  candidate, so nothing is skipped; a model or network error retries that message next time.
- [x] `server/web/EmailReceipts.kt`: one transaction per order (`email_orders`), later receipts
  update the amount (not after a statement reconciled it), the same amount within 36 hours on
  the same or an unknown card is enriched instead of duplicated, card by last 4 then by name,
  aliases and rules apply, always CAPTURED. The phone's sync asks for a category suggestion as
  for any capture.
- [x] The categorizer now asks at low effort with room to think (Claude Haiku 5.5 thinks by
  default, and that counts toward `max_tokens`; the old cap of 32 could leave no answer).
- [x] Tests: subjects, HTML, answer checks, add / update / match / skip, account by name,
  the poller's cursor, daily cap and retry.
- [ ] Turn it on: `GMAIL_ADDRESS` and `GMAIL_APP_PASSWORD` in `.env.production` on the
  mini-PC, then restart the server. The log says "Receipts by email: checking INBOX every 5 min".

## 9. Step 5: notifications for server-added items

The phone only notifies for its own captures. After a sync pulls a new CAPTURED transaction
the phone didn't make (email receipts, statement imports), show the usual capture notification
("$78.61 · Walmart"), at most a few per sync, grouped when there are more.

## 10. Still open, not in this phase

- Linking refunds to the purchase they refund.
- The wipe-and-reinstall restore test.
- Text from Interac for an e-Transfer you send; a physical-card tap (waiting on real examples).
- Amazon orders split across shipments (each shipment can be its own charge): watch what the
  emails say before handling it.

## 11. Done when

- The Home number and the widget show only counted spending, and a week with rent looks like
  a normal week.
- A credit card payment never appears as spending.
- Splitting a meal in half is one tap, and an e-Transfer from the friend you split with settles
  their balance in one more, including when it's a little off.
- A Walmart or Amazon order on the Visa is in the inbox within minutes, with the right amount
  and account, and isn't doubled by the statement later.
