---
name: run-artha-yantra
description: Run, start, build, restart, screenshot, or verify the ArthaYantra application — Docker Compose stack (edge-gateway, market-data-service, strategy-signal-service, backtest-service, optimizer-service, frontend-ui) in live or mock mode. Use when asked to run the app, rebuild and restart services, take a screenshot of the UI, or confirm the stack is healthy.
---

# run-artha-yantra

ArthaYantra is a Dockerised multi-service trading platform. The sole ingress is
`edge-gateway` on `http://localhost:8080`. The Angular SPA, backend services, and
Postgres/Redis all run inside Docker Compose. Drive it with the Playwright MCP
(`mcp__playwright__browser_*`) for UI flows or `curl` for API smoke-tests.

All paths are relative to repo root (`C:\Trading\ArthaYantra\artha-yantra-2`).

---

## Prerequisites

- Docker Desktop running
- `.env` at repo root with `SPRING_PROFILES_ACTIVE=live` (or `mock`)
- `ARTHA_OWNER_PASSWORD_HASH` set in `.env`
- Maven cached at `~/.m2/wrapper/dists/apache-maven-*/`
- Node 22 + `npm` in PATH

---

## Build (when source has changed)

Run from repo root. Never bare `-pl` on a leaf — always use `-am`.

```powershell
# Java services (all four in one reactor pass)
$MVN = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if (-not $MVN) { $MVN = ".\mvnw.cmd" }
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
& $MVN -pl services/market-data-service,services/edge-gateway,services/strategy-signal-service,services/backtest-service -am package -DskipTests

# Angular frontend
Push-Location frontend-ui
npm run build
Pop-Location
```

Then rebuild Docker images:

```powershell
$env:ARTHA_DB_NAME = 'artha'; $env:ARTHA_REDIS_DB = '0'   # live
# $env:ARTHA_DB_NAME = 'artha_mock'; $env:ARTHA_REDIS_DB = '1'  # mock
docker compose -f deploy\docker-compose.yml --env-file .env build `
  edge-gateway market-data-service strategy-signal-service backtest-service frontend-ui
```

optimizer-service is Python (FastAPI) — rebuild only if `services/optimizer-service/` changed:
```powershell
docker compose -f deploy\docker-compose.yml --env-file .env build optimizer-service
```

---

## Start / restart

Use `ay.ps1` — it always passes `--env-file` and derives `ARTHA_DB_NAME`/`ARTHA_REDIS_DB`
from `SPRING_PROFILES_ACTIVE`. Raw `docker compose` without `--env-file` silently blanks
vars and writes mock data to the live DB.

```powershell
# Start everything (or recreate changed containers)
.\ay.ps1 up

# Restart specific services after rebuild
$env:ARTHA_DB_NAME = 'artha'; $env:ARTHA_REDIS_DB = '0'
docker compose -f deploy\docker-compose.yml --env-file .env up -d `
  edge-gateway market-data-service strategy-signal-service backtest-service frontend-ui
```

---

## Health check

```bash
# All containers healthy?
docker ps --format "table {{.Names}}\t{{.Status}}" | grep ay-

# Gateway up?
curl -s http://localhost:8080/actuator/health
# → {"status":"UP","groups":["liveness","readiness"]}
```

---

## Drive: API smoke-test (curl / bash)

```bash
COOKIE=$(mktemp)
# 1. Login (204 = success)
curl -sc "$COOKIE" -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" -d '{"password":"<owner-password>"}' -o /dev/null
# 2. Seed XSRF cookie
curl -sb "$COOKIE" -c "$COOKIE" http://localhost:8080/api/v1/auth/me -o /dev/null
XSRF=$(grep XSRF "$COOKIE" | awk '{print $NF}')
# 3. Authenticated GET
curl -sb "$COOKIE" -H "X-XSRF-TOKEN: $XSRF" http://localhost:8080/api/v1/strategies
rm "$COOKIE"
```

---

## Drive: Browser UI (Playwright MCP)

Load tool schemas first:
```
ToolSearch: select:mcp__playwright__browser_navigate,mcp__playwright__browser_take_screenshot,mcp__playwright__browser_fill_form,mcp__playwright__browser_click,mcp__playwright__browser_snapshot
```

Then:
```js
// Navigate
mcp__playwright__browser_navigate({ url: "http://localhost:8080" })
// → redirects to /login

// Login
mcp__playwright__browser_fill_form({
  fields: [{ target: "input[placeholder='Owner password']", name: "password", type: "textbox", value: "<owner-password>" }]
})
mcp__playwright__browser_click({ target: "button:has-text('Sign in')", element: "Sign in button" })
// → navigates to /dashboard

// Screenshot
mcp__playwright__browser_take_screenshot({ type: "png", filename: "ay-dashboard.png" })

// Accessibility snapshot (better than screenshot for finding elements)
mcp__playwright__browser_snapshot({})
```

Key routes after login: `/dashboard`, `/signals`, `/strategies`, `/options`, `/options-oi`,
`/futures-oi`, `/oi-spurt`, `/big-oi`, `/charts`, `/backtests`, `/jobs`, `/watchlists`.

---

## Gotchas

- **`ay.ps1 up` vs raw `docker compose`** — always use `ay.ps1` or pass `--env-file .env` manually
  AND export `ARTHA_DB_NAME`/`ARTHA_REDIS_DB` first. Raw compose blanks them → mock writes to live DB.
- **Edge-gateway is the ONLY ingress** — `http://localhost:8080`. The frontend nginx container
  (port 80) is internal-only (not host-exposed). Don't try to hit it directly.
- **XSRF pattern** — login (POST → 204 + sets session cookie) then a GET to `/api/v1/auth/me`
  seeds the `XSRF-TOKEN` cookie. Every mutating request needs `X-XSRF-TOKEN: <value>` header.
- **`-am` is mandatory** — bare `-pl services/<svc> package` skips nested libs (`common-web/servlet`,
  `black76-math`) so the fat JAR silently embeds stale lib code.
- **Windows Maven** — `./mvnw` tries to download Maven; AV intercepts TLS and blocks it.
  Use the cached binary from `~/.m2/wrapper/dists/` + `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`.
- **Frontend hard-reload after rebuild** — browser caches chunks. After a rebuild, Ctrl+Shift+R
  (hard reload) to pick up new JS. Stale cache → old UI or white charts.
- **Market data NOT_FOUND outside market hours** — `/api/v1/market/quote`, `/api/v1/market/oi/chain`
  return 404 when market is closed or instruments haven't been synced. On first live boot:
  `POST /api/v1/instruments/sync` (with XSRF) to populate instrument table.
- **Kite token expires at 06:00 IST** — after expiry the dashboard shows "Ticker: DISCONNECTED".
  Re-login via Kite Connect (Settings → Connect Kite) to refresh.
- **PS 5.1 pipeline kill** — never pipe Maven output into `Select-Object -First N`; kills the
  build mid-run (exit 255). Use `-Last N` or capture to variable.
