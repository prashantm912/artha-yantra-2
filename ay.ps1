#Requires -Version 5.1
<#
.SYNOPSIS
    ay — ArthaYantra 2.0 operator CLI (A.1.1). Project-scoped compose only;
    never raw docker kill (the v1 stop.bat force-killed every java.exe).
.EXAMPLE
    .\ay.ps1 up dev-tools
    .\ay.ps1 status
    .\ay.ps1 logs timescaledb
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('up', 'down', 'logs', 'status', 'backup', 'restore', 'reset-db', 'help')]
    [string]$Verb = 'help',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @()
)

$ErrorActionPreference = 'Stop'
$RepoRoot    = $PSScriptRoot
$ComposeFile = Join-Path $RepoRoot 'deploy\docker-compose.yml'
$EnvFile     = Join-Path $RepoRoot '.env'

# Profile isolation (mock vs live share ONE Postgres instance + Redis but get
# SEPARATE databases + Redis logical DBs, so mock's synthetic data can never
# pollute live): derive the names from SPRING_PROFILES_ACTIVE and export them so
# compose interpolates ${ARTHA_DB_NAME}/${ARTHA_REDIS_DB} consistently across
# every service + the db-create/flyway-init/backup containers.
function Set-ProfileEnv {
    $activeProfile = 'mock'
    if (Test-Path $EnvFile) {
        $m = Select-String -Path $EnvFile -Pattern '^SPRING_PROFILES_ACTIVE=(.*)$'
        if ($m) { $activeProfile = $m.Matches[0].Groups[1].Value.Trim() }
    }
    if ($activeProfile -eq 'mock') {
        $env:ARTHA_DB_NAME = 'artha_mock'; $env:ARTHA_REDIS_DB = '1'
    } else {
        $env:ARTHA_DB_NAME = 'artha';      $env:ARTHA_REDIS_DB = '0'
    }
    Write-Host "[ay] profile=$activeProfile -> db=$($env:ARTHA_DB_NAME) redisDb=$($env:ARTHA_REDIS_DB)"
}

