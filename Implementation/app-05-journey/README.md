# APP-05 Journey Planning & Tracking Service

Back-end application for module M2 Journey Planning & Tracking of MMTS (Group CSE752-2025-11).

**Module:** M2 Journey Planning & Tracking · **Owner:** Diksha (MT2025045) · **Port:** 8085

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_journey)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8085/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8085/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-05-journey.md`
