# Stage A — Manual Testing Guide

Everything Stage A built, testable by hand from a PowerShell prompt at the
repo root (`C:\Trading\ArthaYantra\artha-yantra-2`). Each section is
independent; the whole pass takes ~30–40 minutes. Where a step needs Linux
syntax, the WSL2/Git-Bash equivalent is noted.

> **One-time machine notes (this Windows box):**
> - Maven needs the Windows trust store because of the TLS-intercepting AV:
>   `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` is already
>   persisted as a user environment variable — open a **new** terminal if a
>   build fails with `PKIX path building failed`.
> - `gitleaks` (8.30.1) was installed via `winget install Gitleaks.Gitleaks`.
> - When pasting an Argon2id hash into `.env`, escape every `$` as `$$`
>   (docker compose interpolates `$` inside `.env` values).

---

## 0. Prerequisites check (2 min)

```powershell
java -version        # Temurin 21.x
docker --version     # Docker Desktop running (WSL2 backend)
node --version       # v24.x (for the STOMP probe)
python --version     # 3.x (for pre-commit)
gitleaks version     # 8.30.1
```

---

## 1. Clean-machine bring-up — the Stage-A headline test (5 min)

**What you're proving:** `ay up` reaches green with **zero Kite credentials**
(D13 mock mode), all ports loopback-only.

```powershell
.\ay.ps1 down                  # stop anything running (keeps volumes)
.\ay.ps1 up dev-tools          # first run auto-creates .env + db password
.\ay.ps1 status
```

**PASS when:** every container shows `(healthy)` and `ay-flyway-init` shows
`Exited (0)`. Expected set: `ay-timescaledb`, `ay-redis`, `ay-edge-gateway`,
`ay-market-data-service`, `ay-db-backup`, `ay-adminer`, `ay-redisinsight`,
`ay-redis-publish`, `ay-mds-publish`.

Verify nothing binds beyond loopback:

```powershell
docker ps --format '{{.Names}}  {{.Ports}}'
```

**PASS when:** every published port starts `127.0.0.1:` and the only ports
are 8080 (gateway), 5432 (db), and — dev-tools profile only — 8085, 5540,
6379, 8081. **FAIL if** anything shows `0.0.0.0`.

Verify the database password is never a plain env var (threat T4):

```powershell
docker inspect ay-timescaledb --format '{{json .Config.Env}}'
```

**PASS when:** you see `POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password`
and **no** `POSTGRES_PASSWORD=` entry.

---

## 2. Owner login at the gateway (5 min)

**What you're proving:** Argon2id form login (A.2.3), hardened session
cookie, session probe, and that wrong passwords / brute force are rejected.

First set your own password (skip if you already did):

```powershell
.\mvnw.cmd -pl tools/hash-password -q compile exec:java "-Dexec.args=MyPassword123"
```

Copy the printed `$argon2id$...` line into `.env` as
`ARTHA_OWNER_PASSWORD_HASH=`, **doubling every `$` to `$$`**, then:

```powershell
docker compose -f deploy/docker-compose.yml --env-file .env up -d edge-gateway
```

Now test (PowerShell):

```powershell
# wrong password -> 401 + error envelope
try { Invoke-WebRequest -UseBasicParsing -Method POST -Uri http://127.0.0.1:8080/api/v1/auth/login -Body 'password=wrong' -ContentType 'application/x-www-form-urlencoded' } catch { $r=$_.Exception.Response; [int]$r.StatusCode; (New-Object IO.StreamReader($r.GetResponseStream())).ReadToEnd() }

# right password -> 204 + cookie
$resp = Invoke-WebRequest -UseBasicParsing -Method POST -Uri http://127.0.0.1:8080/api/v1/auth/login -Body 'password=MyPassword123' -ContentType 'application/x-www-form-urlencoded' -SessionVariable web
$resp.StatusCode; $resp.Headers['Set-Cookie']

# session probe -> authenticated:true, profile:mock
(Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/api/v1/auth/session -WebSession $web).Content
```

