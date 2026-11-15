# APP-07 Payment & Settlement Service: module guide

**Module:** M4 Payment & Settlement · **Owner:** Kautilya (MT2025064) · **Folder:** `app-07-payment` · **Port:** 8087

> This guide explains the whole application in plain words. Start with "Words you need first", then read
> sections 1 and 2. Sections 6 and 7 are what you use in the review. Section 10 lists likely viva questions.

### Words you need first

| Word | What it means here |
|---|---|
| **paise** | 1 rupee = 100 paise. Every amount in MMTS is a whole number of paise (`18500` = Rs 185.00). We never use decimals for money, because computers store decimals like 0.1 slightly wrongly and the errors add up. In Java the type is `long`. |
| **payment gateway** | A company (here Razorpay) that takes money from a card or UPI for us. The traveller types card details on the gateway's own page, never on ours. |
| **token** | A harmless stand-in for a card that the gateway gives us after checkout, e.g. `tok_demo_visa`. We can charge it again, but it is useless to a thief outside the gateway. |
| **idempotency** | "Doing it twice has the same effect as doing it once." The caller sends an `Idempotency-Key` header (e.g. `b-5001-pay`). If the same key arrives again (a retry after a timeout), we do not charge again; we return the first answer. |
| **double-entry ledger** | The accounting book. Every money movement is written as at least two lines: money leaves one account (**debit**) and arrives in another (**credit**). The debits and credits of one movement are always equal, like the two sides of a see-saw. |
| **account** | A labelled box of money in the books, e.g. `USER_WALLET:u-101` (what we hold for traveller u-101) or `PROVIDER_PAYABLE:DMRC` (what we owe Delhi Metro). |
| **trial balance** | Adding up all debits and all credits in the whole book. If every movement balanced, the two totals are equal (difference 0). If not, something is wrong. |
| **commission** | The platform's cut of each provider's share. |
| **payout / settlement** | Sending providers the money we collected for them (payout), and the daily report of who earned what (settlement file). |
| **webhook** | The gateway calling *us* back later ("payment pay_xxx is captured"). It is an HTTP request from the internet to our URL. |
| **HMAC (HMAC-SHA256)** | A "signature" made from a message plus a secret key. Only someone who knows the key can make the right signature, and changing even one character of the message changes it completely. Razorpay signs each webhook this way. |
| **PCI DSS** | The card industry's security rulebook. Anyone who stores or handles raw card numbers must follow all of it (audits, special networks, encryption). By never touching card data we stay out of almost all of it. |
| **optimistic locking** | A way to stop two requests overwriting each other's change. Each wallet has a version number; a save only works if the version is still the one we read. |

## 1. What this application does

APP-07 is the **only** place in MMTS that handles money. It covers the six functions of module M4 (architecture
document, section 2.3):

| Function | In plain words | Main classes |
|---|---|---|
| **M4.1 Wallet** | Each traveller has a prepaid wallet. It is created the first time it is used. The traveller tops it up through the gateway (with a token, never a card number), and can see the balance and history. | `WalletService`, `Wallet`, `WalletTransaction` |
| **M4.2 Charge once per booking** | APP-06 (Booking) asks us to charge a trip, from the wallet or through the gateway. The same `Idempotency-Key` always gives the same answer and never a second charge. The provider shares must add up to the total. | `PaymentService`, `Payment` |
| **M4.3 Refunds** | Give back part or all of a payment, never more than was taken, once per key. The money goes back the way it came (wallet or card). | `RefundService`, `Refund` |
| **M4.4 Split and commission** | Each payment is split between the providers of its legs, and the platform keeps a commission. The commission rule (5% or a flat Rs 5) is a **Strategy** chosen per provider type in `application.yml`, and the percentage can be changed by a `PRICING_RULES_UPDATED` event. | `CommissionPolicy`, `PercentageCommission`, `FlatFeeCommission` |
| **M4.5 Provider payouts** | Every day (or on request) a payout job sends each provider what it is owed, through a payout gateway (Razorpay Route in real life). | `PayoutJobTemplate`, `ProviderPayoutJob`, `PayoutService` |
| **M4.6 Ledger and settlement** | Every money movement goes into a double-entry ledger that can only grow (no edits, no deletes). A trial-balance endpoint proves the books balance. A daily CSV file (INT-20) tells APP-09 what each provider earned. | `LedgerService`, `LedgerTransaction`, `LedgerEntry`, `SettlementService` |

It also receives **gateway webhooks** (signed with HMAC-SHA256) and publishes events: `payment.events`
(`PAYMENT_CAPTURED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED`) for APP-06, and `audit.events` for APP-09.
It listens to `pricing.rules`.

## 2. How it works

The layers (shared rules in `SHARED-SETUP.md` section 3): a **controller** in `api/` receives the request and checks the
role; a **service** in `service/` applies the business rules; **domain** classes in `domain/` hold the data and the small
rules (wallet balance, split, refund sharing, ledger balance); **repositories** in `repository/` read and write the
database; **adapters** in `adapter/` talk to the outside world (the gateways).

### Flow A: wallet top-up (`POST /api/v1/wallets/me/top-ups`)

1. `CardDataGuardAdvice` looks at the raw JSON before anything else. A field like `cardNumber`, `cvv`, `expiry`, or any
   text that is a real-looking card number, stops the request with `400 CARD_DATA_NOT_ALLOWED`.
2. `WalletController` checks the role is TRAVELLER and takes the user id from the login token (a traveller can only top
   up their own wallet).
3. `WalletService.topUp` checks the amount (at most `max-top-up-paise`) and that the token starts with `tok_`.
4. If an `Idempotency-Key` was sent and was seen before, it returns the earlier result (HTTP 200).
5. It asks the gateway (`PaymentGateway.charge`, in the demo `StubRazorpayGateway`) to take the money. If the gateway
   declines: `402 TOP_UP_DECLINED`, nothing changes.
6. In **one database transaction**: the wallet balance goes up (`Wallet.credit`), a history line is saved
   (`WalletTransaction`), and the ledger gets two lines (`LedgerService.post`). Either all three are saved, or none.
7. An audit event is published and `201` is returned.

### Flow B: trip payment (`POST /api/v1/payments`, INT-11)

