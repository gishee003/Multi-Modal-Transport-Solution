# Contract Management service

Back-end service of application APP-02 TSP Partner, for module M7 TSP Contract Management of MMTS.

**Module:** M7 TSP Contract Management · **Owner:** Group · **Port:** 8089

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_contract_management)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8089/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8089/actuator/health
- Demo data: six TSPs; DMRC, DTC, ZIPRIDE, DRIVEEASY and CITYCAB have ACTIVE contracts, HOPBIKE's is UNDER_REVIEW.

## Main endpoints

| Method | Path | Purpose (role) |
|---|---|---|
| POST | `/api/v1/tsps` | M7.1 register a TSP: company, KYC documents, bank account reference, modes, operator users (ADMIN, OPERATOR) |
| GET | `/api/v1/tsps/{tspId}` | A TSP (staff, services, own OPERATOR) |
| GET | `/api/v1/tsps/{tspId}/contracts` | M7.7 every contract revision, newest first |
| GET | `/api/v1/tsps/{tspId}/active-terms` | Is the contract active today, and on which terms; read by the portals (SERVICE, ADMIN, FINANCE) |
| POST | `/api/v1/contracts` | M7.2-M7.4 draft: modes, area, validity, commercial terms, service levels (ADMIN, OPERATOR) |
| POST | `/api/v1/contracts/{id}/submit` | DRAFT → UNDER_REVIEW (ADMIN, own OPERATOR) |
| POST | `/api/v1/contracts/{id}/approve` | UNDER_REVIEW → ACTIVE, publishes `TSP_CONTRACT_ACTIVATED`, ends older revisions (ADMIN) |
| POST | `/api/v1/contracts/{id}/send-back` | UNDER_REVIEW → DRAFT with a reason (ADMIN) |
| POST | `/api/v1/contracts/{id}/suspend`, `/reactivate`, `/terminate` | M7.6 life cycle, each publishes on `contract.events` (ADMIN) |
| POST | `/api/v1/sla/measurements` | M7.5 one period's metrics; breach → `TSP_SLA_BREACHED`; repeated → suspended (SERVICE, ADMIN) |
| GET | `/api/v1/contracts/{id}/sla` | SLA history (ADMIN, FINANCE, REGULATOR, OPERATOR) |

## More detail

How it works, design decisions, drivers, patterns, demo script and tests:
`../../docs/modules/M7-contract-management-service.md`
