# APP-04 Identity & Verification Service: module guide

**Module:** M1 User, Account & Verification · **Owner:** Athira (MT2025027) · **Folder:** `app-04-identity` · **Port:** 8084

> This guide explains one application so its owner can understand it, demo it and defend it in the viva.
> It is written for a complete beginner. A technical word is explained the first time it appears.
> The short README is in `app-04-identity/README.md`.

---

## 1. What this application does

APP-04 is the "front door" of MMTS. It knows **who each person is** and **whether they may drive**.
Every other MMTS application trusts the login token that APP-04 gives out.

A few words first:

- **Account**: the record of one person (name, mobile number, email, password).
- **Login token (JWT)**: after you log in, APP-04 gives you a long text string called a *JSON Web Token*. It says
  "this is user u-101, with role TRAVELLER, valid for 60 minutes", and it is *signed* with a secret key, so nobody
  can change it without the change being noticed. You send it with every request
  (`Authorization: Bearer <token>`).
- **Role**: a label that decides what you may do: `TRAVELLER`, `DRIVER`, `ADMIN`, `SERVICE` (another MMTS app) and so on.
  Deciding access from roles is called **RBAC** (role-based access control).
- **OTP**: a *one-time password*, a 6-digit code sent by SMS that works only once and only for a few minutes.
- **DigiLocker**: the Government of India service that holds official documents such as driving licences.

The functions of module M1 (architecture document, section 2.3) and where each one is:

| Function | What it means in plain words | Where in the code |
|---|---|---|
| M1.1 Register | Create a traveller or driver account with name, mobile, email and password. The password is stored only as a BCrypt hash. | `AuthController.register` → `AccountService.register` |
| M1.2 Log in | Log in with mobile + password, **or** ask for an OTP by SMS and log in with it. You get a login token. | `AuthController` → `AuthService` → `PasswordLoginStrategy` / `OtpLoginStrategy` |
| M1.3 Profile | See and change your name and email, keep "saved places" (Home, Office), give or withdraw consent for licence checks. | `UserController` → `AccountService` |
| M1.4 Licence verification | Send your licence number and date of birth. APP-04 checks it with DigiLocker in the background. Result: VERIFIED, REJECTED or EXPIRED. | `LicenceController` → `LicenceService` → `IdentityDocumentProvider` |
| M1.5 Drive eligibility | Other apps ask "may user u-101 drive a car?". APP-04 answers yes or no, with a reason. This is INT-10 (APP-06) and INT-13 (APP-08). | `EligibilityController` → `EligibilityService` → `DrivingLicence.eligibilityFor` |
| M1.6 Licence expiry check | Every night a job marks licences whose date has passed as EXPIRED and tells other apps. An admin can also run it now. | `LicenceService.nightlyExpiryCheck` / `runExpiryCheck` |
| M1.7 Delete my data | Delete my account: all personal data and the licence are removed. | `UserController.deleteMe` → `AccountService.deleteMyData` |
| M1.8 Admin and roles | Admins list users and give or take away roles. | `UserController` (admin part) → `AccountService` |

---

## 2. How it works

### 2.0 The layers (who does what)

A request travels through the same layers every time (closed layers, Figure 3 of the architecture document):

```
HTTP request
   │
   ▼  common/security   JwtAuthenticationFilter reads the token; SecurityConfig and @PreAuthorize check the role
   ▼  api/              Controller: reads the JSON into a DTO (a plain data record), checks it with @Valid
   ▼  service/          Service: the business rules and the order of the steps
   ▼  domain/           Entities (UserAccount, DrivingLicence, OtpChallenge) with their own rules
   ▼  repository/       Spring Data repositories: save / load rows in the database (H2 in the demo)
      adapter/          Interfaces to the outside world: IdentityDocumentProvider (DigiLocker), SmsGateway (SMS)
```

- A **controller** never talks to a repository; it always calls a service.
- An **entity** (a Java class that is saved as a database row) never leaves the service layer; controllers return
  **DTOs** (data transfer objects: small Java `record`s in `api/dto/`).
- Errors are thrown as `ApiException` (for example `USER_NOT_FOUND`) and turned into the shared error JSON
  (`code`, `message`, `details`, `timestamp`) by `GlobalExceptionHandler`.

### 2.1 Register (M1.1)

1. `POST /api/v1/auth/register` arrives. This path needs no token: `IdentityConfig.publicAuthEndpoints` opens
   `/api/v1/auth/**` (you cannot have a token before you have an account).
2. `AuthController.register` turns the JSON into a `RegisterRequest` and `@Valid` checks it: name not empty, mobile is a
   10-digit Indian number starting with 6–9, email looks like an email, password 8–72 characters. Bad fields give
   `400 VALIDATION_FAILED`, with each bad field listed in `details`.
3. `AccountService.register` checks the mobile and email are not already used (`409 MOBILE_ALREADY_REGISTERED` /
   `EMAIL_ALREADY_REGISTERED`).
4. It hashes the password with **BCrypt**. A *hash* is a one-way scramble: from the hash you cannot get the password
   back, but you can check whether a typed password matches. BCrypt is deliberately slow and adds a random *salt*,
   so stolen hashes are very hard to crack.
5. It creates a `UserAccount` with id like `u-3f9a1c2e` and role `TRAVELLER` or `DRIVER`, and saves it with
   `UserAccountRepository`.
6. `AuditService` publishes an `AUDIT` event (who did what, no personal data).
7. The answer is `201 Created` with the profile (never the password or hash).

### 2.2 Log in with a password (M1.2)

1. `POST /api/v1/auth/login` with `{"method":"PASSWORD","mobile":"…","password":"…"}`.
2. `AuthService.login` looks at `method` and picks the matching **login strategy** (see Strategy pattern, section 5).
3. `PasswordLoginStrategy.authenticate` loads the active account by mobile and compares the password with the BCrypt hash.
   Wrong mobile **or** wrong password both give the same `401 INVALID_CREDENTIALS`, so nobody can find out which
   mobile numbers are registered.
4. `AuthService` asks the shared `JwtService` to issue a token with `sub` = user id and `roles` = the user's roles.
5. It publishes an `AUDIT` event (`LOGIN`, outcome `SUCCESS_PASSWORD` or `FAILED_<code>`).
6. The answer: `token`, `expiresAt`, `userId`, `roles`.

Nothing is stored on the server for this login: there is **no session**. This is why logins scale (driver D1).

### 2.3 Log in with an OTP (M1.2)

1. `POST /api/v1/auth/otp` with the mobile number → `AuthService.requestOtp` → `OtpLoginStrategy.sendOtp`.
2. If the mobile belongs to an active account, it makes a random 6-digit code (`SecureRandom`), stores **only its BCrypt
   hash** in an `OtpChallenge` row (with an expiry time 5 minutes ahead), and sends the code through the `SmsGateway`
   interface. In the demo, `StubSmsGateway` does not really send an SMS; it remembers the last message.
