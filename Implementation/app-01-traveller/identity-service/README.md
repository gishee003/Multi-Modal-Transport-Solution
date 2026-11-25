# Identity service

Back-end service of application APP-01 Traveller, for module M1 User, Account & Verification of MMTS.

**Module:** M1 User, Account & Verification · **Owner:** Athira · **Port:** 8084

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_identity)
mvn test                                                   # all tests
```

- Swagger UI: http://localhost:8084/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8084/actuator/health

## Main endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/auth/register` | Create a traveller or driver account (no token) |
| POST | `/api/v1/auth/otp` | Send a one-time login code by SMS (no token; demo: read it at `GET /dev/otp/{mobile}`) |
| POST | `/api/v1/auth/login` | Log in with password or OTP, get a login token (no token) |
| GET / PUT / DELETE | `/api/v1/users/me` | My profile / change it / delete my account and personal data |
| PUT | `/api/v1/users/me/saved-places`, `/api/v1/users/me/consent` | Saved places; consent for licence checks |
| POST / GET | `/api/v1/licences`, `/api/v1/licences/me` | Submit my driving licence; see its status |
| GET | `/api/v1/users/{userId}/drive-eligibility` | INT-10 / INT-13 drive-eligibility check (SERVICE, ADMIN) |
| GET, POST, DELETE | `/api/v1/users`, `/api/v1/users/{userId}/roles[/{role}]` | Admin: list users, give / take away roles |
| POST | `/api/v1/licences/expiry-check` | Admin: run the nightly licence expiry check now |
| GET | `/api/v1/info` | Which application this service belongs to, and who is calling |

Demo users (password login): Priya `9876543210` / `Priya@123` (traveller), Rahul `9123456780` / `Rahul@123` (driver),
Meera `9000000001` / `Admin@123` (admin). Full list in the guide.

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../../docs/modules/M1-identity-service.md`
