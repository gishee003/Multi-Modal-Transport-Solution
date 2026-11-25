# Proof of concept: one multimodal trip through the real deployment components

> One use case run end to end on the components that will be in our deployment. This page explains what the POC shows, which deployment components it runs, how to start it,
> and the status of every real external API.

## 1. What it shows in one sentence

A traveller books a **rail + cab** trip through the **API gateway**; the Booking service runs its saga with **real
HTTP calls** to the TSP Integration Hub (APP-02), the Payment service and the Identity service, and the services
learn about each other's events only through a **real Kafka broker** — the paper trace (`PAPER-TRACE.md`) run for real.

## 2. Deployment components in the POC

| Component in the deployment (architecture document §6) | In the POC | Folder / port |
|---|---|---|
| Load balancer + API gateway (LB-1, Kong on the general node group VM-1/VM-2) | Spring Cloud Gateway: checks every login token once, adds a request id, routes by path | `poc/api-gateway`, **8080** |
| Authorisation server (Keycloak) | The Identity service issues the same signed login tokens (JWT, OAuth 2.0 bearer) | identity, 8084 |
| Event broker (Kafka on Amazon MSK, MSK-1, 3 brokers) | One real Kafka broker (KRaft mode) with all 9 topics; no Docker needed | `poc/event-broker`, port chosen at start |
| APP-01 service containers (node groups VM-1 to VM-6) | Identity, Journey, Booking, Payment services, profile `demo,poc` | 8084–8087 |
| APP-02 service containers (general node group VM-1, VM-2) | TSP Integration Hub, Contract Management service | 8088, 8089 |
| One database per service (DB-1 to DB-6) | One H2 database per service, PostgreSQL mode (`postgres` profile = real PostgreSQL) | inside each service |
| Caches (CACHE-1, CACHE-2) | In-memory cache behind the same interface | inside Journey, Hub |
| Front ends (traveller app, partner portal, admin portal) | `poc/run-trace.ps1` plays them: it calls only the gateway, like the apps would | — |
| Monitoring (MON-1, CloudWatch) | `/actuator/health` of every component; logs in `poc/run/` | — |
| APP-03 Analytics, reporting database DB-7 | Not built (group design only) | — |

What changes between demo and POC is **configuration only** (profile `poc`): the in-process event bus is replaced by
`KafkaEventPublisher` / `KafkaEventBridge`, and the stubs by `Http…Client` classes. No business code changed.

## 3. Run it

Needs Java 17+, Maven and about **2.5 GB of free memory** (eight Java programs). Close other large apps first.

```powershell
cd Implementation
powershell -ExecutionPolicy Bypass -File poc\start-poc.ps1 -Build   # first time: builds the jars (later: without -Build)
powershell -ExecutionPolicy Bypass -File poc\run-trace.ps1          # the trip, with a pass / fail check per step
powershell -ExecutionPolicy Bypass -File poc\stop-poc.ps1
```

Start order: broker → the six services one by one (start-up needs the most memory) → gateway. Logs: `poc\run\*.log`.
The trace can be run again and again: it ends by cancelling its own trip, which frees the cab and refunds the traveller.

## 4. The trace and its result

`poc/TRACE-OUTPUT.txt` is a real run (25 Nov 2026). Every check passed:

| Step | What is checked | Driver |
|---|---|---|
| 0 | The gateway refuses a call without a token (401); real logins through the gateway | D12 (security at the edge) |
| 1 | DMRC and CITYCAB have ACTIVE contracts in M7 | D15 |
| 2 | Journey search through the gateway | D4 |
| 3 | Rail + cab booked: legs held at the hub, paid **once** from the wallet, QR ticket + assigned driver | D6, D7, D8 |
| 4 | Same request + same key → same booking, no second charge; same key for another trip → 422 | D6, D8 |
| 5 | Journey created the trip **only** from `BOOKING_CONFIRMED` received through Kafka | D5 (event-driven) |
| 6 | Partner team suspends CITYCAB → `contract.events` via Kafka → hub shows SUSPENDED | D15 |
| 7 | Same trip again → cab hold refused (`TSP_CONTRACT_INACTIVE`), rail hold undone, wallet unchanged | D6, D7 |
| 8 | Contract re-activated → connector usable again | D15, D16 |
| 9 | New driver: the hub (APP-02) asks Identity (APP-01) over REST; verified licence → ACTIVE, no licence → 409 | D3 |
| 10 | SLA measurement for CITYCAB within limits | D16 |
| 11 | Traveller cancels → holds released at both TSPs, refund | D6 |

Also seen in the logs: each service is its own Kafka consumer group and subscribes only to the topics it needs
(`mmts.poc.topic-pattern`); the Payment service took CITYCAB's commission (18 %) from its contract event.

## 5. Real external APIs: which work today

| External module | Real system | Status in our code | What it would take to connect |
|---|---|---|---|
| X3 TSP data, shared vehicles | **GBFS public feeds** (open standard, no key) | **Working live**: `GET /dev/provider-hub/live-gbfs` downloads Bay Wheels' live feed (San Francisco) and parses it with our own GBFS plug-in code — 720 bikes read on 25 Nov 2026 | Nothing; any operator's public GBFS URL works |
| X3 TSP tickets | ONDC network (Beckn protocol), e.g. DMRC | Stub with the real message sequence (`select / on_select / init / on_init / confirm / on_confirm`) | Registration as a buyer app on the ONDC staging registry, signing keys |
| X3 bus timetables | Delhi Open Transit Data (GTFS, GTFS-Realtime) | Sample GTFS files in the real format | Free registration for an API key |
| X3 car rental, cab fleets | Partner APIs | Stubs | Partner agreements (no public API) |
| X1 licence verification | DigiLocker (MeitY) | Stub adapter | Registration as a DigiLocker requester organisation |
| X2 payments | Razorpay (Orders, Checkout, Route) | Stub gateway with signed webhooks | Test-mode keys from a Razorpay account |
| X4 maps | Google Maps Platform | Not called (Journey uses its own sample network) | API key with billing |
| X5 notifications | Firebase Cloud Messaging; DLT SMS gateway | Stubs | Firebase project; DLT registration of sender and templates |
| X6 regulator | City transport authority | Not built (APP-03) | Agreed file format (no public API) |

Every external call already goes through an adapter interface (principle P3), so connecting a real system means adding
one adapter class and a key in configuration — the same way the POC swapped stubs for real HTTP calls.
