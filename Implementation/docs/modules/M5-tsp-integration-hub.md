# TSP Integration Hub: module guide

**Module:** M5 TSP Integration · **Owner:** Kirti · **Folder:** `app-02-tsp-partner/tsp-integration-hub` · **Port:** 8088

## 1. What this service does

MMTS sells one trip that can use several transport companies: a metro ride, then an e-scooter, then a cab.
Each company is a **Transport Service Provider (TSP)**: any organisation that provides a way to travel, whatever the
mode. Each TSP has its own computer system that speaks its own language. The TSP Integration Hub is the one place that
talks to all of them, so that the rest of MMTS only has to speak one language. In the code a TSP is called a
**provider** (`providerId`, `ProviderConnector`, `ProviderRegistry`): provider = TSP.

The modes and TSPs in the demo (Delhi Metro, DTC buses, ZipRide scooters, DriveEasy cars, CityCab cabs, HopBike bikes)
are **examples**. The architecture does not depend on them: a new TSP, or a whole new kind of transport, is one new
connector plus one block in `application.yml`, and only after its contract is active in M7 (see "Contract gating" below).

Think of it as a **universal travel adapter** for plugs. The wall socket (the rest of MMTS) stays the same. For each
country (provider) you click in a small plug piece. A new country needs a new plug piece, not a new wall.

The architecture style is **Microkernel** (also called **plug-in architecture**, lecture 17):

- The **core** (the "kernel") is the part that never changes when a TSP is added: the TSP (provider) registry,
  one common vehicle model, the hold / confirm / release / unlock rules, the event publisher, security, retries,
  the latest-vehicle-state store and the dead-letter store.
- A **plug-in** is one small class per kind of provider that translates between the core and that provider.
- The **extension point** is the Java interface every plug-in implements: `ProviderConnector`.

Functions of module M5 (architecture document, section 2.3) and where they are:

| Function | In plain words | Main class |
|---|---|---|
| M5.1 TSP registry (technical onboarding) | Which TSPs are plugged in, with which plug-in, enabled, disabled or contract-suspended | `ProviderRegistry`, `ContractEventListener` |
| M5.2 Import schedules | Read a bus timetable (GTFS files) and announce it (`SCHEDULE_UPDATED`) | `FeedImportService`, `GtfsBusConnector` |
| M5.3 Ingest live vehicle data | Accept batches of vehicle positions, keep the latest one per vehicle, publish events, keep bad ones aside | `TelemetryService` |
| M5.4 Hold / confirm / release / unlock | Reserve a seat or vehicle for 10 minutes, turn it into a ticket, undo it, unlock the vehicle (INT-12) | `HoldService` |
| M5.5 TSP staff onboarding and activation | Register a driver (TSP staff); activate only if Identity service says the licence is fine; deactivate when the licence expires | `DriverService` |
| M5.6 Ride offers | Push "you have a ride" to a driver's phone; driver accepts or rejects (INT-08) | `OfferService` |
| M5.7 Driver status and location | Driver goes online / offline and sends location (INT-07) | `DriverService` |
| M5.8 Fleet health | An operator sees battery, online / offline and last-seen time for their own vehicles | `TelemetryService.fleetHealth` |

The six plug-ins (all in `adapter/connector/<name>/`):

| Plug-in | `type()` (used in `application.yml`) | Provider in the demo | What the traveller gets |
|---|---|---|---|
| `OndcMetroConnector` | `ondc-metro` | DMRC (Delhi Metro) via ONDC | `QR_TICKET` |
| `GtfsBusConnector` | `gtfs-bus` | DTC (city buses) | `QR_TICKET` |
| `ScooterGbfsConnector` | `gbfs-scooter` | ZIPRIDE (e-scooters) | `UNLOCK_CODE` |
| `CarRentalConnector` | `car-rental` | DRIVEEASY (self-drive cars) | `UNLOCK_CODE` (digital key) |
| `TaxiDriverConnector` | `taxi-driver` | CITYCAB (taxis) | `DRIVER_ASSIGNED` |
| `BikeShareConnector` | `gbfs-bike` | HOPBIKE (pedal bikes): **disabled by default** | `UNLOCK_CODE` |

### Contract gating

Onboarding a TSP has two steps. **Business onboarding** happens in M7, the Contract Management service in the same
application: the TSP signs a contract with commercial terms and service levels, and the partner team approves it.
**Technical onboarding** happens here: the TSP's connector is enabled. The hub listens to `contract.events`
(`ContractEventListener`):

| Event from M7 | What the hub does |
|---|---|
| `TSP_CONTRACT_SUSPENDED` (e.g. reason `SLA_BREACH`) or `TSP_CONTRACT_TERMINATED` | The TSP shows `SUSPENDED` in `GET /api/v1/providers`; new holds get `409 TSP_CONTRACT_INACTIVE`; its feeds are no longer read, so its vehicles stop being offered |
| `TSP_CONTRACT_ACTIVATED` | The connector is usable again |

In the demo, M7 runs as a separate service, so the hub cannot receive its events; use
`POST /dev/provider-hub/contracts/{tspId}/suspend` and `/activate` to publish the same events here.

Words used in this guide:

- **Adapter (pattern):** a class that converts one interface into another, like a plug adapter. Each plug-in is an
  adapter between the core's `ProviderConnector` interface and a provider's own format.
- **GTFS** (General Transit Feed Specification): the world-standard way bus and metro companies publish their
  *timetable*: a folder of CSV text files (`stops.txt`, `routes.txt`, `trips.txt`, `stop_times.txt` ...).
- **GTFS-Realtime:** the companion standard for *live* data, such as "bus 505 is now here". We simulate its
  "VehiclePosition" messages.
- **GBFS** (General Bikeshare Feed Specification): the standard JSON that scooter and bike-share companies publish
  about their vehicles (where each one is, is it reserved, battery level).
- **ONDC** (Open Network for Digital Commerce) and **Beckn:** India's open network for buying services; DMRC sells
  QR tickets on it. Beckn is the protocol ONDC uses. It is **asynchronous**: you send a request (for example
  `select`), get only "ACK" back at once, and the real answer (`on_select`) arrives later as a separate call to
  *your* callback address.
- **Event:** a short message "something happened" sent on a named **topic** (Kafka in production, an in-process bus
  in the demo). Other services subscribe to the topics they care about.
- **Dead letter:** a message the system could not process. Instead of throwing it away, it is parked in a
  **dead-letter store** with the reason, so nothing is lost silently and someone can inspect or replay it.
- **SSE** (Server-Sent Events): the phone opens one long HTTP request and the server writes small text events down it
  whenever something happens. It is one-way push (server to phone). Content type `text/event-stream`.

## 2. How it works

Layers (closed layers, as in `SHARED-SETUP.md`): `api/` (controllers, DTO records) → `service/` (core rules) →
`domain/` (entities) and `repository/` (database, latest-state store) and `adapter/` (plug-ins, eligibility stub).

### 2.1 Start-up: plugging in the providers (M5.1)

