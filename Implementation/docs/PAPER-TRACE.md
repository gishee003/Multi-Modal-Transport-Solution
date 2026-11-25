# Paper trace: one multimodal trip through MMTS

> One multimodal trip planned on paper and walked through the architecture. The same trace is in the
> architecture document (section 3.6, Figure 9). This page adds the **real endpoints and events of our code** for each
> step, so the team can follow the trace in Swagger and so the proof of concept can follow it.

## Scenario

A traveller goes from home to a meeting by **rail** with TSP-1 (a ticket bought through ONDC, e.g. DMRC metro) and then
by **cab** with TSP-2 (a cab fleet whose drivers use the TSP staff app, e.g. CITYCAB). Both TSPs were onboarded earlier:
their contracts are ACTIVE in M7, so their connectors in the TSP Integration Hub are enabled.
A flight + cab trip follows exactly the same steps: a flight is another ticket-type TSP.

## Steps

| Step | What happens | Service · endpoint or event in our code | Driver |
|---|---|---|---|
| 0 | Both TSPs onboarded: contract approved, connector enabled | Contracts · `POST /api/v1/contracts/{id}/approve` → `TSP_CONTRACT_ACTIVATED`; Hub · provider `ENABLED` | D15, D16 |
| 1–2 | Search A → B; ranked options with fares from active TSPs | Journey · `GET /api/v1/journeys/search?from=…&to=…&rankBy=…` (INT-02) | D4 |
| 3 | Book the option with an idempotency key | Booking · `POST /api/v1/bookings` (`Idempotency-Key`) | D6 |
| 4 | Both TSPs' contracts active, terms known (local copy) | Booking and Payment keep the terms from `TSP_CONTRACT_ACTIVATED` on `contract.events` (INT-20, earlier) | D15 |
| 5–8 | Hold leg 1 (rail) and leg 2 (cab) for 10 minutes | Hub · `POST /api/v1/holds` (INT-12) → ONDC connector / taxi connector | D6, D10 |
| 9–10 | Charge once, split per TSP | Payment · `POST /api/v1/payments` (INT-11, `Idempotency-Key`) → Razorpay (stub) | D7, D8, D9 |
| 11–13 | Confirm legs: QR ticket for rail, nearest driver for cab | Hub · `POST /api/v1/holds/{id}/confirm` → `QR_TICKET` / `DRIVER_ASSIGNED`; driver gets `ride-offer` on `GET /api/v1/drivers/{id}/offers` | D7 |
| 14–15 | Booking confirmed; ticket and driver returned | Booking · event `BOOKING_CONFIRMED` on `booking.events` | D5 |
| 16–19 | Live trip and "your cab is 4 min away" alert | Hub · `vehicle.events` → Journey · `GET /api/v1/trips/{tripId}/live` (SSE) | D5, D11 |
| 20 | Driver location stream | Hub · `POST /api/v1/drivers/{id}/locations` (INT-07) | D11 |
| 21–23 | After the trip: metrics, payouts, SLA check, audit | Contracts · `POST /api/v1/sla/measurements`; Payment payouts; `audit.events` everywhere | D8, D12, D15, D16 |

**Failure paths.** Hold of leg 2 fails (no cab, or TSP-2 suspended → Hub answers `409 TSP_CONTRACT_INACTIVE`):
Booking releases hold 1 (`DELETE /api/v1/holds/{id}`) and charges nothing. A confirm fails after payment: Booking
releases the holds, Payment refunds (`POST /api/v1/payments/{id}/refunds`), `BOOKING_CANCELLED` is published.

## What the trace showed

1. Booking needs to know whether each TSP may still be offered and its cancellation terms → a local copy from
   `contract.events` (INT-20) is enough, because the TSP Hub refuses a hold for a TSP without an active contract.
2. The platform's share differs per TSP → Payment takes the commission from each contract, not one global setting.
3. A delay on leg 1 can make the cab too early → Journey's delay alert (M2.6) and Booking's modify (M3.5) cover it.

No step needed a component the architecture does not already have.

## Validation checks in the document

Besides the trace, the architecture document now has a **Validation** part in sections 2 and 3:
features F01–F15 → module functions; every external module used; no cycle of synchronous calls; every module in
exactly one service and application; no shared database; and a table showing, for each driver D1–D16, the decision
that addresses it and the test or document section that checks it.

In the demo the services run separately, so an event from one does not reach another; each step can be shown in its
own service's Swagger UI. The **proof of concept** runs this trace end to end through a real API gateway and Kafka
broker: see `POC.md` and `poc/TRACE-OUTPUT.txt`.
