<#
  MMTS proof of concept: starts the deployment components on this laptop.
    1. event broker (Kafka, KRaft)          -> address written to run\broker.address
    2. six services, profile "demo,poc"     -> ports 8084-8089, events through the broker, real calls between services
    3. API gateway                          -> http://localhost:8080 (the only address the front ends use)
  Usage:   powershell -ExecutionPolicy Bypass -File poc\start-poc.ps1 [-Build]
  Stop:    powershell -ExecutionPolicy Bypass -File poc\stop-poc.ps1
  Needs Java 17+ and Maven; about 2 GB of free memory.
#>
param([switch]$Build)
$ErrorActionPreference = "Stop"
$poc = $PSScriptRoot
$impl = Split-Path $poc -Parent
$run = Join-Path $poc "run"
New-Item -ItemType Directory -Force $run | Out-Null
Remove-Item (Join-Path $run "*") -Force -ErrorAction SilentlyContinue

$services = @(
  @{ name = "identity";  dir = "app-01-traveller\identity-service";             jar = "mmts-identity-1.0.0.jar";            port = 8084 },
  @{ name = "journey";   dir = "app-01-traveller\journey-service";              jar = "mmts-journey-1.0.0.jar";             port = 8085 },
  @{ name = "booking";   dir = "app-01-traveller\booking-service";              jar = "mmts-booking-1.0.0.jar";             port = 8086 },
  @{ name = "payment";   dir = "app-01-traveller\payment-service";              jar = "mmts-payment-1.0.0.jar";             port = 8087 },
  @{ name = "tsp-hub";   dir = "app-02-tsp-partner\tsp-integration-hub";        jar = "mmts-tsp-integration-hub-1.0.0.jar"; port = 8088 },
  @{ name = "contracts"; dir = "app-02-tsp-partner\contract-management-service"; jar = "mmts-contract-management-1.0.0.jar"; port = 8089 }
)
$broker  = @{ dir = "poc\event-broker"; jar = "mmts-event-broker-1.0.0.jar" }
$gateway = @{ dir = "poc\api-gateway";  jar = "mmts-api-gateway-1.0.0.jar" }

if ($Build) {
  foreach ($p in @($broker, $gateway) + $services) {
    Write-Host "Building $($p.dir) ..."
    Push-Location (Join-Path $impl $p.dir); mvn -q -B -DskipTests package; Pop-Location
  }
}

$jvm = @("-Xmx160m", "-Xss256k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=110m", "-XX:CICompilerCount=2")
function Start-Jar($p, $name, [string[]]$extra) {
  $jarPath = Join-Path (Join-Path $impl $p.dir) ("target\" + $p.jar)
  if (-not (Test-Path $jarPath)) { throw "Missing $jarPath - run with -Build first" }
  $args = $jvm + @("-jar", "`"$jarPath`"") + $extra
  $proc = Start-Process java -ArgumentList $args -RedirectStandardOutput (Join-Path $run "$name.log") `
          -RedirectStandardError (Join-Path $run "$name.err") -WindowStyle Hidden -PassThru
  Add-Content (Join-Path $run "pids.txt") "$($proc.Id) $name"
  return $proc
}
function Wait-Health($name, $port) {
  for ($i = 0; $i -lt 90; $i++) {
    try {
      $h = Invoke-RestMethod "http://localhost:$port/actuator/health" -TimeoutSec 2
      if ($h.status -eq "UP") { Write-Host ("  {0,-10} UP on port {1}" -f $name, $port); return }
    } catch { }
    Start-Sleep -Seconds 2
  }
  throw "$name did not start - see poc\run\$name.log"
}

Write-Host "1. Event broker (Kafka)"
$addressFile = Join-Path $run "broker.address"
Start-Jar $broker "broker" @("--mmts.broker.address-file=`"$addressFile`"") | Out-Null
for ($i = 0; $i -lt 60 -and -not (Test-Path $addressFile); $i++) { Start-Sleep -Seconds 1 }
if (-not (Test-Path $addressFile)) { throw "The broker did not start - see poc\run\broker.log" }
$env:KAFKA_BOOTSTRAP = (Get-Content $addressFile -Raw).Trim()
Write-Host "  broker     UP at $($env:KAFKA_BOOTSTRAP)"

Write-Host "2. Services (profile demo,poc)"
foreach ($s in $services) {
  # one at a time: start-up is the moment a JVM needs the most memory
  Start-Jar $s $s.name @("--spring.profiles.active=demo,poc") | Out-Null
  Wait-Health $s.name $s.port
}

Write-Host "3. API gateway"
Start-Jar $gateway "gateway" @() | Out-Null
Wait-Health "gateway" 8080

Write-Host ""
Write-Host "MMTS proof of concept is running. Entry point: http://localhost:8080"
Write-Host "Run the trip:  powershell -ExecutionPolicy Bypass -File poc\run-trace.ps1"