3. If the mobile is not registered, nothing is sent, but the answer is the same (`202`, masked number), again so nobody can
   probe which numbers have accounts.
4. In the demo you read the code at `GET /dev/otp/{mobile}` (`DevOtpController`, demo profile only).
5. `POST /api/v1/auth/login` with `{"method":"OTP","mobile":"…","otp":"123456"}` → `OtpLoginStrategy.authenticate`:
   - no code was asked for → `401 OTP_NOT_REQUESTED`
   - older than 5 minutes → `401 OTP_EXPIRED`
   - 5 wrong tries already → `429 OTP_TOO_MANY_ATTEMPTS` (the code is thrown away)
   - wrong code → the try is counted, `401 OTP_INVALID`
   - right code → the `OtpChallenge` is deleted (works only once) and the user is returned
6. From here it is the same as the password login: a token is issued.

The code is kept in the **database**, not in the memory of one server, so any copy of APP-04 can check it
(in production this would be Redis with a time-to-live).

### 2.4 Profile, saved places and consent (M1.3)

1. `GET /api/v1/users/me`: `UserController.me` takes the user id **from the token** (`@AuthenticationPrincipal`), never from
   the URL, so you can only ever see your own profile. `AccountService.profile` loads it and builds a `ProfileResponse`.
2. `PUT /api/v1/users/me`: change name and email (`409 EMAIL_ALREADY_REGISTERED` if someone else has that email).
   The mobile number cannot be changed here (in production that needs an OTP on the new number).
3. `PUT /api/v1/users/me/saved-places`: replaces the list of saved places (at most 10). Each place has the same fields as
   the shared `Place` type in `CONTRACTS.md` plus a `label`.
4. `PUT /api/v1/users/me/consent` with `{"licenceCheck":true}`: records (or withdraws) consent to fetch the licence from
   DigiLocker, with the time. An `AUDIT` event `CONSENT_GIVEN` / `CONSENT_WITHDRAWN` is published.

### 2.5 Submit a driving licence (M1.4)

