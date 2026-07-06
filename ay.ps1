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
    [ValidateSet('up', 'down', 'logs', 'status', 'backup', 'restore', 'reset-db', 'tag-images', 'help')]
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
    # Spring treats SPRING_PROFILES_ACTIVE as a comma-separated LIST ('mock,debug'
    # still activates mock beans), so classify by list membership, not exact match.
    $profileList = @($activeProfile -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if (($profileList -contains 'mock') -and ($profileList -contains 'live')) {
        Write-Host "[ay] ERROR: SPRING_PROFILES_ACTIVE='$activeProfile' mixes mock and live - refusing"
        exit 1
    }
    if ($profileList -contains 'mock') {
        $env:ARTHA_DB_NAME = 'artha_mock'; $env:ARTHA_REDIS_DB = '1'
        # Mock notifier targets the WireMock stub (audit P0-6: the compose default is now
        # fail-closed blank, so live can never silently post to the stub).
        $env:ARTHA_NOTIFIER_NTFY_URL = 'http://wiremock:8080'
        $env:ARTHA_NOTIFIER_NTFY_TOPIC = 'ay-signals-mock'
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

# Like Invoke-Compose but does NOT abort on a non-zero exit — for restore steps
# where benign errors are expected and non-fatal (pg_dumpall globals re-CREATE a
# role that already exists; pg_restore emits per-statement errors for grants to a
# not-yet-created role, etc.). The restore is verified by a post-restore row count,
# not by these exit codes.
function Invoke-ComposeAllowFail {
    param([string[]]$ComposeArgs)
    Set-ProfileEnv
    $previousEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & docker compose -f $ComposeFile --env-file $EnvFile @ComposeArgs 2>&1 | ForEach-Object { "$_" }
    $ErrorActionPreference = $previousEap
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
    foreach ($name in 'kite_api_key', 'kite_api_secret', 'openalgo_api_key', 'upstox_analytics_token') {
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
            if ($p -eq 'dev-tools' -or $p -eq 'openalgo') { $composeArgs += @('--profile', $p) }
            elseif ($p -eq 'obs') { Write-Error "[ay] the 'obs' profile has NO services yet (audit P2) - Prometheus/Grafana/Loki never shipped; running it silently started the plain stack" }
            else { Write-Error "[ay] unknown profile '$p' (expected: dev-tools, openalgo)" }
        }
        Invoke-Compose ($composeArgs + @('up', '-d', '--wait'))
    }
    'down' {
        Invoke-Compose @('--profile', 'dev-tools', '--profile', 'openalgo', 'down')
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
        # Restore a WHOLE-DATABASE -Fc dump (produced by `ay backup`) into the active
        # profile's database. Accepts a backup DIRECTORY (uses its globals.sql + *-full.dump)
        # or a single *-full.dump file. DESTRUCTIVE: drops + recreates the target DB.
        # CI mirror: .github/workflows/ci-backup-roundtrip.yml exercises this exact sequence weekly — keep in lockstep.
        if ($Rest.Count -lt 1) { Write-Error 'usage: ay restore <backup-dir-or-full-dump>' }
        Set-ProfileEnv
        $db  = $env:ARTHA_DB_NAME
        $src = Resolve-Path $Rest[0]
        if (Test-Path $src -PathType Container) {
            $dump = (Get-ChildItem $src -Filter '*-full.dump' | Select-Object -First 1).FullName
            if (-not $dump) { Write-Error "no *-full.dump found in $src (is this a backup directory?)" }
            $globals = Join-Path $src 'globals.sql'
        } else {
            $dump    = "$src"
            $globals = Join-Path (Split-Path $src) 'globals.sql'
        }
        Write-Host "[ay] RESTORE -> database '$db' from $dump"
        Write-Host "[ay] WARNING: this DROPS and recreates '$db' — its current contents are replaced."
        # 1) stop the stack, bring up ONLY the DB server (no app connections to drop)
        Invoke-Compose @('--profile', 'dev-tools', '--profile', 'openalgo', 'down')
        Invoke-Compose @('up', '-d', '--wait', 'timescaledb')
        # 2) recreate the target database empty
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'dropdb', '-U', 'artha', '--if-exists', "$db")
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'createdb', '-U', 'artha', "$db")
        # 3) cluster globals (roles + grants) — restored into 'postgres'; a CREATE ROLE that
        #    already exists (e.g. the bootstrap 'artha' superuser) is a tolerated no-op.
        if (Test-Path $globals) {
            Invoke-Compose         @('cp', "$globals", 'timescaledb:/tmp/ay-globals.sql')
            Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', 'postgres', '-v', 'ON_ERROR_STOP=0', '-f', '/tmp/ay-globals.sql')
        }
        # 4) the Timescale pre/post_restore dance around a FULL (schema+data) restore.
        #    pre_restore disables chunk routing + background jobs so pg_restore can write the
        #    _timescaledb_internal chunk data directly; post_restore re-enables them.
        Invoke-Compose         @('cp', "$dump", 'timescaledb:/tmp/ay-restore.dump')
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'CREATE EXTENSION IF NOT EXISTS timescaledb')
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'SELECT timescaledb_pre_restore()')
        # pg_restore returns non-zero if ANY statement errored (benign grants to a missing role,
        # etc.) — tolerated here; correctness is asserted by the row count printed below.
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'pg_restore', '-U', 'artha', '-d', "$db", '--no-owner', '/tmp/ay-restore.dump')
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'SELECT timescaledb_post_restore()')
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'ANALYZE')
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'rm', '-f', '/tmp/ay-restore.dump', '/tmp/ay-globals.sql')
        Write-Host "[ay] restored row counts (sanity):"
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c',
            "SELECT 'candles' t, count(*) FROM marketdata.candles UNION ALL SELECT 'oi_snapshots', count(*) FROM marketdata.options_chain_snapshots UNION ALL SELECT 'signals', count(*) FROM strategy.signals")
        # Hard gate, not an eyeball check: Timescale hypertable rows live in _timescaledb_internal,
        # so a bad dump pg_restores "successfully" with 0 rows (the #395 class).
        $candleCountRaw = Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-t', '-A', '-c', 'SELECT count(*) FROM marketdata.candles')
        $candleCount = 0; $parsed = ($candleCountRaw | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -First 1)
        if ($parsed) { $candleCount = [long]$parsed.Trim() }
        if ($candleCount -le 0) {
            Write-Host "[ay] RESTORE FAILED: marketdata.candles is EMPTY after restore (the #395 silent-hypertable-loss class). Stack NOT restarted."
            exit 1
        }
        # 5) bring the full stack back; flyway-init validates the restored history head -> no-op
        Invoke-Compose @('up', '-d', '--wait')
        Write-Host "[ay] restore complete -> '$db' (marketdata.candles=$candleCount rows)"
    }
    'reset-db' {
        # down, drop volumes, re-up -> flyway-init rebuilds all schemas (D17)
        Invoke-Compose @('--profile', 'dev-tools', '--profile', 'openalgo', 'down', '-v')
        Initialize-LocalConfig
        Invoke-Compose @('up', '-d', '--wait')
    }
    'tag-images' {
        # Rollback target (audit P2): app images are the single mutable :dev tag, so a bad deploy
        # had no fast way back. Snapshot the CURRENT :dev images to :<git-sha> right after a good
        # build; roll back with `docker tag arthayantra/<svc>:<sha> arthayantra/<svc>:dev` + `ay up`.
        $sha = (git rev-parse --short HEAD).Trim()
        $svcs = @('edge-gateway', 'market-data-service', 'strategy-signal-service',
                  'backtest-service', 'optimizer-service', 'margin-service', 'frontend-react')
        foreach ($svc in $svcs) {
            $dev = "arthayantra/$svc`:dev"
            if (docker image inspect $dev 2>$null) {
                docker tag $dev "arthayantra/$svc`:$sha"
                Write-Host "[ay] tagged arthayantra/$svc`:$sha"
            }
        }
        Write-Host "[ay] rollback: docker tag arthayantra/<svc>:$sha arthayantra/<svc>:dev; ay up"
    }
    'verify-deploy' {
        # H9 stale-jar guard: the deployable services now bake their build git sha into
        # /actuator/info (git.properties). A running container whose sha != the source HEAD
        # is a STALE jar — the ":dev tag is mutable, COPY baked an old jar" trap that has
        # bitten before with no mechanical signal. This turns that into a loud check.
        $head = (git rev-parse HEAD 2>$null)
        if ($head) { $head = $head.Trim() }
        if (-not $head) { Write-Host '[ay] verify-deploy: not a git checkout — cannot compare.'; break }
        $svcPorts = [ordered]@{
            'ay-edge-gateway'            = 8080
            'ay-market-data-service'     = 8081
            'ay-strategy-signal-service' = 8082
            'ay-backtest-service'        = 8083
        }
        Write-Host "[ay] source HEAD = $head"
        $stale = 0; $checked = 0
        foreach ($name in $svcPorts.Keys) {
            $port = $svcPorts[$name]
            $json = docker exec $name wget -qO- "http://127.0.0.1:$port/actuator/info" 2>$null
            if (-not $json) { Write-Host "[ay]   $name : DOWN or no /actuator/info (pre-H9 image?)"; continue }
            $running = $null
            try { $running = ($json | ConvertFrom-Json).git.commit.id } catch {}
            if (-not $running) { Write-Host "[ay]   $name : /actuator/info has no git sha (pre-H9 image?)"; continue }
            $checked++
            if ($head.StartsWith($running) -or $running.StartsWith($head.Substring(0, [Math]::Min(7, $running.Length)))) {
                Write-Host "[ay]   $name : OK ($running)"
            } else {
                Write-Host "[ay]   $name : STALE — running $running, source HEAD $($head.Substring(0,12))"
                $stale++
            }
        }
        if ($stale -gt 0) {
            Write-Host "[ay] verify-deploy: $stale of $checked checked service(s) run a STALE jar — rebuild the artifact THEN the image (see build-service)."
            exit 1
        }
        Write-Host "[ay] verify-deploy: all $checked checked service(s) match HEAD."
    }
    default {
        Write-Host @'
ay - ArthaYantra operator CLI (project-scoped docker compose)

  ay up [dev-tools] [openalgo]   start the stack (creates .env + db password if missing)
  ay down                   stop project containers (volumes kept)
  ay logs <svc>             follow logs for one service
  ay status                 healthcheck summary of all containers
  ay backup                 manual whole-db pg_dump (+ globals) into ./backups
  ay restore <dir|file>     restore a whole-db backup (dir or *-full.dump) — DROPS+recreates the DB
  ay reset-db               down, DROP VOLUMES, re-up (flyway rebuilds schemas, empty)
  ay tag-images             snapshot the current :dev images to :<git-sha> (a rollback target)
  ay verify-deploy          compare each running service's baked git sha to source HEAD (stale-jar guard)
'@
    }
}
