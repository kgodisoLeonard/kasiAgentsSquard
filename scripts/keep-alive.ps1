# Keeps the backend or frontend running: starts it, and restarts it whenever it stops.
#   powershell -ExecutionPolicy Bypass -File scripts\keep-alive.ps1 -Service backend
#   powershell -ExecutionPolicy Bypass -File scripts\keep-alive.ps1 -Service frontend
# Logs go to logs\<service>-YYYY-MM-DD.log. Only one watchdog per service can run at a time.
# install-autostart.ps1 registers both to start when you sign in to Windows.
param([Parameter(Mandatory)][ValidateSet('backend', 'frontend')][string]$Service)

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null
$port = @{ backend = 8080; frontend = 5173 }[$Service]

$mutex = New-Object System.Threading.Mutex($false, "Local\SpazaKeepAlive-$Service")
if (-not $mutex.WaitOne(0)) { Write-Host "A $Service watchdog is already running."; exit 0 }

function Log([string]$msg) {
  $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') [watchdog] $msg"
  Add-Content -Path (Join-Path $logDir "$Service-$(Get-Date -Format 'yyyy-MM-dd').log") -Value $line -Encoding utf8
}
function PortInUse { [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) }

$delay = 5
Log "watchdog started (port $port)"
while ($true) {
  if (PortInUse) {
    # Something (a manual start, or a previous run still shutting down) already serves this port: don't double-start.
    Start-Sleep -Seconds 15
    continue
  }
  $log = Join-Path $logDir "$Service-$(Get-Date -Format 'yyyy-MM-dd').log"
  Log "starting $Service"
  $started = Get-Date
  # Out-File -Encoding utf8 keeps one encoding in the log (plain *>> would append UTF-16 in Windows PowerShell).
  if ($Service -eq 'backend') {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'backend\run-backend.ps1') *>&1 | ForEach-Object { "$_" } | Out-File -FilePath $log -Append -Encoding utf8
  } else {
    Push-Location (Join-Path $root 'frontend')
    & node 'node_modules\vite\bin\vite.js' --host 0.0.0.0 *>&1 | ForEach-Object { "$_" } | Out-File -FilePath $log -Append -Encoding utf8
    Pop-Location
  }
  $ranFor = (Get-Date) - $started
  # Back off when it keeps failing fast (e.g. Supabase unreachable); reset after a healthy run.
  $delay = if ($ranFor.TotalMinutes -ge 5) { 5 } else { [Math]::Min($delay * 2, 120) }
  Log "$Service stopped (exit $LASTEXITCODE after $([int]$ranFor.TotalSeconds)s); restarting in $delay s"
  Start-Sleep -Seconds $delay
}
