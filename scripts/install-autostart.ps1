# Starts the backend and frontend watchdogs (scripts\keep-alive.ps1) every time you sign in to Windows,
# via a hidden launcher in your Startup folder. No admin rights needed.
#   powershell -ExecutionPolicy Bypass -File scripts\install-autostart.ps1            install (and start now)
#   powershell -ExecutionPolicy Bypass -File scripts\install-autostart.ps1 -NoStart   install only
#   powershell -ExecutionPolicy Bypass -File scripts\install-autostart.ps1 -Remove    uninstall
# (Task Scheduler is not used: on some machines PowerShell launched by a scheduled task stalls at startup.)
param([switch]$Remove, [switch]$NoStart)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$watchdog = Join-Path $root 'scripts\keep-alive.ps1'
$launcher = Join-Path ([Environment]::GetFolderPath('Startup')) 'Spaza app.vbs'

if ($Remove) {
  Remove-Item $launcher -ErrorAction SilentlyContinue
  Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'keep-alive\.ps1' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -Confirm:$false }
  Write-Host "Removed autostart and stopped the watchdogs (the backend/frontend themselves keep running until closed)."
  exit 0
}

$q = '""'  # a literal double quote inside a VBScript string
$lines = @("Set sh = CreateObject(""WScript.Shell"")")
foreach ($svc in 'backend', 'frontend') {
  $lines += "sh.Run ""powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File $q$watchdog$q -Service $svc"", 0, False"
}
Set-Content -Path $launcher -Value $lines -Encoding ascii
Write-Host "Installed: $launcher"
if (-not $NoStart) { Start-Process wscript.exe -ArgumentList "`"$launcher`""; Write-Host 'Started both watchdogs.' }
