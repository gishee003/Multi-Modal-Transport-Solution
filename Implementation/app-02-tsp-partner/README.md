# APP-02 TSP Partner

The application used by **Transport Service Providers (TSPs)** — any organisation that provides a way to travel (metro,
bus, cab fleet, scooter or bike sharing, car rental, any future mode) — and by TSP staff such as drivers.

| Part | Module | Owner | Folder | Port |
|---|---|---|---|---|
| TSP partner portal (web front end) | M5 fleet health, M7 onboarding, contracts, SLA, settlements | not built (designed in the architecture document) | — | — |
| TSP staff mobile app (front end) | M5 staff screens: go online, ride offers, location | not built (designed in the architecture document) | — | — |
| TSP Integration Hub | M5 TSP Integration | Kirti | `tsp-integration-hub/` | 8088 |
| Contract Management service | M7 TSP Contract Management | Group | `contract-management-service/` | 8089 |

**Style:** microkernel for the hub (each TSP is a plug-in connector, added without changing the core, D10) and layered
for the contract service (business rules and approvals).

**Onboarding a TSP = two steps.** Business onboarding in M7 (register, draft contract, partner team approves →
`TSP_CONTRACT_ACTIVATED`), then technical onboarding in M5 (enable the TSP's connector). Suspending or terminating the
contract (by the partner team or automatically after repeated SLA breaches) switches the connector off at once
(`409 TSP_CONTRACT_INACTIVE` on new holds).

Calls into other applications: Hub → APP-01 Identity (INT-13, may this staff member drive?). Live vehicle data goes to
APP-01 Journey as events (INT-14). Contract terms go to APP-01 Payment and Booking as events (INT-20).

Run: `cd tsp-integration-hub` or `cd contract-management-service`, then `mvn spring-boot:run`.
Why this grouping: `../docs/APPLICATIONS-AND-SERVICES.md`.
