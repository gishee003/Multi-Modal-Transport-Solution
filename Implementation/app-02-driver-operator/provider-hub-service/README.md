# Provider Hub service

Back-end service of application APP-02 Driver & Operator, for module M5 Provider & Fleet Integration of MMTS (Group CSE752-2025-11).

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

| Method | Path | Purpose (role) |
|---|---|---|
| POST | `/api/v1/holds` | INT-12: hold a leg for 10 minutes (SERVICE) |
| POST | `/api/v1/holds/{holdId}/confirm` | INT-12: QR ticket, unlock code or assigned driver (SERVICE) |
| DELETE | `/api/v1/holds/{holdId}` | INT-12: release, safe to repeat (SERVICE) |
| POST | `/api/v1/vehicles/{vehicleId}/unlock` | INT-12: unlock a confirmed vehicle (SERVICE) |
| GET | `/api/v1/providers` | Provider registry: plug-in type and status (ADMIN, OPERATOR, SERVICE) |
| GET | `/api/v1/providers/{providerId}/fleet` | Fleet health: battery, offline, last seen (ADMIN, own OPERATOR) |
| POST | `/api/v1/telemetry` | Batch of vehicle readings (OPERATOR, SERVICE) |
| GET | `/api/v1/telemetry/dead-letters` | Rejected readings with reason (ADMIN, OPERATOR) |
| POST / GET | `/api/v1/drivers`, `/api/v1/drivers/{id}/activate` | Onboard and activate taxi drivers (ADMIN, OPERATOR) |
| GET / PUT | `/api/v1/drivers/{driverId}/status` | INT-07: driver online / offline (DRIVER self, ADMIN) |
| POST | `/api/v1/drivers/{driverId}/locations` | INT-07: driver location (DRIVER self) |
| GET | `/api/v1/drivers/{driverId}/offers` | INT-08: ride offers as Server-Sent Events (DRIVER self) |
| POST | `/api/v1/drivers/{driverId}/offers/{offerId}/accept` or `/reject` | INT-08: answer an offer (DRIVER self) |
| POST | `/dev/provider-hub/...` | Demo controls (demo profile only) |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../../docs/modules/M5-provider-hub-service.md`
