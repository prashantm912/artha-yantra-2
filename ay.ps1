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

function Invoke-Compose {
    param([string[]]$ComposeArgs)
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
}

switch ($Verb) {
    'up' {
        Initialize-LocalConfig
        $composeArgs = @()
        foreach ($p in $Rest) {
            if ($p -eq 'obs' -or $p -eq 'dev-tools') { $composeArgs += @('--profile', $p) }
            else { Write-Error "[ay] unknown profile '$p' (expected: obs, dev-tools)" }
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
        Invoke-Compose @('exec', '-T', 'timescaledb', 'pg_restore', '-U', 'artha', '-d', 'artha', '--clean', '--if-exists', '--no-owner', '/tmp/ay-restore.dump')
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

  ay up [obs] [dev-tools]   start the stack (creates .env + db password if missing)
  ay down                   stop project containers (volumes kept)
  ay logs <svc>             follow logs for one service
  ay status                 healthcheck summary of all containers
  ay backup                 manual pg_dump into ./backups
  ay restore <file>         restore a -Fc dump file into the database
  ay reset-db               down, DROP VOLUMES, re-up (flyway rebuilds schemas)
'@
    }
}
