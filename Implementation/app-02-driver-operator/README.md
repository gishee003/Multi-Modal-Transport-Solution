# APP-02 Driver & Operator

The application taxi drivers, fleet operators and transport providers use.

| Part | Module | Owner | Folder | Port |
|---|---|---|---|---|
| Driver mobile app (front end) | M5 driver screens | not built (designed in the architecture document) | — | — |
| Provider Hub service | M5 Provider & Fleet Integration | Kirti | `provider-hub-service/` | 8088 |

**Style:** microkernel. Each transport provider is a plug-in, added without changing the core (D10).

Calls into other applications: Provider Hub → APP-01 Identity (INT-13, may this driver go online?).
Live vehicle data goes to APP-01 Journey as events (INT-14).

Run: `cd provider-hub-service` then `mvn spring-boot:run`.
Why this grouping: `../docs/APPLICATIONS-AND-SERVICES.md`.
