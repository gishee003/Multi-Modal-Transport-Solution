# Applications and services: why MMTS has three applications

> Read this first if you are confused by "APP-04" in older notes. It explains what changed after the project review
> on 2026-11-16 and how the folders in this repository match the architecture.

## 1. What changed

Our first architecture counted **nine** software applications: three front ends (APP-01 to APP-03) and six back-end
services (APP-04 to APP-09). In the review, sir pointed out that this is wrong:

- An **application** is a project that is **deployed and maintained on its own**, with its own users and its own release.
- The back-end services are not deployed as standalone projects. They exist to serve a front end, and the front end
  calls them. So they are **parts of an application**, not applications themselves.

So MMTS now has **three applications**, and the six services sit inside them.

## 2. The three applications

| Application | Users | Front end | Back-end services inside it | Main style | Folder |
|---|---|---|---|---|---|
| **APP-01 Traveller** | Commuters | Traveller mobile app (Android / iOS) | Identity (M1), Journey (M2), Booking (M3), Payment (M4) | Microservices | `app-01-traveller/` |
| **APP-02 Driver & Operator** | Taxi drivers, fleet operators, transport providers | Driver mobile app (Android) | Provider Hub (M5) | Microkernel (plug-in) | `app-02-driver-operator/` |
| **APP-03 Admin & Regulator** | Admins, support, finance, the city regulator | Web portal | Analytics & Compliance (M6) | Layered N-tier | `app-03-admin-regulator/` (design only) |

Old name to new name:

| Old ID | Now called | Part of |
|---|---|---|
| APP-04 Identity & Verification Service | Identity service | APP-01 |
| APP-05 Journey Planning & Tracking Service | Journey service | APP-01 |
| APP-06 Booking & Ticketing Service | Booking service | APP-01 |
| APP-07 Payment & Settlement Service | Payment service | APP-01 |
| APP-08 Provider Integration Hub | Provider Hub service | APP-02 |
| APP-09 Analytics & Compliance Service | Analytics service | APP-03 |

## 3. How we decided which service goes where

We grouped by **users**: each user group gets its own front end, and a back-end service goes into the application whose
users it mainly serves.

- **Identity, Journey, Booking, Payment → APP-01.** Planning, booking, paying and tracking is the commuter's journey.
  Identity is here because almost all logins are commuters.
- **Provider Hub → APP-02.** It talks to drivers (ride offers, GPS stream) and to transport providers (plug-ins).
- **Analytics → APP-03.** Its only users are admins and the regulator, through the portal.

When one application needs another application's data, it calls that application's **API** (or listens to its
**events**). It never reads another application's database. Examples:

| From | To | Why | Integration |
|---|---|---|---|
| APP-01 Booking service | APP-02 Provider Hub service | Hold, confirm and unlock a vehicle for a leg | INT-12 (REST) |
| APP-02 Provider Hub service | APP-01 Identity service | May this driver go online? | INT-13 (REST) |
| APP-02 driver app | APP-01 Identity service | Driver sign-up and licence upload | INT-06 (REST) |
| APP-02 Provider Hub service | APP-01 Journey service | Live vehicle positions | INT-14 (events) |
| APP-01 and APP-02 services | APP-03 Analytics service | Bookings, payments, audit records for reports | INT-16, INT-17, INT-19 (events) |
| APP-03 Analytics service | APP-01 services | New fares and commission | INT-18 (events) |

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
3. **One application would be a monolith**, which conflicts with D4, D9, D10 and D14 (architecture document, section 3.2).
4. **One style per application** (lecture 14): the three need three different styles.

Merging APP-02 into APP-01 (a "driver mode") was also rejected: drivers need constant background location and the
provider plug-in back end has its own main driver (D10, extensibility).

## 6. What changed in the code

Only names and folders. No behaviour changed and all tests still pass.

- Folders moved: `app-04-identity` → `app-01-traveller/identity-service` (and the same for the others).
- `app-template` → `service-template`, `create_app.py` → `create_service.py`.
- Module guides renamed: `docs/modules/APP-04-identity.md` → `docs/modules/M1-identity-service.md` (and so on).
- `mmts.app-id` in each `application.yml` now names the **application the service belongs to** (`APP-01` or `APP-02`),
  so `GET /api/v1/info` and the Swagger title show it.
- Comments and docs say "Identity service", "Booking service" and so on instead of APP-04, APP-06.
- Artifact names (`mmts-identity`, …), Java packages, ports and database names did **not** change.

If you had cloned the repository before this change, run `git pull`; your old folders move automatically.