function Invoke-Compose {
    param([string[]]$ComposeArgs)
    Set-ProfileEnv
    # docker compose writes progress to stderr; stringify it so PS 5.1 does
    # not promote NativeCommandError under redirection - the exit code is
    # the only failure signal that matters here
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker compose -f $ComposeFile --env-file $EnvFile @ComposeArgs 2>&1 | ForEach-Object { "$_" }
    $ErrorActionPreference = $previousEap
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Initialize-LocalConfig {
    if (-not (Test-Path $EnvFile)) {
        Copy-Item (Join-Path $RepoRoot '.env.example') $EnvFile
        Write-Host '[ay] created .env from .env.example (mock mode - no secrets needed)'
    }
    $pwFile = Join-Path $RepoRoot 'deploy\secrets\postgres_password'
    if (-not (Test-Path $pwFile)) {
        $chars = [char[]]((48..57) + (65..90) + (97..122))
        $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
        $bytes = New-Object byte[] 32
        $rng.GetBytes($bytes)
        $pw = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
        Set-Content -Path $pwFile -Value $pw -NoNewline -Encoding ascii
        Write-Host '[ay] generated deploy/secrets/postgres_password (gitignored)'
    }
    # Phase 12: 256-bit AES-GCM master key for the Kite token store (base64)
    $mkFile = Join-Path $RepoRoot 'deploy\secrets\artha_master_key'
    if (-not (Test-Path $mkFile)) {
        $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
        $key = New-Object byte[] 32
        $rng.GetBytes($key)
        Set-Content -Path $mkFile -Value ([Convert]::ToBase64String($key)) -NoNewline -Encoding ascii
        Write-Host '[ay] generated deploy/secrets/artha_master_key (gitignored)'
    }
    # empty placeholders so compose can mount them; mock mode never reads them,
    # live mode fails fast until the owner fills in real Kite credentials. openalgo_api_key is
    # mounted into market-data-service but read only when capture routes through OpenAlgo (§3/§4).
    foreach ($name in 'kite_api_key', 'kite_api_secret', 'openalgo_api_key') {
        $f = Join-Path $RepoRoot "deploy\secrets\$name"
        if (-not (Test-Path $f)) {
            New-Item -ItemType File -Path $f | Out-Null
            Write-Host "[ay] created empty deploy/secrets/$name placeholder (fill for live mode)"
        }
    }
    # OpenAlgo appliance config (plan §2): copy the sample and generate APP_KEY + API_KEY_PEPPER
    # once (gitignored; the appliance's own keys, not ours). Broker login is a runtime UI step.
    $oaEnv = Join-Path $RepoRoot 'deploy\openalgo\.env'
    if (-not (Test-Path $oaEnv)) {
        $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
        $gen = @{}
        foreach ($k in 'APP_KEY', 'API_KEY_PEPPER', 'FERNET_SALT') {
            $b = New-Object byte[] 32
            $rng.GetBytes($b)
            $gen[$k] = (($b | ForEach-Object { $_.ToString('x2') }) -join '')
        }
        # Single-owner: OpenAlgo holds the Kite session (one app -> OpenAlgo), so seed its broker
        # creds from the existing kite_* secret files when present (empty in mock mode -> blank, the
        # owner logs the broker in via the OpenAlgo UI). NOT the _MARKET keys (XTS-only).
        $kkFile = Join-Path $RepoRoot 'deploy\secrets\kite_api_key'
        $ksFile = Join-Path $RepoRoot 'deploy\secrets\kite_api_secret'
        $bkey = if (Test-Path $kkFile) { (Get-Content $kkFile -Raw).Trim() } else { '' }
        $bsec = if (Test-Path $ksFile) { (Get-Content $ksFile -Raw).Trim() } else { '' }
        # The sample is OpenAlgo's COMPLETE native-format config (KEY = 'value'); fill placeholders.
        $sample = Get-Content (Join-Path $RepoRoot 'deploy\openalgo\.env.sample')
        $sample = $sample `
            -replace "^APP_KEY = .*", "APP_KEY = '$($gen['APP_KEY'])'" `
            -replace "^API_KEY_PEPPER = .*", "API_KEY_PEPPER = '$($gen['API_KEY_PEPPER'])'" `
            -replace "^FERNET_SALT = .*", "FERNET_SALT = '$($gen['FERNET_SALT'])'" `
            -replace "^BROKER_API_KEY = .*", "BROKER_API_KEY = '$bkey'" `
            -replace "^BROKER_API_SECRET = .*", "BROKER_API_SECRET = '$bsec'"
        # UTF-8 WITHOUT BOM: it is mounted AS /app/.env and read by python-dotenv (a BOM would
        # corrupt the first key); ascii would mangle the upstream comments' em-dashes.
        [System.IO.File]::WriteAllLines($oaEnv, $sample, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host '[ay] generated deploy/openalgo/.env (APP_KEY/API_KEY_PEPPER/FERNET_SALT + broker creds; gitignored)'
    }
}

switch ($Verb) {
    'up' {
        Initialize-LocalConfig
        $composeArgs = @()
        foreach ($p in $Rest) {
            if ($p -eq 'obs' -or $p -eq 'dev-tools' -or $p -eq 'openalgo') { $composeArgs += @('--profile', $p) }
            else { Write-Error "[ay] unknown profile '$p' (expected: obs, dev-tools, openalgo)" }
        }
        Invoke-Compose ($composeArgs + @('up', '-d', '--wait'))
    }
    'down' {
        Invoke-Compose @('--profile', 'obs', '--profile', 'dev-tools', 'down')
    }
    'logs' {
        if ($Rest.Count -lt 1) { Write-Error 'usage: ay logs <service>' }
        Invoke-Compose (@('logs', '-f') + $Rest)
    }
    'status' {
        Invoke-Compose @('ps', '-a', '--format', 'table {{.Name}}\t{{.Service}}\t{{.Status}}\t{{.Publishers}}')
    }
    'backup' {
        # manual pg_dump via the db-backup sidecar (A.11)
        Invoke-Compose @('exec', 'db-backup', '/usr/local/bin/backup.sh', 'manual')
    }
    'restore' {
        if ($Rest.Count -lt 1) { Write-Error 'usage: ay restore <dump-file>' }
        $dump = Resolve-Path $Rest[0]
        Write-Host "[ay] restoring $dump into database 'artha' (per-schema -Fc dump)"
        Invoke-Compose @('cp', "$dump", 'timescaledb:/tmp/ay-restore.dump')
        # timescaledb_pre_restore disables chunk routing checks so pg_restore can write
        # directly to chunk tables; timescaledb_post_restore re-enables them.
        Invoke-Compose @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', 'artha', '-c', 'SELECT timescaledb_pre_restore()')
        Invoke-Compose @('exec', '-T', 'timescaledb', 'pg_restore', '-U', 'artha', '-d', 'artha', '--data-only', '--no-owner', '/tmp/ay-restore.dump')
        Invoke-Compose @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', 'artha', '-c', 'SELECT timescaledb_post_restore()')
        Invoke-Compose @('exec', '-T', 'timescaledb', 'rm', '-f', '/tmp/ay-restore.dump')
        Write-Host '[ay] restore complete'
    }
    'reset-db' {
        # down, drop volumes, re-up -> flyway-init rebuilds all schemas (D17)
        Invoke-Compose @('--profile', 'obs', '--profile', 'dev-tools', 'down', '-v')
        Initialize-LocalConfig
        Invoke-Compose @('up', '-d', '--wait')
    }
    default {
        Write-Host @'
ay - ArthaYantra operator CLI (project-scoped docker compose)

  ay up [obs] [dev-tools] [openalgo]   start the stack (creates .env + db password if missing)
  ay down                   stop project containers (volumes kept)
  ay logs <svc>             follow logs for one service
  ay status                 healthcheck summary of all containers
  ay backup                 manual pg_dump into ./backups
  ay restore <file>         restore a -Fc dump file into the database
  ay reset-db               down, DROP VOLUMES, re-up (flyway rebuilds schemas)
'@
    }
}
