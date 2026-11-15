# MMTS Implementation (Group CSE752-2025-11)

This folder holds the individual implementations for the Multi-Modal Transport Solution (MMTS).
Each member builds **one back-end application** from the Technical Architecture Document
(`../Technical Architecture/MMTS_Technical_Architecture.docx`, section 3.7).

| Member | Roll no. | Module | Application | Folder | Port |
|---|---|---|---|---|---|
| Athira | MT2025027 | M1 User, Account & Verification | APP-04 Identity & Verification Service | `app-04-identity` | 8084 |
| Diksha | MT2025045 | M2 Journey Planning & Tracking | APP-05 Journey Planning & Tracking Service | `app-05-journey` | 8085 |
| Jeevesh | MT2025054 | M3 Booking & Ticketing | APP-06 Booking & Ticketing Service | `app-06-booking` | 8086 |
| Kautilya | MT2025064 | M4 Payment & Settlement | APP-07 Payment & Settlement Service | `app-07-payment` | 8087 |
| Kirti | MT2025066 | M5 Provider & Fleet Integration | APP-08 Provider Integration Hub | `app-08-provider-hub` | 8088 |

M6 (APP-09 Analytics & Compliance) is covered by the group design only.

## What is in this folder

| Path | What it is |
|---|---|
| `docs/SHARED-SETUP.md` | The rules every application follows: tools, folder structure, errors, login tokens, events, tests, README layout |
| `docs/CONTRACTS.md` | The agreed request / response and event formats between applications (INT-10 to INT-19) |
| `docs/DEMO-VS-PRODUCTION.md` | Why the demo runs on H2 and in-memory stand-ins while the document names PostgreSQL, Redis and Kafka |
| `app-template/` | The starting project every application is copied from (contains the shared code) |
| `app-04-…` to `app-08-…` | The five applications, one per member |

## Running an application

Each application is an independent Maven project. You need **Java 17 or newer** and **Maven 3.9+**.

```bash
cd app-04-identity
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
and real partners such as DigiLocker and Razorpay. In this implementation every application talks to these only **through an
interface**, and the demo plugs in a light stand-in behind that interface: H2 instead of PostgreSQL, an in-memory cache instead
of Redis, an in-process event bus instead of Kafka, and stub adapters instead of the real partners. Switching to the real thing is
a configuration change, not a code change, which is what principles **P3** (external systems only through adapters) and **P6**
(technology independence) in the document require. PostgreSQL can be shown live: the same code runs on PostgreSQL 18 with
`-Dspring-boot.run.profiles=postgres`. Full explanation: `docs/DEMO-VS-PRODUCTION.md`.
