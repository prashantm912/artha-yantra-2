# Stage C — Manual Testing Guide (Strategy Engine MVP, Phases 18–27)

Everything Stage C built, testable by hand. The whole pass takes ~40–55 minutes
in **mock mode with zero Kite credentials** — that is itself a Stage-C / MVP
acceptance criterion (§C-2.28). A short live-mode appendix (moved here from the
Stage B guide) covers the parts that need real Kite keys; you will mint a
**brand-new 2.0 Kite API key/secret** for that.

The Stage-C MVP statement is one sentence:

> a published YAML strategy → evaluated by the signal engine on live mock candles
> → a signal persisted with its per-indicator score breakdown → pushed over the
> gateway STOMP socket → visible on the Angular `/signals` page.

Sections 4 and 6 are that statement, end to end.

> **Shell labels — read this first.** Every fenced block is tagged either
> `powershell` (run from a PowerShell prompt at the repo root
> `C:\Trading\ArthaYantra\artha-yantra-2`) or `bash` (run from **Git-Bash/WSL** —
> the `curl`, cookie-jar, `grep`, `awk` and here-doc syntax is POSIX, it will
> NOT work verbatim in PowerShell). Don't mix them.

> **Machine notes (carried from Stage A/B):**
> - Maven: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` must be in
>   the environment (TLS-intercepting AV).
> - `.env` Argon2id hash: every `$` escaped as `$$`.
> - Running `docker compose -f deploy/docker-compose.yml ...` directly: always add
>   `--env-file .env`, or compose blanks `ARTHA_OWNER_PASSWORD_HASH` and login
>   breaks. `.\ay.ps1` always passes it.
> - The owner password in this setup is `MyPassword123` — substitute yours
>   wherever a command shows it. (A literal placeholder will 401; use the real
>   value.)
> - **Git-Bash `/tmp` vs Windows `python`:** Git-Bash writes `/tmp/...` to its own
>   temp dir, but the Windows `python.exe` resolves `/tmp/...` to `C:\tmp\...` —
>   a different place. So **never have python OPEN a `/tmp` file**
>   (`python -c "open('/tmp/x')"` fails); pipe instead: `cat /tmp/x | python -c
>   "...sys.stdin.read()..."`. (`curl`, `cat`, `grep`, `sed` are Git-Bash and read
>   `/tmp` fine — only Windows python is confused by it.)
> - **Everything under `/api/v1/**`, `/v3/api-docs`, `/docs/**` and
>   `/swagger-ui.html` requires the session** — send `-b /tmp/ay.jar` on every
>   curl (the gateway returns 401 otherwise), or, in a browser, log into the SPA
>   first so the tab carries the cookie.

---

## 0. Bring-up + login (5 min)

```powershell
.\ay.ps1 up dev-tools
.\ay.ps1 status
```

**PASS when:** all containers `(healthy)` — note the two new Stage-C services
`ay-strategy-signal-service` and `ay-frontend-ui` — and `ay-flyway-init`
`Exited (0)`.

Verify the strategy-schema migrations landed:

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select version, description from strategy.flyway_schema_history order by installed_rank"
```

**PASS when:** the list ends `... 002 strategies versions audit, 003 signals`
(plus the `R__seed_sample_strategy` repeatable).

Log in (Git-Bash). The cookie jar `+` CSRF token are reused by every mutating
call below — POST/PUT/DELETE need the `X-XSRF-TOKEN` header:

```bash
curl -s -c /tmp/ay.jar -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'password=MyPassword123' -i | head -3
XSRF=$(grep XSRF-TOKEN /tmp/ay.jar | awk '{print $NF}')
echo "XSRF=$XSRF"
```

**PASS when:** `204 No Content`, a `SESSION` cookie is set, and `$XSRF` is a
non-empty UUID. (5 failed logins/min locks you out ~briefly — wait 60 s if you
typo. The Playwright suite resets this counter per test; by hand, just pause.)

---

## 1. Phase 18 — `strategy-schema/v1` validate + canonicalization (5 min)

The schema is **frozen at this gate**. The registry's `/validate` endpoint is the
hands-on surface: it parses the YAML (SnakeYAML `SafeConstructor`, 256 KB cap),
checks it against the JSON Schema, runs the semantic rules, and returns the
**canonical checksum** (sorted keys + normalized numbers + SHA-256).

Validate a good config and a deliberately broken one (Git-Bash):

```bash
# a minimal valid strategy -> valid:true + a checksum
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  --data '{"config":"schema: strategy-schema/v1\nid: probe-ok\nname: Probe\nversion: 1.0.0\nuniverse: { mode: explicit, instruments: [ { exchange: NSE, tradingsymbol: RELIANCE } ] }\ntimeframes: { primary: 1m }\nindicators: [ { name: RSI, alias: r, timeframe: 1m, params: { period: 14 }, weight: 1.0, normalize: { type: step, bands: [ { score: 1.0 } ] } } ]\nentry_rules: { direction: long, gate: { all: [ \"close > 1\" ] }, scoring: { threshold: 0.05 } }\nexit_rules: [ { type: stop_loss, params: { basis: premium_pct, value: 50 } } ]\nrisk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1, session: { style: intraday } }"}' \
  http://127.0.0.1:8080/api/v1/strategies/validate

# an unknown normalize type -> valid:false with a D8 envelope detail
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  --data '{"config":"schema: strategy-schema/v1\nid: probe-bad\nname: Bad\nversion: 1.0.0\nuniverse: { mode: explicit, instruments: [ { exchange: NSE, tradingsymbol: RELIANCE } ] }\ntimeframes: { primary: 1m }\nindicators: [ { name: RSI, alias: r, timeframe: 1m, params: { period: 14 }, weight: 1.0, normalize: { type: nonsense } } ]\nentry_rules: { direction: long, gate: { all: [ \"close > 1\" ] }, scoring: { threshold: 0.05 } }\nexit_rules: [ { type: stop_loss, params: { basis: premium_pct, value: 50 } } ]\nrisk: { position_sizing: { method: fixed_quantity, params: { quantity: 1 } }, max_positions: 1, session: { style: intraday } }"}' \
  http://127.0.0.1:8080/api/v1/strategies/validate
```

**PASS when:** the first returns `{"valid":true,"errors":[],"warnings":[]}`; the
second returns `"valid":false` and the errors name the offending field
(`/indicators/0/normalize` — the allowed `type` constants `step`/`linear`/
`direction`/`rsi_momentum`) — never a stack trace. (`/validate` reports validity
only; the canonical 64-hex SHA-256 **checksum** is shown when you create or
version a strategy — see §2.)

> The frozen schema lives at `libs/strategy-schema/src/main/resources/strategy-schema/strategy-schema-v1.json`.
> The freeze obligations (the `slippage_bps`, `fees{}`, `objective.fold_aggregation`,
> `walk_forward`, `scoring.{optional_min_score, optional_gate_margin}` keys and the
> A7 additions: `1w`, `risk.session.{pre_close_at, fill_timing, exit_intrabar}`,
> indicator `instrument` override, `universe.mode: futures_of_underlying`) are
> present-and-validated now even though their consumers land later — they are
> pinned by the 31-fixture corpus (`mvnw -pl libs/strategy-schema test`).

---

## 2. Phase 21 — Registry lifecycle (D18 state machine) (8 min)

The seed `minimal-ema-crossover` arrives as a **draft**. Walk it through the
full immutable-version lifecycle.

```bash
XSRF=$(grep XSRF-TOKEN /tmp/ay.jar | awk '{print $NF}')
# the seed strategy is present as a draft; grab its id
SID=$(curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/strategies?q=minimal-ema-crossover' \
  | python -c "import sys,json;print(json.load(sys.stdin)['items'][0]['id'])")
echo "SID=$SID"

# detail carries the canonical 64-hex checksum + the version's status
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID" \
  | python -m json.tool | grep -E '"version"|"status"|"checksum"'

# publish the draft (CSRF required) -> status flips to published
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  --data '{}' http://127.0.0.1:8080/api/v1/strategies/$SID/publish; echo

# update -> mints a NEW draft 1.0.1 (the published 1.0.0 stays untouched). Fetch the
# current config, bump the fast-EMA period 9 -> 11, PUT it back. NOTE the `cat | python`
# stdin pattern (never `python open('/tmp/..')` on Windows — see the machine notes).
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID" \
  | python -c "import sys,json;sys.stdout.write(json.load(sys.stdin)['configYaml'])" \
  | sed 's/period: 9/period: 11/' > /tmp/v2.yaml
cat /tmp/v2.yaml | python -c "import sys,json;print(json.dumps({'config':sys.stdin.read(),'versionBump':'patch','notes':'tune fast ema'}))" \
  | curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' --data @- -X PUT http://127.0.0.1:8080/api/v1/strategies/$SID; echo

# the SAME content again -> 409 CONFLICT_NO_CONTENT_CHANGE (checksum match)
cat /tmp/v2.yaml | python -c "import sys,json;print(json.dumps({'config':sys.stdin.read(),'versionBump':'patch','notes':'again'}))" \
  | curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' --data @- -X PUT http://127.0.0.1:8080/api/v1/strategies/$SID; echo

# the versions list shows the lifecycle; diff two versions
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID/versions" | python -m json.tool | grep -E '"version"|"status"'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID/diff?from=1.0.0&to=1.0.1" | python -m json.tool | head -16

# every mutating call wrote an APPEND-ONLY audit row. There is no REST view by design;
# read the trail (and confirm its tamper-evidence) via psql:
docker exec ay-timescaledb psql -U artha -d artha -c \
  "select action, from_version, to_version, actor from strategy.strategy_audit_log where strategy_id = '$SID' order by created_at"
```

**PASS when:** detail shows a 64-hex `checksum`; publish flips `status` to
`published`; the PUT mints draft `1.0.1` (the versions list still shows `1.0.0`
`published`); the SECOND identical PUT returns `409 CONFLICT_NO_CONTENT_CHANGE`;
the diff lists the changed path (`indicators[alias=ema_fast].params.period`,
`9`→`11`); and the audit log carries `CREATE`, `PUBLISH`, `UPDATE_DRAFT`. (The
audit log is **append-only by grant** — even the service role is denied
`UPDATE`/`DELETE` on it; that D18 tamper-evidence is pinned by
`RegistryLifecycleIntegrationTest`.)

**The `index_constituents`-universe publish guard** (422): a strategy whose
universe is an index-membership set cannot publish until Phase 44 lands the
pinning — publishing one returns `422 STRATEGY_UNIVERSE_UNSUPPORTED`. (Covered by
`RegistryLifecycleIntegrationTest`; no need to craft one by hand.)

---

## 3. Phase 22 — `index_constituents` accrual (4 min)

Append-only point-in-time index membership, resolved over REST.

```bash
# current NIFTY 100 membership (default = latest snapshot) -> {index, asOf, checksum, items[]}
curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/instruments/indices/NIFTY%20100/constituents' \
  | python -c "import sys,json;d=json.load(sys.stdin);print('count:',len(d['items']),'| asOf:',d['asOf'],'| checksum:',d['checksum'][:16]);print('sample:',d['items'][:3])"
# a date BEFORE accrual started -> 404 (the survivorship caveat: the data starts at capture)
curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/instruments/indices/NIFTY%20100/constituents?asOf=2025-01-01' | head -c 170; echo
```

**PASS when:** the list returns ~50 symbols (the committed
`nse-constituents/ind_nifty100list.csv` fixture) with a resolved `asOf` and a
64-hex `checksum` over the canonical ordered list; a date before accrual started
is a clean `404` (`no snapshot of 'NIFTY 100' as of ...`). A second accrual run
never mutates prior rows (append-only — verified by
`IndexConstituentsIntegrationTest`); the survivorship-bias caveat is documented in
the fetcher Javadoc, and the live NSE fetcher stays gated on source verification.

---

## 4. Phases 19/20/23 — Live signal engine + the MVP statement (10 min)

Publish a strategy that **fires deterministically** against the mock feed (a step
catch-all normalize scores 1.0 every bar once RSI has warmed up), then watch a
signal land with its full per-indicator breakdown.

Write the config and create+publish it (Git-Bash):

```bash
cat > /tmp/smoke.yaml <<'YAML'
schema: strategy-schema/v1
id: manual-smoke-entry
name: "Manual Smoke Entry"
version: 1.0.0
universe:
  mode: explicit
  instruments:
    - { exchange: NSE, tradingsymbol: RELIANCE }
timeframes: { primary: 1m }
indicators:
  - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
      normalize: { type: step, bands: [ { score: 1.0 } ] } }
entry_rules:
  direction: long
  gate:
    all:
      - "close > 1"
  scoring: { threshold: 0.05 }
exit_rules:
  - { type: stop_loss, params: { basis: premium_pct, value: 50 } }
risk:
  position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
  max_positions: 1
  session: { style: intraday }
YAML

# build the request + create, then publish. `cat | python` (stdin) — NEVER
# `python open('/tmp/..')` (Windows python can't read Git-Bash /tmp; see machine notes).
XSRF=$(grep XSRF-TOKEN /tmp/ay.jar | awk '{print $NF}')
MID=$(cat /tmp/smoke.yaml \
  | python -c "import sys,json;print(json.dumps({'name':'Manual Smoke Entry','config':sys.stdin.read()}))" \
  | curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' --data @- http://127.0.0.1:8080/api/v1/strategies \
  | python -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
echo "MID=$MID"
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  --data '{}' http://127.0.0.1:8080/api/v1/strategies/$MID/publish; echo

# poll for the signal (the engine warms RSI from market-data history, then scores
# the next 1m bar close — usually within ~60-90 s)
for i in $(seq 1 20); do
  N=$(curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/signals' | grep -o '"id"' | wc -l)
  echo "poll $i: $N signal(s)"; [ "$N" -gt 0 ] && break; sleep 8
done
# inspect the newest signal + its persisted score breakdown
curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/signals?limit=1' | python -m json.tool | head -60
```

**PASS when:** within ~2 minutes `/signals` returns a `RELIANCE` `ENTRY` signal
carrying its immutable `strategyVersionId` (the engine pinning — the resolved
slug/version/checksum triple rides the WS channel payload, §6) and a
`scoreBreakdown` whose `composite` equals `Σ contributions / weightDenominator`
(every decimal is an exact-decimal STRING), with the `rsi_1m` entry
`optional:false` (REQUIRED) and the gate leaf `close > 1` shown with its operand
value. That is the live half of the golden-parity pair (the replay half lands
Stage D); determinism is pinned by the committed golden vectors
(`mvnw -pl services/strategy-signal-service test -Dtest=SignalEngineIntegrationTest`).

> On a **freshly-booted** stack the RSI(14) warm-up needs ~14 one-minute bars; if
> no signal has appeared after a couple of minutes, give the mock feed a few more
> minutes to accrue history (or just continue — it fires once warm).

> **Engine internals you can watch:** the candle-close bus and the engine's own
> metrics.
> ```bash
> # 1m candle closes flowing on Redis (Ctrl-C or it ends in ~70 s)
> timeout 70 docker exec ay-redis redis-cli psubscribe 'candles.1m.NSE.RELIANCE'
> # engine eval + emit counters
> docker exec ay-strategy-signal-service wget -qO- http://127.0.0.1:8082/actuator/prometheus | grep -E 'ay_signal_eval_duration_seconds_count|ay_signals_emitted_total'
> ```

---

## 5. Phase 24 — OpenAPI contracts (3 min)

The three running services' specs are committed under `contracts/` and
**diff-gated** in CI (`openapi-diff`); the Angular client types are generated from
them (`openapi-typescript`) and must compile under `tsc --strict`. The aggregated
Swagger UI is a **mock-profile** convenience:

```powershell
# the aggregated Swagger UI (mock profile only). Log into the SPA first (sec. 6) so the
# browser carries the SESSION cookie, THEN open this in the SAME browser:
start http://127.0.0.1:8080/swagger-ui.html
```

```bash
# the gateway's own spec + each service's proxied api-docs (AUTHENTICATED -> send the cookie)
curl -s -b /tmp/ay.jar http://127.0.0.1:8080/v3/api-docs | head -c 120; echo
curl -s -b /tmp/ay.jar http://127.0.0.1:8080/docs/strategy-signal/v3/api-docs | head -c 120; echo
curl -s -b /tmp/ay.jar http://127.0.0.1:8080/docs/market-data/v3/api-docs | head -c 120; echo
```

**PASS when:** the api-docs endpoints return OpenAPI 3.1 JSON
(`{"openapi":"3.1.0",...}`); without the cookie they are `401` (the A.2.3
deny-by-default). The Swagger UI (once you are logged in) lists edge-gateway,
market-data-service and strategy-signal-service. The committed specs match the
controllers — enforced by each `ContractCaptureTest` and the `ci-contracts`
workflow.

---

## 6. Phases 25/26 — The Angular SPA (10 min)

Open the app **through the gateway** (the sole ingress, same origin, zero CORS):

```powershell
start http://127.0.0.1:8080/
```

Walk these by hand:

1. **Login page.** You land on `/login` (the auth guard redirects anonymous
   users). Deep-link `http://127.0.0.1:8080/signals` directly → it bounces to
   `/login?returnUrl=%2Fsignals` and, after sign-in, returns you to `/signals`.
   Sign in with the owner password.
   **PASS:** the shell renders; the `SESSION` cookie is `HttpOnly` + `SameSite=Strict`.
2. **Signals page.** The strategy you published in §4 streams a live `RELIANCE`
   `ENTRY` row into the virtualized table (no reload). The top-bar WS pill reads
   **WS connected** and the market clock ticks in IST.
   **PASS:** rows arrive live; the pill is green; prices are tabular, exact-decimal.
3. **Reasoning breakdown.** Click the signal row. The right pane renders the
   frozen C-2.6 contract: the gate checklist with operand values, the stacked
   per-indicator contribution bar (`w·s`), and the composite-vs-threshold gauge.
   **PASS:** the panel shows `rsi_1m` (REQUIRED), `close > 1`, the weight
   denominator, and `composite = Σ contributions / weightDenominator`.
4. **WS reconnect.** In another terminal bounce the gateway and watch the pill
   self-heal — **no page reload**, the table re-syncs its REST snapshot:
   ```powershell
   docker restart ay-edge-gateway
   ```
   **PASS:** the pill goes `reconnecting` → `connected` within ~1–2 min (backoff
   with jitter); the table is intact afterwards.
5. **Theme toggle** flips dark/light and persists across reload; **Log out**
   returns you to `/login`.

> Bundle budget (enforced in `angular.json`): initial ≤ 500 KB gz. The last build
> was 457 KB raw / 109 KB transfer with the signals page lazy.

---

## 7. Phase 27 — Playwright E2E (optional, 3 min to kick off)

The regression net that drives §4 + §6 automatically. It reuses a running stack
(or boots one) and is credential-free in CI:

```powershell
cd e2e
$env:E2E_OWNER_PASSWORD = 'MyPassword123'   # match your .env hash; CI uses the committed pair
npx playwright test --reporter=list
cd ..
```

**PASS when:** 7/7 green — login journey (deep-link, cookie flags, wrong/right
password, axe), the MVP signal with its breakdown, signals-page axe, and the
WS-reconnect chaos test. The same suite runs as `ci-e2e` on every PR.

---

## Appendix — Live mode (requires real Kite credentials)

> Moved here from the Stage B guide: you will mint a **brand-new 2.0 Kite API
> key/secret** after Stage C, so live mode is exercised from here on.

1. Put the **brand-new 2.0** API key/secret (A6 — never the v1 pair) into
   `deploy/secrets/kite_api_key` and `deploy/secrets/kite_api_secret`
   (single line; a trailing newline does not matter). `artha_master_key` was
   already generated by `ay up`.
2. `SPRING_PROFILES_ACTIVE=live` in `.env`, then
   `docker compose -f deploy/docker-compose.yml --env-file .env up -d`.
   The service **fails fast** if any of the three secret files is blank —
   that is the D13 design, not a bug.
3. Morning ritual: `GET /api/v1/auth/kite/login-url` → complete Zerodha 2FA →
   the callback exchanges + AES-GCM-persists the token. `GET
   /api/v1/auth/kite/status` flips to `CONNECTED` with `tokenValidUntil`
   ~06:00 IST next day. `docker compose restart market-data-service` —
   status returns CONNECTED **without** re-login (decrypt-and-resume).
4. Within ~10 s of the first LIVE probe the **contract canary** runs once for
   the day — `GET /api/v1/auth/kite/status` then carries `lastContractCheck`
   + an empty `contractDrift`; drift would land on your ntfy topic.
5. Run the **minute-depth probe** from `docs/runbook-notes.md` once and
   record the outcome (feeds amendment A3).
6. **Stage-C live check:** with a real session, publish the §4 strategy against a
   live-fed instrument — the signal path (engine → STOMP → `/signals`) is
   transport-identical to mock; only the candle source changed. Secrets stay in
   Docker secret files only (absent from `docker inspect`).
