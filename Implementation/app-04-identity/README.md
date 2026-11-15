# APP-04 Identity & Verification Service

Back-end application for module M1 User, Account & Verification of MMTS (Group CSE752-2025-11).

**Module:** M1 User, Account & Verification · **Owner:** Athira (MT2025027) · **Port:** 8084

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_identity)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8084/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8084/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-04-identity.md`
