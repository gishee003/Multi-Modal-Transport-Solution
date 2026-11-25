# Stops every process started by start-poc.ps1 (the ids are in poc\run\pids.txt).
$pids = Join-Path $PSScriptRoot "run\pids.txt"
if (-not (Test-Path $pids)) { Write-Host "Nothing to stop."; exit 0 }
foreach ($line in Get-Content $pids) {
  $id, $name = $line -split " ", 2
  try { Stop-Process -Id ([int]$id) -Force -ErrorAction Stop; Write-Host "stopped $name ($id)" }
  catch { Write-Host "$name ($id) was not running" }
}
Remove-Item $pids -Force
