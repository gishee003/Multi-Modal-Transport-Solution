# APP-05 Journey Planning & Tracking Service

> Starting project for every MMTS application. It already contains the shared code (`common/`) and shared tests.
> Each application's README replaces this file and keeps the headings below.

## 1. What this application does

- **Module:** Mx …
- **Owner:** … (roll number)
- **Functions covered:** Mx.1 …, Mx.2 …

## 2. How to run

```bash
mvn spring-boot:run                                        # demo mode (H2), port 8085
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_journey)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8085/swagger-ui.html
- Health: http://localhost:8085/actuator/health
- H2 console: http://localhost:8085/h2-console (JDBC URL `jdbc:h2:mem:mmts-journey`, user `sa`, no password)

Get a test token: `POST /dev/token` with `{"userId":"u-101","roles":["TRAVELLER"]}`, then press **Authorize** in Swagger UI.

## 3. Architecture drivers demonstrated

| Driver | How this application meets it | How to see it in the demo |
|---|---|---|

## 4. Design patterns used

| Pattern | Where in the code | Why |
|---|---|---|

## 5. Endpoints

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/info` | any logged-in user | Which application this is, and who is calling |

## 6. Demo script

1. …

## 7. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
