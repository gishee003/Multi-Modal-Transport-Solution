# MMTS Implementation (Group CSE752-2025-11)

This folder holds the individual implementations for the Multi-Modal Transport Solution (MMTS).
MMTS has **three applications**: the projects that are deployed and maintained. Each application is a front end plus the
back-end services it contains. Each member builds **one back-end service** from the Technical Architecture Document
(`../Technical Architecture/MMTS_Technical_Architecture.docx`, section 3.7).

| Member | Roll no. | Module | Service | Part of application | Folder | Port |
|---|---|---|---|---|---|---|
| Athira | MT2025027 | M1 User, Account & Verification | Identity service | APP-01 Traveller | `app-01-traveller/identity-service` | 8084 |
| Diksha | MT2025045 | M2 Journey Planning & Tracking | Journey service | APP-01 Traveller | `app-01-traveller/journey-service` | 8085 |
| Jeevesh | MT2025054 | M3 Booking & Ticketing | Booking service | APP-01 Traveller | `app-01-traveller/booking-service` | 8086 |
| Kautilya | MT2025064 | M4 Payment & Settlement | Payment service | APP-01 Traveller | `app-01-traveller/payment-service` | 8087 |
| Kirti | MT2025066 | M5 Provider & Fleet Integration | Provider Hub service | APP-02 Driver & Operator | `app-02-driver-operator/provider-hub-service` | 8088 |

M6 (Analytics service, part of APP-03 Admin & Regulator) is covered by the group design only. The three front ends
(traveller app, driver app, admin portal) are designed in the architecture document and not built here.
Why three applications and not nine: `docs/APPLICATIONS-AND-SERVICES.md`.

## What is in this folder

| Path | What it is |
|---|---|
| `docs/SHARED-SETUP.md` | The rules every service follows: tools, folder structure, errors, login tokens, events, tests, README layout |
| `docs/CONTRACTS.md` | The agreed request / response and event formats between services (INT-10 to INT-19) |
| `docs/modules/` | One detailed guide per service: how it works, decisions, drivers, patterns, demo script, tests, likely viva questions |
| `docs/APPLICATIONS-AND-SERVICES.md` | The three applications, which services each contains, and why |
| `docs/DEMO-VS-PRODUCTION.md` | Why the demo runs on H2 and in-memory stand-ins while the document names PostgreSQL, Redis and Kafka |
| `service-template/` | The starting project every service is copied from (contains the shared code) |
| `app-01-traveller/` | APP-01: Identity, Journey, Booking and Payment services |
| `app-02-driver-operator/` | APP-02: Provider Hub service |
| `app-03-admin-regulator/` | APP-03: design only (the Analytics service and admin portal are not built) |

## Running a service

Each service is its own Maven project. You need **Java 17 or newer** and **Maven 3.9+**.

```bash
cd app-01-traveller/identity-service
mvn spring-boot:run        # demo mode: H2 database, nothing else to install
mvn test                   # run the tests
```

Then open:

- `http://localhost:<port>/swagger-ui.html`: every endpoint, with a "Try it out" button
- `http://localhost:<port>/actuator/health`: health check
- `http://localhost:<port>/h2-console`: the demo database (JDBC URL `jdbc:h2:mem:<app-name>`, user `sa`, no password)

Run on real PostgreSQL instead (see `docs/DEMO-VS-PRODUCTION.md`):

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

## Demo vs production in one paragraph

The architecture document describes the **production** system: PostgreSQL databases, Redis caches, a Kafka event broker,
and real partners such as DigiLocker and Razorpay. In this implementation every service talks to these only **through an
interface**, and the demo plugs in a light stand-in behind that interface: H2 instead of PostgreSQL, an in-memory cache instead
of Redis, an in-process event bus instead of Kafka, and stub adapters instead of the real partners. Switching to the real thing is
a configuration change, not a code change, which is what principles **P3** (external systems only through adapters) and **P6**
(technology independence) in the document require. PostgreSQL can be shown live: the same code runs on PostgreSQL 18 with
`-Dspring-boot.run.profiles=postgres`. Full explanation: `docs/DEMO-VS-PRODUCTION.md`.
