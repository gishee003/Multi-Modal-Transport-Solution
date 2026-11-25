# APP-03 Admin & Regulator

The application platform admins, support, finance and the city regulator use.

| Part | Module | Status |
|---|---|---|
| Web portal (front end) | M6 screens | Design only |
| Analytics & Compliance service | M6 Admin, Analytics & Compliance | Design only (whole group) |

**Style:** layered N-tier. A portal that mostly reads: UI, business rules and its own reporting database (PostgreSQL, DB-7), with
role checks at every layer (D12, D13). It gets its data from events published by APP-01 and APP-02, never from their
databases.

Nothing is built in this folder; the design is in the architecture document (section 3.4).
Why this grouping: `../docs/APPLICATIONS-AND-SERVICES.md`.