1. `PaymentController` checks the role is SERVICE (only APP-06 may charge) and validates the body (`@Valid`).
2. `PaymentService.charge` checks the `Idempotency-Key` header is there (`400 IDEMPOTENCY_KEY_REQUIRED`) and that the
   splits add up to `amountPaise` (`400 SPLIT_MISMATCH`).
3. It looks the key up. **Seen before?** It returns the stored payment with HTTP 200 and the very same body. (If the same
   key comes with a different request: `409 IDEMPOTENCY_KEY_REUSED`.)
4. Otherwise, in one transaction (`chargeInTransaction`):
   - If this booking already has a captured payment (under another key): `409 BOOKING_ALREADY_PAID`.
   - For each split, `CommissionPolicy.strategyFor(providerId)` picks the Strategy, which works out the commission.
     The split stores gross, commission and net (net = gross − commission).
   - **WALLET:** the wallet is loaded (or created empty). Enough money → `Wallet.debit`, status `CAPTURED`. Not
     enough → status `FAILED`, `failureReason` `INSUFFICIENT_BALANCE`, and no money moves.
   - **GATEWAY:** `PaymentGateway.charge` with the traveller's saved token. Approved → `CAPTURED` with the gateway's
     reference (`pay_stub_0005`); declined → `FAILED`, `GATEWAY_DECLINED`.
   - The payment is saved. The database has a **unique** rule on the idempotency key, so two identical requests
     arriving at the same moment can never both be saved; the loser returns the winner's answer.
   - If captured: a wallet history line (WALLET only) and the ledger lines are written.
5. After the transaction has committed, `PaymentEvents` publishes `PAYMENT_CAPTURED` or `PAYMENT_FAILED` (APP-06 listens)
   and an `AUDIT` event. The answer is `201` (also for FAILED: the request was handled, the charge just did not succeed).

### Flow C: refund (`POST /api/v1/payments/{paymentId}/refunds`, INT-11)

1. Role SERVICE (APP-06) or SUPPORT. Same idempotency rule: a known key returns the first refund with 200.
2. `RefundService` loads the payment (`404 PAYMENT_NOT_FOUND`); it must be CAPTURED (`409 PAYMENT_NOT_CAPTURED`); the
   amount may not be more than what is left to refund (`400 REFUND_EXCEEDS_PAYMENT`).
3. `Payment.applyRefund` shares the refund over the providers **in proportion** to their part of the payment, and works
   out how much of each part was commission. The last refund takes exactly what is left, so not a paisa is lost.
4. The money goes back the way it came: into the wallet (`Wallet.credit` + history line) or to the card
   (`PaymentGateway.refund`). The ledger gets the reversing lines. Then `PAYMENT_REFUNDED` is published.

### Flow D: payout run (`POST /api/v1/payouts/run`, and daily at 23:30 UTC)

The job follows the **Template Method** `PayoutJobTemplate.run`: collect → total → transfer → record.

1. **Collect** (`ProviderPayoutJob.collect`): asks the ledger how much is owed to each provider (credits minus debits of
   `PROVIDER_PAYABLE:<id>`). Only positive amounts are paid.
