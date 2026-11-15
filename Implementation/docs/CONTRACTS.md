# Contracts between applications

These are the agreed formats for the integrations in section 4 of the architecture document. The **provider** of each
interface must implement it exactly; the **caller** builds a stub (in its `adapter/` package) that behaves the same way, so
each application can be built and tested alone and the real applications could be connected later.

Common rules:

- JSON, field names in `camelCase`. Money is always an integer number of **paise** (`amountPaise`), never a decimal.
- Times are ISO-8601 UTC strings (`2026-11-15T10:00:00Z`). Ids are strings.
- Service-to-service calls send `Authorization: Bearer <token>` with role `SERVICE`.
- Errors use the shared error format (`SHARED-SETUP.md` §4).
- Shared value types:

```json
"Place":  { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" }
"Mode":   "METRO" | "BUS" | "WALK" | "SCOOTER" | "BIKE" | "CAR" | "TAXI"
```

---

## Synchronous interfaces (REST web service)

### INT-10 and INT-13 · Drive eligibility check: provided by APP-04, called by APP-06 and APP-08

`GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE`

| Parameter | Values |
|---|---|
| `vehicleClass` | `LMV` (car), `MCWG` (motorcycle / scooter with gear) |
| `purpose` | `SELF_DRIVE` (APP-06, INT-10) or `TAXI_DRIVER` (APP-08, INT-13) |

Response `200`:

```json
{
  "userId": "u-101",
  "vehicleClass": "LMV",
  "purpose": "SELF_DRIVE",
  "eligible": true,
  "reason": "VERIFIED",
  "licenceExpiresOn": "2031-05-20"
}
```

`reason` is one of `VERIFIED`, `NO_LICENCE`, `PENDING_VERIFICATION`, `REJECTED`, `EXPIRED`, `CLASS_NOT_COVERED`, `NOT_A_DRIVER`
(the last only for `purpose=TAXI_DRIVER` when the licence is fine but the account does not have the `DRIVER` role).
`eligible` is `true` only for `VERIFIED`. Unknown user: `404 USER_NOT_FOUND`.

### INT-11 · Trip payment and refund: provided by APP-07, called by APP-06

**Charge once per booking**

`POST /api/v1/payments` with header `Idempotency-Key: <bookingId>-pay`

```json
{
  "bookingId": "b-5001",
  "userId": "u-101",
  "amountPaise": 18500,
  "method": "WALLET",
  "splits": [
    { "providerId": "DMRC", "amountPaise": 4000 },
    { "providerId": "ZIPRIDE", "amountPaise": 14500 }
  ]
}
```

`method` is `WALLET` or `GATEWAY`. The sum of `splits` must equal `amountPaise` (`400 SPLIT_MISMATCH` otherwise).

Response `201` (or `200` with the **same body** when the same `Idempotency-Key` is sent again, so a retry never charges twice):

```json
{
  "paymentId": "p-9001",
  "bookingId": "b-5001",
  "status": "CAPTURED",
  "amountPaise": 18500,
  "failureReason": null,
  "createdAt": "2026-11-15T10:00:02Z"
}
```

`status` is `CAPTURED` or `FAILED` (`failureReason` e.g. `INSUFFICIENT_BALANCE`, `GATEWAY_DECLINED`).
A `FAILED` charge is also replayed as `FAILED` for the same key; to try again the caller sends a new key
(for example `b-5001-pay-2`).

**Refund**

`POST /api/v1/payments/{paymentId}/refunds` with header `Idempotency-Key: <bookingId>-refund`

```json
{ "amountPaise": 18500, "reason": "BOOKING_FAILED" }
```

Response `201`: `{ "refundId": "r-77", "paymentId": "p-9001", "status": "REFUNDED", "amountPaise": 18500 }`.
Refunding more than was captured: `400 REFUND_EXCEEDS_PAYMENT`.

### INT-12 · Leg hold, confirm, release and unlock: provided by APP-08, called by APP-06

**Hold a leg**

`POST /api/v1/holds`

```json
{
  "bookingId": "b-5001",
  "legId": "b-5001-L2",
  "providerId": "ZIPRIDE",
  "mode": "SCOOTER",
  "from": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197 },
  "to":   { "name": "India Gate", "lat": 28.6129, "lon": 77.2295 },
  "startTime": "2026-11-15T10:20:00Z",
  "vehicleId": "ZR-SC-0042"
}
```

Response `201`:

```json
{
  "holdId": "h-301",
  "legId": "b-5001-L2",
  "providerId": "ZIPRIDE",
  "status": "HELD",
  "pricePaise": 14500,
  "expiresAt": "2026-11-15T10:10:00Z"
}
```

No capacity or vehicle taken: `409 NOT_AVAILABLE`. Unknown provider: `404 PROVIDER_NOT_FOUND`.

**Confirm a hold** (only after payment is captured)

`POST /api/v1/holds/{holdId}/confirm`

```json
{
  "holdId": "h-301",
  "status": "CONFIRMED",
  "access": {
    "type": "UNLOCK_CODE",
    "value": "ZR-7731",
    "validFrom": "2026-11-15T10:15:00Z",
    "validUntil": "2026-11-15T11:15:00Z"
  },
  "driver": null
}
```

