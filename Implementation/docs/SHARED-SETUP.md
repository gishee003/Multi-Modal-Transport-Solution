# Shared setup: rules every application follows

These rules keep the five applications consistent, so they look like one team built them and could be connected later.

## 1. Tools and versions

| Item | Choice | Why |
|---|---|---|
| Language | Java, compiled for **Java 17** | Runs on any teammate's laptop with Java 17 or newer |
| Framework | **Spring Boot 3.5.16** | MVC framework from lecture 08; built-in security for RBAC (lecture 07) |
| Build | Maven 3.9+ | One `pom.xml` per application |
| Database | H2 (demo) / PostgreSQL (real) | See `DEMO-VS-PRODUCTION.md` |
| API docs | springdoc-openapi (Swagger UI) | Every endpoint can be tried in the browser |
| Login tokens | JJWT 0.12.6 (signed JWT, HS256) | One shared way to check who is calling |
| Tests | JUnit 5, Spring Boot Test, MockMvc | Unit tests plus endpoint (contract) tests, lecture 19 |

## 2. Names, ports and packages

| Application | Folder | `artifactId` | Java package | Port |
|---|---|---|---|---|
| APP-04 Identity & Verification | `app-04-identity` | `mmts-identity` | `com.mmts.identity` | 8084 |
| APP-05 Journey Planning & Tracking | `app-05-journey` | `mmts-journey` | `com.mmts.journey` | 8085 |
| APP-06 Booking & Ticketing | `app-06-booking` | `mmts-booking` | `com.mmts.booking` | 8086 |
| APP-07 Payment & Settlement | `app-07-payment` | `mmts-payment` | `com.mmts.payment` | 8087 |
| APP-08 Provider Integration Hub | `app-08-provider-hub` | `mmts-provider-hub` | `com.mmts.provider` | 8088 |

All endpoints start with `/api/v1/`.

## 3. Folder structure inside each application

The layers follow Figure 3 of the architecture document (closed layers, open security layer, lecture 15).

```
src/main/java/com/mmts/<app>/
├── <App>Application.java      start-up class
├── common/                    shared code, identical in every app (do not change per app)
│   ├── security/              JWT check, roles, security rules (the open security layer)
│   ├── error/                 one error format for every app
│   ├── events/                EventPublisher interface + in-process event bus (Kafka stand-in)
│   ├── config/                cache, Swagger, clock
│   └── dev/                   demo-only helpers: issue a test token, list published events
├── api/                       REST controllers and request / response DTOs (API layer)
├── service/                   business rules and workflows (service layer)
├── domain/                    entities, enums, state machines (domain layer)
├── repository/                Spring Data repositories (persistence layer, DAO pattern)
└── adapter/                   outbound adapters: stubs for other apps and external partners
```

Rules:

- A controller never calls a repository directly; it calls a service.
- Entities never leave the service layer; controllers return DTOs (Java `record`s).
- Anything outside the application (another MMTS app, DigiLocker, Razorpay, …) is reached through an interface in `adapter/`
  with a `Stub…` implementation. The stub is the "stub" from lecture 19's stubs and drivers.
- Settings (fares, timeouts, keys) go in `application.yml`, never in code (Externalization pattern, lecture 06a).

## 4. One error format

Every error response has the same JSON shape:

```json
{
  "code": "BOOKING_NOT_FOUND",
  "message": "No booking with id 42",
  "details": [],
  "timestamp": "2026-11-15T10:00:00Z"
}
```

Throw `ApiException` with an HTTP status and a code; `GlobalExceptionHandler` turns it into this shape.
Validation errors (`@Valid`) list each bad field in `details`.

## 5. Login tokens and roles (shared by all apps)

- APP-04 issues tokens; every app **checks** them the same way (`common/security`).
- A token is a JWT signed with HS256 using the shared key `mmts.security.jwt-secret` (in `application.yml`;
  override with the environment variable `MMTS_JWT_SECRET` outside the demo).
- Claims: `sub` = user id, `roles` = list of role names, `iss` = `mmts-identity`, expiry 60 minutes.
- Roles:

| Role | Who |
|---|---|
| `TRAVELLER` | Commuter using APP-01 |
| `DRIVER` | Taxi driver using APP-02 |
| `OPERATOR` | Fleet / provider staff |
| `ADMIN` | Platform admin |
| `SUPPORT` | Support staff (complaints, refunds) |
| `FINANCE` | Finance staff |
| `REGULATOR` | City regulator (read-only) |
| `SERVICE` | Another MMTS application calling this one |

- Protect endpoints with `@PreAuthorize("hasRole('TRAVELLER')")` and similar.
- Open without a token: `/actuator/health`, `/swagger-ui/**`, `/v3/api-docs/**`, `/h2-console/**`, `/dev/**` (demo only).
- **Testing without APP-04:** in demo mode, `POST /dev/token` returns a valid token for any user id and roles,
  so every app can be tested on its own.

## 6. Events (stand-in for Kafka)

- Publish with the shared `EventPublisher` interface: `eventPublisher.publish("booking.events", "BOOKING_CONFIRMED", payload)`.
- Topic names and payloads are fixed in `CONTRACTS.md`.
- Receive with Spring's `@EventListener` on `TopicMessage`, filtering on the topic.
- In demo mode `GET /dev/events` lists the last 100 events the app published, which is useful in the demo.

## 7. Caching (stand-in for Redis)

Use `@Cacheable("cache-name")` on a service method. Caching is switched on in `common/config/CacheConfig`.
In the demo it is an in-memory cache; with Redis it would be the same annotation.

## 8. Tests

Every application has at least:

1. **Unit tests** for its main business rules (for example "an expired licence is refused").
2. **Endpoint tests** with MockMvc that check the HTTP method, path, status codes and JSON fields of the endpoints listed in
   `CONTRACTS.md`. These are the contract tests from lecture 19.
3. The shared tests copied from the template (token check, error format, health endpoint).

Run with `mvn test`. All tests must pass before a module is called done.

## 9. Sample data

Each application loads a small set of realistic sample data on start-up in demo mode (a `DemoDataLoader` class marked
`@Profile("demo")`), so every demo works immediately.

## 10. README layout for each application

Every application's `README.md` uses these headings:

1. **What this application does** (module, owner, functions covered)
2. **How to run** (demo and PostgreSQL)
3. **Architecture drivers demonstrated** (and how to see each one in the demo)
4. **Design patterns used** (where in the code, and why)
5. **Endpoints** (table)
6. **Demo script** (step-by-step requests to show in the review)
7. **What is stubbed** (and what the real integration would be)
