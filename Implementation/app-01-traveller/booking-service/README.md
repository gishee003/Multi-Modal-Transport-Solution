# Booking service

Back-end service of application APP-01 Traveller, for module M3 Booking & Ticketing of MMTS.

**Module:** M3 Booking & Ticketing · **Owner:** Jeevesh · **Port:** 8086

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
| GET | `/api/v1/info` | Which application this service belongs to, and who is calling |
| POST | `/api/v1/bookings` | Book every leg of a journey, all-or-nothing (header `Idempotency-Key`) |
| GET | `/api/v1/bookings`, `/api/v1/bookings/{id}` | List bookings / one booking with legs, tickets and saga log |
| POST | `/api/v1/bookings/{id}/cancel` | Cancel: release holds and refund |
| POST | `/api/v1/bookings/{id}/complete` | Mark the trip finished |
| POST | `/api/v1/bookings/{id}/rating`, `/api/v1/bookings/{id}/problems` | Rate the trip (1-5), report a problem (GET lists problems) |
| POST | `/api/v1/bookings/{id}/legs/{legId}/unlock` | Unlock the scooter / bike / car of a leg |
| POST | `/api/v1/bookings/tickets/verify` | Check a QR ticket offline, as a gate would |
| GET/POST | `/dev/stubs/**` | Demo only: make the next payment / hold / confirm / licence check fail |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../../docs/modules/M3-booking-service.md`
