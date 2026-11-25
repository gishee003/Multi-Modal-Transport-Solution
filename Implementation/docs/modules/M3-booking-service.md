# Booking service: module guide

**Module:** M3 Booking & Ticketing · **Owner:** Jeevesh · **Folder:** `app-01-traveller/booking-service` · **Port:** 8086

## 1. What this service does

A traveller has picked a journey in the app, for example *walk to Rajiv Chowk, metro to Hauz Khas, then a scooter to
Qutub Minar*. Booking service books **all the legs together**, takes the money **once**, and hands out what the traveller needs
for each leg: a ticket, an unlock code or a driver (TSP staff).

| Function (architecture document §2.3) | What it means in plain words | Where in the code |
|---|---|---|
| **M3.1** Book a whole journey | All legs are booked together, **all-or-nothing**: either every leg is booked and paid, or nothing is and any money is given back. Sending the same request twice never makes two bookings. | `BookingFacade.book(...)` |
| **M3.2** Hold, confirm, release | Ask each provider to *hold* a seat or vehicle, *confirm* it once paid, or *release* it if the booking fails. | `HoldLegsStep`, `ConfirmLegsStep` → `ProviderHubClient` |
| **M3.3** Signed QR ticket | Metro and bus legs get a QR code that a gate can check **offline**. Nobody can fake or change it. | `QrTicketService`, `POST /api/v1/bookings/tickets/verify` |
| **M3.4** Unlock code | Scooter, bike and car legs get an unlock code, **only after payment**, and an endpoint unlocks the vehicle when the leg starts. | `IssueAccessStep`, `BookingService.unlock` |
| **M3.5** Taxi request | A taxi leg is confirmed with a driver assigned (name, vehicle number, arrival time). | `ConfirmLegsStep`, `IssueAccessStep` |
| **M3.6** Cancel | Cancelling releases every hold and refunds the money. | `BookingService.cancel` |
| **M3.7** Rate and report | Rate a finished trip 1 to 5 stars (once); report a problem (broken scooter, rude driver, …). | `BookingService.rate`, `reportProblem` |

Other services it talks to (all through stubs in the demo, see section 8):

- **Identity service** (INT-10): "may this user drive a car?"
- **Payment service** (INT-11): "charge this amount" and "refund it".
- **TSP Integration Hub** (INT-12): "hold / confirm / release this leg" and "unlock this vehicle".
- It **publishes** events on `booking.events` (booking confirmed, cancelled, completed) and `audit.events`,
  and **listens** to `licence.events`, `payment.events` and `pricing.rules`.

## 2. How it works

### 2.1 Words you need first

- **Saga.** A booking touches three other systems (licence, payment, providers). They each have their own database,
  so we cannot wrap everything in one database transaction and "roll back" at the end. A *saga* is the answer:
  do the work as a list of small steps, and give **every step an undo**. If step 4 fails, run the undo of steps 3, 2
  and 1, newest first.
- **Compensation.** The name for such an undo. The undo of "hold a seat" is "release the seat"; the undo of
  "take the money" is "refund the money". It does not erase history (the payment did happen), it makes up for it.
- **Orchestration.** One part of the system acts as the conductor and tells every other part what to do, in order.
  Here Booking service is the conductor (`BookingSaga`). The alternative, *choreography*, lets each service react to events
  with no conductor (lecture 18).
- **Idempotency.** An operation is *idempotent* if doing it twice has the same effect as doing it once. The phone app
  sends a unique `Idempotency-Key` with each booking. If the network drops and the app retries with the same key,
  we return the booking we already made instead of making (and charging for) a second one.
- **Optimistic locking.** Every booking row has a `version` number. Saving checks "is the version in the database still
  the one I loaded?" and adds one. If two requests loaded version 3 and both try to save, the first wins (version 4) and
  the second is refused, because the database now says 4. It is *optimistic* because we do not lock the row while
  working; we only check at the end.
- **HMAC (keyed hash).** A short fingerprint of some text computed with a secret key (we use HMAC-SHA256). Change one
  letter of the text and the fingerprint is completely different; without the key you cannot compute the right
  fingerprint. So a gate that knows the key can tell a real ticket from a forged or edited one.
- **State machine.** A booking is always in exactly one *state* (CREATED, LEGS_HELD, PAID, CONFIRMED, COMPLETED or
  CANCELLED) and may only move along the allowed arrows:

```
CREATED ──> LEGS_HELD ──> PAID ──> CONFIRMED ──> COMPLETED
   │            │           │          │
   └────────────┴───────────┴──────────┴──────> CANCELLED
```

### 2.2 Flow 1: booking a journey (everything succeeds)

Request: `POST /api/v1/bookings` with a token for a TRAVELLER and an `Idempotency-Key` header.

1. **`BookingController.create`** (API layer) checks the role (`@PreAuthorize("hasRole('TRAVELLER')")`) and the JSON
   (`@Valid`), then calls the facade. It does nothing else.
2. **`BookingFacade.book`** (service layer):
   1. Refuses a missing key (`400 IDEMPOTENCY_KEY_REQUIRED`) and checks simple rules: non-walking legs need a
      `providerId`, scooter / bike / car legs need a `vehicleId`, and at least one leg must need booking.
   2. Computes a fingerprint (SHA-256) of the request body.
   3. Looks for a booking with the same user and key (`BookingStore.findByKey`). If found, returns it (see Flow 3).
   4. Creates a `Booking` in state **CREATED** with one `BookingLeg` per leg (ids `b-xxxx-L1`, `-L2`, …) and saves it.
   5. Builds a `SagaContext` (the booking plus a few shared values) and calls `BookingSaga.run`.
3. **`BookingSaga.run`** runs five step objects in order. After each one it writes a line to the booking's
   **saga log** and saves the booking (so progress is never lost):