1. `POST /api/v1/licences` with licence number, date of birth and `consent: true` (role TRAVELLER or DRIVER).
2. `LicenceService.submit`:
   - `consent` not true → `400 CONSENT_REQUIRED` (DigiLocker may only be asked with the person's consent, DPDP Act).
   - a check is already running → `409 VERIFICATION_IN_PROGRESS`.
   - the number is tidied (`ka0120200054321` → `KA-0120200054321`), consent is recorded on the account.
   - the licence is saved with status **PENDING**. The licence number is **encrypted** on its way into the database (section 4, D2).
   - the work of asking DigiLocker is handed to a **background worker** (`licenceVerificationExecutor`, a small pool of
     threads named `licence-check-…`). The request does **not** wait for it.
3. The answer comes back at once: `202 Accepted`, status `PENDING`, licence number **masked** (`KA-01*********21`).
4. In the background, `LicenceService.verify` asks `IdentityDocumentProvider.fetchDrivingLicence` (in the demo:
   `StubDigiLockerAdapter`):
   - **found** and still valid → status `VERIFIED`, with the vehicle classes and expiry date → event `LICENCE_VERIFIED`
   - **found** but already past its date → status `EXPIRED` → event `LICENCE_EXPIRED`
   - **not found** or **date of birth does not match** → status `REJECTED` with the reason → event `LICENCE_REJECTED`
   - DigiLocker cannot be reached (an exception) → it stays `PENDING` and a warning is logged.
5. The date of birth is **deleted** after the check: it was only needed for the lookup (data minimisation).
6. The user sees the result with `GET /api/v1/licences/me`.

The status changes form a small **state machine**:

```
          submit                 DigiLocker says…
 (none) ─────────▶ PENDING ──┬──▶ VERIFIED ──(nightly job: date passed)──▶ EXPIRED
                             ├──▶ EXPIRED   (found, but already out of date)
                             └──▶ REJECTED  (not found / details mismatch)
 re-submit (after rejection, or a renewed licence) ──▶ PENDING again
```

### 2.6 Drive eligibility check (M1.5, INT-10 and INT-13)

1. APP-06 (before a self-drive booking) or APP-08 (before a taxi driver goes online) calls
   `GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE` with a token that has role `SERVICE`
   (an `ADMIN` may also call it, for support).
2. `EligibilityController.check` → `EligibilityService.check`:
   1. unknown or deleted user → `404 USER_NOT_FOUND`
   2. no licence → `NO_LICENCE`
   3. otherwise the rule in `DrivingLicence.eligibilityFor(vehicleClass, today)`:
      `PENDING` → `PENDING_VERIFICATION`; `REJECTED` → `REJECTED`; `EXPIRED` → `EXPIRED`;
      `VERIFIED` but the expiry date is before today → `EXPIRED` (checked **live**, so we never wait for the nightly job);
      `VERIFIED` but the class is not on the licence → `CLASS_NOT_COVERED`; otherwise `VERIFIED`.
   4. **Extra rule for `TAXI_DRIVER`** (our decision): if everything above says `VERIFIED` but the account does not have
      the `DRIVER` role → `NOT_A_DRIVER`. Reason: a traveller with a car licence must not be switched on as a taxi driver.
      Becoming a driver is a separate onboarding step (an admin gives the DRIVER role after background checks), and the
      licence alone must not be enough.
3. `eligible` is `true` only when `reason` is `VERIFIED`. `licenceExpiresOn` is the licence's date, or `null` if there is no licence.
4. An `AUDIT` event records who asked about whom, and the answer.
5. "Today" is taken in India time (`Asia/Kolkata`, setting `mmts.identity.zone`). A licence is valid up to **and including** its expiry date.

### 2.7 Licence expiry check (M1.6)

1. `LicenceService.nightlyExpiryCheck` has `@Scheduled(cron = "0 30 1 * * *", zone = "Asia/Kolkata")`: Spring runs it every
   night at 01:30 India time (`@EnableScheduling` is in `IdentityConfig`).
2. For the demo, an admin can run it now: `POST /api/v1/licences/expiry-check`.
3. `runExpiryCheck` finds every `VERIFIED` licence whose expiry date is before today, marks it `EXPIRED`, and publishes
   `LICENCE_EXPIRED` (`userId`, `expiredOn`) for each one, so APP-06 and APP-08 can react (cancel future self-drive
   bookings, take the driver offline).
4. The answer: `checkedOn` and `expiredCount`. One `AUDIT` event records the run.

### 2.8 Delete my account and data (M1.7)

1. `DELETE /api/v1/users/me` → `AccountService.deleteMyData` (one database transaction: all or nothing).
2. It deletes any OTP waiting for this mobile, deletes the **licence row completely**, then calls `UserAccount.anonymise`:
   name, mobile, email, password hash, saved places, roles and consent are set to empty; status becomes `DELETED`.
3. Only the id (`u-…`) stays, so bookings and payments in other apps that store this id do not break, but the id no
   longer points to a person. The mobile number is free again, so the person could register afresh.
4. After this, login fails and every "me" or eligibility call gives `404 USER_NOT_FOUND`.
5. The answer is `204 No Content`. An `AUDIT` event `DELETE_PERSONAL_DATA` is published (with ids only).

### 2.9 Admin: users and roles (M1.8)

1. `GET /api/v1/users` (ADMIN): every user with the mobile **masked** (`******3210`); the admin does not need the full number.
2. `POST /api/v1/users/{userId}/roles` with `{"role":"DRIVER"}`: adds a role. `SERVICE` cannot be given to a person
   (`400 ROLE_NOT_ASSIGNABLE`).
3. `DELETE /api/v1/users/{userId}/roles/{role}`: removes a role. An admin cannot remove their own `ADMIN` role
   (`409 CANNOT_REMOVE_OWN_ADMIN`), so the last admin cannot lock everyone out by accident.
4. A role change shows in the **next** token: tokens already given out keep their roles until they expire (at most 60 minutes).
5. Each change publishes an `AUDIT` event (`ASSIGN_ROLE_DRIVER`, `REMOVE_ROLE_DRIVER`).

---

## 3. Design decisions and why

| Decision | Why | Alternative we rejected |
|---|---|---|
| Stateless JWT login tokens, no server session | Any copy of any MMTS app checks a token with the shared key, without asking APP-04 or a session store. Add servers and logins scale (D1). | Server sessions with a cookie: needs sticky sessions or a shared session store, and every app would have to call APP-04. |
| Licence check in a background worker; `submit` answers `202 PENDING` at once | DigiLocker can be slow or down. Users and logins must never wait for it (D1). | Calling DigiLocker inside the request: one slow partner would slow the whole app. |
| Synchronous worker in tests (`mmts.identity.verification.async=false`) | Tests see the final result immediately and give the same result every time. | Waiting/sleeping in tests: slow and flaky. |
| Licence number encrypted with AES-256-GCM in a JPA `AttributeConverter`; key from configuration | The most sensitive field is unreadable if the database or a backup leaks (D2). The converter works automatically on every save and load, so no business code can forget it. | Database-level disk encryption only: does not protect against someone who can run queries. Encrypting by hand in the service: easy to forget once. |
| Mobile and email are **not** encrypted | Login looks people up by mobile; a value encrypted with a random IV cannot be searched. In production we would add a keyed hash ("blind index") column for lookups and encrypt these too. | Encrypting them anyway: login by mobile would stop working. |
| Date of birth deleted after the check | It is only needed for the DigiLocker lookup (data minimisation, DPDP Act). | Keeping it "just in case". |
| Delete-my-data **anonymises** the account row and **deletes** the licence | Other apps keep the user id in bookings and payments (financial records must be kept). The id stays but points to nobody. | Deleting the row: breaks references in other apps' data. |
| Eligibility rule lives in the entity `DrivingLicence.eligibilityFor` | The most important rule (D3) can be unit-tested with no database or Spring. | Rule spread over the controller or service. |
| Expiry date is checked live in the eligibility rule, as well as by the nightly job | Safety (D3) does not depend on the job having run. The job exists to publish `LICENCE_EXPIRED` so other apps can react. | Relying on the nightly job only: a licence would be "valid" for up to one day too long. |
| Extra reason `NOT_A_DRIVER` for `TAXI_DRIVER` | A licence alone must not make someone a taxi driver; the DRIVER role (given after onboarding) is also needed. | Ignoring the purpose: any traveller with a car licence could be activated as a driver. |
| OTP and passwords both hashed with BCrypt; OTP stored in the database | A database leak does not reveal live codes; any server copy can check the code. | OTP in one server's memory: breaks when there are two servers. |
| Same answer for "unknown mobile" and "wrong password", and for OTP requests | Nobody can use the API to find out who has an MMTS account. | Telling the caller "this mobile is not registered". |
| An extra security rule (`@Order(1)`) opens only `/api/v1/auth/**` | The shared `SecurityConfig` in `common/` must stay identical in every app, so APP-04 adds its own small rule instead of changing it. | Editing the shared `SecurityConfig`. |
| Admin endpoints under `/api/v1/users` and `/api/v1/licences` | These are APP-04's agreed base paths in `CONTRACTS.md`. | A new `/api/v1/admin/**` path. |
| All settings (key, OTP minutes, attempts, cron, time zone, BCrypt cost) in `application.yml` | Externalization pattern: change behaviour without changing code. | Constants in Java. |

---

## 4. Architecture drivers demonstrated

| Driver | How the code meets it | How to see it in the demo |
|---|---|---|
| **D1** Logins fast at scale | Stateless JWT (`JwtService`, `SessionCreationPolicy.STATELESS`): no session is created. OTP codes are in the database, not one server's memory. The DigiLocker check runs on a separate thread pool, never on the login or submit path. | Log in (step 2): the response has a token, no cookie. Submit a licence (step 8): the answer is `PENDING` at once; the log shows the check on thread `licence-check-1`. Test `loginCreatesNoServerSession`. |
| **D2** Personal data encrypted, deletable, never logged | `EncryptedStringConverter` (AES-256-GCM) on the licence number column. `Masking` hides mobiles and licence numbers in logs, in API answers (`maskedLicenceNumber`) and in the admin list. `AUDIT` events carry only ids. Date of birth removed after the check. `DELETE /api/v1/users/me` anonymises the account and deletes the licence. | Open the H2 console and run `select licence_number from driving_licence`: only scrambled text (step 9). Look at the console log: only `******3210`. Delete an account (step 14) and try to log in again. |
| **D3** No self-drive or driver activation without a valid current licence | `DrivingLicence.eligibilityFor` + `EligibilityService`: only `VERIFIED`, unexpired, right class (and the DRIVER role for taxi) gives `eligible: true`. Expiry is checked live. The nightly job marks old licences `EXPIRED` and publishes `LICENCE_EXPIRED`. | Eligibility calls in steps 10–13: `VERIFIED`, `EXPIRED`, `CLASS_NOT_COVERED`, `NOT_A_DRIVER`. |

---

## 5. Design patterns used

| Pattern | Where in the code | Why here |
|---|---|---|
| **Adapter** (ports and adapters) | `adapter/IdentityDocumentProvider` with `StubDigiLockerAdapter`; `adapter/SmsGateway` with `StubSmsGateway` | Business code talks to a simple interface. The DigiLocker or SMS details (OAuth, XML, DLT templates) stay inside one class. Swapping the stub for the real partner needs no change to the services (principle P3). |
| **Strategy** | `service/LoginStrategy` with `PasswordLoginStrategy` and `OtpLoginStrategy`; `AuthService` picks one by `method` | Two ways to prove who you are, one way to issue the token. A new method (for example Aadhaar OTP) is one new class. |
| **RBAC** (role-based access control) | `@PreAuthorize("hasRole('ADMIN')")` and similar on every controller method; roles stored per user (`user_role` table) and copied into the token | Access depends on the role, not on hand-written `if` checks in each method. Admins manage roles through the API (M1.8). |
| **Observer / publish-subscribe (events)** | `LicenceService` publishes `LICENCE_VERIFIED`, `LICENCE_REJECTED`, `LICENCE_EXPIRED` on `licence.events`; `AuditService` publishes `AUDIT` on `audit.events`, through the shared `EventPublisher` | APP-06 and APP-08 learn about licence changes without APP-04 knowing them (loose coupling). In production the topic is Kafka; in the demo it is an in-process bus shown at `GET /dev/events`. |
| **State machine** | `LicenceStatus` and the methods `submit`, `markFound`, `markRejected`, `markExpired` in `DrivingLicence` | Only allowed changes can happen, all in one place. |
| **DAO / Repository** | `repository/*Repository` (Spring Data JPA) | The database is reached only here; SQL is generated from method names. |
| **DTO** | `api/dto/AuthDtos`, `UserDtos`, `LicenceDtos` | Entities (with password hashes and encrypted fields) never go out in JSON. |
| **Externalization** | `mmts.identity.*` in `application.yml` | Keys, times and limits change without code changes. |

---

## 6. Endpoints in detail

All bodies are JSON. Times are UTC (`…Z`); dates are `YYYY-MM-DD`. Every error has the shared shape:

```json
{ "code": "USER_NOT_FOUND", "message": "No user with id u-999", "details": [], "timestamp": "2026-11-15T10:00:00Z" }
```

Errors that can happen on every protected endpoint: `401 UNAUTHORIZED` (no token, wrong or expired token),
`403 FORBIDDEN` (your role may not do this), `400 VALIDATION_FAILED` (bad fields, listed in `details`),
`400 BAD_REQUEST` (unreadable JSON, unknown enum value such as `vehicleClass=TRUCK`).

### 6.1 `POST /api/v1/auth/register`: no token

```json
{ "fullName": "Kavya Reddy", "mobile": "9701234567", "email": "kavya.reddy@example.in",
  "password": "Kavya@123", "accountType": "TRAVELLER" }
```

`201 Created`:

```json
{ "userId": "u-3f9a1c2e", "fullName": "Kavya Reddy", "mobile": "9701234567", "email": "kavya.reddy@example.in",
  "roles": ["TRAVELLER"], "savedPlaces": [], "licenceCheckConsent": false, "consentRecordedAt": null,
  "createdAt": "2026-11-15T10:00:00Z" }
```

`accountType` is `TRAVELLER` or `DRIVER`. Errors: `400 VALIDATION_FAILED`, `409 MOBILE_ALREADY_REGISTERED`,
`409 EMAIL_ALREADY_REGISTERED`.

### 6.2 `POST /api/v1/auth/otp`: no token

```json
{ "mobile": "9876543210" }
```

`202 Accepted` (the same answer whether or not the number is registered):

```json
{ "sentTo": "******3210", "expiresInSeconds": 300 }
```

Errors: `400 VALIDATION_FAILED`.

### 6.3 `GET /dev/otp/{mobile}`: no token, demo profile only

`200`:

```json
{ "mobile": "9876543210", "otp": "482913",
  "message": "Your MMTS login code is 482913. It is valid for 5 minutes. Do not share it with anyone.",
  "sentAt": "2026-11-15T10:00:00Z" }
```

Errors: `404 NO_SMS_SENT`.

### 6.4 `POST /api/v1/auth/login`: no token

Password: `{ "method": "PASSWORD", "mobile": "9876543210", "password": "Priya@123" }`
OTP: `{ "method": "OTP", "mobile": "9876543210", "otp": "482913" }`

`200`:

```json
{ "token": "eyJhbGciOiJIUzI1NiJ9…", "expiresAt": "2026-11-15T11:00:00Z", "userId": "u-101", "roles": ["TRAVELLER"] }
```

Errors: `400 PASSWORD_REQUIRED` / `400 OTP_REQUIRED` (field missing for that method), `401 INVALID_CREDENTIALS`,
`401 OTP_NOT_REQUESTED`, `401 OTP_EXPIRED`, `401 OTP_INVALID`, `429 OTP_TOO_MANY_ATTEMPTS`.

### 6.5 `GET /api/v1/users/me`: TRAVELLER, DRIVER, OPERATOR, ADMIN, SUPPORT, FINANCE, REGULATOR

`200`:

```json
{ "userId": "u-101", "fullName": "Priya Sharma", "mobile": "9876543210", "email": "priya.sharma@example.in",
  "roles": ["TRAVELLER"],
  "savedPlaces": [
    { "label": "Home", "name": "Rajiv Chowk Metro", "lat": 28.6328, "lon": 77.2197, "stopId": "DMRC-RCK" },
    { "label": "Office", "name": "Cyber City, Gurugram", "lat": 28.495, "lon": 77.0895, "stopId": null } ],
  "licenceCheckConsent": true, "consentRecordedAt": "2026-11-15T10:00:00Z", "createdAt": "2026-11-15T10:00:00Z" }
```

Errors: `404 USER_NOT_FOUND` (token for a user id that does not exist or was deleted), `403 FORBIDDEN` (e.g. a SERVICE token).

### 6.6 `PUT /api/v1/users/me`: same roles as 6.5

```json
{ "fullName": "Priya S. Sharma", "email": "priya.s@example.in" }
```

`200`: the updated profile (as 6.5). Errors: `400 VALIDATION_FAILED`, `404 USER_NOT_FOUND`, `409 EMAIL_ALREADY_REGISTERED`.

### 6.7 `PUT /api/v1/users/me/saved-places`: same roles as 6.5

```json
{ "places": [
  { "label": "Home", "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066, "stopId": "DMRC-HKS" },
  { "label": "Office", "name": "India Gate", "lat": 28.6129, "lon": 77.2295, "stopId": null } ] }
```

`200`: the profile with the new list (the old list is replaced). At most 10 places. Errors: `400 VALIDATION_FAILED`, `404 USER_NOT_FOUND`.

### 6.8 `PUT /api/v1/users/me/consent`: same roles as 6.5

```json
{ "licenceCheck": true }
```

`200`: the profile, with `licenceCheckConsent` and `consentRecordedAt` set. Errors: `400 VALIDATION_FAILED`, `404 USER_NOT_FOUND`.

### 6.9 `DELETE /api/v1/users/me`: same roles as 6.5

No body. `204 No Content`. Errors: `404 USER_NOT_FOUND` (already deleted).

### 6.10 `POST /api/v1/licences`: TRAVELLER, DRIVER

```json
{ "licenceNumber": "KA-0120200054321", "dateOfBirth": "1998-01-15", "consent": true }
```

`202 Accepted` (the check continues in the background):

```json
{ "maskedLicenceNumber": "KA-01*********21", "status": "PENDING", "vehicleClasses": [], "expiresOn": null,
  "rejectionReason": null, "submittedAt": "2026-11-15T10:00:00Z", "checkedAt": null }
```

The licence number must be 2 letters + 13 digits (hyphen optional, any case). Errors: `400 VALIDATION_FAILED`,
`400 CONSENT_REQUIRED`, `404 USER_NOT_FOUND`, `409 VERIFICATION_IN_PROGRESS`.

### 6.11 `GET /api/v1/licences/me`: TRAVELLER, DRIVER

`200`:

```json
{ "maskedLicenceNumber": "KA-01*********21", "status": "VERIFIED", "vehicleClasses": ["MCWG"],
  "expiresOn": "2030-01-10", "rejectionReason": null,
  "submittedAt": "2026-11-15T10:00:00Z", "checkedAt": "2026-11-15T10:00:01Z" }
```

`status` is `PENDING`, `VERIFIED`, `REJECTED` (then `rejectionReason` is `NOT_FOUND` or `DETAILS_MISMATCH`) or `EXPIRED`.
Errors: `404 LICENCE_NOT_FOUND`.

### 6.12 `GET /api/v1/users/{userId}/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE`: SERVICE, ADMIN (INT-10 / INT-13)

`vehicleClass` is `LMV` or `MCWG`; `purpose` is `SELF_DRIVE` (APP-06) or `TAXI_DRIVER` (APP-08). `200`, exactly as in `CONTRACTS.md`:

```json
{ "userId": "u-101", "vehicleClass": "LMV", "purpose": "SELF_DRIVE", "eligible": true,
  "reason": "VERIFIED", "licenceExpiresOn": "2031-05-20" }
```

`reason`: `VERIFIED`, `NO_LICENCE`, `PENDING_VERIFICATION`, `REJECTED`, `EXPIRED`, `CLASS_NOT_COVERED`, and for
`TAXI_DRIVER` also `NOT_A_DRIVER` (APP-04's addition, section 2.6). `licenceExpiresOn` is `null` when there is no licence.
Errors: `404 USER_NOT_FOUND`, `400 BAD_REQUEST` (missing or unknown `vehicleClass` / `purpose`), `403 FORBIDDEN` (e.g. a TRAVELLER token).

### 6.13 `POST /api/v1/licences/expiry-check`: ADMIN

No body. `200`:

```json
{ "checkedOn": "2026-11-15", "expiredCount": 1 }
```

### 6.14 `GET /api/v1/users`: ADMIN

`200`:

```json
[ { "userId": "u-101", "fullName": "Priya Sharma", "maskedMobile": "******3210", "roles": ["TRAVELLER"], "status": "ACTIVE" },
  { "userId": "u-900", "fullName": "Meera Krishnan", "maskedMobile": "******0001", "roles": ["ADMIN"], "status": "ACTIVE" } ]
```

(Deleted accounts appear with `fullName` and `maskedMobile` `null` and status `DELETED`.)

### 6.15 `POST /api/v1/users/{userId}/roles`: ADMIN

```json
{ "role": "DRIVER" }
```

`200`: the user summary (as one row of 6.14) with the new role. Errors: `400 ROLE_NOT_ASSIGNABLE` (SERVICE), `404 USER_NOT_FOUND`.

### 6.16 `DELETE /api/v1/users/{userId}/roles/{role}`: ADMIN

`200`: the user summary without the role. Errors: `409 CANNOT_REMOVE_OWN_ADMIN`, `404 USER_NOT_FOUND`.

### 6.17 Shared endpoints (from the template)

`GET /api/v1/info` (any token), `POST /dev/token`, `GET /dev/events`, `GET /actuator/health`: see `../SHARED-SETUP.md`.

---

## 7. Demo script

Start: `cd app-04-identity` then `mvn spring-boot:run`. Open `http://localhost:8084/swagger-ui.html`.

Sample data loaded at start (`DemoDataLoader`):

| Id | Name | Mobile | Password | Roles | Licence |
|---|---|---|---|---|---|
| u-101 | Priya Sharma | 9876543210 | Priya@123 | TRAVELLER | VERIFIED, LMV + MCWG, until 2031-05-20 |
| u-102 | Rahul Verma | 9123456780 | Rahul@123 | DRIVER | VERIFIED, LMV, until 2029-08-31 |
| u-103 | Ananya Iyer | 9988776655 | Ananya@123 | TRAVELLER | EXPIRED (2024-03-31) |
| u-104 | Arjun Nair | 9445566778 | Arjun@123 | TRAVELLER | none: use him to submit one |
| u-105 | Vikram Singh | 9810012345 | Vikram@123 | TRAVELLER, DRIVER | still VERIFIED, but ran out 2026-10-31 (the expiry job will catch it) |
| u-900 | Meera Krishnan | 9000000001 | Admin@123 | ADMIN | none |

Sample licences known to the stub DigiLocker:

| Licence number | Date of birth | Result |
|---|---|---|
| `DL-0420190012345` | 1994-05-20 | VERIFIED, LMV + MCWG, until 2031-05-20 |
| `KA-0520180067890` | 1990-11-02 | VERIFIED, LMV, until 2029-08-31 |
| `KA-0120200054321` | 1998-01-15 | VERIFIED, MCWG only, until 2030-01-10 |
| `MH-1220100098765` | 1985-03-31 | EXPIRED (2024-03-31) |
| any other number, e.g. `TN-0919990011111` | any | REJECTED, `NOT_FOUND` |
| a known number with the wrong date of birth | | REJECTED, `DETAILS_MISMATCH` |

**How to use a token in Swagger:** copy the `token` value from a login answer, press **Authorize** (top right), paste it
(without the word `Bearer`), press Authorize, Close. To switch user, press Authorize → Logout and paste another token.

1. **Register a new traveller.** `POST /api/v1/auth/register`:
   ```json
   { "fullName": "Kavya Reddy", "mobile": "9701234567", "email": "kavya.reddy@example.in", "password": "Kavya@123", "accountType": "TRAVELLER" }
   ```
   Point out: `201`, no token needed, no password in the answer. Run it again: `409 MOBILE_ALREADY_REGISTERED`.
2. **Log in with a password.** `POST /api/v1/auth/login`:
   ```json
   { "method": "PASSWORD", "mobile": "9445566778", "password": "Arjun@123" }
   ```
   Copy the token and **Authorize** as Arjun. Point out: the token is all the app needs; no session (D1).
   Paste the token into jwt.io to show the claims `sub`, `roles`, `iss`, `exp`.
3. **Wrong password.** Same call with `"password": "wrong"`: `401 INVALID_CREDENTIALS`, in the shared error format.
4. **OTP login, step 1.** `POST /api/v1/auth/otp`: `{ "mobile": "9876543210" }` → `202`, `sentTo: "******3210"`.
5. **Read the SMS.** `GET /dev/otp/9876543210` → copy `otp`. (In production this is a real SMS through a DLT gateway.)
6. **OTP login, step 2.** `POST /api/v1/auth/login`: `{ "method": "OTP", "mobile": "9876543210", "otp": "<the code>" }`
   → token for Priya. Send the same body again: `401 OTP_NOT_REQUESTED` (a code works only once). Try a wrong code after
   a new request: `401 OTP_INVALID`.
7. **My profile.** Authorize as **Arjun** (token from step 2). `GET /api/v1/users/me`. Then
   `PUT /api/v1/users/me/saved-places`:
   ```json
   { "places": [ { "label": "Home", "name": "Hauz Khas Metro", "lat": 28.5433, "lon": 77.2066, "stopId": "DMRC-HKS" } ] }
   ```
8. **Submit a licence (as Arjun).** `POST /api/v1/licences`:
   ```json
   { "licenceNumber": "KA-0120200054321", "dateOfBirth": "1998-01-15", "consent": true }
   ```
   Point out: `202`, status `PENDING`, number masked; the check runs on thread `licence-check-1` (see the console log).
   Then `GET /api/v1/licences/me` → `VERIFIED`, classes `["MCWG"]`. Try `"consent": false`: `400 CONSENT_REQUIRED`.
9. **Encrypted in the database (D2).** Open `http://localhost:8084/h2-console` (JDBC URL `jdbc:h2:mem:mmts-identity`, user `sa`,
   no password) and run `select user_id, licence_number, status from driving_licence`. The `licence_number` column is
   Base64 cipher text, not the number. Also look at the console log: only `KA-01*********21` and `******3210` appear.
10. **Get an admin (or service) token.** Log in as Meera: `{ "method": "PASSWORD", "mobile": "9000000001", "password": "Admin@123" }`
    and Authorize. (Or `POST /dev/token` with `{ "userId": "mmts-booking", "roles": ["SERVICE"] }` to act as APP-06.)
11. **Eligibility: verified.** `GET /api/v1/users/u-101/drive-eligibility?vehicleClass=LMV&purpose=SELF_DRIVE` →
    `eligible: true`, `reason: VERIFIED`, `licenceExpiresOn: 2031-05-20`. Point out: these six fields are the INT-10 contract.
12. **Eligibility: refused (D3).**
    - `u-103`, `LMV`, `SELF_DRIVE` → `EXPIRED`
    - `u-104`, `LMV`, `SELF_DRIVE` → `CLASS_NOT_COVERED` (Arjun's licence covers scooters only)
    - `u-101`, `LMV`, `TAXI_DRIVER` → `NOT_A_DRIVER` (Priya is a traveller); `u-102`, `LMV`, `TAXI_DRIVER` → `VERIFIED`
    - `u-999` → `404 USER_NOT_FOUND`
    - With Arjun's token instead: `403 FORBIDDEN` (travellers may not ask about others).
13. **Expiry job.** As Meera: `POST /api/v1/licences/expiry-check` → `expiredCount: 1` (Vikram, u-105). Then
    `GET /dev/events`: `LICENCE_EXPIRED` for `u-105` with `expiredOn: 2026-10-31`, and earlier the `LICENCE_VERIFIED` for
    `u-104`. Point out the `AUDIT` events: ids, roles and outcomes, but no names, mobiles or licence numbers.
14. **Roles (RBAC).** As Meera: `GET /api/v1/users` (masked mobiles). `POST /api/v1/users/u-104/roles` with `{ "role": "DRIVER" }`.
    Try `DELETE /api/v1/users/u-900/roles/ADMIN` → `409 CANNOT_REMOVE_OWN_ADMIN`.
15. **Delete my data (D2).** Authorize as Kavya (log in with the account from step 1), `DELETE /api/v1/users/me` → `204`.
    Log in again with her password → `401 INVALID_CREDENTIALS`. As Meera, `GET /api/v1/users`: Kavya's row now has no name or
    mobile and status `DELETED`.

---

## 8. What is stubbed

| Real system / application | Stub in this app | Real integration (architecture document §4 / §5) |
|---|---|---|
| DigiLocker (licence verification) | `StubDigiLockerAdapter`: answers from the fixed sample licences in section 7 | DigiLocker partner API: the user gives consent (OAuth 2.0 authorisation code flow), APP-04 receives an access token, then calls the Pull Document API for doctype `DRVLC` and reads classes and validity from the issued document (XML). Partner onboarding needs a registered organisation. |
| SMS gateway (OTP delivery) | `StubSmsGateway`: keeps the last message per mobile in memory; `GET /dev/otp/{mobile}` shows it | A DLT-registered Indian SMS gateway (TRAI rules: registered sender id and pre-approved OTP template), called over HTTPS. |
| Kafka (`licence.events`, `audit.events`) | Shared `InProcessEventPublisher`; see published events at `GET /dev/events` | Kafka topics read by APP-06, APP-08 (licence) and APP-09 (audit). Only a new `EventPublisher` class is needed. |
| PostgreSQL (DB-1 `mmts_identity`) | H2 in PostgreSQL mode (demo profile) | Real PostgreSQL with `-Dspring-boot.run.profiles=postgres`, same code. |
| Redis (OTP store with time-to-live) | `otp_challenge` table | A Redis key per mobile with a 5-minute expiry; same `OtpLoginStrategy` logic. |
| Secrets vault (encryption key, JWT key) | Demo values in `application.yml` | Environment variables `MMTS_PII_KEY`, `MMTS_JWT_SECRET` filled from a vault / key management service. |
| APP-06 and APP-08 (callers of INT-10 / INT-13) | A `SERVICE` token from `POST /dev/token` | They call the same endpoint with their own service token. |

---

## 9. Tests

Run with `mvn test` inside `app-04-identity`. Result: **73 tests, 0 failures** (9 shared + 64 for this module).

The 9 shared tests (`SharedSetupTest`, `JwtServiceTest`) are explained in `../SHARED-SETUP.md`, section 8.

How the tests are built:

- **Unit tests** (`DrivingLicenceRulesTest`, `PersonalDataTest`, `LoginStrategyTest`) test one class alone, without starting
  Spring or a database. `LoginStrategyTest` uses **Mockito** *mocks* (fake repositories and a fake SMS gateway that record
  what was asked of them) and a fixed clock, so "5 minutes later" can be tested exactly.
- **Application and endpoint tests** (`LicenceServiceTest`, `AuthApiTest`, `UserApiTest`, `LicenceApiTest`) extend
  `IdentityTestBase`. They start the whole application with the demo data on H2. **MockMvc** sends real HTTP requests
  to the controllers without a network. The background licence check is switched to *synchronous*
  (`mmts.identity.verification.async=false`), so results are there immediately and every run gives the same result.
  Each test registers its own fresh users so tests do not disturb each other.

| Test class | Test | What it checks | Driver / rule it proves |
|---|---|---|---|
| `DrivingLicenceRulesTest` | `verifiedLicenceOfRightClassIsEligible` | A VERIFIED LMV + MCWG licence is eligible for both classes | D3, M1.5 |
| | `wrongVehicleClassIsRefused` | A scooter-only (MCWG) licence gives `CLASS_NOT_COVERED` for a car (LMV) | D3 |
| | `licencePastItsDateIsRefusedEvenBeforeTheNightlyJob` | Status still VERIFIED but the date has passed → `EXPIRED` (live check) | D3 |
| | `licenceIsStillValidOnItsExpiryDay` | On the expiry date itself the licence is still valid | D3 (boundary) |
| | `pendingRejectedAndExpiredLicencesAreRefused` | PENDING → `PENDING_VERIFICATION`, REJECTED → `REJECTED`, EXPIRED → `EXPIRED` | D3 |
| | `licenceFoundButAlreadyOutOfDateBecomesExpiredAndDateOfBirthIsCleared` | DigiLocker finds an old licence → status EXPIRED; the date of birth is removed | M1.4 state machine, D2 minimisation |
| `PersonalDataTest` | `encryptionRoundTripsAndHidesTheValue` | The encrypted text does not contain the number, and decrypting gives it back | D2 |
| | `sameValueIsStoredDifferentlyEachTime` | Two encryptions of the same number differ (random IV) | D2 |
| | `wrongKeyCannotReadTheValue` | A different key cannot decrypt (and tampering is detected) | D2 |
| | `masksMobileAndLicenceNumbers` | `9876543210` → `******3210`, `DL-0420190012345` → `DL-04*********45` | D2 (never logged) |
| | `anonymiseRemovesEveryPersonalField` | After `anonymise` name, mobile, email, password, places, roles, consent are gone; id kept; status DELETED | D2, M1.7 |
| `LoginStrategyTest` | `correctPasswordReturnsTheUser` | The right password logs in | M1.2 password check |
| | `wrongPasswordIsRefused` | A wrong password gives `INVALID_CREDENTIALS` | M1.2 |
| | `unknownMobileGivesTheSameErrorAsWrongPassword` | An unregistered mobile gives the same error (no account probing) | M1.2, security |
| | `passwordIsStoredOnlyAsBcryptHash` | The stored value is a BCrypt hash (`$2…`), not the password | M1.1, D2 |
| | `otpIsSentBySmsAndOnlyItsHashIsStored` | Sending an OTP goes through `SmsGateway`; only the hash is saved; expiry is now + 5 minutes | M1.2, Adapter |
| | `noSmsForUnregisteredMobile` | No SMS is sent for a number without an account | M1.2, security |
| | `correctOtpLogsInAndCanBeUsedOnlyOnce` | The right code logs in and the code is deleted | M1.2 OTP |
| | `wrongOtpIsRefusedAndCounted` | A wrong code gives `OTP_INVALID` and the failed try is counted | M1.2 OTP |
| | `expiredOtpIsRefused` | 301 seconds later the code gives `OTP_EXPIRED` | M1.2 OTP expiry |
| | `tooManyWrongOtpsBlockTheCode` | After 5 wrong tries even the right code gives `OTP_TOO_MANY_ATTEMPTS` | M1.2, brute-force protection |
| | `otpLoginWithoutRequestingACodeIsRefused` | Logging in with a code that was never sent gives `OTP_NOT_REQUESTED` | M1.2 |
| `LicenceServiceTest` | `validLicenceBecomesVerifiedAndPublishesLicenceVerified` | Sample licence → VERIFIED with classes and expiry; `LICENCE_VERIFIED` event has `userId`, `vehicleClasses`, `expiresOn` | M1.4, Observer, CONTRACTS events |
| | `unknownLicenceIsRejectedAndPublishesLicenceRejected` | Unknown number → REJECTED; `LICENCE_REJECTED` with `userId`, `reason` | M1.4, rejected licence |
| | `wrongDateOfBirthIsRejectedAsDetailsMismatch` | Known number, wrong date of birth → REJECTED, `DETAILS_MISMATCH` | M1.4 |
| | `outOfDateLicenceBecomesExpiredAndPublishesLicenceExpired` | Old licence → EXPIRED; `LICENCE_EXPIRED` with `userId`, `expiredOn` | M1.4, D3 |
| | `licenceNumberIsStoredEncryptedInTheDatabase` | Reading the column with plain SQL shows no licence number; the application still reads it correctly | D2 (encrypted column) |
| | `expiryJobMarksOverdueLicencesAndPublishesLicenceExpired` | A VERIFIED licence whose date passed becomes EXPIRED after the job, with an event | M1.6, D3 |
| | `deleteMyDataRemovesPersonalFieldsAndTheLicence` | After delete: no name, mobile, email, password in the row; the licence row is gone | D2, M1.7 |
| | `auditEventsCarryNoPersonalData` | AUDIT events have only the six contract fields, and never contain the mobile or licence number | D2, INT-19 |
| `AuthApiTest` | `registerCreatesTravellerWithoutAToken` | `201`, profile fields, email lower-cased, no password hash in JSON, no token needed | M1.1 |
| | `registerAsDriverGivesDriverRole` | `accountType: DRIVER` gives role DRIVER | M1.1, RBAC |
| | `sameMobileCannotRegisterTwice` | Second registration → `409 MOBILE_ALREADY_REGISTERED` | M1.1 |
| | `badRegistrationListsEveryBadField` | 4 bad fields → `400 VALIDATION_FAILED` with 4 `details` | Shared error format |
| | `passwordLoginReturnsATokenWithTheUsersRoles` | Login as Priya: the token is a valid shared token with her id and role | M1.2, D1 |
| | `loginCreatesNoServerSession` | No HTTP session and no cookie after login | D1 (stateless) |
| | `wrongPasswordGives401InvalidCredentials` | `401 INVALID_CREDENTIALS` in the shared format | M1.2 |
| | `otpLoginWorksEndToEnd` | Request OTP → read it at `/dev/otp` → log in; reusing the code fails | M1.2 OTP, demo helper |
| | `wrongOtpGives401OtpInvalid` | Wrong code → `401 OTP_INVALID` | M1.2 |
| | `otpForUnknownMobileLooksTheSameButSendsNothing` | `202` for an unknown number, but nothing sent (`404 NO_SMS_SENT`) | Security |
| `UserApiTest` | `getMyProfile` | Priya's profile with saved places and consent | M1.3 |
| | `profileOfUnknownUserGives404` | Token for a non-existent user → `404 USER_NOT_FOUND` | Error codes |
| | `serviceTokenCannotUseTheProfileEndpoints` | A SERVICE token on `/users/me` → `403 FORBIDDEN` | RBAC |
| | `updateProfileSavedPlacesAndConsent` | Change name/email, replace saved places, record consent | M1.3 |
| | `deleteMyAccountRemovesDataAndLoginStopsWorking` | `204`; login then fails; profile is `404`; the mobile can register again | D2, M1.7 |
| | `adminListsUsersWithMaskedMobiles` | Admin list shows `******3210`, never the full number | M1.8, D2 |
| | `travellerCannotListUsers` | Traveller → `403 FORBIDDEN` | RBAC |
| | `adminAssignsAndRemovesARole` | Add DRIVER, then remove it | M1.8, RBAC |
| | `roleRulesAreEnforced` | SERVICE not assignable (`400`), cannot remove own ADMIN (`409`), unknown user (`404`), traveller cannot assign (`403`) | M1.8, error codes |
| `LicenceApiTest` | `submitAnswersPendingAtOnceThenLicenceIsVerified` | `202 PENDING` with masked number, then `GET /licences/me` shows VERIFIED, MCWG, 2030-01-10; full number never in JSON | M1.4, D1, D2 |
| | `submitWithoutConsentIsRefused` | `consent: false` → `400 CONSENT_REQUIRED` | M1.4 consent |
| | `badLicenceNumberFailsValidation` | A malformed number → `400 VALIDATION_FAILED` | Validation |
| | `noLicenceYetGives404` | `GET /licences/me` without a licence → `404 LICENCE_NOT_FOUND` | Error codes |
| | `adminCannotSubmitALicence` | ADMIN token → `403` | RBAC |
| | `eligibilityContractFieldsForAVerifiedUser` | Exactly the six INT-10 fields with the contract values | INT-10 / INT-13 contract test |
| | `expiredLicenceIsNotEligible` | Ananya → `eligible: false`, `EXPIRED`, `2024-03-31` | D3 |
| | `userWithoutLicenceGetsNoLicenceAndNullDate` | `NO_LICENCE` and `licenceExpiresOn: null` | D3, contract |
| | `wrongClassAndRejectedLicenceAreNotEligible` | Scooter-only licence asking for LMV → `CLASS_NOT_COVERED`; unknown licence → `REJECTED` | D3 |
| | `taxiDriverPurposeNeedsTheDriverRole` | Rahul (DRIVER) → VERIFIED; Priya (TRAVELLER) → `NOT_A_DRIVER` | D3, TAXI_DRIVER rule |
| | `unknownUserGives404UserNotFound` | `404 USER_NOT_FOUND`, as in the contract | INT-10 contract |
| | `eligibilityIsForServicesAndAdminsOnly` | TRAVELLER → `403`, ADMIN → `200`, no token → `401` | RBAC |
| | `unknownVehicleClassGives400` | `vehicleClass=TRUCK` → `400 BAD_REQUEST` | Validation |
| | `adminRunsTheExpiryCheck` | The admin endpoint answers `checkedOn` and `expiredCount`; Vikram is then EXPIRED | M1.6, D3 |
| | `travellerCannotRunTheExpiryCheck` | Traveller → `403` | RBAC |

---

## 10. Questions you may be asked

1. **Why JWT and not sessions?**
   A JWT carries the user id and roles and is signed, so any MMTS app can check it alone with the shared key. No session store,
   no call back to APP-04: add more servers and login capacity grows (D1). The cost: a token cannot be taken back before it
   expires, so we keep the life short (60 minutes); production would add refresh tokens and a small block-list for stolen tokens.

2. **If an admin removes a role, when does it take effect?**
   At the user's next login, because tokens already given out keep their roles until they expire (at most 60 minutes).
   This is the usual trade-off of stateless tokens.

3. **How is the licence number protected?**
   It is encrypted with AES-256-GCM by a JPA `AttributeConverter` on every save and decrypted on every load. Each value has a
   random IV, so the same number looks different each time, and GCM detects tampering. The key comes from configuration
   (`MMTS_PII_KEY`), not code. The number is masked in logs and answers, and never put in events.

4. **Why not encrypt the mobile number too?**
   We look accounts up by mobile at login, and a randomly encrypted value cannot be searched. In production we would store an
   HMAC "blind index" of the mobile for lookups and encrypt the mobile itself.

5. **Why does licence verification run in the background?**
   DigiLocker is an outside service that can be slow or down. If we waited for it, one slow partner would slow requests and
   tie up server threads. Submitting answers `PENDING` at once; the result arrives as a status change and an event (D1). In tests
   we use a synchronous executor so the tests are deterministic.

6. **What happens if DigiLocker is down?**
   The exception is caught, the licence stays `PENDING`, and eligibility answers `PENDING_VERIFICATION` (not eligible). Safety
   is kept: no one drives without a confirmed licence. Production would retry with back-off.

7. **Where is the Adapter pattern, and what does it buy you?**
   `IdentityDocumentProvider` and `SmsGateway` are interfaces; `StubDigiLockerAdapter` and `StubSmsGateway` implement them.
   The services never know whether they talk to the stub or the real partner, so swapping in DigiLocker or a DLT SMS gateway
   changes one class and no business code (principle P3).

8. **Where is the Strategy pattern?**
   `LoginStrategy` with `PasswordLoginStrategy` and `OtpLoginStrategy`. `AuthService` picks the strategy by the request's
   `method`, then issues the token the same way. A new login method is one new class.

9. **How does "delete my data" work, and why not delete the row?**
   We remove every personal field, delete the licence and any OTP, and mark the account `DELETED`. The id stays because
   bookings and payments in other apps refer to it, and financial records must be kept by law. The id no longer leads to a person.

10. **What stops someone driving with an expired licence?**
    Three things: the eligibility rule checks the expiry date live every time; the nightly job marks old licences `EXPIRED`;
    and `LICENCE_EXPIRED` is published so APP-06 and APP-08 can cancel bookings or take a driver offline (D3).

11. **Why the extra `NOT_A_DRIVER` reason?**
    For `TAXI_DRIVER`, a valid licence is not enough: the person must also have been onboarded as a driver (DRIVER role).
    The contract lists six reasons; we added a seventh only for this purpose. Callers must treat any reason other than
    `VERIFIED` as "not eligible", so they keep working.

12. **How do you stop OTP guessing and account probing?**
    6-digit random codes, stored only as BCrypt hashes, valid 5 minutes, used once, blocked after 5 wrong tries. "Unknown mobile"
    and "wrong password" give the same error, and an OTP request for an unknown number gets the same answer as a real one.
    Production would also rate-limit requests per number and per IP address.
