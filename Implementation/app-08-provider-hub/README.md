# APP-08 Provider Integration Hub

Back-end application for module M5 Provider & Fleet Integration of MMTS (Group CSE752-2025-11).

**Module:** M5 Provider & Fleet Integration · **Owner:** Kirti (MT2025066) · **Port:** 8088

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_provider_hub)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8088/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8088/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-08-provider-hub.md`