| # | Step class | What it does | Booking state after |
|---|---|---|---|
| 1 | `CheckLicenceStep` | Only if a leg is a self-drive **CAR**: asks Identity service (INT-10, via `DriveEligibilityService` → `LicenceClient`) whether the user may drive class LMV. A "no" stops the saga with `NOT_ELIGIBLE`. | CREATED |
| 2 | `HoldLegsStep` | For each non-walking leg, asks TSP Integration Hub to hold it (INT-12 `POST /holds`). Stores the `holdId` and price, adds up the total (taxi legs get the current surge factor from `PricingRules`). | **LEGS_HELD** |
| 3 | `ChargePaymentStep` | Charges the total once through Payment service (INT-11) with key `<bookingId>-pay`, split per provider. A `FAILED` answer stops the saga. | **PAID** |
| 4 | `ConfirmLegsStep` | Confirms every hold (INT-12 `POST /holds/{id}/confirm`). The answer carries the ticket reference, the unlock code or the driver (TSP staff). | PAID |
| 5 | `IssueAccessStep` | Metro / bus: builds a **signed QR** with `QrTicketService`. Scooter / bike / car: stores the unlock code. Taxi: stores the driver. Each goes through `Booking.issueAccess`, which **refuses unless the booking is PAID or later** (driver D7). | **CONFIRMED** |

4. Back in the facade: `BookingEvents.confirmed` publishes `BOOKING_CONFIRMED` on `booking.events` (Journey service listens to
   start live tracking), `AuditLogger` publishes an `AUDIT` event, and the controller answers **201 Created**.

### 2.3 Flow 2: something fails, everything is undone

Suppose the payment is declined at step 3.

1. `ChargePaymentStep` gets `status: FAILED, failureReason: INSUFFICIENT_BALANCE` and throws `StepFailed`.
2. `BookingSaga` catches it and **compensates**: it pops the steps it started from a stack, newest first, and calls
   `undo()` on each:
   - `ChargePaymentStep.undo`: nothing was captured, so nothing to refund.
   - `HoldLegsStep.undo`: releases every hold (`DELETE /holds/{id}`), marks the legs `RELEASED`.
   - `CheckLicenceStep.undo`: a check changes nothing, so nothing to do.
3. The booking moves to **CANCELLED** with `cancelReason = "PAYMENT_FAILED: INSUFFICIENT_BALANCE"`, and is saved.
   No leg ever got a ticket or code, because step 5 never ran (and could not have: the booking was never PAID).
4. The facade publishes `BOOKING_CANCELLED` and an `AUDIT` event with outcome `FAILED`, and answers
   **409 BOOKING_FAILED** (or **403 NOT_ELIGIBLE** for a licence refusal). The message names the booking id, so the app
   can show the details with `GET /api/v1/bookings/{id}`; the `sagaLog` there shows every step done and undone.

Other failure points work the same way:

- **A provider refuses a hold** (leg 2 of 3): legs already held (leg 1) are released; leg 3 is never tried; no money is taken.
  The hold step's own undo runs too, because a step is put on the undo stack *before* it runs, so a half-done step cleans up after itself.
- **Confirm fails after payment**: the payment is **refunded** (key `<bookingId>-refund`, so never twice) and then all holds are
  released. Every hold is released, including ones already confirmed.

If an undo itself fails (for example Payment service is down), the saga logs `undo FAILED` and carries on with the others.
In production that undo would be retried from an "outbox" table.

### 2.4 Flow 3: the same request is sent twice (idempotency)

1. The app sends `POST /api/v1/bookings` with key `K`; the booking is made (201).
2. The answer gets lost; the app retries with the same key `K` and the same body.
3. `BookingFacade` finds the booking for (user, `K`) and returns it with **200 OK** and header `Idempotent-Replay: true`.
   No new booking, no second payment.
4. If the same key comes with a **different** body, the fingerprints differ: **422 IDEMPOTENCY_KEY_REUSED**.
5. If the two copies arrive at exactly the same moment, the database's unique key on (user, key) lets only one insert in;
   the other gets the existing booking.

### 2.5 Flow 4: cancel (M3.6)

`POST /{id}/cancel` → `BookingService.cancel`: checks the caller may see the booking (owner, SUPPORT or ADMIN) and that the
state allows CANCELLED (not COMPLETED, not already CANCELLED: else `409 ILLEGAL_STATE`). Then it **reuses the saga's undo
commands**: `ChargePaymentStep.undo` (refund) and `HoldLegsStep.undo` (release). The booking becomes CANCELLED, every
ticket and code is withdrawn, `BOOKING_CANCELLED` is published.

### 2.6 Flow 5: hold expiry (background job)

`HoldExpiryJob.cancelExpiredHolds` runs every minute (`@Scheduled`). It finds bookings still in CREATED or LEGS_HELD
created more than 10 minutes ago (`mmts.booking.hold-timeout-minutes`) and cancels them with reason `HOLD_EXPIRED`,
releasing their holds. Normally the saga finishes in milliseconds, so this only catches bookings left half-way by a crash.
The demo data contains one such booking (`b-stuck103`) so you can watch it happen.

### 2.7 Flow 6: QR ticket and gate check (M3.3)

- **Making it**: `QrTicketService.sign` turns the ticket data (ticket id, booking, leg, mode, from, to, valid from / until)
  into JSON, encodes it as base64url text, computes HMAC-SHA256 of that text with the key `mmts.booking.qr-signing-key`,
  and joins them: `<data>.<signature>`. This string is what the QR code contains.
- **Checking it**: `POST /api/v1/bookings/tickets/verify` (as a gate, role OPERATOR) calls `QrTicketService.verify`:
  recompute the signature and compare (constant-time); then check the time window. Answers `VALID`, `BAD_SIGNATURE`,
  `EXPIRED`, `NOT_YET_VALID` or `MALFORMED`. It never reads the database, so a real gate could do this with no network.

### 2.8 Flow 7: unlock, complete, rate, report

