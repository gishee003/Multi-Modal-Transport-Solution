# Journey service

Back-end service of application APP-01 Traveller, for module M2 Journey Planning & Tracking of MMTS (Group CSE752-2025-11).

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

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/journeys/search?from=PL-IITD&to=PL-IG&rankBy=FASTEST` | TRAVELLER | Mixed-mode journey options with time and fare per leg (INT-02) |
| GET | `/api/v1/journeys/places` | TRAVELLER | Stations, stops, docks and places of the sample Delhi network |
| GET | `/api/v1/journeys/nearby?lat=28.6328&lon=77.2197` | TRAVELLER | Free scooters, bikes and taxis near a point, with "last seen" |
| GET | `/api/v1/trips`, `/api/v1/trips/{tripId}` | TRAVELLER (owner) | My trips, one trip with the state of each leg |
| GET | `/api/v1/trips/{tripId}/live` | TRAVELLER (owner) | Live positions, ETA and alerts as Server-Sent Events (INT-03) |
| POST | `/dev/journey/bookings`, `/vehicle-positions`, `/pricing-rules` | none (demo) | Stand in for Booking service, Provider Hub service and Analytics service by publishing their events |
| GET | `/api/v1/info` | any | Which application this service belongs to, and who is calling |

Listens to `vehicle.events`, `schedule.events`, `booking.events`, `pricing.rules`; publishes `audit.events`.

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../../docs/modules/M2-journey-service.md`