1. Spring reads `mmts.provider-hub` from `application.yml` into `ProviderHubProperties`. Each block under
   `providers:` becomes a `ProviderDefinition` (name, `connector` type, `enabled`, modes, prices, operator user ids,
   plug-in `settings`).
2. Spring creates every class marked `@Component` that implements `ProviderConnector`: these are the installed
   plug-ins.
3. `ProviderRegistry` receives *all* installed plug-ins as a `List<ProviderConnector>`, without knowing their class
   names. It builds a map `type → plug-in`.
4. For each **enabled** provider it looks up the plug-in whose `type()` equals the provider's `connector` setting and
   stores the pair. Disabled providers are skipped (HOPBIKE). A provider that asks for a plug-in nobody installed stops
   start-up with a clear message.
5. `DemoDataLoader` (demo profile) adds five drivers (TSP staff) with Delhi locations and one scooter (`ZR-SC-0099`) that
   reports once and then stays silent.
6. When the application is ready, `FeedImportService.onStartup()` imports timetables (2.2) and reads every live feed
   once (2.3).

### 2.2 Importing a timetable (M5.2)

1. `FeedImportService.importSchedules()` asks every enabled plug-in `loadSchedule(...)`.
2. Only `GtfsBusConnector` has one. It calls `GtfsFeedParser.parse("gtfs/dtc")`, which reads the CSV files in
   `src/main/resources/gtfs/dtc/` (agency, stops, routes, trips, stop_times, feed_info).
3. The plug-in returns a small summary (`ScheduleFeed`: version, route ids, stop and trip counts).
4. The core publishes `SCHEDULE_UPDATED` on `schedule.events` with `providerId`, `feedVersion`, `routeIds`
   (for Journey service journey planning).

### 2.3 Live vehicle data (M5.3): the pipeline

Data enters from three doors and all of it goes through **one** method, `TelemetryService.ingest(...)`:

- `POST /api/v1/telemetry` (a provider or another service sends a batch of up to 500 readings);
- `POST /api/v1/drivers/{id}/locations` (the TSP staff app);
- feed polling: every 30 s `Schedulers` calls `FeedImportService.pollAllFeeds()`, which asks every plug-in
  `pollVehicles(...)` (GBFS scooters, car partner API, simulated GTFS-Realtime bus positions).

For each reading, `TelemetryService`:

1. Checks it (`problemWith`): vehicle id present, provider known and enabled, an OPERATOR sends only for their own
   provider, mode valid and served by that provider, coordinates on Earth (and not 0,0), battery 0 to 100,
   speed 0 to 250, time readable and not in the future, and not older than what we already have.
2. **Bad reading** → saved as a `DeadLetter` (source, provider, vehicle, reason, the raw JSON, time). Its id
   (`dl-12`) is returned to the sender. Nothing is dropped.
3. **Good reading** → turned into the common vehicle model `VehicleState` and saved in the **latest-state store**
   (`VehicleStateStore`; a map in the demo, Redis in production). One entry per vehicle, overwritten each time.
4. Publishes `VEHICLE_POSITION` on `vehicle.events`, and `VEHICLE_AVAILABILITY` when the vehicle is new, came back
   online, or its availability changed.
5. Answers `202` with how many were received, accepted and dead-lettered.

**Offline detection:** every 10 s `markSilentVehiclesOffline()` marks any vehicle not heard from for 60 s as offline
and publishes `VEHICLE_AVAILABILITY` with `available: false`.

### 2.4 Hold, confirm, release, unlock (M5.4, INT-12)

Booking service calls these with a `SERVICE` token. `HoldController` passes everything to `HoldService` (core).

**Hold** (`POST /api/v1/holds`):

1. `ProviderRegistry.require(providerId)` finds the enabled provider and its plug-in, or answers
   `404 PROVIDER_NOT_FOUND`.
2. The mode must be one the provider offers (`400 MODE_NOT_SUPPORTED`).
3. If a live hold for the same `legId` exists, it is returned (a retry never holds twice).
4. Core check on the common vehicle model: if a `vehicleId` is given and the latest-state store says that vehicle is
   offline or already taken → `409 NOT_AVAILABLE`.
5. The core calls `plugin.hold(...)` through `registry.call(...)`. The plug-in reserves at its provider and returns a
   price, the vehicle it reserved, and its own reference (for ONDC: the transaction id). A plug-in that cannot serve
   throws `409 NOT_AVAILABLE`.
6. The core saves a `Hold` (status `HELD`, `expiresAt` = now + 10 minutes), marks the vehicle unavailable (event),
   publishes an `AUDIT` event and answers `201`.

**Confirm** (`POST /api/v1/holds/{holdId}/confirm`, after payment):

1. Unknown hold → `404 HOLD_NOT_FOUND`. Already confirmed → the same answer again.
2. If the 10 minutes are over, the hold becomes `EXPIRED` (the plug-in releases it) → `409 HOLD_NOT_ACTIVE`.
   A released hold also gives `409 HOLD_NOT_ACTIVE`.