- **Unlock** (`POST /{id}/legs/{legId}/unlock`): `Booking.unlockLeg` checks the booking is CONFIRMED and the leg is a
  scooter / bike / car not yet unlocked, then the provider is asked to unlock (INT-12), and the leg becomes `UNLOCKED`.
- **Complete** (`POST /{id}/complete`): CONFIRMED → COMPLETED, publishes `BOOKING_COMPLETED`. Called by the app or by
  Journey service tracking (role SERVICE).
- **Rate** (`POST /{id}/rating`): only when COMPLETED, only once (`ALREADY_RATED`).
- **Report a problem** (`POST /{id}/problems`): saved as a `ProblemReport` with status OPEN; support staff list them with GET.

### 2.9 Flow 8: events this app listens to (`BookingEventListener`)

| Topic | Event | Reaction |
|---|---|---|
| `licence.events` | `LICENCE_VERIFIED` / `REJECTED` / `EXPIRED` | Forget the cached "may drive" answer for that user, so the next CAR booking asks Identity service again |
| `payment.events` | `PAYMENT_FAILED` | If that booking is still unpaid, cancel it (release holds). A paid booking is left alone. |
| `pricing.rules` | `PRICING_RULES_UPDATED` | Store the new `surgeFactor` (used for taxi legs) |

## 3. Design decisions and why

| Decision | Why | Alternative we rejected |
|---|---|---|
| Orchestrated saga with Booking service as the conductor | One place knows the whole booking order and its undo; easy to read, test and explain; matches SOA orchestration (lecture 18) | Choreography (each service reacts to events): no single place to see the flow, harder to undo in the right order |
| Each saga step is its own class with `execute` / `undo` (Command) | Adding a step (e.g. "apply coupon") means one new class; undo sits right next to the action it undoes | One long method with nested try/catch: hard to read, easy to forget an undo |
| Undo stack: a step is pushed **before** it runs | A step that fails half-way (2 of 3 holds taken) still releases what it took | Undo only fully finished steps: would leak the partial holds |
| State machine in the `BookingStatus` enum, used by every change | Illegal changes (e.g. ticket before payment) are impossible from any code path, not just one endpoint | `if` checks scattered in services: easy to miss one |
| `@Version` optimistic locking | Bookings are rarely edited at the same moment, so no need to block; a clash is detected and refused (409 `CONCURRENT_UPDATE`) | Pessimistic `SELECT … FOR UPDATE` locks: slower, risk of deadlocks, holds a DB connection during remote calls |
| Idempotency key stored on the booking + unique DB constraint (user, key) + request fingerprint | Retries are safe even when two arrive together; a reused key with a different body is caught | Key kept only in memory: lost on restart, not shared between instances |
| Payment and refund also use idempotency keys (`<id>-pay`, `<id>-refund`) | A retried charge or refund can never happen twice (INT-11 contract) | Rely on "we only call once": not true with timeouts and retries |
| Saga does not use one big `@Transactional` | Remote calls cannot be rolled back by the database; each step saves its progress separately | One transaction around remote calls: holds DB locks for seconds and still cannot undo the remote side |
| QR signed with HMAC-SHA256, key in `application.yml` | Gates can verify offline; tampering is detected; key can be changed without code changes | Plain ticket number checked online: gates stop working when the network is down |
| Money as integer paise | No rounding errors (0.1 + 0.2 ≠ 0.3 with decimals) | `double` rupees |
| Each failure gives a clear error code (`BOOKING_FAILED`, `NOT_ELIGIBLE`, `ILLEGAL_STATE`, …) and the booking keeps a saga log | The app and the support team can see exactly what happened | A generic 500 error |
| Positive licence answers are cached; `licence.events` clears them | Fewer calls to Identity service; still correct when a licence changes | No cache (more load) or a cache with no invalidation (stale answers) |
| Cancelling reuses the saga's undo steps | One implementation of "release" and "refund"; they cannot drift apart | Separate cancel code that duplicates the undo logic |

## 4. Architecture drivers demonstrated

| Driver | How the code meets it | How to see it in the demo |
|---|---|---|
| **D6 All-or-nothing and never twice** | Saga with an undo for every step (`BookingSaga`, `*Step.undo`); idempotency key + unique constraint (`BookingFacade`, `Booking` table); idempotent payment / refund keys; optimistic locking (`@Version` in `Booking`, `BookingStore.save`) | Demo steps 4–6: force a payment failure and see the holds released in the saga log; repeat a request with the same key and get the same booking with 200 |
| **D7 No ticket or unlock before payment** | `BookingStatus.canIssueAccess()` is true only for PAID and CONFIRMED; `Booking.issueAccess` and `assignDriver` refuse otherwise; `unlockLeg` needs CONFIRMED; cancelling withdraws every ticket and code | Demo step 5: the failed booking's legs have `"access": null`; step 9: unlock after cancel gives `409 ILLEGAL_STATE` |
| Security / RBAC (shared) | `@PreAuthorize` per endpoint; owner checks in `BookingService` (`NOT_YOUR_BOOKING`); audit event for every action | Call GET with another traveller's token → 403 |
| P3 external systems only through adapters | `LicenceClient`, `PaymentClient`, `ProviderHubClient` interfaces with stub implementations | Section 8 |

## 5. Design patterns used

