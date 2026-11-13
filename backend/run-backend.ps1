# Starts the Spring Boot backend using secrets from a .env file (never committed).
# Looks for .env next to this script, then in the repo root, then one folder above the repo.
#   SUPABASE_CONNECTION  postgresql://USER:[YOUR-PASSWORD]@HOST:PORT/DB   (Supabase "Session pooler" string)
#   password             the Supabase database password (replaces [YOUR-PASSWORD])
#   OPEN_AI_KEY / GEMINI_API_KEY   Google AI Studio key used by the restock agent
#   -LocalDb             ignore SUPABASE_CONNECTION and use the local H2 file database
param([switch]$LocalDb)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = @("$here\.env", "$here\..\.env", "$here\..\..\.env") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $envFile) { throw 'No .env file found.' }

$vars = @{}
foreach ($line in Get-Content $envFile) {
  if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $vars[$Matches[1]] = $Matches[2].Trim().TrimEnd('\').Trim('"', "'") }
}

$conn = $vars['SUPABASE_CONNECTION']
if ($conn -and -not $LocalDb) {
  # Parsed by hand (not [Uri]) so a raw '@' in the password works: the host starts after the LAST '@'.
  if ($conn -notmatch '^postgres(?:ql)?://([^:]+):(.*)@([^@:/]+)(?::(\d+))?(/[^?]*)?') { throw 'SUPABASE_CONNECTION is not a postgresql://user:password@host:port/db string.' }
  $user = $Matches[1]; $pw = [Uri]::UnescapeDataString($Matches[2]); $dbHost = $Matches[3]
  $port = if ($Matches[4]) { $Matches[4] } else { 5432 }
  $db = if ($Matches[5]) { $Matches[5] } else { '/postgres' }
  if (-not $pw -or $pw -match 'YOUR-PASSWORD') { $pw = $vars['password'] }
  # Supabase's template shows [YOUR-PASSWORD]; the brackets are placeholder markers, not part of the password.
  if ($pw -match '^\[(.*)\]$') { $pw = $Matches[1] }
  $env:SPAZA_DB_URL = "jdbc:postgresql://${dbHost}:$port$db`?sslmode=require"
  $env:SPAZA_DB_USER = $user
  $env:SPAZA_DB_PASSWORD = $pw
  Write-Host "Database: Supabase Postgres at $dbHost"
} else {
  Write-Host 'Database: local H2'
}

$key = if ($vars['GEMINI_API_KEY']) { $vars['GEMINI_API_KEY'] } else { $vars['OPEN_AI_KEY'] }
if ($key) { $env:GEMINI_API_KEY = $key; Write-Host 'LLM key: loaded' } else { Write-Host 'LLM key: not set (agent runs rule-based only)' }

Set-Location $here
mvn -q spring-boot:run