3. The core calls `plugin.confirm(...)` and stores what comes back: `QR_TICKET`, `UNLOCK_CODE` or `DRIVER_ASSIGNED`
   (with the driver's id, name, vehicle number and ETA).

**Release** (`DELETE /api/v1/holds/{holdId}`): calls `plugin.release(...)` (retried up to 3 times if the provider is
briefly down), marks `RELEASED`, makes the vehicle available again. Releasing an already released or expired hold does
nothing and still answers `204`.

**Unlock** (`POST /api/v1/vehicles/{vehicleId}/unlock`): the hold must be `CONFIRMED` (`409 HOLD_NOT_CONFIRMED`
otherwise) and be for this vehicle; the plug-in sends the unlock command; the hold becomes `UNLOCKED`.

**Expiry job:** every 30 s `expireOverdueHolds()` expires and releases holds older than 10 minutes.

### 2.5 What each plug-in does inside

- **ONDC metro** (`OndcMetroConnector` + `StubOndcNetwork`): hold = `select` (ask for a fare) then `init` (draft
  order); confirm = `confirm`; release = `cancel`. For each request the plug-in parks a "promise" (`CompletableFuture`)
  under the message id, sends the request, receives `ACK`, and waits up to `callback-timeout-ms` (2000 ms) until the
  stub network calls `onCallback(on_select ...)` from another thread. No callback in time → `504 PROVIDER_TIMEOUT`.
  The stub prices by DMRC's distance slabs (Rs 11 to Rs 64) and returns a QR payload `ONDC|DMRC|txn-...`.
- **GTFS bus** (`GtfsBusConnector`): checks that given `stopId`s are DTC stops, prices by distance, issues a QR ticket.
  Its `pollVehicles` moves one bus per trip from stop to stop (one stop gap per minute): simulated GTFS-Realtime.
- **GBFS scooter / bike** (`ScooterGbfsConnector`, `BikeShareConnector`, both extend `GbfsConnectorSupport`): load
  the GBFS JSON, keep a "reserved" flag per vehicle. Hold a named vehicle, or the nearest usable one. Scooters below
  20 % battery and disabled vehicles cannot be held. Confirm gives an unlock code such as `ZR-7731`.
- **Car rental** (`CarRentalConnector`): fleet and parking hub from `application.yml`; reservation, digital key
  `DE-123456` valid for the rental period, remote unlock.
- **Taxi** (`TaxiDriverConnector`): hold checks that a free driver is within 5 km; confirm asks `DriverService` to
  give the ride to the nearest one (see 2.7).

### 2.6 Driver onboarding and activation (M5.5) and status (M5.7)

1. `POST /api/v1/drivers` (ADMIN, or the taxi provider's OPERATOR) creates a driver with status `REGISTERED`.
2. `POST /api/v1/drivers/{id}/activate`: `DriverService` asks Identity service through `EligibilityClient` (INT-13,
   `vehicleClass=LMV`, `purpose=TAXI_DRIVER`). In the demo `StubEligibilityClient` answers. Eligible (reason
   `VERIFIED`) → `ACTIVE`. Anything else (`PENDING_VERIFICATION`, `EXPIRED`, `NOT_A_DRIVER` ...) →
   `409 DRIVER_NOT_ELIGIBLE`.
3. `PUT /api/v1/drivers/{id}/status {"status":"ONLINE"}`: only an `ACTIVE` driver, and the licence is checked
   **again** (it may have expired since activation).
4. `POST /api/v1/drivers/{id}/locations`: the driver must be online. The location goes through the same pipeline as
   every vehicle (2.3); a bad one → `400 INVALID_LOCATION` and a dead letter.
5. **Licence expires:** Identity service publishes `LICENCE_EXPIRED` on `licence.events`. `DriverService.onLicenceEvent`
   (an `@EventListener`, i.e. a subscriber) sets the driver to `DEACTIVATED` and offline at once.

### 2.7 Ride offers to drivers (M5.6, INT-08)

1. Taxi hold confirmed → `DriverService.assignNearest` takes the free drivers of that provider (active, online, not on a
   trip) and asks the `DriverMatchingStrategy` (today `NearestDriverStrategy`) to choose one within 5 km.
2. The driver is marked "on trip"; ETA = distance / 20 km/h.
3. `OfferService.create` stores a `RideOffer` and pushes an SSE event `ride-offer` to every open stream of that driver.
4. The TSP staff app keeps `GET /api/v1/drivers/{id}/offers` open; on connecting it also gets any offer still waiting.
5. `POST .../offers/{offerId}/accept` → `ACCEPTED`. `.../reject` → `REJECTED` and the driver is free again.
6. If Booking service releases the hold, the offer is cancelled (`offer-cancelled` event) and the driver is freed.

### 2.8 Fleet health (M5.8)

`GET /api/v1/providers/{providerId}/fleet` reads the latest-state store for that provider and returns every vehicle
with battery, `online`, `available`, `lowBattery` (< 20 %) and `lastSeenAt`, plus totals. An OPERATOR may only ask for
a provider whose `operators:` list contains their user id (`403 NOT_YOUR_PROVIDER` otherwise).

### 2.9 How to add a new provider (recipe)

**Case A: the plug-in already exists** (for example a second scooter company that also publishes GBFS):

1. Put its GBFS file (or URL setting) where the plug-in expects it.
2. Add one block under `mmts.provider-hub.providers` in `application.yml`:
   ```yaml
   QUICKRIDE:
     name: QuickRide scooters
     connector: gbfs-scooter
     modes: [SCOOTER]
     base-price-paise: 4500
     per-km-paise: 3500
     operators: [op-quickride]
     settings:
       feed: gbfs/quickride/vehicle_status.json
       min-battery-pct: 15
       code-prefix: QR
   ```
3. Restart. Done: no Java file changed.

**Case B: a new kind of provider** (for example an auto-rickshaw aggregator with its own API):

1. Create a package `adapter/connector/auto/`.
2. Write one class `AutoRickshawConnector implements ProviderConnector`, marked `@Component`.
3. Give it a unique `type()`, for example `"auto-partner"`.
4. Implement `hold`, `confirm`, `release` (and `unlock` / `pollVehicles` / `loadSchedule` if they apply), translating
   to the partner's API. Throw `ProviderConnector.notAvailable(...)` when there is nothing to hold.
5. Add the provider block in `application.yml` with `connector: auto-partner`.
6. Write a unit test like `ConnectorPluginsTest` for the new plug-in.
7. Nothing in `service/`, `api/`, `domain/` or `repository/` changes. `ProviderRegistryTest.coreCodeNeverNamesAConcretePlugin`
   checks this automatically.

**Proof in the demo:** HOPBIKE's plug-in (`BikeShareConnector`) is installed but the provider has `enabled: false`.
Setting it to `true` (in `application.yml` or on the command line) plugs it in.

## 3. Design decisions and why

| Decision | Why | Alternative we rejected |
|---|---|---|
| Microkernel with a `ProviderConnector` extension point | Providers keep arriving (driver D10); each is isolated in one class; the core is tested once | One big service with `if (provider == "DMRC") ... else if ...`: every new provider edits and re-tests the core |
| Plug-ins chosen by a `type` string from `application.yml` | Adding / switching off a provider is a configuration change (Externalization) | Hard-coding provider → class in Java; or a database table (harder to review in the demo) |
| Registry receives `List<ProviderConnector>` from Spring | The core never imports a concrete plug-in (Open/Closed), checked by a test | Registry with `new OndcMetroConnector()` inside it |
| Core pulls data from plug-ins (`loadSchedule`, `pollVehicles`) and pushes it through one pipeline | One place validates, stores, publishes; plug-ins stay simple and have no event code | Each plug-in publishing its own events (duplicated, inconsistent rules) |
| Bad readings go to a dead-letter table with reason and raw JSON | Nothing is silently lost (driver D11); a bad sender cannot block good data in the same batch | Rejecting the whole batch with 400; or logging and dropping |
| Latest-state store behind an interface (map now, Redis later) | Reads like "where is vehicle X" are constant-time; swapping in Redis changes one class (P6) | Writing every reading to PostgreSQL and querying the newest row |
| Readings arrive in batches (`POST /api/v1/telemetry`, up to 500) | Fewer HTTP calls for high-volume providers | One HTTP call per reading |
| Retries for safe-to-repeat calls (release, unlock) only, in the core | Retrying a non-repeatable call (hold) could double-book; one retry rule for all plug-ins | Retries inside each plug-in, or retrying everything |
| ONDC modelled as request + `on_*` callback with a timeout | Matches the real Beckn protocol, so the real integration is the same shape | A simple synchronous stub that hides the asynchronous nature |
| Holds expire after 10 minutes (setting); confirm checks expiry itself and a job sweeps | Capacity is never locked forever even if Booking service crashes; works even if the job has not run yet | Only a job (a hold could be confirmed after expiry between runs) |
| Release is idempotent (204 again) | Booking service's saga may repeat an undo step after a crash | 404 / 409 on a second release, which would break the saga |
| Licence checked at activation **and** at going online, plus `LICENCE_EXPIRED` listener | Safety: an expired licence is caught even if an event is missed | Checking only once at onboarding |
| Driver matching as a Strategy | Matching rules will change (rating, fairness); a new rule is a new class | Matching code inside `DriverService` |
| Ride offers by SSE (demo) | One-way push, works with `curl` and a browser, no extra client code | WebSocket (production choice, needs a client to demo) or polling |

## 4. Architecture drivers demonstrated

| Driver | How the code meets it | How to see it in the demo |
|---|---|---|
| **D10** A new provider can be added without changing the core | Microkernel: `ProviderConnector` extension point; `ProviderRegistry` picks plug-ins by `type()` from `application.yml`; core never names a plug-in class (checked by `coreCodeNeverNamesAConcretePlugin`); HOPBIKE plug-in installed but disabled | `GET /api/v1/providers` shows HOPBIKE `DISABLED`, a hold gives `404 PROVIDER_NOT_FOUND`; restart with `--mmts.provider-hub.providers.HOPBIKE.enabled=true`, now it is `ENABLED` and a hold on `HB-BK-001` works (demo step 9) |
| **D11** Live-data pipeline scales without losing data | Batch ingestion; every reading either accepted (latest-state store + `vehicle.events`) or kept in the dead-letter store; offline detection after 60 s; stateless service so many copies can run | Send a batch with one bad reading → `accepted` + `deadLettered` = `received`; `GET /api/v1/telemetry/dead-letters`; after 60 s `ZR-SC-0099` is offline in fleet health (demo steps 6 to 8) |
| D1 / P6 (shared) Technology independence | Kafka → `EventPublisher`, Redis → `VehicleStateStore`, Identity service → `EligibilityClient`, ONDC → `StubOndcNetwork` behind the plug-in | `GET /dev/events` shows what would go to Kafka |

**D11 in production.** The `vehicle.events` Kafka topic is **partitioned by `vehicleId`** (for example 12 partitions):
all readings of one vehicle land in the same partition and stay in order, while different vehicles are processed in
parallel by several TSP Integration Hub instances. **Replication factor 3** keeps a copy of each partition on three brokers
(Amazon MSK runs them for us, one per availability zone), so losing a broker loses no data. The latest-state store is Redis (CACHE-2), shared by all instances. The dead letters
would go to their own topic (for example `vehicle.events.dlq`) as well as the table. Offline detection would use a
Redis key expiry (TTL 60 s) instead of a scan.

## 5. Design patterns used

| Pattern | Where in the code | Why here |
|---|---|---|
| **Microkernel** (architecture style) | Core = `service/` + `ProviderConnector`; plug-ins = `adapter/connector/<name>/` | Providers change often; core must stay stable (D10) |
| **Adapter** | Every plug-in adapts `ProviderConnector` to a provider format (Beckn, GTFS, GBFS, partner API); `EligibilityClient` adapts INT-13; `VehicleStateStore` adapts Redis | Rest of the code speaks one language (principle P3) |
| **Factory / registry** | `ProviderRegistry` builds and hands out the right plug-in for a `providerId` from configuration | Callers never use `new` or know concrete classes |
| **Open/Closed principle** (SOLID) | New provider = new class + YAML block; the core is closed for change | Proven by `coreCodeNeverNamesAConcretePlugin` and `NewProviderByConfigurationTest` |
| **Observer** (publish-subscribe) | Events on `vehicle.events`, `schedule.events`, `audit.events`; `DriverService.onLicenceEvent` subscribes to `licence.events`; SSE streams subscribe to a driver's offers | Senders do not know who listens |
| **Strategy** | `DriverMatchingStrategy` / `NearestDriverStrategy` | Matching rule can be swapped |
| **Template method** (small) | `GbfsConnectorSupport` holds the GBFS logic; `ScooterGbfsConnector` overrides `usable(...)` to add a battery rule | Scooter and bike plug-ins share code |
| **DAO / Repository** | `HoldRepository`, `DriverRepository`, `DeadLetterRepository` | Shared rule: persistence behind interfaces |
| **State machine** | `Hold.Status` (HELD → CONFIRMED → UNLOCKED, or RELEASED / EXPIRED), `Driver.Status` | Clear allowed transitions, clear error codes |
| **Retry** (reliability tactic) | `ProviderRegistry.call(..., safeToRepeat, ...)` | Providers are briefly unavailable sometimes |
| **Stubs** (lecture 19) | `StubEligibilityClient`, `StubOndcNetwork`, partner APIs inside the plug-ins | Test without Identity service, ONDC or partners |

## 6. Endpoints in detail

All bodies are JSON. Errors use the shared format `{code, message, details, timestamp}`. Get tokens in demo mode with
`POST /dev/token` (for example `{"userId":"booking-service","roles":["SERVICE"]}`). Every endpoint answers `401` without a
valid token and `403 FORBIDDEN` with the wrong role.

### INT-12 (role SERVICE, caller Booking service)

**`POST /api/v1/holds`**

```json
{ "bookingId": "b-5001", "legId": "b-5001-L2", "providerId": "ZIPRIDE", "mode": "SCOOTER",
  "from": { "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197 },
  "to":   { "name": "India Gate", "lat": 28.6129, "lon": 77.2295 },
  "startTime": "2026-11-15T10:20:00Z", "vehicleId": "ZR-SC-0042" }
```

`201`:
```json
{ "holdId": "h-6e5c379b", "legId": "b-5001-L2", "providerId": "ZIPRIDE", "status": "HELD",
  "pricePaise": 14161, "expiresAt": "2026-11-15T10:10:00Z" }
```
Errors: `404 PROVIDER_NOT_FOUND` (unknown or disabled), `409 NOT_AVAILABLE`, `400 MODE_NOT_SUPPORTED`,
`400 VALIDATION_FAILED`, `504 PROVIDER_TIMEOUT` (ONDC no callback), `503 PROVIDER_UNAVAILABLE`.
`vehicleId` is optional (scooter / car: the plug-in picks one; metro / bus / taxi: not used).

**`POST /api/v1/holds/{holdId}/confirm`** → `200`:
```json
{ "holdId": "h-6e5c379b", "status": "CONFIRMED",
  "access": { "type": "UNLOCK_CODE", "value": "ZR-7731",
              "validFrom": "2026-11-15T10:15:00Z", "validUntil": "2026-11-15T11:15:00Z" },
  "driver": null }
```
Taxi example: `"access": {"type": "DRIVER_ASSIGNED", "value": "DL1CAB1234", ...}, "driver": {"driverId": "d-201",
"name": "Ramesh Kumar", "vehicleNumber": "DL1CAB1234", "etaMinutes": 1}`. Metro: `"type": "QR_TICKET", "value":
"ONDC|DMRC|txn-1a2b3c4d|DMRC-RCK>null|9F3A..."`.
Errors: `404 HOLD_NOT_FOUND`, `409 HOLD_NOT_ACTIVE` (expired or released), `409 NOT_AVAILABLE` (taxi: driver gone).

**`DELETE /api/v1/holds/{holdId}`** → `204` (also `204` when already released or expired). `404 HOLD_NOT_FOUND`.

**`POST /api/v1/vehicles/{vehicleId}/unlock`** with `{ "holdId": "h-6e5c379b", "userId": "u-101" }` → `200`:
```json
{ "vehicleId": "ZR-SC-0042", "status": "UNLOCKED", "unlockedAt": "2026-11-15T10:16:02Z" }
```
Errors: `409 HOLD_NOT_CONFIRMED`, `400 VEHICLE_MISMATCH`, `409 UNLOCK_NOT_SUPPORTED` (metro / bus / taxi),
`404 HOLD_NOT_FOUND`.

### Registry, telemetry, fleet

**`GET /api/v1/providers`** (ADMIN, OPERATOR, SERVICE) → `200`:
```json
[ { "providerId": "DMRC", "name": "Delhi Metro Rail Corporation (via ONDC)", "connectorType": "ondc-metro",
    "modes": ["METRO"], "status": "ENABLED" },
  { "providerId": "HOPBIKE", "name": "HopBike pedal bike-share", "connectorType": "gbfs-bike",
    "modes": ["BIKE"], "status": "DISABLED" } ]
```

**`POST /api/v1/telemetry`** (OPERATOR for own provider, SERVICE):
```json
{ "readings": [
  { "vehicleId": "ZR-SC-0042", "providerId": "ZIPRIDE", "mode": "SCOOTER", "lat": 28.6331, "lon": 77.2196,
    "speedKmph": 14.5, "batteryPct": 79, "available": true, "recordedAt": "2026-11-15T10:00:00Z" },
  { "vehicleId": "ZR-SC-0043", "providerId": "ZIPRIDE", "mode": "SCOOTER", "lat": 123.4, "lon": 77.2201,
    "speedKmph": 0, "batteryPct": 64, "available": true, "recordedAt": "2026-11-15T10:00:00Z" } ] }
```
`202`: `{ "received": 2, "accepted": 1, "deadLettered": 1, "deadLetterIds": ["dl-3"] }`.
Errors: `400 VALIDATION_FAILED` (empty batch or more than 500). Dead-letter reasons: `MISSING_VEHICLE_ID`,
`UNKNOWN_PROVIDER`, `PROVIDER_MISMATCH`, `INVALID_MODE`, `MODE_NOT_SERVED_BY_PROVIDER`, `INVALID_COORDINATES`,
`INVALID_BATTERY`, `INVALID_SPEED`, `INVALID_TIMESTAMP`, `TIMESTAMP_IN_FUTURE`, `STALE_READING`.

**`GET /api/v1/telemetry/dead-letters`** (ADMIN all; OPERATOR own providers) → `200`:
```json
[ { "deadLetterId": "dl-3", "source": "TELEMETRY_API", "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0043",
    "reason": "INVALID_COORDINATES", "rawReading": "{\"vehicleId\":\"ZR-SC-0043\",...}",
    "receivedAt": "2026-11-15T10:00:01Z" } ]
```

**`GET /api/v1/providers/{providerId}/fleet`** (ADMIN; OPERATOR own provider) → `200`:
```json
{ "providerId": "ZIPRIDE", "totalVehicles": 8, "online": 7, "offline": 1, "lowBattery": 1,
  "vehicles": [ { "vehicleId": "ZR-SC-0012", "mode": "SCOOTER", "lat": 28.6318, "lon": 77.221, "batteryPct": 12,
                  "available": false, "online": true, "lowBattery": true, "lastSeenAt": "2026-11-15T10:00:30Z" } ] }
```
Errors: `404 PROVIDER_NOT_FOUND`, `403 NOT_YOUR_PROVIDER`.

### Drivers (INT-07, INT-08, M5.5)

| Method and path | Role | Body | Answer | Errors |
|---|---|---|---|---|
| `POST /api/v1/drivers` | ADMIN, OPERATOR of the taxi provider | `{"driverId":"d-206","name":"Pooja Rana","vehicleNumber":"DL1CAB2468","providerId":"CITYCAB"}` | `201` driver, `status: REGISTERED` | `404 PROVIDER_NOT_FOUND`, `400 NOT_A_TAXI_PROVIDER`, `409 DRIVER_EXISTS`, `403 NOT_YOUR_PROVIDER` |
| `GET /api/v1/drivers` | ADMIN, OPERATOR | none | `200` list | |
| `POST /api/v1/drivers/{id}/activate` | ADMIN, OPERATOR | none | `200`, `status: ACTIVE` | `409 DRIVER_NOT_ELIGIBLE`, `404 DRIVER_NOT_FOUND`, `404 USER_NOT_FOUND` (from Identity service) |
| `GET /api/v1/drivers/{id}/status` | DRIVER (self), ADMIN, OPERATOR | none | `200` driver | `403 NOT_YOUR_DRIVER_ACCOUNT` |
| `PUT /api/v1/drivers/{id}/status` | DRIVER (self), ADMIN | `{"status":"ONLINE"}` or `OFFLINE` | `200` driver | `409 DRIVER_NOT_ACTIVE`, `409 DRIVER_NOT_ELIGIBLE`, `400 VALIDATION_FAILED` |
| `POST /api/v1/drivers/{id}/locations` | DRIVER (self) | `{"lat":28.6331,"lon":77.2192,"speedKmph":18}` (`recordedAt` optional) | `200` driver | `409 DRIVER_OFFLINE`, `400 INVALID_LOCATION` (kept as dead letter) |
| `GET /api/v1/drivers/{id}/offers` | DRIVER (self), ADMIN | none (`Accept: text/event-stream`) | stream of `ride-offer` / `offer-cancelled` events | `403` |
| `POST /api/v1/drivers/{id}/offers/{offerId}/accept` or `/reject` | DRIVER (self) | none | `200` offer, `status: ACCEPTED` / `REJECTED` | `404 OFFER_NOT_FOUND`, `409 OFFER_NOT_PENDING` |

Driver answer example:
```json
{ "driverId": "d-201", "name": "Ramesh Kumar", "vehicleNumber": "DL1CAB1234", "providerId": "CITYCAB",
  "status": "ACTIVE", "statusReason": "VERIFIED", "online": true, "onTrip": false,
  "lat": 28.633, "lon": 77.219, "lastSeenAt": "2026-11-15T10:00:00Z" }
```
One SSE event as it appears on the wire:
```
id:of-3c9e1f20
event:ride-offer
data:{"offerId":"of-3c9e1f20","driverId":"d-201","holdId":"h-41d2aa07","pickup":{"name":"Start","lat":28.6328,"lon":77.2197,"stopId":null},"drop":{...},"farePaise":8700,"status":"OFFERED","createdAt":"2026-11-15T10:01:00Z"}
```

### Demo controls (demo profile only, no token needed)

| Path (POST) | What it does |
|---|---|
| `/dev/provider-hub/eligibility/{userId}` body `{"reason":"EXPIRED"}` | What the Identity service stub answers for a user (`VERIFIED`, `NO_LICENCE`, `PENDING_VERIFICATION`, `REJECTED`, `EXPIRED`, `CLASS_NOT_COVERED`, `NOT_A_DRIVER`) |
| `/dev/provider-hub/licence-expired/{userId}` | Publishes `LICENCE_EXPIRED` on `licence.events`, as Identity service would |
| `/dev/provider-hub/bad-telemetry` | Sends a reading with latitude 123.4 into the pipeline |
| `/dev/provider-hub/jobs/offline-check`, `/jobs/expire-holds`, `/jobs/poll-feeds`, `/jobs/import-schedules` | Run a background job now |

Plus the shared `/dev/token` and `/dev/events`.

## 7. Demo script

Start: `cd Implementation/app-02-tsp-partner/tsp-integration-hub` then `mvn spring-boot:run`. Open `http://localhost:8088/swagger-ui.html`.
In curl examples, `$SVC`, `$OP`, `$ADM`, `$DRV` are tokens from `POST /dev/token`:

```bash
tok() { curl -s -X POST localhost:8088/dev/token -H 'Content-Type: application/json' \
  -d "{\"userId\":\"$1\",\"roles\":[\"$2\"]}" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }
SVC=$(tok booking-service SERVICE); OP=$(tok op-zipride OPERATOR); ADM=$(tok admin-1 ADMIN); DRV=$(tok d-201 DRIVER)
```

1. **The plug-ins.** `GET /api/v1/providers` with `$ADM`. Point out six providers, each with its `connectorType`, and
   HOPBIKE `DISABLED`. Open `application.yml` and show the `providers:` blocks.
2. **Schedule import.** `POST /dev/provider-hub/jobs/import-schedules`, then `GET /dev/events`: `SCHEDULE_UPDATED`
   with `feedVersion: 2026-11-01` and route ids from the GTFS files.
3. **Hold → confirm → unlock (scooter).** Swagger "Authorize" with `$SVC`, then `POST /api/v1/holds` with the body from
   section 6. Note `holdId`, `status: HELD`, `expiresAt` 10 minutes ahead. Then
   `POST /api/v1/holds/{holdId}/confirm`: `UNLOCK_CODE` such as `ZR-7731`. Then
   `POST /api/v1/vehicles/ZR-SC-0042/unlock` with `{"holdId":"<holdId>","userId":"u-101"}`: `UNLOCKED`.
   Show `GET /dev/events`: `VEHICLE_AVAILABILITY` false for ZR-SC-0042 and `AUDIT` events.
4. **Rules.** Hold `ZR-SC-0043` (same body, new `legId`, `vehicleId` changed), then unlock **before** confirm →
   `409 HOLD_NOT_CONFIRMED`. `DELETE /api/v1/holds/{id}` twice → `204` both times. Confirm it → `409 HOLD_NOT_ACTIVE`.
   Hold `ZR-SC-0012` → `409 NOT_AVAILABLE` (12 % battery). Hold `"providerId":"HOPBIKE"` → `404 PROVIDER_NOT_FOUND`.
5. **Metro via ONDC and taxi.** Same body with `"providerId":"DMRC","mode":"METRO"` and no `vehicleId`: price 2100
   (DMRC slab Rs 21); confirm → `QR_TICKET` `ONDC|DMRC|txn-...`. Point out in the log the `select/on_select/init/on_init`
   sequence. Then `"providerId":"CITYCAB","mode":"TAXI"`: confirm → `DRIVER_ASSIGNED`, driver `d-201` (nearest to
   Rajiv Chowk), ETA 1 minute. In a terminal, first open the driver's stream:
   `curl -N -H "Authorization: Bearer $DRV" localhost:8088/api/v1/drivers/d-201/offers`. The `ride-offer` event
   appears; accept it with `POST /api/v1/drivers/d-201/offers/{offerId}/accept`.
6. **Bad telemetry → dead letter (D11).** With `$OP`, `POST /api/v1/telemetry` with the two-reading batch from section 6:
   `received 2, accepted 1, deadLettered 1`. Then `GET /api/v1/telemetry/dead-letters`: reason `INVALID_COORDINATES`
   and the raw JSON. (Or simply `POST /dev/provider-hub/bad-telemetry`.)
7. **Fleet health.** `GET /api/v1/providers/ZIPRIDE/fleet` with `$OP`: batteries, `ZR-SC-0012` `lowBattery: true`.
   With `$OP` try `/providers/DRIVEEASY/fleet` → `403 NOT_YOUR_PROVIDER`.
8. **Offline after 60 s.** Wait a minute (or call `POST /dev/provider-hub/jobs/offline-check` after 60 s):
   `ZR-SC-0099` shows `online: false`; `GET /dev/events` has its `VEHICLE_AVAILABILITY` with `available: false`.
9. **Driver eligibility.** With `$ADM`: `POST /api/v1/drivers` with
   `{"driverId":"d-205x","name":"New Driver","vehicleNumber":"DL1CAB0000","providerId":"CITYCAB"}`, then
   `POST /dev/provider-hub/eligibility/d-205x` `{"reason":"PENDING_VERIFICATION"}` and
   `POST /api/v1/drivers/d-205x/activate` → `409 DRIVER_NOT_ELIGIBLE`. Change the reason to `VERIFIED` and activate
   again → `ACTIVE`. Then `POST /dev/provider-hub/licence-expired/d-201` and `GET /api/v1/drivers/d-201/status` →
   `DEACTIVATED`, `LICENCE_EXPIRED`.
10. **New provider by configuration only (D10).** Stop the app. Either set `enabled: true` under `HOPBIKE` in
    `application.yml`, or start with
    `mvn spring-boot:run -Dspring-boot.run.arguments=--mmts.provider-hub.providers.HOPBIKE.enabled=true`
    (PowerShell: put the whole `-D...` argument in double quotes). `GET /api/v1/providers` → HOPBIKE `ENABLED`. Hold
    `{"providerId":"HOPBIKE","mode":"BIKE","vehicleId":"HB-BK-001",...}` → `201`; confirm → `HB-xxxx`. Point out: no Java
    file changed. Undo the YAML change afterwards.

11. **Contract gating (M7).** `POST /dev/provider-hub/contracts/DMRC/suspend` with `{"reason":"SLA_BREACH"}`, then a
    metro hold → `409 TSP_CONTRACT_INACTIVE`, and `GET /api/v1/providers` shows DMRC `SUSPENDED`.
    `POST /dev/provider-hub/contracts/DMRC/activate` → holds work again.

## 8. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
| Identity service drive eligibility (INT-13) | `StubEligibilityClient` (sample users d-201 to d-208, changeable by demo control) | `GET /api/v1/users/{id}/drive-eligibility?vehicleClass=LMV&purpose=TAXI_DRIVER` with a SERVICE token |
| Contract Management service (M7) `contract.events` | `POST /dev/provider-hub/contracts/{tspId}/suspend` and `/activate` publish on the in-process bus | Kafka consumer on `contract.events` |
| Identity service `licence.events` | `POST /dev/provider-hub/licence-expired/{userId}` publishes on the in-process bus | Kafka consumer on `licence.events` |
| ONDC network + DMRC seller app | `StubOndcNetwork` (ACK now, `on_*` callback on another thread, DMRC fare slabs) | Beckn APIs via ONDC gateway; BPP posts `on_select` / `on_init` / `on_confirm` / `on_cancel` to our callback URL; signed requests |
| Delhi Open Transit Data (GTFS, GTFS-Realtime) | Sample GTFS CSV files; simulated bus positions | Download the GTFS zip daily; poll the GTFS-Realtime protobuf feed |
| Scooter / bike operators (GBFS) | Sample `vehicle_status.json` / `free_bike_status.json`; reservations in memory | Poll the operator's GBFS URL every `ttl` seconds; operator reservation / unlock API |
| Car-rental partner API | Fleet from `application.yml`, reservations in memory | Partner REST API for reservation and remote unlock |
| Kafka | Shared `InProcessEventPublisher` | Kafka topics `vehicle.events` (partitioned by vehicleId, replication 3), `schedule.events`, `audit.events` |
| Redis (CACHE-2) | `InMemoryVehicleStateStore` | `RedisVehicleStateStore` behind the same interface |
| WebSocket to the TSP staff app | SSE stream | WebSocket (or FCM push when the app is in the background) |

### In the proof of concept (profile `poc`, see `../POC.md`)

The hub is called for real by Booking (INT-12) and calls the Identity service for real when TSP staff are activated
(`HttpEligibilityClient`, INT-13). `contract.events` arrive from the Contract Management service through Kafka, so
suspending CITYCAB's contract in M7 switches its connector off here (trace step 6).

**A real external API, live:** `GET /dev/provider-hub/live-gbfs` downloads a public GBFS feed (default: Bay Wheels,
San Francisco) and parses it with the same `GbfsFeedParser` the scooter and bike connectors use; on 25 Nov 2026 it
read 720 bikes. The controller lives in the GBFS plug-in folder, so the core still never names a plug-in.

### Where it runs in production (architecture document, section 6)

- **Servers:** the general node group (VM-1, VM-2), at least two copies in two availability zones; more copies are added
as TSP traffic grows (D11). A new TSP connector is a new container image in REG-1: the core is not redeployed (D10, D16).
- **Data:** DB-5 (PostgreSQL run by AWS) and the Redis cache CACHE-2 for the latest state of every vehicle.
- **Outside calls and events:** TSP systems (ONDC, GTFS / GBFS feeds, fleet APIs) through NAT-1. `vehicle.events` go to
Kafka on Amazon MSK (MSK-1: 3 brokers, one per zone, replication 3); `contract.events` and `licence.events` arrive from it.

Shared by every service: requests from the apps arrive only through the API gateway (Kong + Keycloak on the general
node group); logs and metrics go to CloudWatch (MON-1); the container image is built and tested by GitHub Actions and
stored in the container registry (REG-1); Kubernetes (EKS-1) restarts a crashed copy and adds copies under load.

## 9. Tests

Run `mvn test` in `app-02-tsp-partner/tsp-integration-hub`: **73 tests, all passing** (64 module tests + the 9 shared tests).
The 9 shared tests (`SharedSetupTest`, `JwtServiceTest`) are explained in `../SHARED-SETUP.md`, section 8.
Background jobs are switched off in tests by `src/test/resources/config/application.yml`; tests run the jobs
themselves. Service tests use `MutableClock` so "10 minutes later" takes no time.

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `ProviderRegistryTest` (unit) | `registryPicksTheConnectorNamedInConfiguration` | ZIPRIDE → scooter plug-in, DTC → GTFS plug-in, id in any letter case | Factory / registry, M5.1 |
| | `unknownProviderIs404ProviderNotFound` | Unknown id → `404 PROVIDER_NOT_FOUND` | INT-12 error code |
| | `disabledProviderIsAbsentThenPresentAfterConfigurationChange` | HOPBIKE missing while disabled; present with the bike plug-in once enabled; same plug-in list | **D10** |
| | `providerNeedingAMissingPluginStopsStartUpWithAClearMessage` | A provider naming a plug-in type nobody installed fails fast, naming the type | Safe configuration |
| | `safeCallsAreRetriedUntilTheProviderAnswers` | Provider down twice then up → third attempt succeeds | Retry tactic |
| | `unsafeCallsAreTriedOnceAndGive503` | A hold is not retried; failure → `503 PROVIDER_UNAVAILABLE` | No double booking |
| | `coreCodeNeverNamesAConcretePlugin` | Scans the source: no file outside the plug-in folders mentions a plug-in class | **D10**, Open/Closed |
| `ConnectorPluginsTest` (unit) | `scooterHoldConfirmUnlockReleaseGivesUnlockCode` | Reserve, second hold refused, `UNLOCK_CODE` `ZR-dddd` valid 10:15 to 11:15, unlock, release twice | M5.4, plug-in 3 |
| | `scooterWithLowBatteryOrDisabledCannotBeHeld` | 12 % battery, disabled, unknown → `NOT_AVAILABLE` | GBFS rules |
| | `scooterWithoutVehicleIdGetsTheNearestUsableOne` | No vehicle named → nearest usable scooter | Plug-in behaviour |
| | `bikeShareHoldsAFreeBikeAndRefusesAReservedOne` | Reserved bike refused; free bike gives `HB-` code | Plug-in 6 works |
| | `carRentalReservesUnlocksAndRunsOutOfCars` | Car picked, `DE-dddddd` key, unlock, all taken → `NOT_AVAILABLE`, release frees, poll shows availability | Plug-in 4 |
| | `gtfsBusIssuesQrTicketAndSimulatesRealtimePositions` | Unknown stop refused, `QR_TICKET`, unlock not supported, 3 simulated buses inside Delhi | Plug-in 2, GTFS-Realtime |
| | `ondcHoldAndConfirmFollowTheBecknRequestCallbackPairs` | Message log `select, on_select, init, on_init`, then `confirm, on_confirm`, `cancel, on_cancel`; fare 2100; QR ticket | Plug-in 1, ONDC asynchronous flow |
| | `ondcWithoutCallbackTimesOutWith504` | No callback → `504 PROVIDER_TIMEOUT` | Timeout tactic |
| `FeedParsersTest` (unit) | `gtfsSampleFeedIsParsed` | Agency, version, 6 stops, 2 routes (quoted CSV), 3 trips, stops of a trip in order | M5.2, GTFS |
| | `gtfsConnectorTurnsTheFeedIntoAScheduleSummary` | Version, route ids, counts | M5.2 |
| | `gbfsVersion3VehicleStatusIsParsed` | 7 scooters, battery 0.82 → 82 %, disabled one found | GBFS 3.0 |
| | `gbfsVersion2FreeBikeStatusIsParsed` | 4 bikes, no battery, reserved one found, epoch time | GBFS 2.3 |
| `HoldServiceTest` (Spring, test clock) | `scooterHoldConfirmUnlockReturnsUnlockCode` | Full flow; expiry = now + 10 min; vehicle unavailable while held, available after release | M5.4 |
| | `metroHoldGoesThroughOndcAndReturnsQrTicket` | Core → ONDC plug-in → `QR_TICKET` | QR_TICKET |
| | `taxiConfirmAssignsTheNearestFreeDriver` | Nearest driver assigned, ETA 1 min, on trip; freed on release | DRIVER_ASSIGNED, matching |
| | `holdExpiresAfterTenMinutes` | 10 min 1 s later confirm → `HOLD_NOT_ACTIVE`; status `EXPIRED` saved | Hold expiry |
| | `schedulerExpiresOverdueHolds` | Expiry job expires old holds | Hold expiry |
| | `releaseTwiceIsSafeAndAReleasedHoldCannotBeConfirmed` | Second release no error; confirm → `HOLD_NOT_ACTIVE` | Repeat-safe release |
| | `unlockBeforeConfirmIsRefused` | `HOLD_NOT_CONFIRMED` | INT-12 rule |
| | `aHeldVehicleIsNotAvailableToSomeoneElse` | Second hold on same scooter → `NOT_AVAILABLE` | INT-12 rule |
| | `repeatedHoldForTheSameLegReturnsTheSameHold` | Same leg twice → same holdId | Retry-safe hold |
| | `unknownOrDisabledProviderAndWrongModeAreRefused` | `PROVIDER_NOT_FOUND` (unknown, HOPBIKE), `MODE_NOT_SUPPORTED`, `HOLD_NOT_FOUND` | Error codes |
| `TelemetryServiceTest` (Spring, test clock) | `validReadingUpdatesLatestStateAndPublishesEvents` | Latest state saved; `VEHICLE_POSITION` (all contract fields) and `VEHICLE_AVAILABILITY` | **D11**, INT-14 |
| | `invalidReadingsGoToTheDeadLetterStoreWithAReason` | 6 in = 1 accepted + 5 dead letters, each with its reason | **D11** nothing lost |
| | `olderReadingDoesNotMoveTheVehicleBack` | Older reading → `STALE_READING`, position unchanged | Data correctness |
| | `vehicleSilentForSixtySecondsIsMarkedOffline` | Silent vehicle offline after 61 s, talkative one stays online, event published | Offline detection |
| | `gtfsImportPublishesScheduleUpdated` | `SCHEDULE_UPDATED` on `schedule.events` with DTC version and routes | M5.2 |
| | `feedPollingBringsProviderVehiclesIntoTheCommonModel` | Bus, scooter, car in the store; disabled HOPBIKE not | Common vehicle model |
| `DriverServiceTest` (Spring) | `activationIsRefusedWhenTheLicenceIsNotEligible` | `PENDING_VERIFICATION` and `NOT_A_DRIVER` → `DRIVER_NOT_ELIGIBLE`; cannot go online | M5.5, INT-13 |
| | `eligibleDriverIsActivatedAndCanGoOnline` | `VERIFIED` → `ACTIVE` → online | M5.5 |
| | `goingOnlineChecksTheLicenceAgain` | Licence expired after activation → cannot go online | Safety |
| | `licenceExpiredEventDeactivatesTheDriver` | `LICENCE_EXPIRED` event → `DEACTIVATED`, offline | Observer, licence.events |
| | `unknownUserAndDuplicateOnboardingAreRefused` | `USER_NOT_FOUND`, `DRIVER_EXISTS`, `NOT_A_TAXI_PROVIDER` | Error codes |
| | `nearestDriverStrategyPicksTheClosestDriverWithinRange` | Closest chosen; nobody within 5 km → none | Strategy, nearest-driver matching |
| `HoldApiTest` (MockMvc) | `holdConfirmUnlockMatchTheContract` | Exactly 6 / 4 / 3 JSON fields, status codes 201 / 200 / 200 / 204, example times | INT-12 contract |
| | `releaseReturns204TwiceAndConfirmAfterReleaseIs409HoldNotActive` | `204`, `204`, `409 HOLD_NOT_ACTIVE` | INT-12 contract |
| | `unlockBeforeConfirmIs409HoldNotConfirmed` | `409 HOLD_NOT_CONFIRMED` | INT-12 contract |
| | `unusableVehicleIs409NotAvailable` | Low-battery and disabled scooters → `409 NOT_AVAILABLE` | INT-12 contract |
| | `unknownAndDisabledProvidersAre404ProviderNotFound` | `404 PROVIDER_NOT_FOUND`; `404 HOLD_NOT_FOUND` | INT-12, D10 baseline |
| | `metroConfirmGivesQrTicket` | `QR_TICKET`, `driver: null` | INT-12 access types |
| | `taxiConfirmGivesDriverAssignedWithDriverDetails` | `DRIVER_ASSIGNED` and the 4 driver fields | INT-12 access types |
| | `onlyServiceTokensMayHoldAndBadBodiesAre400` | No token 401, TRAVELLER 403, missing fields 400 | RBAC, validation |
| `DriverApiTest` (MockMvc) | `onboardActivateGoOnlineAndSendLocation` | 201 REGISTERED → ACTIVE → ONLINE → location stored, operator can read status | INT-07, M5.5 |
| | `activationOfIneligibleDriverIs409` | `409 DRIVER_NOT_ELIGIBLE` | M5.5 |
| | `operatorOfAnotherProviderCannotOnboardTaxiDrivers` | `403 NOT_YOUR_PROVIDER` | RBAC |
| | `driverCannotUseAnotherDriversAccount` | `403 NOT_YOUR_DRIVER_ACCOUNT`; driver cannot list drivers | RBAC |
| | `badLocationIs400AndKeptAsDeadLetter` | `400 INVALID_LOCATION` naming the dead-letter id | D11 |
| | `offersArriveAsServerSentEventsAndCanBeAccepted` | Content type `text/event-stream`, `ride-offer` event with the hold, accept → ACCEPTED, again → `409`, another driver → `404` | INT-08, Observer |
| | `licenceExpiredDemoControlDeactivatesTheDriver` | Demo control publishes the event; driver DEACTIVATED, cannot go online | Demo control |
| `FleetApiTest` (MockMvc) | `providersAreListedWithConnectorTypeAndStatus` | 6 providers, types, HOPBIKE DISABLED; traveller 403 | M5.1 |
| | `telemetryBatchAcceptsGoodReadingsAndDeadLettersBadOnes` | 4 in: 2 accepted, 2 dead letters; operator sees own dead letters only; fleet shows new vehicle and low battery | **D11**, M5.3, M5.8 |
| | `emptyBatchIs400AndTravellerCannotSendTelemetry` | `400 VALIDATION_FAILED`; traveller 403 | Validation, RBAC |
| | `operatorSeesOnlyTheirOwnFleet` | Own fleet 200 (3 cars); other provider `403 NOT_YOUR_PROVIDER`; unknown 404 | M5.8 |
| | `demoControlInjectsABadReadingIntoTheDeadLetterStore` | Bad reading dead-lettered; stub can return `NOT_A_DRIVER` | Demo controls |
| `ContractGatingApiTest` (MockMvc) | `suspendedContractBlocksNewHoldsAndShowsSuspended` | Suspend event → hold `409 TSP_CONTRACT_INACTIVE`, status `SUSPENDED` | M7 gates M5 |
| | `reactivatedContractMakesTheConnectorUsableAgain` | Activate event → hold `201`, status `ENABLED` | M7 gates M5 |
| `NewProviderByConfigurationTest` | `enablingTheDisabledProviderInConfigurationPlugsItIn` | With only `HOPBIKE.enabled=true`: listed ENABLED, hold + confirm `HB-` code, fleet shows its bikes | **D10** end to end |
