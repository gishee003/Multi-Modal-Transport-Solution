<#
  MMTS proof of concept: the paper trace (docs/PAPER-TRACE.md) run for real.
  Every call goes through the API gateway (http://localhost:8080); events travel through the Kafka broker.
  Start the components first:  powershell -ExecutionPolicy Bypass -File poc\start-poc.ps1
  Then:                        powershell -ExecutionPolicy Bypass -File poc\run-trace.ps1
#>
$ErrorActionPreference = "Stop"
$G = "http://localhost:8080"
$failures = 0

function Api($method, $path, $body = $null, $token = $null, $headers = @{}) {
  $h = @{}
  foreach ($k in $headers.Keys) { $h[$k] = $headers[$k] }
  if ($token) { $h["Authorization"] = "Bearer $token" }
  $params = @{ Uri = "$G$path"; Method = $method; Headers = $h; UseBasicParsing = $true; TimeoutSec = 30 }
  if ($null -ne $body) { $params["Body"] = ($body | ConvertTo-Json -Depth 8); $params["ContentType"] = "application/json" }
  try {
    $r = Invoke-WebRequest @params
    $data = if ($r.Content) { $r.Content | ConvertFrom-Json } else { $null }
    return @{ status = [int]$r.StatusCode; data = $data }
  } catch [System.Net.WebException] {
    $resp = $_.Exception.Response
    if ($null -eq $resp) { throw }
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
    $text = $reader.ReadToEnd()
    $data = if ($text) { try { $text | ConvertFrom-Json } catch { $text } } else { $null }
    return @{ status = [int]$resp.StatusCode; data = $data }
  }
}
function Step($title) { Write-Host ""; Write-Host "== $title" -ForegroundColor Cyan }
function Show($label, $value) { Write-Host ("   {0,-34} {1}" -f $label, $value) }
function Expect($ok, $what) {
  if ($ok) { Write-Host "   OK   $what" -ForegroundColor Green }
  else { Write-Host "   FAIL $what" -ForegroundColor Red; $script:failures++ }
}
function Login($mobile, $password) {
  $r = Api POST "/api/v1/auth/login" @{ method = "PASSWORD"; mobile = $mobile; password = $password }
  if ($r.status -ne 200) { throw "login failed for $mobile ($($r.status))" }
  return $r.data.token
}
function TripBody() {
  $t1 = (Get-Date).ToUniversalTime().AddMinutes(10).ToString("yyyy-MM-ddTHH:mm:ssZ")
  $t2 = (Get-Date).ToUniversalTime().AddMinutes(40).ToString("yyyy-MM-ddTHH:mm:ssZ")
  return @{ paymentMethod = "WALLET"; legs = @(
    @{ mode = "METRO"; providerId = "DMRC"; startTime = $t1;
       from = @{ name = "Hauz Khas Metro"; lat = 28.5433; lon = 77.2066; stopId = "DMRC-HKS" };
       to   = @{ name = "Rajiv Chowk Metro"; lat = 28.6328; lon = 77.2197; stopId = "DMRC-RCK" } },
    @{ mode = "TAXI"; providerId = "CITYCAB"; startTime = $t2;
       from = @{ name = "Rajiv Chowk Metro"; lat = 28.6328; lon = 77.2197 };
       to   = @{ name = "India Gate"; lat = 28.6129; lon = 77.2295 } }) }
}

Step "0. The API gateway is the only entry point and checks the login token"
$r = Api GET "/api/v1/bookings"
Show "GET /api/v1/bookings, no token" "$($r.status) $($r.data.code)"
Expect ($r.status -eq 401) "the gateway refuses a call without a token"
$traveller = Login "9876543210" "Priya@123"     # Priya, u-101 (Identity service, APP-01)
$admin = Login "9000000001" "Admin@123"         # Meera, platform admin / partner team
Show "logged in through the gateway" "traveller u-101, admin u-900 (real tokens from the Identity service)"

Step "1. Both TSPs have an ACTIVE contract (M7, APP-02)"
foreach ($tsp in @("DMRC", "CITYCAB")) {
  $r = Api GET "/api/v1/tsps/$tsp/active-terms" $null $admin
  Show "$tsp active-terms" "active=$($r.data.active) commission=$($r.data.terms.commissionPercent)%"
  Expect ($r.data.active -eq $true) "$tsp may be offered"
}

Step "2. Plan: Journey search (M2, APP-01)"
$r = Api GET "/api/v1/journeys/search?from=PL-IITD&to=PL-IG&rankBy=FASTEST" $null $traveller
Show "options found" "$($r.data.optionCount) (best: $($r.data.options[0].totalMinutes) min, modes $($r.data.options[0].modes -join ' + '))"
Expect ($r.status -eq 200 -and $r.data.optionCount -gt 0) "route search answered through the gateway"

Step "3. Book rail (DMRC via ONDC) + cab (CITYCAB): Booking saga across APP-01 and APP-02"
$wallet0 = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
$key = [guid]::NewGuid().ToString()
$trip1 = TripBody
$r = Api POST "/api/v1/bookings" $trip1 $traveller @{ "Idempotency-Key" = $key }
$booking = $r.data
Show "booking" "$($r.status) $($booking.bookingId) status=$($booking.status) total=Rs $($booking.totalPaise / 100)"
foreach ($leg in $booking.legs) {
  $extra = if ($leg.driver) { "driver $($leg.driver.name) ($($leg.driver.vehicleNumber)), ETA $($leg.driver.etaMinutes) min" } else { "" }
  Show ("  leg " + $leg.mode + " " + $leg.providerId) "$($leg.status) access=$($leg.access.type) $extra"
}
Show "saga steps" ($booking.sagaLog -join " | ")
Expect ($booking.status -eq "CONFIRMED") "all legs held, paid once, confirmed (real calls to TSP Hub and Payment)"
$wallet1 = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
Show "wallet" "Rs $($wallet0 / 100) -> Rs $($wallet1 / 100)"
Expect (($wallet0 - $wallet1) -eq $booking.totalPaise) "charged exactly once by the Payment service"

Step "4. Same request again (a retry with the same Idempotency-Key, e.g. after a network drop)"
$r = Api POST "/api/v1/bookings" $trip1 $traveller @{ "Idempotency-Key" = $key }
$walletRetry = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
Show "retry" "$($r.status) $($r.data.bookingId), wallet still Rs $($walletRetry / 100)"
Expect ($r.data.bookingId -eq $booking.bookingId -and $walletRetry -eq $wallet1) "the same booking comes back; nothing is booked or charged twice (D6, D8)"
$r = Api POST "/api/v1/bookings" @{ paymentMethod = "WALLET"; legs = @($trip1.legs[0]) } $traveller @{ "Idempotency-Key" = $key }
Show "same key, different trip (metro only)" "$($r.status) $($r.data.code)"
Expect ($r.status -eq 422) "a key cannot be reused for a different request"

Step "5. BOOKING_CONFIRMED travelled through Kafka: Journey created the trip (event-driven, INT-16)"
$trip = $null
for ($i = 0; $i -lt 10 -and -not $trip; $i++) {
  Start-Sleep -Seconds 1
  $trips = (Api GET "/api/v1/trips" $null $traveller).data
  $trip = @($trips) | Where-Object { $_.bookingId -eq $booking.bookingId } | Select-Object -First 1
}
Show "trip in Journey service" $(if ($trip) { "$($trip.tripId) status=$($trip.status)" } else { "not found" })
Expect ($null -ne $trip) "Journey learned about the booking only from the event broker"

Step "6. Partner team suspends CITYCAB's contract (SLA breach): contract.events -> TSP Hub, Payment"
$r = Api POST "/api/v1/contracts/c-citycab-1/suspend" @{ reason = "SLA_BREACH" } $admin
Show "contract c-citycab-1" "$($r.data.status) ($($r.data.statusReason))"
Start-Sleep -Seconds 3
$providers = (Api GET "/api/v1/providers" $null $admin).data
$cab = @($providers) | Where-Object { $_.providerId -eq "CITYCAB" }
Show "TSP Hub sees CITYCAB as" $cab.status
Expect ($cab.status -eq "SUSPENDED") "the hub switched the connector off after the Kafka event (D15)"

Step "7. The same trip again: the cab leg is refused, the rail hold is undone, nothing is charged"
$walletBefore = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
$r = Api POST "/api/v1/bookings" (TripBody) $traveller @{ "Idempotency-Key" = [guid]::NewGuid().ToString() }
Show "booking attempt" "$($r.status) $($r.data.code)"
Show "reason" $r.data.message
$walletAfter = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
Expect ($r.status -ge 400 -and $walletAfter -eq $walletBefore) "all-or-nothing: no booking, no charge (D6, D7)"

Step "8. Contract re-activated: CITYCAB is offered again"
$r = Api POST "/api/v1/contracts/c-citycab-1/reactivate" $null $admin
Start-Sleep -Seconds 3
$cab = @((Api GET "/api/v1/providers" $null $admin).data) | Where-Object { $_.providerId -eq "CITYCAB" }
Show "contract / hub" "$($r.data.status) / $($cab.status)"
Expect ($cab.status -eq "ENABLED") "connector usable again"

Step "9. A new driver joins CITYCAB: TSP Hub (APP-02) asks Identity (APP-01) over REST (INT-13)"
$null = Api POST "/api/v1/drivers" @{ driverId = "u-102"; name = "Rahul Verma"; vehicleNumber = "DL1CAB4242"; providerId = "CITYCAB" } $admin
$r = Api POST "/api/v1/drivers/u-102/activate" $null $admin
Show "activate u-102 (verified LMV licence)" "$($r.status) $($r.data.status)"
Expect ($r.data.status -eq "ACTIVE") "activated only after the Identity service confirmed the licence (D3)"
$null = Api POST "/api/v1/drivers" @{ driverId = "u-104"; name = "Arjun Nair"; vehicleNumber = "DL1CAB4343"; providerId = "CITYCAB" } $admin
$r = Api POST "/api/v1/drivers/u-104/activate" $null $admin
Show "activate u-104 (no licence)" "$($r.status) $($r.data.code)"
Expect ($r.status -eq 409) "refused: Identity says the person may not drive"

Step "10. After the trip: SLA measurement for CITYCAB (M7.5)"
$period = "poc-" + (Get-Date -Format "yyyyMMddHHmmss")
$r = Api POST "/api/v1/sla/measurements" @{ tspId = "CITYCAB"; period = $period; legsConfirmed = 200; legsCancelledByTsp = 4; availabilityPct = 99.4; avgResponseMinutes = 6 } $admin
Show "period $period" "cancellations $($r.data.cancellationRatePct)% breached=$($r.data.breached) contract=$($r.data.contractStatus)"
Expect ($r.data.breached -eq $false) "within the SLA (5 % cancellations, 97 % availability, 8 min response)"

Step "11. The traveller cancels the trip: holds released at both TSPs, money refunded (M3.5, M4.3)"
$wBefore = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
$r = Api POST "/api/v1/bookings/$($booking.bookingId)/cancel" @{ reason = "Plans changed" } $traveller
$wAfter = (Api GET "/api/v1/wallets/me" $null $traveller).data.balancePaise
Show "booking $($booking.bookingId)" "$($r.status) $($r.data.status), refund $($r.data.refundId)"
Show "wallet" "Rs $($wBefore / 100) -> Rs $($wAfter / 100)"
Expect ($r.data.status -eq "CANCELLED" -and $wAfter -gt $wBefore) "cab driver freed and the traveller refunded (the trace can be run again)"

Write-Host ""
if ($failures -eq 0) { Write-Host "Trace finished: every check passed." -ForegroundColor Green }
else { Write-Host "Trace finished with $failures failed check(s)." -ForegroundColor Red; exit 1 }