**PASS when:** wrong password gives `401` with body
`{"code":"AUTH_BAD_CREDENTIALS",...}`; right password gives `204` and the
`SESSION` cookie carries `HTTPOnly; SameSite=Strict; Path=/`; the probe shows
`"authenticated":true` and `"profile":"mock"`.

**Brute-force lockout:** run the *wrong*-password command 6 times within a
minute. **PASS when:** the 6th answers `429` with
`{"code":"AUTH_RATE_LIMITED","details":{"retryAfterMs":...}}`. (You're now in
a 15-minute cooldown for login — continue with the `$web` session you already
have, or wait it out / `docker exec ay-redis redis-cli del login:cooldown:<ip>`.)

**Security headers** (on any response):

```powershell
(Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/api/v1/auth/session).Headers | Format-Table
```

**PASS when:** `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'self'...`,
and an `X-Request-Id` UUID are all present.

**Routing skeleton:** an authenticated call to an upstream that is down must
return a clean 503 envelope, never a stack trace. Stop the strategy service,
hit its route, then restart:

```powershell
docker stop ay-strategy-signal-service
try { Invoke-WebRequest -UseBasicParsing -Uri http://127.0.0.1:8080/api/v1/strategies -WebSession $web } catch { $r=$_.Exception.Response; [int]$r.StatusCode }
docker start ay-strategy-signal-service
```

**PASS when:** `503` (body code `UPSTREAM_UNAVAILABLE`). Unauthenticated, the
same URL gives `401` with `AUTH_REQUIRED`.

> **Stage C note:** from Stage C onward all services (`strategy-signal-service`,
> `market-data-service`) are deployed, so `/api/v1/strategies/x` routes to the
> live service rather than returning 503. Non-UUID path params (like `x`) now
> return `400 VALIDATION_FAILED` instead. Unknown paths not covered by any
> service route are caught by the Angular SPA router (200 + SPA HTML).

---

## 3. Mock tick pipeline on Redis (3 min)

**What you're proving:** the deterministic mock feed publishes normalized
ticks on the canonical D9 channels.

```powershell
docker exec ay-redis redis-cli psubscribe 'ticks.*'
# watch for ~5 seconds, then Ctrl+C
```

**PASS when:** you see channels named exactly
`ticks.NSE.NIFTY 50`, `ticks.NSE.RELIANCE`, … and each JSON body has
`"lastPrice":"<digits>.<2 digits>"` (a **string**, never a bare number),
`"timestamp":"...+05:30"`, and a `"seq"` that climbs by 1 per channel.

Status keys:

```powershell
docker exec ay-redis redis-cli get kite:session:status   # -> MOCK
docker exec ay-redis redis-cli hlen ticks:last           # -> 10 (instruments)
```

**Scenario knobs (CD-10):** edit `.env`, set `MOCK_SCENARIO=trend-down`, then
`docker compose -f deploy/docker-compose.yml --env-file .env up -d market-data-service`
and re-subscribe — prices should drift downward over a minute. Set back to
`trend-up` afterwards.

---

## 4. End-to-end STOMP probe — browser path (3 min)

**What you're proving:** mock feed → Redis → gateway WS bridge → an
authenticated STOMP client (the Stage-A exit-gate demo).

```powershell
cd e2e; npm install; cd ..
$env:ARTHA_OWNER_PASSWORD = 'MyPassword123'
node e2e/tools/stomp-probe.mjs                                   # NIFTY 50, 10 frames
node e2e/tools/stomp-probe.mjs '/topic/ticks.NSE.RELIANCE' 5     # another symbol
```

**PASS when:** it prints `login ok`, `CONNECTED`, then the requested number
of tick frames and `✔ received N frames end-to-end`. **FAIL if** you ever see
frames for a symbol you didn't subscribe to (firehose behavior).

Bonus — unauthenticated WS is rejected: temporarily set a wrong
`ARTHA_OWNER_PASSWORD` and rerun; the probe must fail at login, never connect.

---

## 5. Database: schemas, roles, the single grant (3 min)

