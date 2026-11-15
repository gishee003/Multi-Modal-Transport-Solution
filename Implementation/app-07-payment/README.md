# APP-07 Payment & Settlement Service

Back-end application for module M4 Payment & Settlement of MMTS (Group CSE752-2025-11).

**Module:** M4 Payment & Settlement · **Owner:** Kautilya (MT2025064) · **Port:** 8087

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_payment)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8087/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8087/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-07-payment.md`
