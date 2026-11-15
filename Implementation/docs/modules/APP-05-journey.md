# APP-05 Journey Planning & Tracking Service: module guide

**Module:** M2 Journey Planning & Tracking · **Owner:** Diksha (MT2025045) · **Folder:** `app-05-journey` · **Port:** 8085

## 1. What this application does

APP-05 answers two questions for a traveller in Delhi:

1. **"How do I get from A to B?"** It suggests journeys that mix several ways of travelling (walk, metro, bus,
   e-scooter, shared bike, taxi), with the time and the price of every part.
2. **"Where is my ride now?"** Once a journey is booked, it follows the trip live and pushes positions, the expected
   arrival time and warnings to the traveller's phone.

The functions of module M2 (architecture document, section 2.3) and where they are:

| Function | What it means in plain words | Where in the code |
|---|---|---|
| M2.1 Search mixed-mode routes | Find routes from A to B that combine walking, metro, bus, scooter, bike and taxi | `GraphRoutePlanner` (behind the `RoutePlanner` interface) |
| M2.2 What is nearby now | Show free scooters, bikes and taxis close to a point, from the latest vehicle positions | `VehicleService`, `VehiclePositionStore` |
| M2.3 Rank options | Order the options by fastest, cheapest or fewest changes | `RankingStrategy` + `FastestStrategy`, `CheapestStrategy`, `FewestChangesStrategy` |
| M2.4 Fare and time per leg | Every leg has minutes and a fare in paise; the journey adds them up. Fares come from `application.yml` and can be changed at run time by a pricing event | `FareRules`, `FareService`, `Leg` subclasses |
| M2.5 Track an active trip live | A trip starts when a booking is confirmed; the app streams positions and ETA; old positions are flagged "stale" | `TripService`, `TrackingService`, `TripController` |
| M2.6 Alerts | "You are running late" (DELAY) and "your scooter has gone silent, switch mode" (SWITCH_MODE), on the stream and as a phone push | `TrackingService.checkAlerts`, `PushNotifier` |
| M2.7 Re-plan the remaining trip (optional) | Not built. The SWITCH_MODE alert already suggests the nearest free vehicle; a full re-plan would call the same search from the vehicle's last position | see section 8 |

Some words used everywhere in this guide:

- **Leg:** one part of a journey done with one mode, for example "ride the Yellow Line from Hauz Khas to Central Secretariat".
- **Mode:** a way of travelling: `WALK`, `METRO`, `BUS`, `SCOOTER`, `BIKE`, `TAXI` (and `CAR`, which is only booked in APP-06).
- **Paise:** money is always stored as a whole number of paise (100 paise = 1 rupee), so there are no rounding errors.
- **Event:** a short message one application announces ("booking confirmed", "vehicle moved"). Other applications that
  are interested receive it. In production the messages travel through **Kafka**; in the demo through an in-process bus.

## 2. How it works

### 2.1 The sample Delhi network (loaded at start-up)

A **graph** is a set of points (called *nodes*) joined by connections (called *edges* or *links*). Our network is a
graph: the points are stations, bus stops, scooter/bike docks and well-known places; a link says "you can go from
here to there by this mode in this many minutes". `SampleDelhiNetwork` builds it when the application starts and keeps
it in memory (32 points and a few hundred links). It uses real place names and plausible coordinates; some metro stations in
between are left out to keep it small.

```
 Blue Line:    Karol Bagh -- Jhandewalan -- RAJIV CHOWK -- Barakhamba Rd -- Mandi House -- Pragati Maidan
                                                 |   (interchange)
 Yellow Line:  Hauz Khas -- Green Park -- AIIMS -- INA -- Central Secretariat -- RAJIV CHOWK -- New Delhi

 Bus DTC 505:  Central Secretariat -- India Gate -- Pragati Maidan
 Bus DTC 520:  AIIMS -- Lodhi Road -- Khan Market -- India Gate

 Docks (S = scooter ZIPRIDE, B = bike GREENBIKE):
   IIT Delhi Gate (S,B)   Hauz Khas Village (S,B)   Hauz Khas Metro (S,B)   Central Secretariat (S)
   Rajiv Chowk Gate 7 (S,B)   Khan Market (B)   India Gate (S,B)

 Places: IIT Delhi Main Gate, Hauz Khas Village, Connaught Place, Khan Market, India Gate,
         New Delhi Railway Station, Bharat Mandapam

 Walking links: added automatically between any two points less than 1.2 km apart.
 Scooter rides: between any two scooter docks up to 8 km apart. Bike rides: bike docks up to 5 km apart.
 Taxi (CITYCAB): door to door, added as a separate option.
```

Point ids look like `DMRC-RCK` (metro), `DTC-IG` (bus stop), `DOCK-IG` (dock) and `PL-IG` (place). The full list is
at `GET /api/v1/journeys/places`. At start-up (demo profile) `VehicleFeedSimulator` also announces 12 sample vehicles:
6 scooters, 4 bikes parked at docks and 2 taxis.

### 2.2 Flow: searching for a journey (M2.1, M2.3, M2.4, driver D4)

`GET /api/v1/journeys/search?from=PL-IITD&to=PL-IG&rankBy=FASTEST`

1. **Security filter** (shared code) checks the login token; `@PreAuthorize("hasRole('TRAVELLER')")` on
   `JourneyController` lets only travellers in.