`access.type` is `QR_TICKET` (metro / bus, `value` = ticket payload), `UNLOCK_CODE` (scooter, bike, car) or
`DRIVER_ASSIGNED` (taxi; then `driver` = `{ "driverId", "name", "vehicleNumber", "etaMinutes" }`).
Expired or released hold: `409 HOLD_NOT_ACTIVE`.

**Release a hold** (undo step)

`DELETE /api/v1/holds/{holdId}` → `204`. Releasing an already released hold also returns `204` (safe to repeat).

**Unlock a vehicle when the leg starts**

`POST /api/v1/vehicles/{vehicleId}/unlock` with `{ "holdId": "h-301", "userId": "u-101" }`
→ `200 { "vehicleId": "ZR-SC-0042", "status": "UNLOCKED", "unlockedAt": "…" }`.
Hold not confirmed: `409 HOLD_NOT_CONFIRMED`.

---

## Client-facing interfaces (INT-01 to INT-09)

Each owner designs these endpoints in their own application and lists them in its README. Base paths:

| Integration | Provider | Base path |
|---|---|---|
| INT-01 Traveller login and profile, INT-06 driver onboarding | APP-04 | `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/licences/**` |
| INT-02 Trip search | APP-05 | `/api/v1/journeys/**` |
| INT-03 Live trip tracking (push) | APP-05 | `/api/v1/trips/{tripId}/live` |
| INT-04 Booking and tickets | APP-06 | `/api/v1/bookings/**` |
| INT-05 Wallet | APP-07 | `/api/v1/wallets/**` |
| INT-07 Driver location and status | APP-08 | `/api/v1/drivers/{driverId}/locations`, `/api/v1/drivers/{driverId}/status` |
| INT-08 Ride offers to driver (push) | APP-08 | `/api/v1/drivers/{driverId}/offers` |

**Push in the demo:** INT-03 and INT-08 are push flows (WebSocket in the architecture). The demo uses **Server-Sent Events**
(`text/event-stream`): the same one-way push from server to app, but it can be watched with a browser or `curl`, with no extra
client code.

---

## Events (asynchronous messaging; Kafka topics in production)

Every event uses one envelope:

```json
{
  "eventId": "6f1c…",
  "topic": "booking.events",
  "type": "BOOKING_CONFIRMED",
  "source": "mmts-booking",
  "occurredAt": "2026-11-15T10:00:05Z",
  "payload": { }
}
```

| Topic (integration) | Publisher | Type | Payload fields | Subscribers |
|---|---|---|---|---|
| `licence.events` (INT-15) | APP-04 | `LICENCE_VERIFIED` | `userId`, `vehicleClasses[]`, `expiresOn` | APP-06, APP-08 |
| | | `LICENCE_REJECTED` | `userId`, `reason` | |
| | | `LICENCE_EXPIRED` | `userId`, `expiredOn` | |
| `vehicle.events` (INT-14) | APP-08 | `VEHICLE_POSITION` | `vehicleId`, `providerId`, `mode`, `lat`, `lon`, `speedKmph`, `batteryPct` (left out when unknown; event payloads never contain null values), `recordedAt` | APP-05 |
| | | `VEHICLE_AVAILABILITY` | `vehicleId`, `providerId`, `mode`, `available`, `lat`, `lon` | |
| `schedule.events` (INT-14) | APP-08 | `SCHEDULE_UPDATED` | `providerId`, `feedVersion`, `routeIds[]` | APP-05 |
| `booking.events` (INT-16) | APP-06 | `BOOKING_CONFIRMED` | `bookingId`, `userId`, `totalPaise`, `legs[]` (`legId`, `mode`, `providerId`, `from`, `to`, `startTime`) | APP-05 |
| | | `BOOKING_CANCELLED` | `bookingId`, `userId`, `reason` | |
| | | `BOOKING_COMPLETED` | `bookingId`, `userId` | |
| `payment.events` (INT-17) | APP-07 | `PAYMENT_CAPTURED` | `paymentId`, `bookingId`, `amountPaise` | APP-06 |
| | | `PAYMENT_FAILED` | `paymentId`, `bookingId`, `reason` | |
| | | `PAYMENT_REFUNDED` | `refundId`, `paymentId`, `bookingId`, `amountPaise` | |
| `pricing.rules` (INT-18) | APP-09 | `PRICING_RULES_UPDATED` | `surgeFactor`, `commissionPercent`, `promotions[]` | APP-05, APP-06, APP-07 |
| `audit.events` (INT-19) | every app | `AUDIT` | `actorId`, `actorRoles[]`, `action`, `resourceType`, `resourceId`, `outcome` (no personal data) | APP-09 |

APP-09 is not built, so `pricing.rules` is never published in the demo. Apps that need fares or commission read **default
values from `application.yml`** and also listen for `PRICING_RULES_UPDATED`, so the design stays complete.

Each application publishes its own events through the shared `EventPublisher`. Because the applications run separately in the
demo, an event does not travel to another application; each application shows what it published at `GET /dev/events`.

---

## INT-20 · Daily settlement file (data interface): APP-07 to APP-09

APP-07 writes one CSV per day to `exports/settlement-YYYY-MM-DD.csv`:

```
settlementDate,providerId,grossPaise,commissionPaise,netPaise,paymentCount
2026-11-15,DMRC,400000,20000,380000,100
```
