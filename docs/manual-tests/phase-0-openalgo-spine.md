# Manual test guide — Phase 0: OpenAlgo integration spine

Branch `feat/openalgo-spine`. Covers the master-plan Phase 0 deliverables (§2 OpenAlgo appliance +
§3 gateway spine + contract tests). PowerShell-first (this box). See plan §16 Phase-0 exit gate.

**What Phase 0 is / is NOT.** Phase 0 *stands up* the OpenAlgo appliance and the anti-corruption
gateway behind the existing ports — it does **NOT** yet route market-data capture through OpenAlgo
(default `artha.marketdata.source.*=kite`). So there is **no OI flowing through OpenAlgo into
TimescaleDB to observe yet** — that is Phase 1 (§4). The gateway/canary are proven by the automated
contract tests; the appliance is what you can run and click here.

## 0. Prerequisites

- Docker Desktop running; repo checked out on branch `feat/openalgo-spine`.
- The pinned OpenAlgo image present (Phase 0 pinned it by digest):
  ```powershell
  docker pull marketcalls/openalgo@sha256:b1bc2ec4fc40a0e32730bab9c4b9dd3a43daefee30453de46885544eab45fdd7
  ```

## 1. Automated spine proof (the §3 gateway + contract tests)

The gateway/wire/canary have no UI surface — their proof is the test suite.

```powershell
Push-Location C:\Trading\ArthaYantra\artha-yantra-2
$mvn = (Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists\apache-maven-*\*\bin\mvn.cmd" | Select-Object -First 1).FullName
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
& $mvn -pl services/market-data-service -am verify
Pop-Location
```
**Expect:** `BUILD SUCCESS`. The new tests run green: `OpenAlgoWireContractTest` (6),
`OpenAlgoMappersTest` (3), `OpenAlgoQuoteGatewayTest` (2), `OpenAlgoHistoricalCandleGatewayTest` (1),
`OpenAlgoContractCanaryIntegrationTest` (4).

## 2. No-regression: the default stack still boots WITHOUT OpenAlgo

```powershell
.\ay.ps1 up
.\ay.ps1 status
```
**Expect:** all core containers `healthy` (timescaledb, redis, market-data-service, …). There is
**no `ay-openalgo`** container — it is opt-in and nothing depends on it, so the default boot is
unchanged. (This is the entry-gate guarantee.)

## 3. Bring up the OpenAlgo appliance (opt-in)

```powershell
.\ay.ps1 up openalgo
.\ay.ps1 status
```
**Expect:** `ay-openalgo` and `ay-openalgo-publish` become `healthy` (allow up to the 60s
`start_period`; first boot runs OpenAlgo's DB migrations). `ay` auto-creates `deploy/openalgo/.env`
(with generated `APP_KEY`/`API_KEY_PEPPER`) and the empty `deploy/secrets/openalgo_api_key`
placeholder on first run.

Verify the health endpoint (through the loopback publisher on :5001):
```powershell
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:5001/health/status | Select-Object -ExpandProperty Content
```
**Expect:** HTTP 200 with a small JSON `{"status":"pass",...}`.

Verify loopback-only (the appliance port is NOT published directly):
```powershell
docker inspect ay-openalgo --format '{{json .NetworkSettings.Ports}}'
```
**Expect:** no host port bindings on the `openalgo` container itself (only `ay-openalgo-publish`
maps `127.0.0.1:5001`).

## 4. Open the OpenAlgo UI + (optional) broker login

Open **http://127.0.0.1:5001** in a browser → OpenAlgo's setup/login page renders. To exercise live
data later you would, in the UI: pick a broker, enter that broker's API key/secret, complete OAuth
(redirect must be `http://127.0.0.1:5001/<broker>/callback` — edit `deploy/openalgo/.env`
`REDIRECT_URL` if not Zerodha), then **generate an OpenAlgo API key** and save it to
`deploy/secrets/openalgo_api_key` (single line, no trailing newline). *Broker login is optional for
Phase 0* — the spine does not consume it until Phase 1.

## 5. (Optional, needs the OpenAlgo API key from step 4) round-trip a quote

```powershell
$key = (Get-Content deploy/secrets/openalgo_api_key -Raw).Trim()
$body = @{ apikey = $key; symbol = "NIFTY"; exchange = "NSE_INDEX" } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing -Method POST http://127.0.0.1:5001/api/v1/quotes -ContentType "application/json" -Body $body | Select-Object -ExpandProperty Content
```
**Expect:** `{"status":"success","data":{...,"ltp":...,"oi":...}}` — note `oi` is present (0 for the
index). This is the wire shape the `OpenAlgoQuoteGateway` maps.

## 6. (Optional) analyzer / sandbox toggle (§17.8)

```powershell
$body = @{ apikey = $key; mode = $true } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing -Method POST http://127.0.0.1:5001/api/v1/analyzer/toggle -ContentType "application/json" -Body $body | Select-Object -ExpandProperty Content
```
**Expect:** the response confirms analyzer (sandbox) mode ON. This is the RUNTIME toggle the
mock-profile coupling will use in Phase 1 (it is NOT an env flag).

## 7. Secret isolation

```powershell
docker inspect ay-market-data-service --format '{{json .Mounts}}'   # should reference openalgo_api_key
docker inspect ay-edge-gateway --format '{{json .Mounts}}'          # should NOT
```
**Expect:** only `ay-market-data-service` mounts `openalgo_api_key`; the broker's own secrets live
only inside `ay-openalgo`. (In Phase 0 the key is mounted but unread — capture still routes via Kite.)

## 8. Persistence

```powershell
docker compose -f deploy/docker-compose.yml --env-file .env --profile openalgo restart openalgo
```
**Expect:** after restart the OpenAlgo config / generated API key survive (the `openalgo-data` volume
holds `/app/db`). Note `ay reset-db` drops volumes — you re-log-in the broker afterward.

## 9. Teardown

```powershell
.\ay.ps1 down
```
Stops all project containers (volumes kept).

---

### Pass criteria (Phase-0 exit gate)
- [ ] `mvnw -pl services/market-data-service -am verify` green incl. the OpenAlgo tests.
- [ ] Default `ay up` boots green with **no** `ay-openalgo` (no regression).
- [ ] `ay up openalgo` → `ay-openalgo` healthy; `/health/status` returns 200; port loopback-only.
- [ ] (If broker logged in) a NIFTY quote round-trips with `oi` present.
- [ ] Only market-data-service mounts the OpenAlgo API key.