2. **`JourneyController.search`** passes the four text parameters to `JourneySearchService.search`.
3. **`JourneySearchService`** checks the input: unknown place → `404 UNKNOWN_PLACE`, same start and end →
   `400 SAME_PLACE`, unknown ranking → `400 UNKNOWN_RANKING`, unknown mode → `400 UNKNOWN_MODE`.
   It then picks the ranking **strategy** by name (`FASTEST`, `CHEAPEST`, `FEWEST_CHANGES`).
4. It starts a stopwatch and asks **`RouteCandidateService.candidates(...)`** for the options. This method carries
   `@Cacheable`: a **cache** is a fast memory of earlier answers. If the same from/to/modes was searched recently, the
   answer comes straight from the cache and the planner does not run at all.
5. Otherwise **`GraphRoutePlanner.plan`** runs. It gets a **`LegFactory`** from `FareService` that prices with the
   fares valid right now. It runs **Dijkstra's algorithm** four times with four ideas of "cost" (see 2.3) and adds a
   door-to-door taxi option if a free taxi is within 6 km. Same routes found twice are kept once (same `optionId`).
6. Each route is turned into **legs** by the factory (`WalkLeg`, `MetroLeg`, `BusLeg`, `ScooterLeg`, `BikeLeg`,
   `TaxiLeg`), and the legs into a **`Journey`**. Each leg computes its own fare; the journey adds up minutes and fares.
7. Back in `JourneySearchService`: no options → `404 NO_ROUTE_FOUND`. Otherwise the strategy sorts them, the first
   5 are kept, and the stopwatch gives `responseMillis`. `computeMillis` is how long the planner took;
   `servedFromCache` is `true` when the answer was computed before this request started.
8. The controller turns the result into JSON records (`JourneyResponses`) and returns `200`.

### 2.3 How the route search works (Dijkstra, explained simply)

Dijkstra's algorithm finds the cheapest path in a graph. Think of it as spreading out from the start like water: it
always continues from the point that is cheapest to reach so far, and the first time it reaches the destination, that
path is the cheapest one. A **priority queue** (a list that always hands out the smallest item first) keeps the
waiting points in order.

Our version is **label-based**: a search state is not just "at Rajiv Chowk" but "at Rajiv Chowk, sitting on the
Yellow Line". That is how the search knows when you change vehicle. When you board a new vehicle it adds the usual
wait (metro 3 min, bus 6 min, scooter unlock 2 min). Other rules: no two walks in a row, and a scooter/bike link can
only be used if the vehicle store shows a free scooter/bike within 300 m of that dock (so M2.2 data feeds M2.1).

The four runs ("profiles") are:

| Profile | Cost of a path | Typical result |
|---|---|---|
| fastest | minutes | walk + scooter + metro + scooter |
| few-changes | minutes + 20 extra minutes per boarding | one long scooter ride |
| cheap | minutes + 3 minutes per rupee | bike + metro + bus |
| public-transport | minutes, only walk / metro / bus | walk + metro + bus |

The search uses only a rough fare guess to steer the "cheap" run. The real fare is calculated afterwards by each leg.
Consecutive links become one leg when you stay on foot, stay inside the metro (a change of line is one ticket but
counts as a change) or stay on the same bus route.

### 2.4 Flow: what is nearby right now (M2.2)

1. APP-08 publishes `VEHICLE_POSITION` and `VEHICLE_AVAILABILITY` events on `vehicle.events` (INT-14). In the demo
   `VehicleFeedSimulator` does this every 5 seconds, because APP-08 does not run here.
2. **`VehicleService.onVehicleEvent`** (an `@EventListener`) receives each event and saves the newest state of the
   vehicle in **`VehiclePositionStore`**, an in-memory map that stands in for the Redis cache CACHE-1. An event that is
   older than what is stored is ignored (events can arrive out of order).
3. For a position event it then calls `TrackingService.onVehicleUpdate` at once (see 2.6).
4. `GET /api/v1/journeys/nearby?lat=..&lon=..&radiusMetres=800` → `JourneyController` → `VehicleService.nearby`,
   which asks the store for free vehicles inside the circle, nearest first, and adds `lastSeenSeconds` and `stale`.

### 2.5 Flow: a booking becomes a trip (M2.5)

1. APP-06 publishes `BOOKING_CONFIRMED` on `booking.events` (INT-16) with the booked legs. In the demo,
   `POST /dev/journey/bookings` runs a search, takes the chosen option and publishes this event in the contract format.
2. **`TripService.onBookingEvent`** receives it and calls `createFromBooking`. If a trip for this booking already
   exists it does nothing (Kafka may deliver an event twice, so this must be safe to repeat).
3. Each leg becomes a `TripLeg`. The app decides which vehicle to follow: the `vehicleId` in the event if present,
   otherwise the nearest free scooter / bike / taxi, and for metro / bus a name for "the train or bus of this leg".
4. A **`Trip`** (a JPA entity, stored with `TripRepository` in H2 or PostgreSQL) is saved as `trip-<bookingId>`, with
   status `ACTIVE`, the first tracked leg as current, and a planned arrival time. An `AUDIT` event (`TRIP_CREATED`) is
   published on `audit.events` for APP-09.
5. `BOOKING_CANCELLED` sets the trip to `CANCELLED`; `BOOKING_COMPLETED` sets it to `COMPLETED`.

### 2.6 Flow: following a trip live (M2.5, M2.6, driver D5)

**Server-Sent Events (SSE)** is a simple web standard: the client opens one HTTP request and the server keeps the
answer open, writing a new message into it whenever something happens. It is one-way push (server to phone) and needs
no special client: a browser or `curl -N` can watch it. The architecture names WebSocket; `CONTRACTS.md` chose SSE
for the demo because it is the same push idea and easier to show.

