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
    [ValidateSet('up', 'down', 'logs', 'status', 'backup', 'restore', 'reset-db', 'tag-images', 'verify-deploy', 'help')]
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
# Read SPRING_PROFILES_ACTIVE from .env and return it as a normalized token list.
# Spring treats it as a comma-separated LIST ('mock,debug' still activates mock
# beans), so callers classify by list membership, not exact match. Defaults to
# ['mock'] when .env is absent (fresh checkout = mock, no secrets).
function Get-ActiveProfileList {
    $activeProfile = 'mock'
    if (Test-Path $EnvFile) {
        $m = Select-String -Path $EnvFile -Pattern '^SPRING_PROFILES_ACTIVE=(.*)$'
        if ($m) { $activeProfile = $m.Matches[0].Groups[1].Value.Trim() }
    }
    return @($activeProfile -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Set-ProfileEnv {
    $profileList = Get-ActiveProfileList
    $activeProfile = ($profileList -join ',')
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

# SEC-01: host-ACL regression guard. The owner tightened .env, deploy\secrets and
# backups to owner+SYSTEM+Administrators only (2026-07-18). If a later change (a
# fresh clone, an inheritance flip, a *CodexSandboxUsers grant) re-widens any of
# them to a broad principal WITH write access, a low-privilege account could read
# or tamper with the Kite token key / owner-password hash. Returns a list of
# human-readable violation strings (empty = clean). Pure function -> unit-testable.
function Get-SecretAclViolations {
    param([string[]]$Paths)
    $violations = @()
    # Broad principals that must NEVER hold write/modify/full on a secret path
    # (matched by resolved name AND well-known SID, in case the name won't resolve).
    $forbiddenNames = @('NT AUTHORITY\Authenticated Users', 'BUILTIN\Users', 'Everyone')
    $forbiddenSids  = @('S-1-5-11', 'S-1-5-32-545', 'S-1-1-0')
    # ATOMIC dangerous rights ONLY (review R2): OR-ing in FullControl made the mask
    # equal ALL filesystem rights, so a read-only ACE (ReadAndExecute, bare
    # Synchronize) was flagged and a fresh clone hard-stopped live startup. Write is
    # itself a small composite of the data-write bits (WriteData/AppendData/
    # WriteAttributes/WriteExtendedAttributes); Delete/TakeOwnership/ChangePermissions
    # are atomic. Composite grants (Modify, FullControl) still intersect these bits,
    # so they are still caught — while pure-read ACEs share no bit with the mask.
    $writeMask = [System.Security.AccessControl.FileSystemRights]::Write `
        -bor [System.Security.AccessControl.FileSystemRights]::Delete `
        -bor [System.Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles `
        -bor [System.Security.AccessControl.FileSystemRights]::TakeOwnership `
        -bor [System.Security.AccessControl.FileSystemRights]::ChangePermissions
    foreach ($p in $Paths) {
        if (-not (Test-Path $p)) { continue }   # an absent path can hold no ACE
        $acl = $null
        try { $acl = Get-Acl -Path $p -ErrorAction Stop }
        catch { $violations += "$p : could not read ACL ($($_.Exception.Message))"; continue }
        foreach ($ace in $acl.Access) {
            if ($ace.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow) { continue }
            $id = "$($ace.IdentityReference)"
            $isForbidden = $false
            foreach ($f in $forbiddenNames) { if ($id -ieq $f) { $isForbidden = $true } }
            foreach ($s in $forbiddenSids)  { if ($id -ieq $s) { $isForbidden = $true } }
            if ($id -match '(?i)CodexSandboxUsers') { $isForbidden = $true }
            if (-not $isForbidden) { continue }
            if (($ace.FileSystemRights -band $writeMask) -ne 0) {
                $violations += "$p grants '$($ace.FileSystemRights)' to '$id' (inherited=$($ace.IsInherited))"
            }
        }
    }
    return $violations
}

# Run one SQL statement against the restored DB and return the first non-empty
# data line, trimmed (psql -t -A). Empty string when the query yields NULL/nothing
# or psql errored; the 4-lineage restore validation decides whether empty = failure.
function Get-RestoreScalar {
    param([string]$Db, [string]$Sql)
    $out = Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$Db", '-t', '-A', '-c', "$Sql")
    foreach ($line in @($out)) {
        $s = "$line".Trim()
        if ($s -ne '') { return $s }
    }
    return ''
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

# Dot-sourcing (`. .\ay.ps1`) loads the functions above for unit tests WITHOUT
# running a verb; a normal `-File` / `& .\ay.ps1 <verb>` invocation executes it.
if ($MyInvocation.InvocationName -ne '.') {
switch ($Verb) {
    'up' {
        Initialize-LocalConfig
        # SEC-01: fail-closed secret-ACL regression guard. Broad-principal write access
        # to .env / deploy\secrets / backups is a hard stop on live, a warning on mock
        # (mock holds no real secrets). Runs AFTER Initialize-LocalConfig so .env +
        # deploy\secrets exist; absent paths (e.g. a not-yet-created backups/) are skipped.
        $profileList = Get-ActiveProfileList
        $isMockProfile = ($profileList -contains 'mock')
        $secretPaths = @($EnvFile, (Join-Path $RepoRoot 'deploy\secrets'), (Join-Path $RepoRoot 'backups'))
        $aclViolations = Get-SecretAclViolations -Paths $secretPaths
        if ($aclViolations.Count -gt 0) {
            Write-Host '[ay] SEC-01: secret-path ACL(s) grant WRITE access to a broad principal:'
            foreach ($v in $aclViolations) { Write-Host "[ay]   - $v" }
            if ($isMockProfile) {
                Write-Host '[ay] SEC-01: WARN (mock profile) - tighten these to owner+SYSTEM+Administrators; continuing.'
            } else {
                Write-Host '[ay] SEC-01: FAIL (live profile) - refusing to start. Remove the broad grant (or re-disable inheritance) then retry.'
                exit 1
            }
        }
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
        # manual pg_dump via the db-backup sidecar (A.11). `ay backup keep` (audit M30) takes a
        # ROTATION-EXEMPT pre-migration dump into pinned/ that the 3-slot retention never evicts.
        $mode = if ($Rest.Count -gt 0 -and ($Rest[0] -eq 'keep' -or $Rest[0] -eq 'pinned')) { 'pinned' } else { 'manual' }
        Invoke-Compose @('exec', 'db-backup', '/usr/local/bin/backup.sh', $mode)
        if ($mode -eq 'pinned') { Write-Host "[ay] pinned (rotation-exempt) backup taken -> backups/pinned/ (prune it yourself once the migration is proven good)" }
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
        # 2) recreate the target database GENUINELY empty: -T template0 (review R1c —
        #    createdb defaults to template1, which can carry site-local objects; the
        #    benign-error anchors below assume nothing pre-exists but the extension +
        #    template0's public schema).
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'dropdb', '-U', 'artha', '--if-exists', "$db")
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'createdb', '-U', 'artha', '-T', 'template0', "$db")
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
        # OPS-R01: pg_restore failure is FATAL (scanned, not blindly tolerated). It exits
        # non-zero if ANY statement errored; the ONLY tolerated errors are the anchored
        # benign patterns below, and a non-zero exit that produced NO benign-classified
        # error line is fatal too (review R1a: a crash / connection loss / unknown error
        # shape must never pass). PostgreSQL keeps restoring data after a failed CREATE,
        # so a partial restore can look complete — this classification gate is the real
        # defense; the manifest below is the backstop.
        $restoreOut  = Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'pg_restore', '-U', 'artha', '-d', "$db", '--no-owner', '/tmp/ay-restore.dump')
        $restoreExit = $LASTEXITCODE
        # Benign-error allowlist — ANCHORED to the exact objects that pre-exist in the
        # template0-cloned target (review R1b: a bare 'already exists' could suppress a
        # collision on a USER object). Only the timescaledb extension (pre-created above)
        # and template0's public schema exist before pg_restore runs; 'must be owner of
        # extension timescaledb' is the --no-owner COMMENT ON EXTENSION artifact.
        $benignPatterns = @(
            'extension "timescaledb" already exists',
            'schema "public" already exists',
            'must be owner of extension timescaledb'
        )
        $benignHits = 0
        $summaryN = -1   # pg_restore's terminal 'errors ignored on restore: N' count (-1 = summary absent)
        $fatalLines = @()
        foreach ($line in @($restoreOut)) {
            $t = "$line"
            if ($t -match '(?i)warning:\s*errors ignored on restore:\s*(\d+)') { $summaryN = [int]$Matches[1]; continue }
            if ($t -notmatch '(?i)pg_restore:\s*error:' -and $t -notmatch '(?i)^\s*error:') { continue }
            $isBenign = $false
            foreach ($b in $benignPatterns) { if ($t -match [regex]::Escape($b)) { $isBenign = $true } }
            if ($isBenign) { $benignHits++ } else { $fatalLines += $t }
        }
        if ($fatalLines.Count -gt 0) {
            Write-Host "[ay] RESTORE FAILED: pg_restore reported $($fatalLines.Count) non-benign error(s) (exit=$restoreExit). Stack NOT restarted:"
            foreach ($fl in $fatalLines) { Write-Host "[ay]   $fl" }
            exit 1
        }
        # Review round 3: tolerate a non-zero exit ONLY when pg_restore terminated
        # NORMALLY and self-accounts for every error. PG17 pg_restore.c prints
        # 'pg_restore: warning: errors ignored on restore: N' iff n_errors>0, then
        # exits 1 — so a run killed AFTER a benign error line (SIGKILL 137, docker
        # disconnect) has NO summary and must never pass as tolerated, and a summary
        # count above our benign-classified count means unaccounted errors.
        if ($restoreExit -ne 0) {
            if ($summaryN -lt 0) {
                Write-Host "[ay] RESTORE FAILED: pg_restore exit=$restoreExit WITHOUT its terminal 'errors ignored on restore' summary - abnormal termination (killed, connection lost, partial run). Stack NOT restarted."
                exit 1
            }
            if ($summaryN -ne $benignHits) {
                Write-Host "[ay] RESTORE FAILED: pg_restore summary reports $summaryN ignored error(s) but only $benignHits classified benign - unaccounted error(s). Stack NOT restarted."
                exit 1
            }
            Write-Host "[ay] note: pg_restore exit=$restoreExit; all $benignHits error(s) matched the anchored benign allowlist and the terminal summary is consistent."
        }
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'SELECT timescaledb_post_restore()')
        Invoke-Compose         @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c', 'ANALYZE')
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'rm', '-f', '/tmp/ay-restore.dump', '/tmp/ay-globals.sql')
        Write-Host "[ay] restored row counts (sanity):"
        Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-c',
            "SELECT 'candles' t, count(*) FROM marketdata.candles UNION ALL SELECT 'oi_snapshots', count(*) FROM marketdata.options_chain_snapshots UNION ALL SELECT 'signals', count(*) FROM strategy.signals")
        # OPS-R01: 4-lineage post-restore validation manifest. The old gate was
        # marketdata.candles>0 ALONE, so a dump missing strategy/backtest/roles/grants
        # restored "successfully". Probe a key relation in EACH Flyway lineage
        # (admin/marketdata/strategy/backtest), the three service roles, and the ONE
        # cross-schema grant. Any miss = hard fail, stack left DOWN.
        $problems = @()
        # (a) a key relation exists in each lineage (to_regclass -> NULL/empty when absent).
        #     Each lineage's flyway_schema_history is the universal per-lineage witness.
        $regclassProbes = @(
            'admin.flyway_schema_history',
            'marketdata.flyway_schema_history', 'marketdata.candles', 'marketdata.instruments', 'marketdata.options_chain_snapshots',
            'strategy.flyway_schema_history', 'strategy.signals',
            'backtest.flyway_schema_history', 'backtest.backtest_runs', 'backtest.jobs'
        )
        foreach ($obj in $regclassProbes) {
            $r = Get-RestoreScalar -Db $db -Sql "SELECT to_regclass('$obj')"
            if ($r -eq '') { $problems += "missing relation: $obj" }
        }
        # (b) hypertable row-count sanity (#395 silent-hypertable-loss class): candle rows
        #     live in _timescaledb_internal, so a bad dump restores the empty shell cleanly.
        $candleCount = 0
        $ccRaw = Get-RestoreScalar -Db $db -Sql 'SELECT count(*) FROM marketdata.candles'
        if ($ccRaw -match '^\d+$') { $candleCount = [long]$ccRaw }
        if ($candleCount -le 0) { $problems += 'marketdata.candles is EMPTY (the #395 silent-hypertable-loss class)' }
        # (c) the three service roles (cluster globals) restored.
        $roleCount = Get-RestoreScalar -Db $db -Sql "SELECT count(*) FROM pg_roles WHERE rolname IN ('ay_marketdata','ay_strategy','ay_backtest')"
        if ($roleCount -ne '3') { $problems += "service roles incomplete: expected 3 of ay_marketdata/ay_strategy/ay_backtest, found '$roleCount'" }
        # (d) the ONE cross-schema grant (CD-1: ay_backtest SELECTs marketdata). Per-object
        #     ACLs ride the whole-db dump (NOT globals), so a SELECT AS ay_backtest proves the
        #     dump's grants restored; a permission-denied means the grant was lost.
        $grantOut  = Invoke-ComposeAllowFail @('exec', '-T', 'timescaledb', 'psql', '-U', 'artha', '-d', "$db", '-t', '-A', '-v', 'ON_ERROR_STOP=1', '-c', 'SET ROLE ay_backtest; SELECT count(*) FROM marketdata.instruments; RESET ROLE')
        $grantText = (@($grantOut) -join "`n")
        if ($grantText -match '(?i)permission denied' -or $grantText -match '(?i)ERROR:') {
            $problems += "cross-schema grant lost: ay_backtest cannot SELECT marketdata.instruments -> $grantText"
        }
        if ($problems.Count -gt 0) {
            Write-Host "[ay] RESTORE FAILED: post-restore validation found $($problems.Count) problem(s). Stack NOT restarted:"
            foreach ($pb in $problems) { Write-Host "[ay]   - $pb" }
            exit 1
        }
        # 5) bring the full stack back; flyway-init validates the restored history head -> no-op
        Invoke-Compose @('up', '-d', '--wait')
        Write-Host "[ay] restore complete -> '$db' (4 lineages + roles + cross-schema grant validated; marketdata.candles=$candleCount rows)"
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
  ay backup [keep]          manual whole-db pg_dump (+ globals) into ./backups
                            (`keep` = a rotation-EXEMPT pre-migration dump into backups/pinned/)
  ay restore <dir|file>     restore a whole-db backup (dir or *-full.dump) — DROPS+recreates the DB
  ay reset-db               down, DROP VOLUMES, re-up (flyway rebuilds schemas, empty)
  ay tag-images             snapshot the current :dev images to :<git-sha> (a rollback target)
  ay verify-deploy          compare each running service's baked git sha to source HEAD (stale-jar guard)
'@
    }
}
}