| Pattern | Where in the code | Why here |
|---|---|---|
| **Command** | `service/saga/SagaStep` and its five implementations, each with `execute()` and `undo()` | Each action carries its own undo, so the orchestrator can undo any list of steps without knowing what they do |
| **State** | `domain/BookingStatus` (each state lists allowed next states and allowed actions); `Booking.moveTo`, `issueAccess`, `unlockLeg`, `rate` ask the state | The rules "no ticket before payment", "no rating before completion" live with the states, not in many `if`s |
| **Facade** | `service/BookingFacade.book(...)` | The controller makes one call; the idempotency check, booking creation, five-step saga and events are hidden behind it |
| **Orchestrator (saga)** | `service/saga/BookingSaga` | SOA orchestration (lecture 18): one conductor runs and, if needed, compensates the steps |
| **Optimistic locking** (database pattern, lecture 08) | `@Version Long version` in `domain/Booking`; `BookingStore.save` turns a clash into `409 CONCURRENT_UPDATE` | Prevents lost updates without locking rows |
| **Adapter** | `adapter/LicenceClient`, `PaymentClient`, `ProviderHubClient` + `Stub…` classes | Business code depends on an interface; the real HTTP client can replace the stub without changing it (P3, P6) |
| **Observer / publish-subscribe** | Publish: `BookingEvents`, `AuditLogger` via `EventPublisher`. Subscribe: `BookingEventListener` (`@EventListener`) | Booking service does not need to know who reacts to a confirmed booking; other apps' changes reach Booking service without direct calls |
| **Repository / DAO** | `repository/BookingRepository`, `ProblemReportRepository`; `service/BookingStore` | SQL is generated; services never write SQL |
| **DTO** | `api/BookingRequest`, `BookingResponse`, `ActionRequests`, `ActionResponses` | Entities never leave the service layer |

## 6. Endpoints in detail

Get a token first (demo only): `POST /dev/token` with `{"userId":"u-101","roles":["TRAVELLER"]}`, then press
**Authorize** in Swagger UI and paste the token. All errors use the shared format
`{"code","message","details","timestamp"}`. Common to all `/api/v1/**` endpoints: `401 UNAUTHORIZED` (no / bad token),
`403 FORBIDDEN` (role not allowed).

### POST /api/v1/bookings — book a journey (M3.1)

- **Role:** TRAVELLER. **Header:** `Idempotency-Key: <any unique text, e.g. a UUID>`.
- **Request:**

```json
{
  "legs": [
    { "mode": "METRO", "providerId": "DMRC",
      "from": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" },
      "to":   { "name": "Hauz Khas Metro",   "lat": 28.5433, "lon": 77.2066, "stopId": "DMRC-HKS" },
      "startTime": "2026-11-15T10:00:00Z" },
    { "mode": "SCOOTER", "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0042",
      "from": { "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066 },
      "to":   { "name": "Qutub Minar",     "lat": 28.5245, "lon": 77.1855 },
      "startTime": "2026-11-15T10:25:00Z" }
  ],
  "paymentMethod": "WALLET"
}
```

- **Response 201** (200 with header `Idempotent-Replay: true` when the key was already used with the same body):

```json
{
  "bookingId": "b-3f9a1c2e",
  "userId": "u-101",
  "status": "CONFIRMED",
  "totalPaise": 18500,
  "paymentMethod": "WALLET",
  "paymentId": "p-9003",
  "refundId": null,
  "cancelReason": null,
  "ratingStars": null,
  "ratingComment": null,
  "createdAt": "2026-11-15T09:40:00Z",
  "updatedAt": "2026-11-15T09:40:00Z",
  "version": 5,
  "legs": [
    { "legId": "b-3f9a1c2e-L1", "mode": "METRO", "providerId": "DMRC",
      "from": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" },
      "to": { "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066, "stopId": "DMRC-HKS" },
      "startTime": "2026-11-15T10:00:00Z", "vehicleId": null, "status": "CONFIRMED", "holdId": "h-305",
      "pricePaise": 4000,
      "access": { "type": "QR_TICKET", "value": "eyJ0aWNrZXRJZCI6IkRNUkMtVC0zMDUi...Hq3x9QeR...",
                  "validFrom": "2026-11-15T09:40:00Z", "validUntil": "2026-11-15T13:00:00Z" },
      "driver": null },
    { "legId": "b-3f9a1c2e-L2", "mode": "SCOOTER", "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0042",
      "status": "CONFIRMED", "holdId": "h-306", "pricePaise": 14500,
      "access": { "type": "UNLOCK_CODE", "value": "ZI-7733",
                  "validFrom": "2026-11-15T10:20:00Z", "validUntil": "2026-11-15T11:20:00Z" },
      "driver": null, "...": "..." }
  ],
  "sagaLog": [
    "CHECK_LICENCE: done - no self-drive leg, nothing to check",
    "HOLD_LEGS: done - held [h-305, h-306], total 18500 paise",
    "CHARGE_PAYMENT: done - payment p-9003 captured, 18500 paise",
    "CONFIRM_LEGS: done - confirmed [h-305, h-306]",
    "ISSUE_ACCESS: done - issued access for 2 legs"
  ]
}
```

A TAXI leg comes back with `"access": {"type": "DRIVER_ASSIGNED", "value": "d-21", …}` and
`"driver": {"driverId": "d-21", "name": "Ramesh Kumar", "vehicleNumber": "DL 1C AB 4321", "etaMinutes": 6}`.

- **Errors:**

| Status | Code | When |
|---|---|---|
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | No `Idempotency-Key` header |
| 400 | `VALIDATION_FAILED` | Missing / wrong fields (no legs, no `from`, bad `paymentMethod` …); `details` lists them |
| 400 | `PROVIDER_REQUIRED`, `VEHICLE_REQUIRED`, `NOTHING_TO_BOOK` | Non-walking leg without provider; scooter / bike / car without vehicle; only walking legs |
| 403 | `NOT_ELIGIBLE` | CAR leg and Identity service says the user may not drive; booking cancelled |
| 409 | `BOOKING_FAILED` | A hold was refused, the payment failed or a confirm failed; everything was undone, booking cancelled. The message contains the booking id and the reason |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key, different request body |

### GET /api/v1/bookings — list bookings

