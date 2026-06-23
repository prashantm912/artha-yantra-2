<#
.SYNOPSIS
  Fetch today's NSE SPAN (.spn) risk file into deploy/span-files/ for margin-service (§8.2).

.DESCRIPTION
  margin-service computes NOTHING without a daily exchange SPAN file. This host-side
  fetcher downloads today's NSE Clearing (NSCCL) PR zip, extracts the .spn into the
  read-only bind-mount directory deploy/span-files/, and prunes files older than
  -KeepDays. It runs OUTSIDE the container (Windows Task Scheduler, ~08:30 IST) so an
  NSE outage never crashes the appliance — it just keeps serving yesterday's params,
  and /health.spnDate makes the staleness visible.

  Idempotent: re-running for a date that is already present is a no-op (the existing
  .spn keeps its mtime, so margin-service does not re-parse).

.PARAMETER Date
  Trading date to fetch (default: today). Format yyyy-MM-dd.

.PARAMETER KeepDays
  Prune .spn files older than this many days (default 7). margin-service only ever
  reads the newest-by-mtime file; older ones are kept briefly for audit/rollback.

.NOTES
  (VERIFY) NSE rotates its archive hosts and the SPAN file naming/format changes
  periodically. The $SpanUrl template below is the historically-documented NSCCL PR
  archive pattern but MUST be confirmed against the live NSE site before relying on
  automated fetches — see services/margin-service/README.md "VERIFY-pending" items.
  SENSEX/BSE F&O SPAN comes from BSE/ICCL via a separate (also VERIFY) URL — out of
  scope for v1 (NIFTY/NSE scalping first).

.EXAMPLE
  pwsh tools/span-fetch/fetch_spn.ps1
  pwsh tools/span-fetch/fetch_spn.ps1 -Date 2026-06-25 -KeepDays 14
#>
[CmdletBinding()]
param(
  [string]$Date = (Get-Date -Format 'yyyy-MM-dd'),
  [int]$KeepDays = 7
)

$ErrorActionPreference = 'Stop'

# Resolve repo-root-relative target dir (this script lives in tools/span-fetch/).
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..' '..')
$spnDir   = Join-Path $repoRoot 'deploy/span-files'
New-Item -ItemType Directory -Force -Path $spnDir | Out-Null

$dt    = [datetime]::ParseExact($Date, 'yyyy-MM-dd', $null)
$ddmmyy = $dt.ToString('ddMMyy')

# (VERIFY) NSCCL daily PR (price/risk-parameter) archive. Confirm the live host +
# path; NSE has used nsearchives.nseindia.com/archives/nsccl/span/PR_<DDMMYY>.zip.
$SpanUrl = "https://nsearchives.nseindia.com/archives/nsccl/span/PR_$ddmmyy.zip"

$zipPath = Join-Path $env:TEMP "PR_$ddmmyy.zip"
$destSpn = Join-Path $spnDir  "PR_$ddmmyy.spn"

if (Test-Path $destSpn) {
  Write-Host "SPAN file already present for $Date ($destSpn) — nothing to do."
  exit 0
}

Write-Host "Downloading NSE SPAN PR zip for $Date from $SpanUrl"
try {
  # NSE rejects requests without a browser-like UA. --ssl-no-revoke equivalent:
  # PowerShell's Invoke-WebRequest honours the system trust store (AV CA handled by box setup).
  Invoke-WebRequest -UseBasicParsing -Uri $SpanUrl -OutFile $zipPath `
    -Headers @{ 'User-Agent' = 'Mozilla/5.0'; 'Accept' = '*/*' }
} catch {
  Write-Warning "SPAN download failed for ${Date}: $($_.Exception.Message)"
  Write-Warning "margin-service keeps serving the previous .spn; /health.spnDate shows the staleness."
  exit 1
}

# Extract the .spn out of the zip (the zip may contain multiple files; take the .spn).
$tmpExtract = Join-Path $env:TEMP "span_$ddmmyy"
if (Test-Path $tmpExtract) { Remove-Item -Recurse -Force $tmpExtract }
Expand-Archive -Path $zipPath -DestinationPath $tmpExtract -Force

$spn = Get-ChildItem -Path $tmpExtract -Filter '*.spn' -Recurse | Select-Object -First 1
if (-not $spn) {
  Write-Warning "No .spn found inside $zipPath (NSE format may have changed — VERIFY)."
  exit 1
}

# Atomic-ish place: copy to a temp name in the target dir, then move into place so a
# half-written file is never the newest-by-mtime that margin-service would parse.
$staging = Join-Path $spnDir ".PR_$ddmmyy.spn.partial"
Copy-Item -Path $spn.FullName -Destination $staging -Force
Move-Item -Path $staging -Destination $destSpn -Force
Write-Host "Placed $destSpn"

# Cleanup temp artefacts.
Remove-Item -Force $zipPath -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $tmpExtract -ErrorAction SilentlyContinue

# Prune .spn files older than KeepDays (keep the bind-mount tidy; newest is always served).
$cutoff = (Get-Date).AddDays(-$KeepDays)
Get-ChildItem -Path $spnDir -Filter '*.spn' |
  Where-Object { $_.LastWriteTime -lt $cutoff } |
  ForEach-Object { Write-Host "Pruning stale $($_.Name)"; Remove-Item -Force $_.FullName }

Write-Host "SPAN fetch complete for $Date."
