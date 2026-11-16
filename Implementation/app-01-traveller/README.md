# APP-01 Traveller

The application commuters use: plan, book, pay for and track a city trip.

| Part | Module | Owner | Folder | Port |
|---|---|---|---|---|
| Traveller mobile app (front end) | M1–M4 screens | not built (designed in the architecture document) | — | — |
| Identity service | M1 User, Account & Verification | Athira | `identity-service/` | 8084 |
| Journey service | M2 Journey Planning & Tracking | Diksha | `journey-service/` | 8085 |
| Booking service | M3 Booking & Ticketing | Jeevesh | `booking-service/` | 8086 |
| Payment service | M4 Payment & Settlement | Kautilya | `payment-service/` | 8087 |

**Style:** microservices. The back end is split into four services so each can scale and fail on its own (D4, D9, D14).
It is still one application with one release.

Calls into other applications: Booking → APP-02 Provider Hub (INT-12). Events go to APP-03 Analytics.

Each service is its own Maven project: `cd <service-folder>` then `mvn spring-boot:run`.
Why this grouping: `../docs/APPLICATIONS-AND-SERVICES.md`.