1. `GET /api/v1/trips/{tripId}/live` → `TripController.live` → `TrackingService.subscribe`.
2. `TripService.requireOwned` checks the trip exists (`404 TRIP_NOT_FOUND`) and belongs to the caller
   (`403 NOT_TRIP_OWNER`, plus an `AUDIT` event with outcome `DENIED`). An ended trip gives `409 TRIP_NOT_ACTIVE`.
3. A `SseEmitter` (Spring's object for one open stream) is created, remembered for that trip, and a **`snapshot`**
   message with the current state is sent at once.
4. Each time a `VEHICLE_POSITION` for the trip's current vehicle arrives, `onVehicleUpdate` pushes a **`position`**
   message immediately, with no polling. If the vehicle is within 100 m of the end of its leg, the trip moves to the
   next tracked leg (`leg-completed`); after the last one the trip is `COMPLETED`, an **`ended`** message is sent and
   the stream closes.
5. Every 5 seconds `publishStatusToWatchers` sends a **`status`** message to everyone watching. This is what makes a
   *silent* vehicle visible: no new position means `lastSeenSeconds` keeps growing, and after 10 s `stale` becomes
   `true` (the app shows "last seen 14 s ago" instead of a wrong live dot).
6. Each message carries the ETA: remaining distance of the current leg ÷ the vehicle's speed, plus the planned minutes
   of the legs after it. `delayMinutes` = expected arrival − planned arrival.
7. **Alerts** (`checkAlerts`), each sent once per leg, both as an **`alert`** message on the stream and through the
   **`PushNotifier`** (the FCM stub logs it and keeps it for `/dev/journey/push-log`):
   - `DELAY` when the trip is at least 5 minutes behind plan;
   - `SWITCH_MODE` when the vehicle has been silent for 30 s, with a suggestion such as
     "free SCOOTER ZR-SC-0041 is 776 m away", or "book a taxi".

### 2.7 Flow: fares change while the app runs (M2.4)

1. At start-up `FareRules` is filled from `mmts.journey.fares` in `application.yml` (metro and bus distance bands,
   scooter/bike unlock + per-minute price, taxi base + per-km price, surge factor). This is the **Externalization**
   pattern: change a price without changing code.
2. APP-09 publishes `PRICING_RULES_UPDATED` on `pricing.rules` (INT-18). `FareService.onPricingRulesUpdated` reads
   `surgeFactor` (multiplies scooter, bike and taxi fares) and `promotions[]` (we read each as
   `{"mode":"BIKE","discountPercent":20}`), swaps in new rules, and **empties the search cache**, because cached
   answers carry old prices. `commissionPercent` does not change what the traveller pays, so it is ignored here.
3. A `SCHEDULE_UPDATED` event on `schedule.events` also empties the cache (timetables changed). The cache is also
   emptied every 60 s, like a Redis expiry time, so scooter availability in cached answers never gets old.

## 3. Design decisions and why

| Decision | Why | Alternative we rejected |
|---|---|---|
| Our own Dijkstra search behind a `RoutePlanner` interface | Small, explainable, runs with nothing to install; the interface lets OpenTripPlanner plug in later without changing services | Running OpenTripPlanner in the demo (needs Java server, GTFS and OSM data, a lot of memory) |
| Several searches with different cost profiles, then rank | Gives the traveller real choices (fast, cheap, few changes) instead of one route | One search only (every ranking would show the same route) |
| Label-based states (stop + vehicle) | Knows when you change vehicle, so waits and "changes" are counted correctly | Plain stop-only Dijkstra (cannot see line changes) |
| Whole network and latest positions in memory | Search answers in a few milliseconds (D4); positions read without a database | Querying the database for every search step |
| `@Cacheable` search results, emptied on fare/schedule change and every 60 s | Repeated peak-time searches cost nothing (D4) but answers do not get stale | No cache (slower at peak) or a cache that never expires (wrong prices) |
| Strategy pattern for ranking | New ranking = one new class; the search service does not change | `if/else` on the ranking name inside the service |
| One `Leg` subclass per mode, created by `LegFactory` | Each mode prices and describes itself; the planner never needs `new MetroLeg(...)` | One `Leg` class with a big `switch` for fares |
| Money as `long` paise | No rounding errors, matches `CONTRACTS.md` | `double` rupees |
| Push with Server-Sent Events | One-way push, watchable with `curl`, agreed in `CONTRACTS.md` | Client polling every few seconds (slower, more load); WebSocket (needs client code to show) |
| Staleness flag at 10 s plus a status message every 5 s | The app is honest when data is old (D5) | Showing the last position as if it were live |
| Trip created from the `BOOKING_CONFIRMED` event, safe to repeat | Loose coupling to APP-06; at-least-once delivery cannot create duplicates | APP-06 calling APP-05 synchronously |
| Trips stored with JPA; positions only in memory | Trips must survive a restart; positions are replaced every few seconds anyway | Storing every position in the database |
| Demo simulator and demo endpoints publish real events | The messaging flow is visible at `/dev/events` and uses the same listeners as production | Calling services directly from the demo endpoints |

## 4. Architecture drivers demonstrated

| Driver | How the code meets it | How to see it in the demo |
|---|---|---|
| **D4** Route suggestions within about 2 s at peak | Network in memory; Dijkstra on a small graph; `@Cacheable` on `RouteCandidateService.candidates` (Redis stand-in); response reports `computeMillis`, `responseMillis`, `servedFromCache` | Run the same search twice: `servedFromCache` turns `true`, `responseMillis` is 0. Test `d4SearchIsWellUnderTwoSecondsAndRepeatIsServedFromCache` |
| **D5** Live positions fresh within about 10 s | Event-driven updates (`@EventListener` on `vehicle.events`), immediate push on the SSE stream, `stale` flag after `stale-after-seconds: 10`, status every 5 s, SWITCH_MODE after 30 s of silence | Watch `/live` with `curl -N`; pause the vehicle; after 10 s `stale:true`, after 30 s an `alert`. Tests `positionOlderThanTenSecondsIsStale`, `silentVehicleIsStaleAndTriggersSwitchModeAlert` |
| Security (role-based access) | `@PreAuthorize("hasRole('TRAVELLER')")`; trip owner check; audit events | A DRIVER token gets `403`; another traveller gets `403 NOT_TRIP_OWNER` |
| Modifiability / technology independence (P3, P6) | `RoutePlanner`, `PushNotifier`, `EventPublisher` interfaces; fares in `application.yml` | Change a fare in `application.yml` and restart; publish new pricing rules |

## 5. Design patterns used

| Pattern | Where in the code | Why here |
|---|---|---|
| **Strategy** | `RankingStrategy` with `FastestStrategy`, `CheapestStrategy`, `FewestChangesStrategy`; chosen by name in `JourneySearchService` | The way of ordering changes at run time per request; new rankings are added without editing existing code |
| **Composite** | `JourneyPart` (interface), `Leg` (leaf), `Journey` (composite, may even contain another `Journey`) | Total minutes, fare and boardings are asked the same way for one leg or a whole journey |
| **Factory Method** | `LegFactory.create(mode, ...)` returns `WalkLeg`, `MetroLeg`, `BusLeg`, `ScooterLeg`, `BikeLeg` or `TaxiLeg` | The planner says *which mode*; the factory decides *which class*, and hands every leg the current fares |
| **Adapter** | `RoutePlanner` (our `GraphRoutePlanner` now, OpenTripPlanner later); `PushNotifier` (`StubFcmPushNotifier` now, Firebase Cloud Messaging later) | External systems are reached only through our own interface (principle P3) |
| **Observer / publish-subscribe** | `@EventListener` methods in `VehicleService`, `TripService`, `FareService`, `RouteCandidateService`; SSE streams are observers of a trip | Applications react to each other's events without calling each other; the phone observes its trip |
| **Repository (DAO)** | `TripRepository` (Spring Data JPA), `VehiclePositionStore` | Services do not write SQL or know where data lives |
| **Externalization** | `FareRules` bound from `mmts.journey.fares`, timings in `mmts.journey.tracking` | Prices and limits change without code changes |

## 6. Endpoints in detail

Every `/api/v1/**` call needs `Authorization: Bearer <token>`. Get a token from `POST /dev/token` (section 7).
Errors always have the shared shape `{"code","message","details","timestamp"}`. Common to all: no or bad token →
`401 UNAUTHORIZED`; wrong role → `403 FORBIDDEN`.

### GET `/api/v1/journeys/search` (role TRAVELLER)

Parameters: `from`, `to` (a place id or name), `rankBy` = `FASTEST` (default) / `CHEAPEST` / `FEWEST_CHANGES`,
`modes` (optional, for example `METRO,BUS`; walking is always allowed).

```
GET /api/v1/journeys/search?from=PL-HKV&to=PL-IG&rankBy=FEWEST_CHANGES
```

Response `200` (shortened to one option; numbers can differ slightly):

```json
{
  "from": { "stopId": "PL-HKV", "name": "Hauz Khas Village", "lat": 28.5535, "lon": 77.194, "kind": "PLACE" },
  "to":   { "stopId": "PL-IG", "name": "India Gate", "lat": 28.6129, "lon": 77.2295, "kind": "PLACE" },
  "rankBy": "FEWEST_CHANGES",
  "computeMillis": 4,
  "responseMillis": 5,
  "servedFromCache": false,
  "optionCount": 3,
  "options": [
    {
      "optionId": "opt-31e3bb5b", "rank": 2, "totalMinutes": 39, "totalFarePaise": 11200, "changes": 0,
      "modes": ["WALK", "SCOOTER"],
      "legs": [
        { "legNo": 1, "mode": "WALK", "from": { "stopId": "PL-HKV", "...": "..." }, "to": { "stopId": "DOCK-HKV", "...": "..." },
          "distanceMetres": 140, "minutes": 2, "farePaise": 0, "routeNames": [], "providerId": null, "vehicleId": null,
          "instruction": "Walk 140 m to Hauz Khas Village Dock" },
        { "legNo": 2, "mode": "SCOOTER", "distanceMetres": 9240, "minutes": 34, "farePaise": 11200,
          "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0042",
          "instruction": "Unlock scooter ZR-SC-0042 at Hauz Khas Village Dock and ride to India Gate Dock" },
        { "legNo": 3, "mode": "WALK", "minutes": 3, "farePaise": 0, "instruction": "Walk 190 m to India Gate" }
      ]
    }
  ]
}
```

Errors: `404 UNKNOWN_PLACE`, `404 NO_ROUTE_FOUND` (for example `modes=WALK` between far places), `400 SAME_PLACE`,
`400 UNKNOWN_RANKING`, `400 UNKNOWN_MODE` (also for `CAR`), `400 BAD_REQUEST` (missing `from` or `to`).

### GET `/api/v1/journeys/places` (role TRAVELLER)

Response `200`: the 32 points of the network, sorted by name:
`[{"stopId":"DMRC-AIIMS","name":"AIIMS Metro","lat":28.5687,"lon":77.2079,"kind":"METRO_STATION"}, ...]`.

### GET `/api/v1/journeys/nearby` (role TRAVELLER)

Parameters: `lat`, `lon`, `radiusMetres` (default 800, max 5000), `mode` (optional).

```
GET /api/v1/journeys/nearby?lat=28.6328&lon=77.2197&radiusMetres=800
```

```json
[
  { "vehicleId": "ZR-SC-0043", "providerId": "ZIPRIDE", "mode": "SCOOTER", "lat": 28.6336, "lon": 77.2186,
    "distanceMetres": 139, "batteryPct": 64, "recordedAt": "2026-11-15T16:27:10Z", "lastSeenSeconds": 2, "stale": false },
  { "vehicleId": "CC-TX-2201", "providerId": "CITYCAB", "mode": "TAXI", "lat": 28.6315, "lon": 77.2167,
    "distanceMetres": 327, "batteryPct": null, "recordedAt": "2026-11-15T16:27:10Z", "lastSeenSeconds": 2, "stale": false }
]
```

Errors: `400 INVALID_LOCATION`, `400 INVALID_RADIUS`, `400 UNKNOWN_MODE`.

### GET `/api/v1/trips` and GET `/api/v1/trips/{tripId}` (role TRAVELLER, owner only)

`/api/v1/trips` lists the caller's trips, newest first. One trip:

```json
{
  "tripId": "trip-b-5002", "bookingId": "b-5002", "userId": "u-101", "status": "ACTIVE", "totalPaise": 11200,
  "currentLegIndex": 1, "plannedArrival": "2026-11-15T17:05:04Z", "liveUrl": "/api/v1/trips/trip-b-5002/live",
  "legs": [
    { "legNo": 1, "legId": "b-5002-L1", "mode": "WALK", "providerId": "SELF", "vehicleId": null,
      "from": { "name": "Hauz Khas Village", "lat": 28.5535, "lon": 77.194 },
      "to": { "name": "Hauz Khas Village Dock", "lat": 28.553, "lon": 77.195 },
      "startTime": "2026-11-15T16:26:04Z", "plannedMinutes": 2, "state": "DONE" },
    { "legNo": 2, "legId": "b-5002-L2", "mode": "SCOOTER", "providerId": "ZIPRIDE", "vehicleId": "ZR-SC-0042",
      "...": "...", "state": "CURRENT" }
  ]
}
```

`state` is `DONE`, `CURRENT` or `UPCOMING`. Walking legs are not tracked, so tracking starts at the first vehicle leg.
Errors: `404 TRIP_NOT_FOUND`, `403 NOT_TRIP_OWNER`.

### GET `/api/v1/trips/{tripId}/live` (role TRAVELLER, owner only), Server-Sent Events

Response `200`, `Content-Type: text/event-stream`, kept open. Messages (`event:` name, then `data:` JSON):

```
event:snapshot
data:{"tripId":"trip-b-5002","status":"ACTIVE","legIndex":1,"legId":"b-5002-L2","mode":"SCOOTER","vehicleId":"ZR-SC-0042","lat":28.5531,"lon":77.1951,"recordedAt":"...","lastSeenSeconds":4,"stale":false,"etaMinutes":37,"delayMinutes":0,"at":"..."}

event:position          (every new vehicle position, pushed at once)
event:status            (every 5 s; shows lastSeenSeconds growing and stale turning true after 10 s)
event:alert
data:{"tripId":"trip-b-5002","type":"SWITCH_MODE","message":"Your SCOOTER ZR-SC-0042 has not reported for 30 s","suggestion":"Switch mode: free SCOOTER ZR-SC-0041 is 776 m away","at":"..."}

event:leg-completed
data:{"tripId":"trip-b-5001","legId":"b-5001-L1","to":"India Gate"}

event:ended
data:{"status":"COMPLETED","tripId":"trip-b-5001"}
```

Errors (normal JSON, before the stream starts): `404 TRIP_NOT_FOUND`, `403 NOT_TRIP_OWNER`, `409 TRIP_NOT_ACTIVE`.

### Demo-only helpers (demo profile, no token)

| Method and path | Stands in for | Body example | Returns |
|---|---|---|---|
| POST `/dev/journey/bookings` | APP-06 publishing `BOOKING_CONFIRMED` | `{"userId":"u-101","from":"PL-HKV","to":"PL-IG","rankBy":"FEWEST_CHANGES","optionId":"opt-31e3bb5b"}` (`optionId` optional: best option) | `bookingId`, `tripId`, `liveUrl`, the trip, the published event. `404 OPTION_NOT_FOUND`, `400 VALIDATION_FAILED` |
| POST `/dev/journey/vehicle-positions` | APP-08 publishing `VEHICLE_POSITION` | `{"vehicleId":"ZR-SC-0042","providerId":"ZIPRIDE","mode":"SCOOTER","lat":28.5531,"lon":77.1951,"speedKmph":3}` | the published event |
| POST `/dev/journey/pricing-rules` | APP-09 publishing `PRICING_RULES_UPDATED` | `{"surgeFactor":1.5,"commissionPercent":10,"promotions":[{"mode":"BIKE","discountPercent":20}]}` | the published event |
| POST `/dev/journey/vehicles/{vehicleId}/pause?paused=true` | a vehicle losing signal | none | `{"vehicleId","paused","allPaused"}` |
| GET `/dev/journey/push-log` | the traveller's phone | none | the pushes the FCM stub would have sent |
| GET `/dev/events` (shared) | the Kafka topics | none | the last 100 events this app published |

## 7. Demo script

Start the app: `cd Implementation/app-05-journey` then `mvn spring-boot:run`. Swagger UI: http://localhost:8085/swagger-ui.html.
The commands below are for Git Bash; in Swagger UI use the same values with "Try it out".

1. **Get a token.**
   ```bash
   TOKEN=$(curl -s -X POST localhost:8085/dev/token -H 'Content-Type: application/json' \
     -d '{"userId":"u-101","roles":["TRAVELLER"]}' | sed 's/.*"token":"\([^"]*\)".*/\1/')
   ```
   In Swagger: run `POST /dev/token` with the same body, copy `token`, press **Authorize**.
   *Point out:* the same token format as APP-04 issues; no login session.
2. **Show the network.** `curl -s localhost:8085/api/v1/journeys/places -H "Authorization: Bearer $TOKEN"`
   *Point out:* the sample Delhi network (section 2.1), loaded in memory at start-up.
3. **Search, three rankings.**
   ```bash
   curl -s "localhost:8085/api/v1/journeys/search?from=PL-IITD&to=PL-IG&rankBy=FASTEST" -H "Authorization: Bearer $TOKEN"
   curl -s "localhost:8085/api/v1/journeys/search?from=PL-IITD&to=PL-IG&rankBy=CHEAPEST" -H "Authorization: Bearer $TOKEN"
   ```
   *Point out:* FASTEST puts the taxi first (36 min, about ₹251); CHEAPEST puts bike + metro + bus first (about ₹59);
   there is a walk + scooter + metro + scooter option. Every leg has minutes and `farePaise`; the totals are sums
   (Composite). The order changes only because the Strategy changes.
4. **D4: compute time and cache.** Look at `computeMillis` (a few ms) and `servedFromCache:false` in the first call;
   the second call (CHEAPEST) shows `servedFromCache:true` and `responseMillis` 0, because ranking happens after the cache.
5. **Nearby.** `curl -s "localhost:8085/api/v1/journeys/nearby?lat=28.6328&lon=77.2197" -H "Authorization: Bearer $TOKEN"`
   *Point out:* `lastSeenSeconds` stays small because the simulator sends positions every 5 s. Then open
   http://localhost:8085/dev/events and show the `VEHICLE_POSITION` events: this is the messaging flow (Kafka stand-in).
6. **Book an option (APP-06 stand-in).** Search `from=PL-HKV&to=PL-IG&rankBy=FEWEST_CHANGES`, copy the `optionId`
   of the WALK + SCOOTER option, then:
   ```bash
   curl -s -X POST localhost:8085/dev/journey/bookings -H 'Content-Type: application/json' \
     -d '{"userId":"u-101","from":"PL-HKV","to":"PL-IG","rankBy":"FEWEST_CHANGES","optionId":"<optionId>"}'
   ```
   *Point out:* the answer shows the `BOOKING_CONFIRMED` event (contract format) and the trip it created (`tripId`,
   `vehicleId` `ZR-SC-0042`). `/dev/events` also shows the `AUDIT` event `TRIP_CREATED`.
7. **Watch the trip live (SSE).**
   ```bash
   curl -N localhost:8085/api/v1/trips/<tripId>/live -H "Authorization: Bearer $TOKEN"
   ```
   *Point out:* `snapshot` at once, then a `position` every 5 s as the scooter moves, without the client asking (push,
   D5). The leg takes about 60 s in the demo (12 steps); then `leg-completed` and `ended`, and the stream closes.
8. **Stale flag and SWITCH_MODE (D5, M2.6).** Book again (step 6, new trip), start step 7 in one terminal, and in
   another: `curl -s -X POST "localhost:8085/dev/journey/vehicles/ZR-SC-0042/pause?paused=true"`.
   *Point out:* `lastSeenSeconds` grows in each `status`; after 10 s `stale:true`; at 30 s an `alert` of type
   `SWITCH_MODE` with the nearest free vehicle. `curl -s localhost:8085/dev/journey/push-log` shows the phone push.
9. **DELAY alert.** While the scooter is paused, report it crawling at the start:
   ```bash
   curl -s -X POST localhost:8085/dev/journey/vehicle-positions -H 'Content-Type: application/json' \
     -d '{"vehicleId":"ZR-SC-0042","providerId":"ZIPRIDE","mode":"SCOOTER","lat":28.5531,"lon":77.1951,"speedKmph":3}'
   ```
   *Point out:* a `DELAY` alert ("Running about … min late") on the stream and in the push log. Unpause with `paused=false`.
10. **Only the owner.** Get a token for `u-999` (step 1) and open the same `/live` URL: `403 NOT_TRIP_OWNER`.
11. **Fares change at run time (APP-09 stand-in).**
    ```bash
    curl -s "localhost:8085/api/v1/journeys/search?from=PL-KM&to=PL-IG&modes=BIKE" -H "Authorization: Bearer $TOKEN"
    curl -s -X POST localhost:8085/dev/journey/pricing-rules -H 'Content-Type: application/json' \
      -d '{"surgeFactor":1.5,"commissionPercent":10,"promotions":[{"mode":"BIKE","discountPercent":20}]}'
    curl -s "localhost:8085/api/v1/journeys/search?from=PL-KM&to=PL-IG&modes=BIKE" -H "Authorization: Bearer $TOKEN"
    ```
    *Point out:* the bike fare changes (×1.5 surge, then 20% off) and `servedFromCache` is `false`: the cache was
    emptied because prices changed.
12. **Errors.** `from=Nowhere` → `404 UNKNOWN_PLACE`; `modes=WALK` from `PL-IITD` to `PL-IG` → `404 NO_ROUTE_FOUND`.

## 8. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
| OpenTripPlanner with GTFS timetables (Delhi Open Transit Data), OpenStreetMap streets, GBFS dock feeds | `GraphRoutePlanner` + `SampleDelhiNetwork` behind `RoutePlanner` | An `OtpRoutePlanner` adapter calling OTP's planning API, same interface |
| Redis CACHE-1 (search results, latest positions) | Spring in-memory cache (`@Cacheable`) and `VehiclePositionStore` | Spring Data Redis with a TTL; same annotation |
| Kafka topics `vehicle.events`, `schedule.events`, `booking.events`, `pricing.rules`, `audit.events` | Shared in-process `EventPublisher` and `@EventListener` | Kafka publisher/listener behind the same interface |
| APP-08 Provider Integration Hub (vehicle feed, INT-14) | `VehicleFeedSimulator` (demo profile) and `POST /dev/journey/vehicle-positions` | Kafka events from APP-08 |
| APP-06 Booking & Ticketing (INT-16) | `POST /dev/journey/bookings` publishes `BOOKING_CONFIRMED` | Kafka events from APP-06 |
| APP-09 Analytics (INT-18 pricing rules) | `POST /dev/journey/pricing-rules` | Kafka events from APP-09 |
| Firebase Cloud Messaging (phone push) | `StubFcmPushNotifier` (logs, keeps last 50) | FCM HTTP v1 API behind `PushNotifier` |
| WebSocket push (INT-03) | Server-Sent Events | WebSocket gateway; same messages |
| M2.7 re-planning | Not built; SWITCH_MODE suggests the nearest free vehicle | Call the search from the vehicle's last position for the remaining legs |

## 9. Tests

Run with `mvn test` inside `app-05-journey`: **41 tests, all passing** (32 module tests + 9 shared tests).
The 9 shared tests (`SharedSetupTest`, `JwtServiceTest`) are explained in `../SHARED-SETUP.md`, section 8.
In tests the timers are switched off (`src/test/resources/config/application.yml` sets
`mmts.journey.scheduler.enabled: false`), so the simulator never interferes; tests publish the events themselves.

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `GraphRoutePlannerTest` | `findsMixedModeRouteWithWalkMetroAndScooter` | IIT Delhi → India Gate has an option using walk + metro + scooter, and every option's legs join up from start to end | M2.1 mixed-mode search |
| | `offersSeveralDifferentOptionsIncludingTaxi` | At least 3 different options (no duplicate ids), one of them a door-to-door taxi | M2.1 real choices |
| | `walkingOnlyBetweenFarPlacesFindsNothing` | Walking only, IIT Delhi → India Gate: no route | NO_ROUTE_FOUND rule |
| | `noScooterRouteWhenNoScooterIsParkedAtTheDock` | With no vehicles reported, no scooter/bike/taxi is suggested; Connaught Place still has a public-transport route; IIT Delhi then has none | M2.2 data feeds M2.1 |
| | `metroLineChangeIsOneTicketButCountsAsAChange` | Karol Bagh → Hauz Khas is one metro leg (Blue then Yellow Line), one fare, counted as one change | Fare and change counting |
| `JourneyDomainTest` | `factoryCreatesTheRightLegTypeForEachMode` | WALK→`WalkLeg`, METRO→`MetroLeg`, BUS→`BusLeg`, SCOOTER→`ScooterLeg`, BIKE→`BikeLeg`, TAXI→`TaxiLeg`; CAR refused | Factory Method |
| | `eachLegPricesItselfWithItsOwnRule` | Walk free; metro and bus by distance band; scooter/bike unlock + per minute; taxi base + per km | M2.4 fares |
| | `pricingRulesChangeSurgeAndPromotions` | Surge 1.5 raises taxi; surge + 20% promotion on bike; metro unchanged | M2.4 pricing rules |
| | `compositeTotalsWorkTheSameForOneLegOrMany` | Totals for one leg, three legs, and a journey nested inside a journey are the same sums; changes counted | Composite |
| | `positionOlderThanTenSecondsIsStale` | 10 s old is still live, 11 s old is stale | D5 staleness rule |
| `RankingAndStoreTest` | `fastestPutsShortestTimeFirst` | Order taxi, mixed, transit | Strategy (M2.3) |
| | `cheapestPutsLowestFareFirst` | Order transit, mixed, taxi | Strategy (M2.3) |
| | `fewestChangesPutsFewestVehicleChangesFirst` | Order taxi, transit, mixed | Strategy (M2.3) |
| | `storeKeepsNewestPositionAndIgnoresOlderOnes` | A late, older event does not overwrite a newer position | D5 correctness |
| | `storeFindsOnlyFreeVehiclesInsideTheRadiusNearestFirst` | Busy, far and wrong-mode vehicles are left out; nearest first | M2.2 nearby |
| `JourneyApiTest` | `searchReturnsRankedOptionsWithLegsTimesAndFares` | `200` with from/to, rank, legs, instructions; CHEAPEST is ascending; each total equals the sum of its legs | INT-02 contract, M2.3, M2.4 |
| | `d4SearchIsWellUnderTwoSecondsAndRepeatIsServedFromCache` | First search: not cached, `computeMillis` and wall time under 2000 ms; same search again: `servedFromCache` true, same options | **D4** |
| | `scheduleUpdatedEventEmptiesTheCache` | After `SCHEDULE_UPDATED` the next search is computed again | Event subscription (INT-14) |
| | `pricingRulesEventChangesFares` | After `PRICING_RULES_UPDATED` the bike fare equals (unlock + per minute) × 1.5 × 80%, and the cache was emptied | M2.4, INT-18 |
| | `placesListsTheSampleNetwork` | 32 points including Rajiv Chowk and India Gate | Sample data |
| | `nearbyShowsFreeVehiclesWithLastSeenAndStaleFlag` | Nearest scooter to Rajiv Chowk is ZR-SC-0043, with distance, `lastSeenSeconds`, `stale` | M2.2, D5 |
| | `errorsUseClearCodes` | `UNKNOWN_PLACE`, `NO_ROUTE_FOUND`, `SAME_PLACE`, `UNKNOWN_RANKING`, `UNKNOWN_MODE`, `BAD_REQUEST`, `INVALID_RADIUS`, `INVALID_LOCATION` | Shared error format |
| | `searchNeedsTravellerLogin` | No token `401`; DRIVER token `403 FORBIDDEN` | RBAC |
| `TripTrackingTest` | `bookingConfirmedEventCreatesTripForItsOwner` | A `BOOKING_CONFIRMED` event creates `trip-b-T1` with its legs, scooter leg current, and an `AUDIT` `TRIP_CREATED` event | M2.5, INT-16, INT-19 |
| | `sameBookingEventTwiceGivesOneTrip` | The same event twice gives one trip | Idempotent event handling |
| | `onlyTheOwnerMaySeeATrip` | Other traveller `403 NOT_TRIP_OWNER` (trip and stream, audited as DENIED); unknown `404 TRIP_NOT_FOUND`; DRIVER `403`; no token `401` | Owner-only access |
| | `liveStreamIsServerSentEventsAndStartsWithSnapshot` | `/live` starts an async response of type `text/event-stream`, first message `snapshot` with the trip and vehicle; a new position is pushed into the open stream | INT-03, **D5** push |
| | `cancelledTripCannotBeStreamed` | After `BOOKING_CANCELLED`, `/live` gives `409 TRIP_NOT_ACTIVE` | Trip lifecycle |
| | `vehicleReachingTheEndOfTheLastLegCompletesTheTrip` | A position at the leg's end makes the trip `COMPLETED` | M2.5 tracking |
| | `silentVehicleIsStaleAndTriggersSwitchModeAlert` | A position 40 s old: `stale` true, and a "Switch mode?" push to the owner | **D5**, M2.6 |
| | `slowVehicleTriggersDelayAlert` | A vehicle at 2 km/h far from the end: `delayMinutes` ≥ 5 and a "Trip delayed" push | M2.6 |
| | `demoHelpersSimulateTheOtherApplications` | Demo booking publishes `BOOKING_CONFIRMED` and returns the trip; `OPTION_NOT_FOUND`; validation; vehicle-position, pricing-rules, pause and push-log helpers work | Demo stand-ins |

## 10. Questions you may be asked

1. **Why did you write your own route planner instead of using OpenTripPlanner?**
   OTP needs a separate server plus GTFS and OpenStreetMap data, which is heavy for a demo. Our planner sits behind the
   `RoutePlanner` interface (Adapter), so OTP can replace it later without touching any service code.
2. **How does Dijkstra's algorithm work here?** It always continues from the cheapest state found so far (a priority
   queue gives it that), so the first time it reaches the destination it has the cheapest path. A state is "stop +
   vehicle you are on", which lets it add waiting time and count changes.
