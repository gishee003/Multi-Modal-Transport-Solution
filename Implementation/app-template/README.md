# APP-00 Template

Starting project for every MMTS application. Contains the shared code (`common/`) and the 9 shared tests.

**Module:** Mx … · **Owner:** … · **Port:** 8080

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_template)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8080/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8080/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/GUIDE-TEMPLATE.md`
