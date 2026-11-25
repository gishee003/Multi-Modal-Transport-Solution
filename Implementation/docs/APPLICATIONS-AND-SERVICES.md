# Applications and services: why MMTS has three applications

> Read this first if you are confused by "APP-04" or "Driver & Operator" in older notes. It explains why there are three
> applications and how the folders in this repository match the architecture.

## 0. Transport Service Providers and M7

The architecture does not depend on particular modes or providers:

- Every provider of a way to travel (metro, bus, cab fleet, scooter or bike sharing, car rental, any future mode) is a
  **Transport Service Provider (TSP)**. Modes in our code and sample data are only examples.
- Onboarding a TSP has two steps: **business onboarding** (a contract with commercial terms and service levels) and
  **technical onboarding** (one connector for that TSP's systems). A connector is used only while the contract is active.
- New internal module **M7 TSP Contract Management**: TSP registration, contracts, revenue share and fare split,
  cancellation charges, service levels (SLA), automatic suspension after repeated breaches, renewal and off-boarding.
- APP-02 is renamed **TSP Partner** (it was "Driver & Operator"); M5 is renamed **TSP Integration**
  (`provider-hub-service` became `tsp-integration-hub`); M7 lives in APP-02 next to M5.

## 1. What changed

Our first architecture counted **nine** software applications: three front ends (APP-01 to APP-03) and six back-end
services (APP-04 to APP-09). That was wrong:

- An **application** is a project that is **deployed and maintained on its own**, with its own users and its own release.
- The back-end services are not deployed as standalone projects. They exist to serve a front end, and the front end
  calls them. So they are **parts of an application**, not applications themselves.

So MMTS now has **three applications**, and the six services sit inside them.

## 2. The three applications

| Application | Users | Front end | Back-end services inside it | Main style | Folder |
|---|---|---|---|---|---|
| **APP-01 Traveller** | Commuters | Traveller mobile app (Android / iOS) | Identity (M1), Journey (M2), Booking (M3), Payment (M4) | Microservices | `app-01-traveller/` |
| **APP-02 TSP Partner** | TSPs (account managers, operations) and TSP staff such as drivers | TSP partner portal (web) + TSP staff mobile app (Android) | TSP Integration Hub (M5), Contract Management (M7) | Microkernel (hub) + layered (contracts) | `app-02-tsp-partner/` |
| **APP-03 Admin & Regulator** | Admins, support, finance, the city regulator | Web portal | Analytics & Compliance (M6) | Layered N-tier | `app-03-admin-regulator/` (design only) |

Old name to new name:

| Old ID | Now called | Part of |
|---|---|---|
| APP-04 Identity & Verification Service | Identity service | APP-01 |
| APP-05 Journey Planning & Tracking Service | Journey service | APP-01 |
| APP-06 Booking & Ticketing Service | Booking service | APP-01 |
| APP-07 Payment & Settlement Service | Payment service | APP-01 |
| APP-08 Provider Integration Hub | TSP Integration Hub | APP-02 |
| — (new, 25 Nov) | Contract Management service (M7) | APP-02 |
| APP-09 Analytics & Compliance Service | Analytics service | APP-03 |

## 3. How we decided which service goes where

We grouped by **users**: each user group gets its own front end, and a back-end service goes into the application whose
users it mainly serves.

- **Identity, Journey, Booking, Payment → APP-01.** Planning, booking, paying and tracking is the commuter's journey.
  Identity is here because almost all logins are commuters.
- **TSP Integration Hub and Contract Management → APP-02.** Their users are the TSPs themselves: connectors, fleet health,
  staff (ride offers, GPS stream), contracts, SLA reports and settlements. The contract status also decides which
  connectors in the same application are switched on. The MMTS partner team approves contracts from the APP-03 portal
  through M7's API.
- **Analytics → APP-03.** Its only users are admins and the regulator, through the portal.

When one application needs another application's data, it calls that application's **API** (or listens to its
**events**). It never reads another application's database. Examples:

| From | To | Why | Integration |
|---|---|---|---|
| APP-01 Booking service | APP-02 TSP Integration Hub | Hold, confirm and unlock a vehicle for a leg | INT-12 (REST) |
| APP-02 TSP Integration Hub | APP-01 Identity service | May this driver go online? | INT-13 (REST) |
| APP-02 TSP staff app | APP-01 Identity service | Driver sign-up and licence upload | INT-06 (REST) |
| APP-02 TSP Integration Hub | APP-01 Journey service | Live vehicle positions | INT-14 (events) |
| APP-01 and APP-02 services | APP-03 Analytics service | Bookings, payments, audit records for reports | INT-16, INT-17, INT-19 (events) |
| APP-03 Analytics service | APP-01 services | New fares and promotions | INT-18 (events) |
| APP-02 Contract Management | APP-02 TSP Integration Hub, APP-01 Payment and Booking | Contract activated / suspended / terminated, commercial terms | INT-20 (events `contract.events`) |

## 4. "If APP-01 is one application, why are its four services separate projects?"

This is the question most likely to come up. Our answer:

- APP-01 is **one application**: one product, one release pipeline, one owner team.
- **Inside** it, the back end is split into four services (microservices style) because the parts have very different
  needs: route search must scale at rush hour (D4), payment must sit in an isolated zone for card-payment rules (D9), and
  a fault in one part must not stop the others (D14).
- Each service still runs as its own container, so the services talk over REST and events, not by direct method calls.
- For the course, each member builds one service, so each service also has its own Maven project here. That is how we
  split the **work**; it does not make each service a separate application.

## 5. Why not fewer than three?

One application for everything was considered and rejected:

1. **Different users and devices.** A public phone app for commuters, an always-online Android app for drivers, and a
   web portal for admins that is reached only through a VPN with hardware security keys. Admin screens cannot sit inside a
   public app (security, D12).
2. **Different releases.** The two mobile apps go through the app stores; the portal is deployed on our own servers.
3. **One application would be a monolith**, which conflicts with D4, D9, D10 and D14 (architecture document, section 3).
4. **One style per application** (lecture 14): the three need three different styles.

Merging APP-02 into APP-01 (a "driver mode") was also rejected: drivers need constant background location and the
provider plug-in back end has its own main driver (D10, extensibility).

## 6. What changed in the code

### 6.1 On 16 Nov (nine applications → three)

Only names and folders. No behaviour changed and all tests still pass.

- Folders moved: `app-04-identity` → `app-01-traveller/identity-service` (and the same for the others).
- `app-template` → `service-template`, `create_app.py` → `create_service.py`.
- Module guides renamed: `docs/modules/APP-04-identity.md` → `docs/modules/M1-identity-service.md` (and so on).
- `mmts.app-id` in each `application.yml` now names the **application the service belongs to** (`APP-01` or `APP-02`),
  so `GET /api/v1/info` and the Swagger title show it.
- Comments and docs say "Identity service", "Booking service" and so on instead of APP-04, APP-06.
- Artifact names (`mmts-identity`, …), Java packages, ports and database names did **not** change.

If you had cloned the repository before this change, run `git pull`; your old folders move automatically.

### 6.2 TSP language and M7

- `app-02-driver-operator/provider-hub-service` → `app-02-tsp-partner/tsp-integration-hub` (artifact
  `mmts-tsp-integration-hub`, database `mmts_tsp_integration_hub`). The Java package stays `com.mmts.provider`, the
  settings prefix `mmts.provider-hub` and the demo paths `/dev/provider-hub/...`: in the code, **"provider" means TSP**.
- New service `app-02-tsp-partner/contract-management-service` (M7, port 8089): TSP registration, contract life cycle,
  commercial terms, SLA monitoring with automatic suspension; publishes `contract.events`.
- The TSP Integration Hub now listens to `contract.events`: a TSP whose contract is suspended or terminated gets
  `409 TSP_CONTRACT_INACTIVE` on new holds and its feeds are not read (business onboarding gates technical onboarding).
- Role comments, Swagger texts and docs use "TSP" and "TSP staff (e.g. drivers)" instead of "provider" and "taxi driver".
  Modes such as TAXI, SCOOTER or METRO remain in the code as **examples**; adding a mode needs no change to the core.