- **Role:** TRAVELLER (own bookings), SUPPORT / ADMIN (latest 50, or one user's with `?userId=u-101`).
- **Response 200:** array of booking objects (as above), newest first.
- **Errors:** 403 `NOT_YOUR_BOOKING` when a traveller asks for another `userId`.

### GET /api/v1/bookings/{bookingId} — one booking

- **Role:** TRAVELLER (owner), SUPPORT, ADMIN. **Response 200:** the booking object.
- **Errors:** 404 `BOOKING_NOT_FOUND`; 403 `NOT_YOUR_BOOKING`.

### POST /api/v1/bookings/{bookingId}/cancel — cancel (M3.6)

- **Role:** TRAVELLER (owner), SUPPORT, ADMIN. **Body (optional):** `{ "reason": "Plans changed" }`.
- **Response 200:** the booking with `"status": "CANCELLED"`, `"refundId": "r-77"`, legs `RELEASED`, `access: null`,
  and saga log lines `CANCEL: refund r-77 of 18500 paise` and `CANCEL: released [h-305, h-306]`.
- **Errors:** 409 `ILLEGAL_STATE` (already cancelled or completed); 404; 403 `NOT_YOUR_BOOKING`.

### POST /api/v1/bookings/{bookingId}/complete — trip finished

- **Role:** TRAVELLER (owner), SERVICE (Journey service tracking), ADMIN. **Response 200:** booking with `"status": "COMPLETED"`.
- **Errors:** 409 `ILLEGAL_STATE` (not CONFIRMED).

### POST /api/v1/bookings/{bookingId}/rating — rate (M3.7)

- **Role:** TRAVELLER (owner). **Body:** `{ "stars": 4, "comment": "Smooth ride" }` (stars 1–5).
- **Response 200:** booking with `"ratingStars": 4, "ratingComment": "Smooth ride"`.
- **Errors:** 400 `VALIDATION_FAILED` (stars outside 1–5); 409 `ILLEGAL_STATE` (not COMPLETED); 409 `ALREADY_RATED`.

### POST /api/v1/bookings/{bookingId}/problems — report a problem (M3.7)

- **Role:** TRAVELLER (owner).
- **Body:** `{ "category": "VEHICLE_FAULT", "description": "Brake is loose", "legId": "b-3f9a1c2e-L2" }`
  (category: `VEHICLE_FAULT`, `DRIVER_BEHAVIOUR`, `TICKET_NOT_ACCEPTED`, `OVERCHARGED`, `SAFETY`, `OTHER`; `legId` optional).
- **Response 201:**
  `{ "problemId": "pr-1a2b3c4d", "bookingId": "b-3f9a1c2e", "legId": "b-3f9a1c2e-L2", "category": "VEHICLE_FAULT", "description": "Brake is loose", "status": "OPEN", "createdAt": "…" }`
- **Errors:** 400 `VALIDATION_FAILED`; 404 `LEG_NOT_FOUND`; 403 `NOT_YOUR_BOOKING`.

### GET /api/v1/bookings/{bookingId}/problems — list problems

- **Role:** TRAVELLER (owner), SUPPORT, ADMIN. **Response 200:** array of problem objects.

### POST /api/v1/bookings/{bookingId}/legs/{legId}/unlock — unlock vehicle (M3.4)

- **Role:** TRAVELLER (owner). **No body.**
- **Response 200:** `{ "bookingId": "b-3f9a1c2e", "legId": "b-3f9a1c2e-L2", "vehicleId": "ZR-SC-0042", "status": "UNLOCKED", "unlockedAt": "…" }`
- **Errors:** 409 `ILLEGAL_STATE` (booking not CONFIRMED, e.g. cancelled); 409 `NOT_UNLOCKABLE` (metro / bus / taxi leg);
  409 `ALREADY_UNLOCKED`; 409 `HOLD_NOT_CONFIRMED` (from the provider); 404 `LEG_NOT_FOUND`.

### POST /api/v1/bookings/tickets/verify — gate check of a QR (M3.3)

- **Role:** OPERATOR (gate staff), SERVICE, ADMIN. **Body:** `{ "qr": "<the text in the QR>" }`.
- **Response 200** (always 200; `valid` says yes or no, like a gate turning green or red):

```json
{ "valid": true, "reason": "VALID", "ticketId": "DMRC-T-305", "bookingId": "b-3f9a1c2e",
  "legId": "b-3f9a1c2e-L1", "mode": "METRO", "providerId": "DMRC",
  "from": "Rajiv Chowk Metro", "to": "Hauz Khas Metro",
  "validFrom": "2026-11-15T09:40:00Z", "validUntil": "2026-11-15T13:00:00Z" }
```

  `reason` can also be `BAD_SIGNATURE` (forged or edited), `EXPIRED`, `NOT_YET_VALID` or `MALFORMED`.

### Demo-only stub controls: /dev/stubs (no token, `demo` profile only)

| Method and path | Effect |
|---|---|
| `GET /dev/stubs` | Shows the current settings |
| `POST /dev/stubs/fail-next-payment?reason=INSUFFICIENT_BALANCE` | Payment service stub: the next payment returns FAILED |
| `POST /dev/stubs/refuse-holds?providerId=ZIPRIDE` | TSP Integration Hub stub: that provider refuses every hold (`409 NOT_AVAILABLE`) |
| `POST /dev/stubs/fail-next-confirm` | TSP Integration Hub stub: the next confirm fails (`409 HOLD_NOT_ACTIVE`), after payment, so a refund is needed |
| `POST /dev/stubs/ineligible?userId=u-104&reason=EXPIRED` | Identity service stub: that user may not drive (`400 BAD_REASON` for an unknown reason) |
| `POST /dev/stubs/reset` | Everything succeeds again |

Also from the shared setup: `POST /dev/token`, `GET /dev/events`, `GET /api/v1/info`.

## 7. Demo script

Start the app: `cd app-01-traveller/booking-service && mvn spring-boot:run`, open `http://localhost:8086/swagger-ui.html`.

1. **Get a token.** `POST /dev/token` with `{"userId":"u-101","roles":["TRAVELLER"]}`. Copy `token`, press **Authorize**, paste it.
2. **Show the sample data.** `GET /api/v1/bookings`: u-101 already has a CONFIRMED metro + scooter booking (made through the
   real saga at start-up). Point out the `sagaLog`, the QR text on the metro leg and the unlock code on the scooter leg.
3. **A successful multi-leg booking.** `POST /api/v1/bookings`, header `Idempotency-Key: demo-key-1`, body:

```json
{
  "legs": [
    { "mode": "WALK", "from": { "name": "Janpath", "lat": 28.6250, "lon": 77.2186 },
      "to": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" },
      "startTime": "2026-11-15T10:00:00Z" },
    { "mode": "METRO", "providerId": "DMRC",
      "from": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" },
      "to": { "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066, "stopId": "DMRC-HKS" },
      "startTime": "2026-11-15T10:05:00Z" },
    { "mode": "SCOOTER", "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0042",
      "from": { "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066 },
      "to": { "name": "Qutub Minar", "lat": 28.5245, "lon": 77.1855 },
      "startTime": "2026-11-15T10:30:00Z" },
    { "mode": "TAXI", "providerId": "MERU",
      "from": { "name": "Qutub Minar", "lat": 28.5245, "lon": 77.1855 },
      "to": { "name": "IGI Airport Terminal 3", "lat": 28.5562, "lon": 77.0870 },
      "startTime": "2026-11-15T12:00:00Z" }
  ],
  "paymentMethod": "WALLET"
}
```

   Tip: change the `startTime`s to a few minutes after the current time (UTC) so the QR is inside its validity window.
   Point out: **201**, status `CONFIRMED`, total `53500` paise (4000 + 14500 + 35000; walking is free), five saga log lines
   in order, the walking leg is `NOT_NEEDED`, metro has `QR_TICKET`, scooter `UNLOCK_CODE`, taxi `DRIVER_ASSIGNED` with
   a driver. Copy the `bookingId`, the metro leg's `access.value` (the QR) and the scooter's `legId`.
4. **Repeated Idempotency-Key (D6, never twice).** Send exactly the same request again with `Idempotency-Key: demo-key-1`.
   Point out: **200** (not 201), header `Idempotent-Replay: true`, the **same** `bookingId` and `paymentId`.
   Then change one leg and send with the same key: **422 IDEMPOTENCY_KEY_REUSED**.
5. **Forced payment failure, showing the undo (D6 + D7).** `POST /dev/stubs/fail-next-payment?reason=INSUFFICIENT_BALANCE`.
   Then `POST /api/v1/bookings` with a new key `demo-key-2` and the same body.
   Point out: **409 BOOKING_FAILED**, the message names the booking. `GET /api/v1/bookings/{that id}`: status `CANCELLED`,
   `cancelReason: "PAYMENT_FAILED: INSUFFICIENT_BALANCE"`, every leg `RELEASED`, every `access` is `null` (no ticket
   without payment), and the saga log:
   `CHECK_LICENCE: done`, `HOLD_LEGS: done - held [...]`, `CHARGE_PAYMENT: FAILED - ...`, `HOLD_LEGS: undone - released [...]`.
6. **(Optional) Failure after payment: refund.** `POST /dev/stubs/fail-next-confirm`, book again with key `demo-key-3`.
   The booking shows a `paymentId` **and** a `refundId`, and the saga log lists `CHARGE_PAYMENT: undone - refund ...`
   **before** `HOLD_LEGS: undone - released ...` (undo runs newest first).
7. **(Optional) Licence refusal.** `POST /dev/stubs/ineligible?userId=u-101&reason=EXPIRED`, then book a CAR leg
   (`"mode":"CAR","providerId":"ZOOMCAR","vehicleId":"ZC-CAR-0007"`) with a new key: **403 NOT_ELIGIBLE**, no hold was taken.
   `POST /dev/stubs/reset` afterwards.
8. **QR verification (M3.3).** Get a gate token: `POST /dev/token` with `{"userId":"gate-rck-1","roles":["OPERATOR"]}`, Authorize
   with it. `POST /api/v1/bookings/tickets/verify` with `{"qr":"<QR from step 3>"}` → `valid: true`, from / to shown.
   Change one character in the middle of the QR and send again → `valid: false`, reason `BAD_SIGNATURE` (or `MALFORMED`).
   Point out: no database was used; a gate could do this offline with only the key.
9. **Unlock and cancel.** Back as u-101: `POST /api/v1/bookings/{id}/legs/{scooter legId}/unlock` → `UNLOCKED`.
   `POST /api/v1/bookings/{id}/cancel` → `CANCELLED` with a `refundId`, all tickets withdrawn.
   Try unlock again → **409 ILLEGAL_STATE**; cancel again → **409 ILLEGAL_STATE** (state machine).
10. **Rating and problem.** As u-102 (token for `u-102`, TRAVELLER): `GET /api/v1/bookings` shows a COMPLETED bus + taxi trip.
    `POST /api/v1/bookings/{id}/rating` with `{"stars":5,"comment":"Driver was on time"}`; again → `409 ALREADY_RATED`.
    `POST /api/v1/bookings/{id}/problems` with `{"category":"OVERCHARGED","description":"Taxi asked for extra cash"}` → 201.
11. **Hold expiry.** About one minute after start-up, as a SUPPORT user: `GET /api/v1/bookings/b-stuck103` →
    `CANCELLED`, `cancelReason: HOLD_EXPIRED` (it was held 15 minutes ago and never paid).
12. **Events.** `GET /dev/events`: `BOOKING_CONFIRMED`, `BOOKING_CANCELLED`, `BOOKING_COMPLETED` on `booking.events`
    and `AUDIT` on `audit.events`: what would go to Kafka in production.

## 8. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
| Identity service, drive eligibility (INT-10) | `adapter/StubLicenceClient` behind `LicenceClient`: every user `VERIFIED` (licence till 2031-05-20) unless marked ineligible via `/dev/stubs/ineligible` | REST `GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE` with a SERVICE token |
| Payment service (INT-11) | `adapter/StubPaymentClient` behind `PaymentClient`: same `Idempotency-Key` → same answer; checks `SPLIT_MISMATCH` and `REFUND_EXCEEDS_PAYMENT`; payment ids `p-9001…`, refund ids `r-77…` | REST `POST /api/v1/payments` and `POST /api/v1/payments/{id}/refunds` with `Idempotency-Key` |
| TSP Integration Hub (INT-12) | `adapter/StubProviderHubClient` behind `ProviderHubClient`: knows providers DMRC, DTC, ZIPRIDE, YULU, ZOOMCAR, MERU and fares per mode from `application.yml`; `404 PROVIDER_NOT_FOUND`, `409 NOT_AVAILABLE`, `409 HOLD_NOT_ACTIVE`, `409 HOLD_NOT_CONFIRMED`; release is safe to repeat | REST `POST /holds`, `POST /holds/{id}/confirm`, `DELETE /holds/{id}`, `POST /vehicles/{id}/unlock` |
| Kafka (`booking.events`, `audit.events`, subscriptions) | Shared in-process `EventPublisher` and `@EventListener` | Kafka topics; one new `EventPublisher` class, no business code change |
| Redis / cache for eligibility | Spring in-memory cache (`@Cacheable("drive-eligibility")`) | Same annotation with Redis |
| PostgreSQL DB-3 | H2 (demo) | `-Dspring-boot.run.profiles=postgres`, database `mmts_booking` |
| Analytics service pricing rules | Default surge factor 1.0 in `application.yml`; listener for `PRICING_RULES_UPDATED` | Analytics service publishes on `pricing.rules` |

### In the proof of concept (profile `poc`, see `../POC.md`)

The three stubs are replaced by real HTTP clients in `adapter/http` (`HttpLicenceClient`, `HttpPaymentClient`,
`HttpProviderHubClient`, marked `@Primary @Profile("poc")`). `RemoteCalls` adds a SERVICE login token and turns an
error answer back into the same `ApiException` code the stub would throw, so the saga code is unchanged.
In the trace a rail + cab booking is held at the TSP Hub, paid in the Payment service and confirmed (QR ticket +
driver); with CITYCAB's contract suspended the cab hold is refused (`TSP_CONTRACT_INACTIVE`) and the rail hold is
undone with nothing charged. The startup sample bookings are skipped in `poc` (`DemoDataLoader` is `demo & !poc`).

### Where it runs in production (architecture document, section 6)

- **Servers:** the general node group (VM-1, VM-2), at least two copies in two availability zones.
- **Data:** DB-3, PostgreSQL run by AWS with a standby in the second zone that takes over automatically.
- **Events:** `booking.events` and `audit.events` go to Kafka on Amazon MSK (MSK-1). Each event is first saved in an
outbox table in the same database transaction as the booking, then published by the service itself; if Kafka is
briefly down it is sent later, so no event is lost (D6). No separate tool is needed for this.

Shared by every service: requests from the apps arrive only through the API gateway (Kong + Keycloak on the general
node group); logs and metrics go to CloudWatch (MON-1); the container image is built and tested by GitHub Actions and
stored in the container registry (REG-1); Kubernetes (EKS-1) restarts a crashed copy and adds copies under load.

## 9. Tests

Run with `mvn test` in `app-01-traveller/booking-service`. Result on 2026-11-15: **53 tests, 0 failures, 0 errors**.
The 9 shared tests (`SharedSetupTest`, `JwtServiceTest`) are explained in `../SHARED-SETUP.md`, section 8.
All Spring tests use the same set-up (`@SpringBootTest` + `@AutoConfigureMockMvc`), so they share one running application on H2;
each test uses a fresh user id and key so tests never see each other's data. A test-only file
`src/test/resources/config/application.yml` stops the hold-expiry timer during tests (the test calls the job directly).

**Unit tests (no Spring, no database)**

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `BookingStateMachineTest` | `happyPathTransitionsAreAllowed` | CREATED → LEGS_HELD → PAID → CONFIRMED → COMPLETED is allowed | State machine |
| | `illegalTransitionsAreRefused` | Skipping holds or payment, leaving CANCELLED or COMPLETED is refused with `ILLEGAL_STATE`; status unchanged | State machine |
| | `cancelledBookingCannotBeCancelledAgain` | Second cancel → `ILLEGAL_STATE` | State machine |
| | `noTicketOrUnlockCodeBeforePayment` | `issueAccess` on a LEGS_HELD booking fails and leaves no code; after PAID it works | **D7** |
| | `cancellingWithdrawsTickets` | Cancelling a paid booking removes the unlock code | D7 |
| | `unlockOnlyForVehicleLegsOfConfirmedBookings` | Unlock refused while only PAID; works when CONFIRMED; refused for a metro leg (`NOT_UNLOCKABLE`) | M3.4, D7 |
| | `ratingOnlyOnceAndOnlyAfterCompletion` | Rating before COMPLETED → `ILLEGAL_STATE`; second rating → `ALREADY_RATED` | M3.7 |
| `QrTicketServiceTest` | `validSignatureIsAccepted` | A signed QR verifies as `VALID` and gives back the same ticket data | M3.3 |
| | `tamperedTicketIsRejected` | Changing the destination inside the QR → `BAD_SIGNATURE` | M3.3 (HMAC) |
| | `ticketSignedWithAnotherKeyIsRejected` | QR made with a different key → `BAD_SIGNATURE` | M3.3 |
| | `expiredTicketIsRejected` | Checked 4 hours later (fixed clock) → `EXPIRED` | M3.3 |
| | `garbageIsMalformed` | Random text → `MALFORMED` | M3.3 |
| `StubPaymentClientTest` | `sameIdempotencyKeyChargesOnlyOnce` | Same key twice → same answer, only one payment | INT-11 contract, D6 |
| | `splitsMustAddUpToTheAmount` | Wrong splits → `SPLIT_MISMATCH` | INT-11 contract |
| | `refundCannotExceedThePayment` | Refunding more than paid → `REFUND_EXCEEDS_PAYMENT` | INT-11 contract |
| | `forcedFailureAffectsOnlyTheNextPayment` | Demo switch fails exactly one payment | Demo control |

**Saga and service tests (Spring + H2, real stubs)**

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `BookingSagaTest` | `happyPathConfirmsEveryLegAndIssuesAccess` | Walk + metro + scooter + taxi: CONFIRMED, total from yml fares, charged once, QR verifies, unlock code, driver assigned, 5 saga lines, `BOOKING_CONFIRMED` published | M3.1–M3.5 |
| | `holdRefusedReleasesTheEarlierHolds` | Provider of leg 2 refuses: leg 1's hold is RELEASED, leg 3 never tried, no payment, `BOOKING_FAILED` | **D6** compensation |
| | `paymentFailureReleasesHoldsAndIssuesNoTicket` | Payment FAILED: all holds released, no access on any leg, no refund needed, saga log shows the undo | **D6**, **D7** |
| | `confirmFailureRefundsAndReleases` | Confirm fails after payment: full refund, all holds released, refund undone before release (reverse order) | **D6** |
| | `carLegNeedsAValidLicence` | Ineligible user + CAR leg: `NOT_ELIGIBLE`, cancelled before any hold | INT-10, M3.1 |
| | `carLegWithValidLicenceIsBooked` | Eligible user + CAR leg: confirmed, log shows `licence VERIFIED` | INT-10 |
| | `sameIdempotencyKeyReturnsTheSameBooking` | Same request + key twice: same booking, one payment, one booking for the user | **D6** idempotency |
| | `sameKeyWithDifferentRequestIsRefused` | Same key, different legs: `IDEMPOTENCY_KEY_REUSED` | D6 |
| `OptimisticLockingTest` | `secondUpdateFromAStaleCopyIsRefused` | Two copies of one booking: first save wins (version +1), second gets `CONCURRENT_UPDATE`, first change survives | **D6** optimistic locking |
| | `twoThreadsRacingOnlyOneWins` | Two threads save stale copies at the same moment: exactly one succeeds | **D6** optimistic locking |
| `HoldExpiryJobTest` | `unpaidBookingOlderThanTenMinutesIsCancelled` | Booking held 15 min ago, unpaid → CANCELLED `HOLD_EXPIRED`, hold released; one held 2 min ago untouched | Hold expiry |
| `BookingEventListenerTest` | `licenceEventDropsTheCachedYes` | After `LICENCE_EXPIRED`, the cached "eligible" is gone and the next CAR booking is refused | Observer, cache |
| | `pricingRulesChangeTheTaxiFare` | `PRICING_RULES_UPDATED` with surge 1.5 → taxi total 52500 | Observer, INT-18 |
| | `paymentFailedEventCancelsOnlyUnpaidBookings` | `PAYMENT_FAILED` cancels an unpaid booking but leaves a confirmed one alone | Observer, INT-17 |

**Endpoint (contract) tests with MockMvc: `BookingControllerTest`**

| Test | What it checks | Driver / rule it proves |
|---|---|---|
| `createBookingReturns201WithLegsAndAccess` | 201, header `Idempotent-Replay: false`, id `b-…`, CONFIRMED, total 18500, `p-…`, 3 legs with right access types, 5 saga lines | INT-04 contract |
| `repeatedIdempotencyKeyReturns200WithTheSameBooking` | Second call: 200, `Idempotent-Replay: true`, same id; list shows one booking | D6 |
| `createBookingErrors` | 401 no token; 403 SUPPORT may not book; 400 `IDEMPOTENCY_KEY_REQUIRED`, `VALIDATION_FAILED`, `NOTHING_TO_BOOK`, `VEHICLE_REQUIRED`; 409 `BOOKING_FAILED` for an unknown provider | Error codes, RBAC |
| `forcedPaymentFailureGives409AndTheBookingShowsTheUndo` | `/dev/stubs/fail-next-payment` then book: 409; booking CANCELLED with reason, legs RELEASED, no access | D6, D7, demo control |
| `ineligibleDriverGets403NotEligible` | `/dev/stubs/ineligible` then CAR booking: 403 `NOT_ELIGIBLE` | INT-10 |
| `getAndListRespectOwnership` | Owner and SUPPORT can read; other traveller 403 `NOT_YOUR_BOOKING`; 404 `BOOKING_NOT_FOUND`; SUPPORT filter by user; OPERATOR may not list | RBAC |
| `cancelReleasesAndRefundsAndCannotBeRepeated` | Cancel: 200, reason kept, `r-…` refund, legs RELEASED, access gone; second cancel 409 `ILLEGAL_STATE` | M3.6, state machine |
| `supportCanCancelAnyBooking` | SUPPORT cancels someone's booking; another traveller cannot (403) | RBAC |
| `completeThenRateOnce` | Rate before completion 409; complete 200; stars 6 → 400; rate 200; again `ALREADY_RATED`; complete twice 409 | M3.7, state machine |
| `reportAndListProblems` | Report 201 with `pr-…`, OPEN; unknown leg 404 `LEG_NOT_FOUND`; invalid body lists 2 bad fields; SUPPORT lists the problem | M3.7 |
| `unlockOnlyTheVehicleLegOfAConfirmedBooking` | Metro leg 409 `NOT_UNLOCKABLE`; scooter 200 `UNLOCKED`; twice 409 `ALREADY_UNLOCKED` | M3.4 |
| `noUnlockAfterCancel` | Unlock after cancel: 409 `ILLEGAL_STATE` | D7 |
| `gateVerifiesQrOfflineAndSpotsTampering` | OPERATOR: real QR valid with booking id and from; tampered QR invalid; TRAVELLER 403 | M3.3, RBAC |
| `stubControlsShowAndResetTheirState` | `/dev/stubs` controls set and reset their state; unknown reason 400 `BAD_REASON` | Demo controls |