3. **How do you get several options and not just one?** We run the search four times with different costs (time,
   time + change penalty, time + price, public transport only), add a taxi option, and remove duplicates. Then a
   ranking strategy orders them.
4. **How do you meet D4 (about 2 s)?** The network is in memory, the graph is small, and results are cached with
   `@Cacheable`. The response reports `computeMillis` and `servedFromCache`; a test checks it is far under 2 s and
   that a repeat comes from the cache. With Redis the annotation stays the same.
5. **Doesn't the cache give old prices or vehicles?** It is emptied when pricing rules or schedules change and every
   60 seconds (like a Redis TTL). The booking step (APP-06/APP-08 hold) checks availability again anyway.
6. **How do you meet D5 (positions fresh within about 10 s)?** Positions arrive as events and are pushed to the open
   SSE stream immediately; nobody polls. Every message has `lastSeenSeconds` and `stale`; after 10 s without news the
   app says "stale" instead of showing a wrong live position, and after 30 s it suggests switching mode.
7. **Why Server-Sent Events and not WebSocket?** The flow is one-way (server to phone). SSE is plain HTTP, works with
   `curl`, and was agreed in `CONTRACTS.md` for the demo. WebSocket would carry the same messages in production.
8. **Where is the Strategy pattern, and why?** `RankingStrategy` with three classes. The ranking is chosen per request;
   a new ranking (for example "greenest") is one new class and nothing else changes (open/closed principle).
9. **Explain Composite and Factory Method in your code.** `Journey` and `Leg` both implement `JourneyPart`, so total
   time and fare are asked the same way for one leg or many (a journey can even contain a journey). `LegFactory`
   decides which `Leg` subclass to create for each mode, and gives it the current fares.
10. **How is a trip created? What if the event comes twice?** From the `BOOKING_CONFIRMED` event on `booking.events`.
    The trip id is `trip-<bookingId>`, and an existing trip is returned unchanged, so a duplicate event does no harm.
11. **How do fares change without a code change?** Defaults are in `application.yml` (Externalization). APP-09's
    `PRICING_RULES_UPDATED` event changes surge and promotions at run time; money is integer paise.
12. **Who may see a trip?** Only a TRAVELLER whose user id matches the trip's owner. Others get `403 NOT_TRIP_OWNER`
    and the attempt is published as an `AUDIT` event for APP-09.