**What you're proving:** Flyway built everything from empty and the
single-writer grant rules hold (CD-1/D10).

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "\dn"
docker exec ay-timescaledb psql -U artha -d artha -c "SELECT rolname FROM pg_roles WHERE rolname LIKE 'ay_%'"
docker exec ay-timescaledb psql -U artha -d artha -t -A -c "SELECT has_schema_privilege('ay_backtest','marketdata','USAGE'), has_schema_privilege('ay_strategy','marketdata','USAGE')"
```

**PASS when:** schemas `admin, marketdata, strategy, backtest` exist; roles
`ay_marketdata, ay_strategy, ay_backtest` exist; the privilege query prints
`t|f` (backtest **can** read marketdata, strategy **cannot**).

**Fresh-volume rebuild (the v1 bug-class killer):**

```powershell
.\ay.ps1 reset-db          # down, DROP VOLUMES, re-up; flyway rebuilds all
.\ay.ps1 status
```

**PASS when:** the stack returns to all-healthy and the three checks above
still pass. Run `reset-db` twice if you want the spec-literal test.

---

## 6. Backups: dump, rotation, failure alert, restore (5 min)

```powershell
.\ay.ps1 backup
Get-ChildItem -Recurse backups
```

**PASS when:** `backups\manual\<timestamp>\` appears on the **Windows**
filesystem containing `marketdata.dump`, `strategy.dump`, `backtest.dump`.

**Failure alert (ntfy):** point the alert at a throwaway listener and force a
failure:

```powershell
$resp = "SYSTEM:printf 'HTTP/1.1 200 OK\r\n\r\n'"
docker run -d --name ntfy-stub --network arthayantra_default alpine/socat:1.8.0.3 -v TCP-LISTEN:8099,fork,reuseaddr $resp
docker compose -f deploy/docker-compose.yml --env-file .env exec -e PGHOST=no-such-host -e ARTHA_NTFY_TOPIC=test-topic -e ARTHA_NTFY_URL=http://ntfy-stub:8099 db-backup /usr/local/bin/backup.sh manual
docker logs ntfy-stub 2>&1 | Select-String 'POST|Title'
docker rm -f ntfy-stub
```

**PASS when:** the backup run exits non-zero printing `[backup] FAILED ...`
and the stub log shows `POST /test-topic` with
`Title: ArthaYantra backup FAILED`.

**Restore drill:** restore is designed for disaster-recovery onto a
fresh-schema database (Flyway recreates structure, then data is loaded).
Reset the DB first, then restore:

```powershell
.\ay.ps1 reset-db
$dump = (Get-ChildItem backups\manual -Recurse -Filter marketdata.dump | Select-Object -First 1).FullName
.\ay.ps1 restore $dump
```

**PASS when:** it ends with `[ay] restore complete`. (`reset-db` ensures the
schema is freshly created by Flyway with no existing data, so the
`--data-only` restore has no duplicate-key conflicts.)

---

## 7. Secrets hygiene (3 min)

**Planted-secret block:**

```powershell
Set-Content scratch.txt 'aws_access_key_id = "AKIAW7Q2QXKZ3T4B6MJN"'   # fake test key - gitleaks:allow
git add scratch.txt
git commit -m "should be blocked"        # MUST fail on the gitleaks hook
git reset HEAD scratch.txt; Remove-Item scratch.txt
```

**PASS when:** the commit is refused with `leaks found: 1`.

**Log masking:** prove secrets can't leak into service logs:

```powershell
docker logs ay-edge-gateway 2>&1 | Select-String 'argon2|password=' | Select-Object -First 5
```

**PASS when:** nothing shows a real hash/password — at most masked forms like
`$argon2***` or `password=***`. (The masking rules themselves are pinned by
`LogMaskerTest`.)

---

## 8. Dev-tools UIs (3 min)

- **Adminer** — open http://127.0.0.1:8085 → System *PostgreSQL*, Server
  `timescaledb`, Username `artha`, Password = contents of
  `deploy\secrets\postgres_password`, Database `artha`. **PASS when** you can
  browse the `marketdata/strategy/backtest` schemas.
- **RedisInsight** — open http://127.0.0.1:5540 → add database, host
  `redis`, port `6379`. **PASS when** you can see keys `kite:session:status`,
  `ticks:last`, and `artha:session:*` (your login session).

---

## 9. Tier-2 host-run inner loop (4 min)

**What you're proving:** the docs/dev-setup.md T2 promise — a host-run
service connects to the *same* compose Redis published on loopback.

```powershell
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
.\mvnw.cmd -ntp -pl services/market-data-service spring-boot:run "-Dspring-boot.run.profiles=dev,mock" "-Dspring-boot.run.arguments=--server.port=18081"
# in a SECOND terminal:
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18081/actuator/health
```

**PASS when:** health returns `{"status":"UP",...}`. Ctrl+C the first
terminal when done.

---

## 10. Build, tests, lint — the full local gate (5 min)

```powershell
.\mvnw.cmd -ntp verify
```

**PASS when:** `BUILD SUCCESS` with 0 failures across all modules
(common-web core/servlet, market-calendar, edge-gateway incl. Testcontainers
ITs, market-data-service incl. the Redis pipeline IT) and the JaCoCo line
`All coverage checks have been met.` for both services.

Pre-commit hooks standalone:

```powershell
python -m pre_commit run --all-files
```

**PASS when:** `gitleaks` and `maven checkstyle` both pass.

---

## 11. CI on GitHub (observation only)

Open the PR's **Checks** tab
(https://github.com/prashantm912/artha-yantra-2/pull/1):

- `ci-java / gitleaks` and `ci-java / build-test` green; `build-images`
  builds both service images (GHCR push happens only on `main`).
- `ci-migrations / flyway` green — it migrates the merge-base's migrations,
  validates the PR's files against that database (checksum-drift check), then
  rebuilds everything from an empty volume.

**Drift-check sanity (optional, local):** edit one character in
`deploy/flyway/admin/V001__roles_and_schemas.sql`, run
`.\ay.ps1 up` → `docker logs ay-flyway-init` shows a **checksum mismatch
error** instead of applying. Revert the edit afterwards (`git checkout -- deploy/flyway`).

**Owner one-time action:** GitHub → Settings → Branches → protect `main`
(require PRs + green checks, disable force-push/direct push).

---

## Troubleshooting quick refs

| Symptom | Cause / fix |
|---|---|
| `PKIX path building failed` in any Maven run | new terminal (MAVEN_OPTS user var), or set `$env:MAVEN_OPTS='-Djavax.net.ssl.trustStoreType=Windows-ROOT'` |
| `gitleaks` not found / `gitleaks version` fails in section 0 | winget added it to the PATH of **new** shells only; the pre-commit hook resolves it regardless via `tools/precommit/run-gitleaks.py`. For the bare `gitleaks` CLI, open a new terminal or run `$env:Path=[Environment]::GetEnvironmentVariable('Path','User')+';'+$env:Path` |
| Login always 401 with the right password | `$` in `.env` PHC not escaped as `$$` — check `docker exec ay-edge-gateway printenv ARTHA_OWNER_PASSWORD_HASH` starts with `$argon2id$` |
| `429 AUTH_RATE_LIMITED` during testing | `docker exec ay-redis redis-cli del login:cooldown:<your-ip>` or wait 15 min |
| Compose pull hangs forever | don't run two pulls of the same image concurrently; `Ctrl+C` and `docker pull <image>` once |
| Probe `timeout: 0 frames` | is `ay-market-data-service` healthy? `.\ay.ps1 logs market-data-service` |
| `ay backup` says schemas absent | flyway-init hasn't run on this volume — `.\ay.ps1 reset-db` |
| `ay restore` fails / `market-data-service` logs `invalid INSERT on root table of hypertable "_hyper_1_X_chunk"` after a restore attempt | failed `--clean` restore leaves `ts_insert_blocker` on chunk tables. Fix: `docker exec ay-timescaledb psql -U artha -d artha -c "SELECT c.schema_name\|\|'.'||c.table_name FROM _timescaledb_catalog.chunk c"` to list chunks, then `DROP TRIGGER IF EXISTS ts_insert_blocker ON _timescaledb_internal._hyper_X_Y_chunk;` for each affected chunk. Use `.\ay.ps1 restore` (which now wraps with `timescaledb_pre_restore()`) to avoid recurrence. |
