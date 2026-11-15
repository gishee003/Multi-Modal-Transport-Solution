# APP-06 Booking & Ticketing Service

Back-end application for module M3 Booking & Ticketing of MMTS (Group CSE752-2025-11).

**Module:** M3 Booking & Ticketing · **Owner:** Jeevesh (MT2025054) · **Port:** 8086

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_booking)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8086/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8086/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/info` | Which application this is, and who is calling |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-06-booking.md`
