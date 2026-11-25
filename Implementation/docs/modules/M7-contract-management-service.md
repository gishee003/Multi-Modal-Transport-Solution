# Contract Management service: module guide

**Module:** M7 TSP Contract Management · **Owner:** Group ·
**Folder:** `app-02-tsp-partner/contract-management-service` · **Port:** 8089

## 1. What this service does

MMTS sells trips that use many **Transport Service Providers (TSPs)**: metro operators, bus companies, cab fleets,
scooter and bike sharing, car rental, any future mode. Before a TSP can be offered to travellers, MMTS and the TSP must
agree on business terms, as any travel aggregator does with every operator it sells. This service manages that
agreement. It is its own functional module, separate from M5:

- **M5 (TSP Integration Hub)** = the *technical, runtime* connection to a TSP's systems.
- **M7 (this service)** = the *business relationship*: whether, where and on what terms a TSP may be offered.

Words you need first:

- **Contract:** what a TSP may offer (modes), where (service area), until when (validity), on which **commercial terms**
  and with which **service levels**. Each change makes a new **revision**; old revisions are kept for audit.
- **Commercial terms:** the money side. `commissionPercent` (platform's share of each fare), `bookingFeePaise`,
  `cancellationFeePaise` (charged to the traveller when a confirmed leg is cancelled) and `payoutCycleDays`.
- **Service levels (SLA):** what the TSP promises: at most `maxCancellationRatePct` cancellations, at least
  `minAvailabilityPct` system availability, at most `maxResponseMinutes` pickup / response time (0 = not measured for that
  mode), and how many breached periods in a row (`breachesBeforeSuspension`) lead to automatic suspension.
- **Off-boarding:** ending a TSP's contract (TERMINATED) so it can no longer be offered.

| Function | In plain words | Main class |
|---|---|---|
| M7.1 Register a TSP | Company details, KYC / legal documents, bank account reference, modes, the users who act for it | `ContractService.registerTsp` |
| M7.2 Create the contract | Modes, service area, validity | `ContractService.create` |
| M7.3 Commercial terms | Commission, booking fee, cancellation fee, payout cycle | `CommercialTerms` |
| M7.4 Service levels | Cancellation rate, availability, response time, breaches before suspension | `ServiceLevels` |
| M7.5 Monitor SLA | Compare one period's metrics with the SLA; breach events; automatic suspension | `SlaService` |
| M7.6 Activate, suspend, off-board | Life cycle; every change is published on `contract.events` | `Contract.moveTo`, `ContractStatus` |
| M7.7 Renew and amend | A new revision; approving it ends the old one (kept for history) | `ContractService.approve` |

## 2. How it works

**Life cycle (State pattern):**

```
DRAFT ──submit──> UNDER_REVIEW ──approve──> ACTIVE <──reactivate── SUSPENDED
  ^                    │                       │──────suspend──────────^
  └──── send-back ─────┘           any live state ──terminate──> TERMINATED
```

`ContractStatus` holds the table of allowed moves; any other move is refused with `409 INVALID_TRANSITION`.

**Onboarding a new TSP, step by step:**

1. The TSP's operator registers the TSP in the partner portal (`POST /api/v1/tsps`, role OPERATOR).
2. They draft a contract with terms and SLA (`POST /api/v1/contracts`) and submit it.
3. The MMTS partner team reviews it in the APP-03 portal and approves it (`/approve`, role ADMIN).
4. The service publishes `TSP_CONTRACT_ACTIVATED` with the terms. The TSP Integration Hub enables the connector
   (technical onboarding); the Payment service uses the commission; the Booking service uses the cancellation fee.
5. From now on the TSP can be planned, booked and paid. Other services keep the terms from the event; the portals
   can always read them with `GET /api/v1/tsps/{tspId}/active-terms`.

**SLA monitoring:** once per period (a day or a week) the metrics of each TSP — confirmed legs, legs cancelled by the
TSP, availability, average response time — are posted to `POST /api/v1/sla/measurements`. In production they are
aggregated from events (booking cancellations, the hub's operational metrics). The service computes the cancellation
rate, compares each value with the SLA and records the result. A breach publishes `TSP_SLA_BREACHED`; after
`breachesBeforeSuspension` breaches in a row it suspends the contract itself (`TSP_CONTRACT_SUSPENDED`, reason
`SLA_BREACH`), for example a cab TSP whose drivers keep cancelling. A good period resets the count. Sending the
same period twice returns the stored result and does not count twice.

## 3. Design decisions and why

| Decision | Why | Alternative rejected |
|---|---|---|
| M7 separate from M5 | Contracts are a core business function, the hub is runtime integration; they change for different reasons | Contract fields inside the hub's provider config |
| M7 in APP-02 (TSP Partner) | Its users are the TSPs (portal); the contract status decides which connectors in the same application are on | APP-03 (admin portal): the partner team approves from there through the API instead |
| Publish every state change as an event | The hub, Payment, Booking and Analytics react without M7 knowing them (loose coupling, P2) | M7 calling each service |
| `active-terms` returns `active: false` instead of 404 | Callers ask "may I use this TSP?" and always get a yes / no answer | 404 for "no contract" |
| Amendment = new revision | Old terms stay readable for disputes and audit (M7.7) | Editing the active contract in place |
| Automatic suspension after N breaches | SLA must have teeth; N is per contract, so a strict TSP and a lenient one can coexist | Manual review only |
| Modes are plain strings, not an enum | A new kind of transport (e.g. a ferry) needs no code change; the tests onboard a ferry operator | A fixed enum of modes |
| Optimistic locking (`@Version`) on contracts | Two admins acting at once cannot overwrite each other | No locking |

## 4. Architecture drivers demonstrated

M7 was added after the drivers submission, so the architecture document adds two drivers for it:

| Driver | How this service shows it |
|---|---|
| **D15** A TSP is offered, priced and paid only under its active contract; terms apply automatically and consistently | Only an ACTIVE contract publishes `TSP_CONTRACT_ACTIVATED`; suspension / termination switch the hub's connector off; `active-terms` gives every service the same answer |
| **D16** A new TSP or mode is onboarded through configuration and approval, with SLA breaches acted on automatically | Register → draft → submit → approve, no code change (ferry test); repeated breaches suspend the contract |
| D10 (shared with M5) | Business onboarding of any mode without code change |
| D12 | Every action is published on `audit.events` |

## 5. Design patterns used

| Pattern | Where | Why |
|---|---|---|
| **State** (as a transition table) | `ContractStatus`, `Contract.moveTo` | Illegal moves are impossible, in one place |
| **Observer / publish-subscribe** | `contract.events` published by `ContractService`, `SlaService` | Other services react without being called |
| **Value object** | `CommercialTerms`, `ServiceLevels` (records) | Terms are compared and copied as a whole |
| **Repository** | `TspRepository`, `ContractRepository`, `SlaMeasurementRepository` | Persistence behind interfaces |
| **Idempotency** | One SLA measurement per contract and period (unique key) | A retry never counts a breach twice |

## 6. Endpoints in detail

Register a TSP (`POST /api/v1/tsps`, OPERATOR who lists themself, or ADMIN):

```json
{ "tspId": "KOCHIFERRY", "legalName": "Kochi Water Metro Ltd", "registrationNumber": "U12345KL2020",
  "bankAccountRef": "acc_kwm", "modes": ["FERRY"], "operators": ["op-kwm"], "documents": ["gst.pdf", "permit.pdf"] }
```

Draft a contract (`POST /api/v1/contracts`):

```json
{ "tspId": "KOCHIFERRY", "validFrom": "2026-12-01", "validTo": "2027-11-30", "serviceArea": "Kochi", "modes": ["FERRY"],
  "terms": { "commissionPercent": 8, "bookingFeePaise": 0, "cancellationFeePaise": 500, "payoutCycleDays": 7 },
  "serviceLevels": { "maxCancellationRatePct": 2, "minAvailabilityPct": 99, "maxResponseMinutes": 0,
                     "breachesBeforeSuspension": 2 } }
```

`201` with `contractId` (`c-kochiferry-1`), `status: DRAFT`, `revision: 1`. Then `/submit`, `/approve` (ADMIN).

SLA metrics (`POST /api/v1/sla/measurements`, SERVICE or ADMIN):

```json
{ "tspId": "CITYCAB", "period": "2026-W48", "legsConfirmed": 200, "legsCancelledByTsp": 30,
  "availabilityPct": 99.5, "avgResponseMinutes": 6 }
```

→ `{ "cancellationRatePct": 15.0, "breached": true, "breachReasons": "CANCELLATION_RATE", "consecutiveBreaches": 1,
"contractStatus": "ACTIVE" }`.

Errors: `404 TSP_NOT_FOUND`, `404 CONTRACT_NOT_FOUND`, `404 NO_LIVE_CONTRACT`, `409 TSP_EXISTS`,
`409 INVALID_TRANSITION`, `400 INVALID_DATES`, `400 MODE_NOT_REGISTERED`, `403 NOT_YOUR_TSP`, `400 VALIDATION_FAILED`.

## 7. Demo script

Start: `cd app-02-tsp-partner/contract-management-service` then `mvn spring-boot:run`; open
`http://localhost:8089/swagger-ui.html`. Tokens from `POST /dev/token`: `admin-1` / `ADMIN`, `op-hopbike` / `OPERATOR`,
`op-citycab` / `OPERATOR`, `booking-service` / `SERVICE`.

1. **The TSPs.** `GET /api/v1/tsps/CITYCAB/contracts`: ACTIVE, commission 18 %, SLA. `GET /api/v1/tsps/HOPBIKE/contracts`:
   UNDER_REVIEW.
2. **Business onboarding.** As ADMIN, `POST /api/v1/contracts/c-hopbike-1/approve` → ACTIVE. `GET /dev/events`:
   `TSP_CONTRACT_ACTIVATED` with the terms. Point out: next, the hub enables HOPBIKE's connector (technical onboarding,
   M5 demo step 10).
3. **A new mode.** As `op-kwm` / OPERATOR register `KOCHIFERRY` with mode `FERRY` (section 6), draft, submit; approve as
   ADMIN. No code knows about ferries.
4. **SLA.** As SERVICE post the CITYCAB metrics above for `2026-W48` → breached; again for `2026-W49` → `SUSPENDED`,
   and `GET /dev/events` shows `TSP_SLA_BREACHED` then `TSP_CONTRACT_SUSPENDED` (reason `SLA_BREACH`). In the hub,
   `POST /dev/provider-hub/contracts/CITYCAB/suspend` shows the effect: cab holds get `409 TSP_CONTRACT_INACTIVE`.
5. **Rules.** Approving a DRAFT → `409 INVALID_TRANSITION`; an OPERATOR approving → `403`; another TSP's operator reading
   the contract → `403 NOT_YOUR_TSP`.
6. **Amendment.** Draft revision 2 for ZIPRIDE with commission 10, submit, approve: `GET .../ZIPRIDE/contracts` shows
   revision 2 ACTIVE and revision 1 TERMINATED (`SUPERSEDED`); `active-terms` returns 10.

## 8. What is stubbed

| Real system / application | In this service | Production |
|---|---|---|
| Kafka `contract.events`, `audit.events` | Shared `InProcessEventPublisher`; see `GET /dev/events` | Kafka topics |
| SLA metrics aggregation | Posted to `/api/v1/sla/measurements` | Stream job over `booking.events` and the hub's operational metrics |
| Signed contract documents, e-signature | Document names only | Encrypted object storage (OBJ-1) and an e-sign provider |
| Payout account | `bankAccountRef` | Razorpay Route linked account (EXT-03) |

### Where it runs in production (architecture document, section 6)

- **Servers:** the general node group (VM-1, VM-2), at least two copies in two availability zones. The load is small;
the active terms are read often, so callers keep a copy from `contract.events`.
- **Data:** DB-6 (PostgreSQL run by AWS). Signed contracts and KYC documents are in OBJ-1, encrypted with KMS-1.
- **Events:** `contract.events` and `audit.events` go to Kafka on Amazon MSK (MSK-1), so every service applies the same
terms (D15).

Shared by every service: requests from the apps arrive only through the API gateway (Kong + Keycloak on the general
node group); logs and metrics go to CloudWatch (MON-1); the container image is built and tested by GitHub Actions and
stored in the container registry (REG-1); Kubernetes (EKS-1) restarts a crashed copy and adds copies under load.

## 9. Tests

`mvn test` in `app-02-tsp-partner/contract-management-service`: **17 tests, all passing** (8 module + 9 shared).

| Test (`ContractApiTest`) | What it checks |
|---|---|
| `anyNewModeCanBeOnboardedAndApprovalPublishesTheTerms` | A ferry TSP (a mode nobody else knows) is registered, approved; `TSP_CONTRACT_ACTIVATED` carries the terms; `active-terms` false → true (D10, D15, D16) |
| `lifeCycleRulesAreEnforced` | DRAFT cannot be approved; suspend / reactivate / terminate publish events; a TERMINATED contract cannot come back |
| `anAmendmentReplacesTheOldRevision` | Revision 2 ACTIVE, revision 1 TERMINATED, new commission used (M7.7) |
| `repeatedSlaBreachesSuspendTheContract` | 15 % cancellations breach a 5 % SLA; the same period counts once; the second breach suspends (D16) |
| `aGoodPeriodResetsTheBreachCount` | Breach, good period, breach → still ACTIVE |
| `onlyThePartnerTeamApprovesAndOperatorsSeeOnlyTheirOwnTsp` | OPERATOR cannot approve; other operators and travellers get 403 (RBAC) |
| `badInputIsRefused` | `MODE_NOT_REGISTERED`, `TSP_EXISTS`, `NO_LIVE_CONTRACT` |
| `demoDataHasFiveActiveTspsAndOneUnderReview` | Sample data matches the hub's six TSPs |