2. **Total**: adds them up (the run's `totalPaise`).
3. **Transfer**: `PayoutGateway.transfer` to the provider's linked account (`StubRazorpayRouteGateway`). A provider
   without a linked account gets `FAILED / NO_LINKED_ACCOUNT` and stays owed until the next run.
4. **Record**: saves a `Payout`; if paid, the ledger moves the money from `PROVIDER_PAYABLE` to `PROVIDER_SETTLED`.

`FinanceScheduler` runs the job every day (`mmts.payment.schedule.payout-cron`). Tests switch the scheduler off.

### Flow E: settlement file (`POST /api/v1/settlements/export`, and daily at 23:45 UTC)

`SettlementService.export(date)` loads that day's captured payments, `SettlementCsv.summarise` adds them up per provider,
and the file `exports/settlement-YYYY-MM-DD.csv` is written in the exact INT-20 format. Writing the same day again
simply replaces the file.

### Flow F: gateway webhook (`POST /api/v1/webhooks/razorpay`)

1. The request has no MMTS login token (Razorpay cannot have one), so `PaymentConfig` lets `/api/v1/webhooks/**` past
   the token check.
2. `WebhookService.handle` computes HMAC-SHA256 of the exact body with the secret from `application.yml` and compares
   it with the `X-Razorpay-Signature` header (in constant time, so nothing leaks through timing). Wrong or missing:
   `401 INVALID_SIGNATURE` and an audit event with outcome `REJECTED`.
3. A valid `payment.captured` event marks the matching payment as confirmed by the gateway (`gatewayConfirmedAt`).

### Worked example: the ledger lines for one top-up, one trip, one refund and one payout

These are the real lines produced by the demo script in section 7 (balances in paise). Remember: in every movement,
the debits add up to the same number as the credits.

**1. Top-up of Rs 200 by u-101 (reference `TOPUP:wt-4`).** The card money arrives at the gateway, and we now hold it for
u-101.

| Account | Debit | Credit |
|---|---|---|
| GATEWAY_CLEARING | 20000 | |
| USER_WALLET:u-101 | | 20000 |

**2. Trip payment p-9001 of Rs 185 from the wallet, two providers (reference `PAYMENT:p-9001`).** DMRC's leg costs 4000
(commission 5% = 200), ZipRide's scooter leg costs 14500 (flat commission 500).

| Account | Debit | Credit |
|---|---|---|
| USER_WALLET:u-101 | 18500 | |
| PROVIDER_PAYABLE:DMRC | | 3800 |
| PROVIDER_PAYABLE:ZIPRIDE | | 14000 |
| PLATFORM_REVENUE | | 700 |

Debits 18500 = credits 3800 + 14000 + 700 = 18500.

**3. Partial refund r-70 of Rs 40 (reference `REFUND:r-70`).** The 4000 is shared in proportion: DMRC's part is 865
(of which 43 was commission), ZipRide's part is 3135 (of which 108 was commission). Each side gives its part back.

| Account | Debit | Credit |
|---|---|---|
| PROVIDER_PAYABLE:DMRC | 822 | |
| PROVIDER_PAYABLE:ZIPRIDE | 3027 | |
| PLATFORM_REVENUE | 151 | |
| USER_WALLET:u-101 | | 4000 |

**4. Payout po-2 to DMRC (reference `PAYOUT:po-2`).** DMRC is now owed 3800 − 822 = 2978. The transfer is sent and the
money moves from "owed" to "settled".

| Account | Debit | Credit |
|---|---|---|
| PROVIDER_PAYABLE:DMRC | 2978 | |
| PROVIDER_SETTLED:DMRC | | 2978 |

After all of this, u-101's wallet shows 55500 (50000 demo + 20000 − 18500 + 4000), and the ledger account
`USER_WALLET:u-101` says exactly the same: the trial balance reports `walletsReconciled: true`.

## 3. Design decisions and why

| Decision | Why | Alternative we rejected |
|---|---|---|
| Money as `long` paise everywhere | Whole numbers are exact; the contract also says paise | `double` rupees (rounding errors), `BigDecimal` (exact but heavier and easy to misuse) |
| Unique database column on the idempotency key, plus "look up the key first" | The look-up gives a fast replay; the unique rule makes it safe even when two retries arrive together | Only an in-memory check (fails with two copies of the service), or a lock around the whole call (slow) |
| The stored payment's `status` never changes after the charge; refunds are counted separately | A repeat of the same request must get the *same* body, even after a refund | Changing status to REFUNDED (the replayed body would then differ) |
| Double-entry ledger with append-only `LedgerEntry` (no setters, `@PreUpdate` / `@PreRemove` refuse, repository has no delete) | Driver D8: the books can always be checked and history cannot be rewritten; corrections are new entries | One "balance" column per provider (no history, cannot prove anything) |
| `LedgerTransaction.toEntries` refuses unbalanced movements | A mistake in code can never put unbalanced lines into the books | Checking only at report time (the damage would already be done) |
| Payouts are worked out from the ledger (`PROVIDER_PAYABLE` balance) | Refunds after a payout are handled automatically: they lower the next payout | Summing unpaid payment splits (must be adjusted by hand for every refund) |
| Wallet change, history line and ledger lines in one database transaction | All-or-nothing: the wallet can never disagree with the books | Separate saves (a crash in between would leave them out of step) |
| Optimistic locking (`@Version`) on wallet and payment, with up to 3 retries | Clashes are rare, so we do not slow every request with a database lock; a clash is detected and retried | Pessimistic `SELECT ... FOR UPDATE` locks (simpler to reason about, but block other requests) |
| Card fields refused by a request-body guard before the JSON becomes a Java object | Driver D9: card data never enters the service, not even in a log; the caller gets a clear error | Ignoring unknown fields (Spring's default: the caller would never notice) |
| Commission rule per provider **type**, values in `application.yml` | Changing a rate is a settings change (Externalization); a new rule is a new Strategy class | `if (type == SCOOTER) ...` scattered in the payment code |
| Events published only after the database transaction has committed | APP-06 never hears "captured" for a payment that was then rolled back | Publishing inside the transaction |
| Webhook path has its own small security chain, protected by HMAC instead of a JWT | The gateway cannot hold an MMTS login token; HMAC is how Razorpay proves it is Razorpay | Leaving the webhook open (anyone could fake a "captured" message) |

## 4. Architecture drivers demonstrated

| Driver | How the code meets it | How to see it in the demo |
|---|---|---|
| **D8 Charged once, split correctly, books balance** | Idempotency key (unique column + replay); `SPLIT_MISMATCH` check; `BOOKING_ALREADY_PAID`; commission Strategy with net = gross − commission; refund limit; double-entry ledger that refuses unbalanced movements and cannot be edited; trial balance with zero difference and wallet reconciliation; settlement totals = sum of captured payments | Demo steps 5–6 (same body, 201 then 200), 7 (split mismatch), 9–11 (ledger lines, refund, limit), 15 (trial balance difference 0), 17–18 (payout and CSV totals) |
| **D9 Card-payment rules, no raw card data** | Only tokens (`tok_...`) and gateway references (`pay_stub_...`) are stored; `CardDataGuardAdvice` + `CardDataPolicy` refuse card fields and card-like numbers anywhere in a body; all payment logic is in this one service behind the `PaymentGateway` adapter; webhooks must carry a valid HMAC | Demo step 4 (`CARD_DATA_NOT_ALLOWED`), step 14 (webhook accepted with signature, refused without). Open the H2 console: no table has a card column. |

**D9 in production.** Because payment logic lives only in APP-07, only this service (and its database DB-4) sits in the
**isolated payment network zone** described in the architecture document: separate subnet, strict firewall rules, only
APP-06 and the gateway may reach it. Card numbers are entered only on Razorpay's page, so MMTS keeps a small PCI DSS
scope (the "SAQ A" level for merchants who never see card data). The **RBI** (Reserve Bank of India) requires all
payment data of Indian payments to be **stored only in India**, so the production database, backups and logs of this
service live in an Indian cloud region.

## 5. Design patterns used

| Pattern | Where in the code | Why here |
|---|---|---|
| **Adapter** | `adapter/PaymentGateway` with `StubRazorpayGateway`; `adapter/PayoutGateway` with `StubRazorpayRouteGateway` | Like a travel plug: business code talks to one fixed interface; swapping the stub for real Razorpay (Orders, Checkout, webhooks; Route transfers) touches no business code (principle P3) |
| **Strategy** | `domain/CommissionStrategy`, `PercentageCommission`, `FlatFeeCommission`, chosen by `CommissionPolicy` | Different providers are paid with different commission rules; each rule is its own small class; adding one never changes `PaymentService` |
| **Template Method** | `service/PayoutJobTemplate.run` (final: collect → total → transfer → record) and `ProviderPayoutJob` | Every payout run must do the same steps in the same order; subclasses fill in the steps but cannot skip or reorder them |
| **Optimistic locking** | `@Version` on `Wallet` and `Payment`; `OptimisticRetry` | Two simultaneous wallet changes cannot both win; the loser is retried on fresh data |
| **Observer / events** | `PaymentEvents` publishes `payment.events` and `audit.events`; `CommissionPolicy.onPricingRules` listens to `pricing.rules` | Like a YouTube subscription: we announce "payment captured" and do not need to know who listens (APP-06, APP-09) |
| **Repository (DAO)** | `repository/*` (Spring Data JPA); `LedgerEntryRepository` deliberately has no delete | Database code is kept out of the business rules |

## 6. Endpoints in detail

All bodies are JSON; money in paise; times in UTC. Errors use the shared format
`{ "code", "message", "details", "timestamp" }`. Every `/api/v1/**` endpoint (except the webhook) needs
`Authorization: Bearer <token>`; with no token you get `401 UNAUTHORIZED`, with the wrong role `403 FORBIDDEN`.

### 6.1 `GET /api/v1/wallets/me` (TRAVELLER)

Response `200` (wallet created with balance 0 if it did not exist):

```json
{ "userId": "u-101", "balancePaise": 50000, "currency": "INR", "updatedAt": "2026-11-15T16:31:25Z" }
```

### 6.2 `POST /api/v1/wallets/me/top-ups` (TRAVELLER)

Optional header `Idempotency-Key`. Request:

```json
{ "amountPaise": 20000, "paymentToken": "tok_demo_visa" }
```

Response `201` (or `200` with the same body for a repeated key):

```json
{ "transactionId": "wt-4", "type": "TOP_UP", "amountPaise": 20000, "balanceAfterPaise": 70000,
  "reference": "pay_stub_0004", "createdAt": "2026-11-15T16:31:25Z" }
```

Errors: `400 CARD_DATA_NOT_ALLOWED` (card fields), `400 INVALID_PAYMENT_TOKEN` (not a `tok_` token),
`400 TOP_UP_LIMIT_EXCEEDED` (more than 1000000), `400 VALIDATION_FAILED`, `402 TOP_UP_DECLINED` (gateway said no),
`409 IDEMPOTENCY_KEY_REUSED`, `409 CONCURRENT_UPDATE`.

### 6.3 `GET /api/v1/wallets/me/transactions` (TRAVELLER)

Response `200`, newest first, at most 50:

```json
[ { "transactionId": "wt-6", "type": "REFUND", "amountPaise": 4000, "balanceAfterPaise": 55500,
    "reference": "r-70", "createdAt": "2026-11-15T16:31:25Z" },
  { "transactionId": "wt-5", "type": "PAYMENT", "amountPaise": 18500, "balanceAfterPaise": 51500,
    "reference": "p-9001", "createdAt": "2026-11-15T16:31:25Z" } ]
```

### 6.4 `GET /api/v1/wallets/{userId}` and `GET /api/v1/wallets/{userId}/transactions` (FINANCE, ADMIN, SUPPORT)

Same bodies as 6.1 and 6.3 for any user. Unknown wallet: `404 WALLET_NOT_FOUND`. A traveller gets `403`.

### 6.5 `POST /api/v1/payments` (SERVICE) — INT-11 charge

Header `Idempotency-Key: b-5001-pay`. Request (exactly as in `CONTRACTS.md`):

```json
{ "bookingId": "b-5001", "userId": "u-101", "amountPaise": 18500, "method": "WALLET",
  "splits": [ { "providerId": "DMRC", "amountPaise": 4000 }, { "providerId": "ZIPRIDE", "amountPaise": 14500 } ] }
```

Response `201` (`200` with the identical body when the same key is sent again):

```json
{ "paymentId": "p-9001", "bookingId": "b-5001", "status": "CAPTURED", "amountPaise": 18500,
  "failureReason": null, "createdAt": "2026-11-15T16:31:25Z" }
```

A failed charge is also `201`, with `"status": "FAILED"` and `"failureReason": "INSUFFICIENT_BALANCE"` or
`"GATEWAY_DECLINED"`. Errors: `400 IDEMPOTENCY_KEY_REQUIRED`, `400 SPLIT_MISMATCH`, `400 VALIDATION_FAILED`,
`400 CARD_DATA_NOT_ALLOWED`, `409 IDEMPOTENCY_KEY_REUSED` (same key, different request), `409 BOOKING_ALREADY_PAID`,
`409 CONCURRENT_UPDATE`. Events: `PAYMENT_CAPTURED` / `PAYMENT_FAILED` on `payment.events`, `AUDIT` on `audit.events`.

### 6.6 `GET /api/v1/payments/{paymentId}` (SERVICE, ADMIN, FINANCE)

Response `200`:

```json
{ "paymentId": "p-9004", "bookingId": "b-5005", "userId": "u-103", "method": "GATEWAY", "status": "CAPTURED",
  "amountPaise": 6000, "refundedPaise": 0, "failureReason": null, "gatewayReference": "pay_stub_0005",
  "gatewayConfirmedAt": null, "createdAt": "2026-11-15T16:31:26Z",
  "splits": [
    { "providerId": "DTC", "amountPaise": 2000, "commissionPaise": 100, "netPaise": 1900,
      "commissionRule": "PERCENTAGE 5.00%", "refundedPaise": 0 },
    { "providerId": "CITYCABS", "amountPaise": 4000, "commissionPaise": 200, "netPaise": 3800,
      "commissionRule": "PERCENTAGE 5.00%", "refundedPaise": 0 } ] }
```

Error: `404 PAYMENT_NOT_FOUND`.

### 6.7 `POST /api/v1/payments/{paymentId}/refunds` (SERVICE, SUPPORT) — INT-11 refund

Header `Idempotency-Key: b-5001-refund`. Request: `{ "amountPaise": 4000, "reason": "LEG_CANCELLED" }`
(`reason` is optional). Response `201` (`200` with the same body for a repeated key):

```json
{ "refundId": "r-70", "paymentId": "p-9001", "status": "REFUNDED", "amountPaise": 4000 }
```

Errors: `400 REFUND_EXCEEDS_PAYMENT`, `400 IDEMPOTENCY_KEY_REQUIRED`, `404 PAYMENT_NOT_FOUND`,
`409 PAYMENT_NOT_CAPTURED`, `409 IDEMPOTENCY_KEY_REUSED`. Event: `PAYMENT_REFUNDED`.

### 6.8 `GET /api/v1/ledger/trial-balance` (FINANCE, ADMIN)

Response `200` (`balancePaise` = credits − debits, so money the platform holds or owes is positive, and the
gateway clearing account, which is money *received*, shows as negative):

```json
{ "totalDebitsPaise": 303500, "totalCreditsPaise": 303500, "differencePaise": 0, "balanced": true,
  "entryCount": 20, "walletBalancesPaise": 260500, "walletLedgerPaise": 260500, "walletsReconciled": true,
  "accounts": [ { "account": "GATEWAY_CLEARING", "debitsPaise": 281000, "creditsPaise": 0, "balancePaise": -281000 },
                { "account": "PLATFORM_REVENUE", "debitsPaise": 151, "creditsPaise": 1000, "balancePaise": 849 } ] }
```

### 6.9 `GET /api/v1/ledger/entries?reference=PAYMENT:p-9001` (FINANCE, ADMIN)

The lines of one movement (without `reference`: the latest 100). Response `200`:

```json
[ { "entryId": 9, "reference": "PAYMENT:p-9001", "account": "USER_WALLET:u-101", "direction": "DEBIT",
    "amountPaise": 18500, "description": "Trip payment for booking b-5001", "createdAt": "2026-11-15T16:31:25Z" } ]
```

### 6.10 `GET /api/v1/commission-rules` (FINANCE, ADMIN)

```json
{ "percentageBasisPoints": 500, "flatFeePaise": 500, "defaultRule": "PERCENTAGE",
  "ruleByProviderType": { "METRO": "PERCENTAGE", "SCOOTER": "FLAT_FEE", "...": "..." },
  "providers": [ { "providerId": "ZIPRIDE", "name": "ZipRide Scooters", "providerType": "SCOOTER",
                   "rule": "FLAT_FEE 500 paise", "linkedAccountId": "acc_ZIPRIDE01" } ] }
```

### 6.11 `POST /api/v1/payouts/run` and `GET /api/v1/payouts` (FINANCE, ADMIN)

Run response `200`:

```json
{ "runId": "run-5ef14976", "trigger": "MANUAL", "ranAt": "2026-11-15T16:31:27Z", "providerCount": 4,
  "totalPaise": 19651, "paidPaise": 19651,
  "payouts": [ { "payoutId": "po-2", "runId": "run-5ef14976", "providerId": "DMRC", "amountPaise": 2978,
                 "status": "PAID", "transferReference": "trf_stub_0002", "failureReason": null,
                 "createdAt": "2026-11-15T16:31:27Z" } ] }
```

`GET /api/v1/payouts` lists the latest 100 payouts in the same shape as one item of `payouts`.

### 6.12 `POST /api/v1/settlements/export?date=2026-11-15` (FINANCE, ADMIN) — INT-20

`date` is optional (default: today, UTC). Writes `exports/settlement-2026-11-15.csv` and returns:

```json
{ "settlementDate": "2026-11-15", "file": "exports/settlement-2026-11-15.csv", "totalGrossPaise": 24500,
  "totalCommissionPaise": 1000, "totalNetPaise": 23500, "rows": [ "..." ],
  "csv": "settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount\n2026-11-15,CITYCABS,4000,200,3800,1\n..." }
```

Error: `400 BAD_REQUEST` for a date that is not `YYYY-MM-DD`; `500 EXPORT_FAILED` if the file cannot be written.

### 6.13 `POST /api/v1/webhooks/razorpay` (no token; HMAC signature)

Header `X-Razorpay-Signature: <hex HMAC-SHA256 of the body>`. Request:

```json
{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_stub_0005","amount":6000}}}}
```

Response `200`: `{ "received": true, "event": "payment.captured", "matchedPaymentId": "p-9004" }`.
Errors: `401 INVALID_SIGNATURE` (wrong or missing), `400 WEBHOOK_BODY_INVALID` (signed but not JSON).

### 6.14 Demo helpers (demo profile only, no token)

- `POST /dev/gateway/decline-next` → `{ "declineNextCharge": true }`: the next gateway charge (payment or top-up) fails.
- `POST /dev/webhooks/sign` with any text body → `{ "signature": "08d7..." }`: what Razorpay would send for that body.
- Shared: `POST /dev/token` (test login token) and `GET /dev/events` (published events).

## 7. Demo script

Start with `mvn spring-boot:run` in `app-07-payment` and open http://localhost:8087/swagger-ui.html. The ids below are
what a fresh start gives (the demo data uses wt-1 to wt-3 and pay_stub_0001 to 0003).

1. **Get three tokens** with `POST /dev/token`:
   `{"userId":"u-101","roles":["TRAVELLER"]}`, `{"userId":"app-06","roles":["SERVICE"]}`,
   `{"userId":"f-1","roles":["FINANCE"]}`. Copy each `token`; press **Authorize** and paste the one the step needs.
2. *(TRAVELLER)* `GET /api/v1/wallets/me` → balance 50000. Point out: demo data, created through normal top-ups.
3. *(TRAVELLER)* `POST /api/v1/wallets/me/top-ups`, Idempotency-Key `u-101-topup-1`, body
   `{"amountPaise":20000,"paymentToken":"tok_demo_visa"}` → 201, `wt-4`, balance after 70000,
   reference `pay_stub_0004`. Point out: only a token and the gateway's reference are stored.
4. *(TRAVELLER)* Same endpoint, body
   `{"amountPaise":20000,"paymentToken":"tok_demo_visa","cardNumber":"4111111111111111","cvv":"123"}` →
   `400 CARD_DATA_NOT_ALLOWED` naming `$.cardNumber, $.cvv`. **Driver D9.**
5. *(SERVICE)* `POST /api/v1/payments`, Idempotency-Key `b-5001-pay`, body
   `{"bookingId":"b-5001","userId":"u-101","amountPaise":18500,"method":"WALLET","splits":[{"providerId":"DMRC","amountPaise":4000},{"providerId":"ZIPRIDE","amountPaise":14500}]}`
   → **201**, `p-9001`, CAPTURED.
6. Press **Execute** again with the same key → **200** with exactly the same body. Point out: a retry never charges twice
   (**D8**). Wallet is 51500, not 33000.
7. *(SERVICE)* Idempotency-Key `b-5002-pay`, body
   `{"bookingId":"b-5002","userId":"u-101","amountPaise":18000,"method":"WALLET","splits":[{"providerId":"DMRC","amountPaise":4000},{"providerId":"ZIPRIDE","amountPaise":14500}]}`
   → `400 SPLIT_MISMATCH`.
8. *(SERVICE)* Idempotency-Key `b-5003-pay`, body
   `{"bookingId":"b-5003","userId":"u-102","amountPaise":18500,"method":"WALLET","splits":[{"providerId":"DMRC","amountPaise":4000},{"providerId":"ZIPRIDE","amountPaise":14500}]}`
   → 201 with `"status":"FAILED","failureReason":"INSUFFICIENT_BALANCE"` (`p-9002`; u-102 has only 5000).
9. *(FINANCE)* `GET /api/v1/ledger/entries?reference=PAYMENT:p-9001` → the 4 lines of the worked example
   (debit 18500 = credits 3800 + 14000 + 700). Point out the commission Strategy: 5% for metro, flat 500 for scooter.
10. *(SERVICE)* `POST /api/v1/payments/p-9001/refunds`, Idempotency-Key `b-5001-refund`, body
    `{"amountPaise":4000,"reason":"LEG_CANCELLED"}` → 201 `r-70`. Execute again → 200, same body.
    Then *(FINANCE)* `GET /api/v1/ledger/entries?reference=REFUND:r-70` → 822 + 3027 + 151 = 4000.
11. *(SERVICE)* Same refund endpoint, Idempotency-Key `b-5001-refund-2`, body
    `{"amountPaise":20000,"reason":"TOO_MUCH"}` → `400 REFUND_EXCEEDS_PAYMENT` ("At most 14500 paise").
12. `POST /dev/gateway/decline-next`, then *(SERVICE)* `POST /api/v1/payments`, Idempotency-Key `b-5004-pay`, body
    `{"bookingId":"b-5004","userId":"u-103","amountPaise":6000,"method":"GATEWAY","splits":[{"providerId":"DTC","amountPaise":2000},{"providerId":"CITYCABS","amountPaise":4000}]}`
    → 201 FAILED, `GATEWAY_DECLINED` (`p-9003`).
13. Same body with `b-5005` in place of `b-5004` (in the body and the key) → CAPTURED `p-9004`. *(FINANCE)*
    `GET /api/v1/payments/p-9004` → `gatewayReference` `pay_stub_0005`, `gatewayConfirmedAt` null.
14. `POST /dev/webhooks/sign` with the body
    `{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_stub_0005","amount":6000}}}}` → copy
    `signature`. `POST /api/v1/webhooks/razorpay` with exactly the same body and header `X-Razorpay-Signature` →
    200, `matchedPaymentId` `p-9004`. Change one character of the signature → `401 INVALID_SIGNATURE`.
15. *(FINANCE)* `GET /api/v1/ledger/trial-balance` → `differencePaise: 0`, `balanced: true`,
    `walletsReconciled: true` (wallets 260500 = ledger 260500). **Driver D8.**
16. *(FINANCE)* `GET /api/v1/commission-rules` → each provider and its Strategy.
17. *(FINANCE)* `POST /api/v1/payouts/run` → 4 payouts, total 19651: CITYCABS 3800, DMRC 2978, DTC 1900,
    ZIPRIDE 10973 (DMRC and ZipRide are lower because of the refund). Point out the Template Method steps.
    Run the trial balance again: still difference 0.
18. *(FINANCE)* `POST /api/v1/settlements/export` → the CSV text and the file `exports/settlement-<today>.csv`:
    `CITYCABS,4000,200,3800,1`, `DMRC,4000,200,3800,1`, `DTC,2000,100,1900,1`, `ZIPRIDE,14500,500,14000,1`.
19. `GET /dev/events` → `PAYMENT_CAPTURED`, `PAYMENT_FAILED`, `PAYMENT_REFUNDED` and `AUDIT` events (no card data, no
    personal data).
20. *(TRAVELLER)* `GET /api/v1/ledger/trial-balance` → `403 FORBIDDEN` (role check).

## 8. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
| Razorpay payments (cards, UPI) | `StubRazorpayGateway` behind `PaymentGateway`: approves everything except `tok_decline` or after `/dev/gateway/decline-next`; ids `pay_stub_0001` | Razorpay Orders API + Checkout on the client; capture confirmed by a signed `payment.captured` webhook; refunds through the Refunds API |
| Razorpay Route (provider payouts) | `StubRazorpayRouteGateway` behind `PayoutGateway`: succeeds when the provider has a linked account; ids `trf_stub_0001` | Route transfers to each provider's linked account |
| Gateway webhook sender | You (Swagger) with a signature from `/dev/webhooks/sign` | Razorpay sends the webhook with `X-Razorpay-Signature` |
| APP-06 Booking (caller of INT-11) | Requests sent from Swagger / tests with a SERVICE token | APP-06 calls `POST /api/v1/payments` and listens to `payment.events` |
| APP-09 Analytics (pricing rules, settlement file, audit) | `pricing.rules` is never published in the demo (defaults from `application.yml`; a test publishes one); the CSV is just written to `exports/` | APP-09 publishes `PRICING_RULES_UPDATED`, reads the daily file (INT-20) and consumes `audit.events` |
| Kafka | Shared in-process event bus (`GET /dev/events`) | Kafka topics `payment.events`, `audit.events`, `pricing.rules` |
| PostgreSQL DB-4 | H2 in PostgreSQL mode (`postgres` profile uses real PostgreSQL, database `mmts_payment`) | PostgreSQL in the isolated payment zone, hosted in India |

## 9. Tests

`mvn test` runs **69 tests, all passing**: 60 written for this module plus the 9 shared tests, which are explained in
`../SHARED-SETUP.md`, section 8. Spring tests use new user and booking ids each time, so they never disturb each other.
The daily scheduler is switched off in tests (`src/test/resources/config/application.properties`), and test
settlement files go to `target/test-exports`.

**Unit tests (no Spring, run in milliseconds)**

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `CommissionStrategyTest` | `percentageTakesItsShareInWholePaise` | 5% of 4000 = 200, of 400000 = 20000; 200.5 rounds up, 200.45 down | M4.4 Strategy, money in whole paise |
| | `flatFeeIsTheSameForEveryRideButNeverMoreThanTheRide` | Flat fee 500 on any ride; a 300-paise ride pays 300 | M4.4 Strategy |
| | `invalidRulesAndAmountsAreRefused` | More than 100%, negative fee or negative amount are refused | Input safety |
| `LedgerTransactionTest` | `balancedTripPaymentBecomesEntries` | The contract trip gives 4 lines with debits = credits = 18500 | D8 double entry |
| | `unbalancedMovementIsRefused` | Debits 18500 vs credits 18000 → refused before saving | D8 books always balance |
| | `emptyOrNegativeMovementsAreRefusedAndZeroLinesSkipped` | No lines or a negative amount are refused; 0-paise lines are left out | D8 |
| `RefundAllocationTest` | `fullRefundGivesBackEverySplitExactly` | Full refund: DMRC 3800 + 200, ZipRide 14000 + 500 | M4.3 refund split |
| | `partialRefundsAddUpToTheRefundAndThenToTheWholePayment` | Two partial refunds (10001 + 8499) give back exactly every net and commission paisa | D8 no paisa lost |
| | `refundAboveWhatIsLeftIsRefused` | After 18000 refunded, 501 more is refused | M4.3 limit |
| `CardDataPolicyTest` | `cardFieldsAreFoundWhateverTheirSpelling` | `cardNumber`, `CVV`, `expiry_month`, `card-expiry` (even nested) are found | D9 |
| | `aRealLookingCardNumberIsFoundEvenInAnInnocentField` | A Luhn-valid card number in `paymentToken` or `providerId` is found | D9 |
| | `normalPaymentBodiesAndTokensPass` | A normal INT-11 body and `tok_...` tokens pass; non-Luhn and short numbers pass | D9 without false alarms |
| `SettlementCsvTest` | `rowsAreAddedUpPerProviderAndWrittenInTheAgreedFormat` | Exact CSV text: header, one row per provider sorted, two legs of one payment counted once | INT-20 format |
| | `aDayWithoutPaymentsHasOnlyTheHeader` | Empty day = header only | INT-20 |
| `WebhookSignatureTest` | `signatureMatchesTheStandardHmacSha256TestValue` | Our HMAC equals the well-known HMAC-SHA256 test value | Webhook security |
| | `onlyTheRightSignatureForTheExactBodyIsValid` | Right signature valid (any hex case); changed body, other secret, missing or empty signature invalid | Webhook security, D9 |
| `OptimisticRetryTest` | `aConflictIsRetriedAndTheSecondTryWins` | One lock conflict, then success on the second try | Optimistic locking |
| | `afterThreeConflictsTheCallerGets409` | Three conflicts → `409 CONCURRENT_UPDATE` | Optimistic locking |

**Service tests (whole application with H2)**

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `PaymentFlowTest` | `walletPaymentDebitsWalletAndSplitsByCommissionRule` | Wallet 50000 → 31500; splits 200/3800 and 500/14000; the 4 ledger lines | M4.2, M4.4, D8 |
| | `insufficientBalanceGivesFailedAndMovesNoMoney` | FAILED + INSUFFICIENT_BALANCE, wallet unchanged, no ledger lines, `PAYMENT_FAILED` event | M4.2 |
| | `walletIsCreatedOnFirstUse` | A user with no wallet gets one (balance 0) | M4.1 |
| | `sameIdempotencyKeyNeverChargesTwiceAndGivesTheSameBody` | Second call: not created, equal body, wallet debited once, still 4 ledger lines | D8 charged once |
| | `sameKeyForADifferentRequestIsRefused` | Same key, other amount → `IDEMPOTENCY_KEY_REUSED` | D8 |
| | `splitsMustAddUpToTheAmount` | Splits 18000 vs 18500 → `SPLIT_MISMATCH`, wallet untouched | D8 split correct |
| | `aBookingCanBeCapturedOnlyOnceEvenWithAnotherKey` | Second key for a paid booking → `BOOKING_ALREADY_PAID` | D8 |
| | `gatewayPaymentIsCapturedOrDeclined` | Declined → FAILED / GATEWAY_DECLINED; approved → CAPTURED with `pay_stub_` ref and a GATEWAY_CLEARING debit | M4.2 GATEWAY, Adapter |
| | `refundsCannotExceedTheCapturedAmount` | 10000 OK, 9000 more refused, 8500 OK; wallet back to 50000; books balance | M4.3, D8 |
| | `sameRefundKeyRefundsOnlyOnce` | Repeat refund: same body, wallet credited once | M4.3 idempotent |
| | `failedOrUnknownPaymentsCannotBeRefunded` | `PAYMENT_NOT_CAPTURED` and `PAYMENT_NOT_FOUND` | M4.3 |
| | `pricingRulesEventChangesThePercentageCommission` | A `PRICING_RULES_UPDATED` event with 10% makes DMRC's commission 400; flat fee unchanged; rate restored after | M4.4, Observer |
| | `ledgerIsBalancedAfterEveryKindOfMoneyMovement` | Trial balance 0 and wallets reconciled after top-up, wallet payment, gateway payment, partial refund, full gateway refund and payout | D8 books balance |
| `FinanceFlowTest` | `payoutRunPaysEachProviderExactlyWhatTheLedgerSaysItIsOwed` | Run total = sum owed; DMRC paid its exact balance with a `trf_stub_` ref; nothing owed afterwards | M4.5 Template Method, D8 |
| | `providerWithoutLinkedAccountIsNotPaidAndStaysOwed` | Unknown provider → FAILED / NO_LINKED_ACCOUNT, still owed 9500 | M4.5 |
| | `settlementFileAddsUpTodaysCapturedPayments` | File name, header, row `…,16000,800,15200,2`, commission + net = gross, file total = today's captured payments | INT-20, D8 settlement totals |
| | `ledgerEntriesCannotBeChangedOrDeleted` | Changing or deleting a saved entry fails with "append-only"; the repository has no delete/update method | D8 append-only ledger |
| | `twoChangesFromTheSameOldWalletCopyCannotBothBeSaved` | Two copies of one wallet: the second save is refused, only the first change counts | M4.1 optimistic locking |

**Endpoint (contract) tests with MockMvc**

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `PaymentApiTest` | `chargeAnswers201WithExactlyTheContractFields` | 201; exactly `paymentId, bookingId, status, amountPaise, failureReason, createdAt` in that order; `failureReason` null; ISO time | INT-11 contract |
| | `repeatWithTheSameKeyAnswers200WithTheSameBody` | 201 then 200 with a byte-identical body; wallet debited once | INT-11, D8 |
| | `insufficientBalanceIs201WithStatusFailed` | 201 with FAILED / INSUFFICIENT_BALANCE | INT-11 |
| | `chargeErrorsUseTheAgreedCodes` | No key → `IDEMPOTENCY_KEY_REQUIRED`; `SPLIT_MISMATCH`; `VALIDATION_FAILED` with 3 details; `CARD_DATA_NOT_ALLOWED` | INT-11 errors, D9 |
| | `onlyServicesMayCharge` | No token 401; TRAVELLER 403 `FORBIDDEN` | RBAC |
| | `capturedPaymentIsPublishedOnPaymentEvents` | `PAYMENT_CAPTURED` with `paymentId`, `amountPaise`; `AUDIT` with `PAYMENT_CHARGE` | INT-17, INT-19 |
| | `refundAnswers201ThenRepeats200AndRespectsTheLimit` | Exactly `refundId, paymentId, status, amountPaise`; REFUNDED; repeat 200 same body; extra paisa → `REFUND_EXCEEDS_PAYMENT`; `PAYMENT_REFUNDED` event | INT-11 refund, D8 |
| | `refundErrorsAndRoles` | `PAYMENT_NOT_FOUND`; TRAVELLER 403; SUPPORT allowed but FAILED payment → `PAYMENT_NOT_CAPTURED` | INT-11, RBAC |
| | `paymentDetailIsReadableByFinanceButNotByTravellers` | FINANCE sees splits and commission rules; TRAVELLER 403; ADMIN 404 for an unknown id | RBAC |
| `WalletApiTest` | `walletIsCreatedOnFirstUseWithZeroBalance` | `GET /me` for a new user → 0, INR | INT-05, M4.1 |
| | `topUpAddsMoneyAndAppearsInHistory` | 201 TOP_UP, `pay_stub_` reference, balance and history updated | INT-05 |
| | `topUpWithTheSameKeyIsAppliedOnce` | 201 then 200 with the same body, balance added once | D8 |
| | `cardDetailsAreRefusedAndNothingIsCharged` | Card fields or a card number as token → `CARD_DATA_NOT_ALLOWED`; other token → `INVALID_PAYMENT_TOKEN`; balance 0 | D9 |
| | `declinedOrTooLargeTopUpsAddNothing` | `402 TOP_UP_DECLINED`, `TOP_UP_LIMIT_EXCEEDED`, balance 0 | M4.1 |
| | `travellersSeeOnlyTheirOwnWalletAndStaffCanLookUpAny` | FINANCE/SUPPORT read any wallet; other traveller 403; SERVICE 403 on `/me`; `WALLET_NOT_FOUND` | RBAC |
| `FinanceApiTest` | `trialBalanceShowsZeroDifferenceAndReconciledWallets` | difference 0, balanced, wallets reconciled, accounts listed | D8 |
| | `ledgerEntriesOfOnePaymentCanBeListed` | 4 lines, GATEWAY_CLEARING debit first, PLATFORM_REVENUE 700 last | M4.6 |
| | `financeEndpointsAreOnlyForFinanceAndAdmin` | TRAVELLER, SERVICE, SUPPORT get 403 on ledger, payouts, settlement; no token 401 | RBAC |
| | `commissionRulesShowEachProvidersStrategy` | ZIPRIDE flat fee, DMRC percentage, SCOOTER → FLAT_FEE | M4.4 |
| | `payoutsCanBeRunNowAndListed` | `run-…`, MANUAL, DMRC PAID; payout list starts with `po-` | M4.5 |
| | `settlementExportWritesTheDailyFile` | File name, header, file content = returned CSV; bad date → 400 | INT-20 |
| | `webhookWithAValidSignatureConfirmsThePayment` | Signed webhook without a token → 200, matched payment, `gatewayConfirmedAt` set | Webhook HMAC |
| | `webhookWithAWrongOrMissingSignatureIsRejected` | Wrong or missing signature → 401 `INVALID_SIGNATURE` | Webhook HMAC, D9 |
| | `demoControlsDeclineTheNextChargeAndSignBodies` | `/dev/gateway/decline-next` makes the next GATEWAY payment FAILED; `/dev/webhooks/sign` gives the right signature | Demo controls |

## 10. Questions you may be asked

1. **Why paise and `long`, not rupees and `double`?** A `double` cannot store 0.1 exactly, so sums drift by fractions of
   a paisa. Whole paise in a `long` are exact, and the shared contract says so.
2. **What happens if APP-06 times out and sends the payment again?** It sends the same `Idempotency-Key`. We find the
   stored payment and return it with 200 and the same body; no second charge. If both copies arrive at the same moment,
   the unique key in the database lets only one be saved and the other returns its result.
3. **A payment FAILED for low balance. How does APP-06 try again after a top-up?** With a new key (e.g.
   `b-5001-pay-2`); the old key will always answer FAILED, which is what idempotency means.
4. **What is double-entry and why use it?** Every movement is written as debits and credits that are equal. If code ever
   loses or creates money, the totals stop matching and the trial balance shows it. It also gives a full history:
   every paisa can be traced from the traveller to the provider.
5. **How do you stop someone changing the ledger?** No setters; JPA `@PreUpdate`/`@PreRemove` throw; the repository has
   no delete method; in production the database user also has no UPDATE/DELETE right. Mistakes are corrected by a new
   reversing entry (a test proves edits and deletes fail).
6. **How does the split stay correct?** The splits must add up to the amount (`SPLIT_MISMATCH`), and each split stores
   commission and net with net = gross − commission. Refunds are shared in proportion and the last one takes exactly
   what is left. The CSV and payouts are calculated from the same numbers.
7. **Where is the Strategy pattern and what does it buy?** `CommissionStrategy` with `PercentageCommission` and
   `FlatFeeCommission`. `PaymentService` asks "what commission?" without knowing the rule. Which rule a provider type
   gets is in `application.yml`; a new rule is one new class.
8. **Where is the Template Method?** `PayoutJobTemplate.run` is `final` and always does collect → total → transfer →
   record. `ProviderPayoutJob` fills in the steps. Another payout kind (weekly, one provider) would reuse the skeleton.
9. **What is optimistic locking and why not a normal lock?** Each wallet has a version. A save succeeds only if nobody
   saved in between; otherwise it fails and we retry (3 times, then 409). Clashes on one wallet are rare, so this is
   faster than locking the row for every request.
10. **How does D9 work if the card number never reaches you?** The traveller types the card into Razorpay's page; we
    receive a token or a payment id. Our API refuses any card field. So we store no card data, our PCI DSS scope is
    tiny, and only this one service (in an isolated network zone, data kept in India as RBI requires) handles payments.
11. **Why can anyone call the webhook URL? Isn't that unsafe?** Anyone can call it, but we accept it only with a correct
    HMAC-SHA256 signature of the exact body, made with a secret only Razorpay and we know. A faked or altered message
    fails with 401.
12. **What changes to go live with Razorpay?** Write `RazorpayGateway implements PaymentGateway` and
    `RazorpayRouteGateway implements PayoutGateway`, put the real keys and webhook secret in the environment, and remove
    the stubs. Business code, tests of rules and the ledger stay the same (Adapter pattern, principle P3).
