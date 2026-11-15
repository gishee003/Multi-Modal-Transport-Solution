# APP-07 Payment & Settlement Service

Back-end application for module M4 Payment & Settlement of MMTS (Group CSE752-2025-11).

**Module:** M4 Payment & Settlement · **Owner:** Kautilya (MT2025064) · **Port:** 8087

## Run

```bash
mvn spring-boot:run                                        # demo mode (H2 database, nothing to install)
mvn spring-boot:run -Dspring-boot.run.profiles=postgres    # real PostgreSQL (database mmts_payment)
mvn test                                                   # all tests (69)
```

- Swagger UI: http://localhost:8087/swagger-ui.html (get a token from `POST /dev/token`, then press **Authorize**)
- Health: http://localhost:8087/actuator/health

## Main endpoints

| Method | Path | Role | Purpose |
|---|---|---|---|
| GET | `/api/v1/wallets/me` | TRAVELLER | My wallet balance (INT-05; created on first use) |
| POST | `/api/v1/wallets/me/top-ups` | TRAVELLER | Top up with a gateway token (card data refused) |
| GET | `/api/v1/wallets/me/transactions` | TRAVELLER | My wallet history |
| GET | `/api/v1/wallets/{userId}` (`/transactions`) | FINANCE, ADMIN, SUPPORT | Any user's wallet and history |
| POST | `/api/v1/payments` | SERVICE | INT-11 charge once per booking (`Idempotency-Key`) |
| GET | `/api/v1/payments/{paymentId}` | SERVICE, ADMIN, FINANCE | Payment with splits and commission |
| POST | `/api/v1/payments/{paymentId}/refunds` | SERVICE, SUPPORT | INT-11 refund (`Idempotency-Key`) |
| GET | `/api/v1/ledger/trial-balance`, `/api/v1/ledger/entries` | FINANCE, ADMIN | Double-entry ledger and trial balance |
| GET | `/api/v1/commission-rules` | FINANCE, ADMIN | Commission rule of each provider |
| POST / GET | `/api/v1/payouts/run`, `/api/v1/payouts` | FINANCE, ADMIN | Run provider payouts now / list them |
| POST | `/api/v1/settlements/export?date=` | FINANCE, ADMIN | Write `exports/settlement-YYYY-MM-DD.csv` (INT-20) |
| POST | `/api/v1/webhooks/razorpay` | none (HMAC signature) | Gateway callback |
| POST | `/dev/gateway/decline-next`, `/dev/webhooks/sign` | demo only | Make the next charge fail; sign a webhook body |

## More detail

How it works, design decisions, drivers, patterns, demo script, stubs and tests:
`../docs/modules/APP-07-payment.md`
