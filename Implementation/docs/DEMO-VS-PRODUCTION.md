# Demo vs production: which technologies run in the demo

The Technical Architecture Document describes MMTS as it would run **in production**. It names these technologies:

- **PostgreSQL** for each service's database (DB-1 to DB-6) and for the reporting database of APP-03 (DB-7)
- **Redis** as a fast in-memory cache (CACHE-1 for Journey service, CACHE-2 for TSP Integration Hub)
- **Apache Kafka** as the event broker, run by AWS (Amazon MSK, MSK-1)
- Real external partners: **DigiLocker, Razorpay, ONDC, Delhi Open Transit Data, Google Maps, FCM, SMS gateway**

MySQL is **not** part of the design.

## The approach: interfaces with swappable stand-ins

Every service talks to a database, cache, event broker or partner **only through an interface** (a Java interface or a
Spring abstraction). Each service has two run modes, called Spring *profiles*:

| Technology in the document | `demo` profile (default) | `postgres` profile |
|---|---|---|
| PostgreSQL | **H2**, a database that runs inside the application, in PostgreSQL-compatible mode. Nothing to install. | **Real PostgreSQL 18**: same code, one setting changed |
| Redis (Journey service, TSP Integration Hub) | Spring's in-memory cache behind the same `@Cacheable` cache interface | In-memory cache (Redis is not installed) |
| Kafka (events) | An in-process event bus behind the shared `EventPublisher` interface | In-process event bus (Kafka is not installed) |
| DigiLocker, Razorpay, ONDC, … | Stub adapters that return realistic answers | Stub adapters (real partners need business accounts) |

## Why this is a fair way to demonstrate the architecture

1. **It follows our own principles.** Principle **P3** says external systems are reached only through adapters, and principle
   **P6** says services are independent of the technology underneath. Swapping H2 for PostgreSQL, or the in-process bus
   for Kafka, without touching business code is that principle working.
2. **PostgreSQL can be shown live.** Run a service once in demo mode, then again with
   `mvn spring-boot:run -Dspring-boot.run.profiles=postgres`: the same code now stores its data in the PostgreSQL 18
   installed on the laptop.
3. **Redis and Kafka are swappable the same way.** Adding them means adding a dependency and a configuration file
   (Spring Data Redis, Spring for Apache Kafka) plus one new `EventPublisher` class. Business code does not change.
4. **One-command demo on any laptop.** Redis and Kafka would need Docker on all five laptops, which adds setup risk on the day
   and does not change what the architecture shows.
5. **Real partners need real business accounts.** DigiLocker partner onboarding, a Razorpay merchant account and ONDC
   registration are not available to a student project. The stubs follow the real integration styles documented in section 5
   of the architecture document (for example, the ONDC stub uses the same request / callback pairs as the Beckn protocol).

## The `poc` profile (proof of concept, added 25 Nov)

Run together with `demo` (`--spring.profiles.active=demo,poc`). Two stand-ins are replaced by the real thing:
events go through a **real Kafka broker** (`KafkaEventPublisher` / `KafkaEventBridge` in `common/events`) and the
stubs for other MMTS services are replaced by **real HTTP calls** (`Http…Client` classes). Partners outside MMTS stay
stubs. Start everything with `poc/start-poc.ps1`; details in `POC.md`.

## Using the `postgres` profile

1. Create the database once (PostgreSQL 18 is already installed on this laptop):

   ```bash
   psql -U postgres -c "CREATE DATABASE mmts_identity"     # use the app's own database name, see its README
   ```

2. Run the service with the profile. Username and password default to `postgres` / `postgres`;
   override them with environment variables if yours differ:

   ```bash
   # Git Bash / macOS / Linux
   DB_USER=postgres DB_PASSWORD=yourpassword mvn spring-boot:run -Dspring-boot.run.profiles=postgres
   ```

   ```powershell
   # Windows PowerShell
   $env:DB_USER="postgres"; $env:DB_PASSWORD="yourpassword"; mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
   ```

Tables are created automatically on first start.

| Service | Database name |
|---|---|
| Identity service | `mmts_identity` |
| Journey service | `mmts_journey` |
| Booking service | `mmts_booking` |
| Payment service | `mmts_payment` |
| TSP Integration Hub | `mmts_tsp_integration_hub` |
| Contract Management service | `mmts_contract_management` |

One database per service, matching principle **P1** (database per service).
