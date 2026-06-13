# Stage D — Manual Testing Guide (Backtesting + Optimization, Phases 28–34)

Everything Stage D built, testable by hand on the **mock stack with zero Kite
credentials** (the Stage-D testing posture, plan §15.2). The whole pass takes
~45–60 min. Stage D adds two services — `backtest-service` (Java, :8083) and
`optimizer-service` (Python/FastAPI/Optuna, :8084) — plus the `libs/black76-math`
hoist. The optimizer never evaluates a strategy itself: it proposes parameter
vectors that backtest-service scores through the **same engine JAR as live** (D6).

This guide is also the **Stage-D exit-gate walk** (`PHASE_GATES.md` Phase-3 row):
each section ends with the PASS criterion that ticks a checklist box. **It was
walked end-to-end against the running mock stack on 2026-06-13**; the commands and
response shapes below are the real ones.

> **Shell labels — read this first.** Every fenced block is tagged `powershell`
> (PowerShell at repo root `C:\Trading\ArthaYantra\artha-yantra-2`) or `bash`
> (Git-Bash/WSL — POSIX `curl`/cookie-jar/`grep`/here-doc; will NOT work in
> PowerShell). Don't mix them.

> **Prerequisites (one-time on this machine):**
> - **`jq`** (the bash blocks parse JSON with it): `winget install --id jqlang.jq
>   -e --source winget`. Open a fresh shell afterwards so it lands on `PATH`.
> - **PowerShell execution policy** blocks `.\ay.ps1` by default. Either run
>   `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` once, or invoke it as
>   `powershell -ExecutionPolicy Bypass -File .\ay.ps1 <args>` each time.

> **Machine notes (carried from Stage A/B/C):**
> - Maven: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` in the
>   environment (TLS-intercepting AV blocks the wrapper download — build with the
>   cached `mvn`, see §0). The optimizer image build trusts the same AV CA via
>   `deploy/dev-certs/` (conditional layer; a no-op in CI/prod where it's empty).
> - `.env` Argon2id hash: every `$` escaped `$$`.
> - `docker compose -f deploy/docker-compose.yml ...` directly: always add
>   `--env-file .env` or compose blanks the owner hash and login 401s. `.\ay.ps1`
>   always passes it.
> - Owner password in this setup is `MyPassword123` — substitute yours.
> - Everything under `/api/v1/**` needs the session cookie — send `-b /tmp/ay.jar`
>   on every curl; mutating calls also need `-H "X-XSRF-TOKEN: $XSRF"`.
> - **Mock data is real-time + rolling.** The mock feed accrues candles from boot,
>   so there is no fixed historical window — §0 *derives* the covered window into
>   `$FROM`/`$TO` rather than hardcoding dates, and back-fills the daily benchmark
>   history the regime pre-flight needs.

---

## 0. Build + bring-up + login (10 min)

Build the Stage-D Java jars (full reactor + `-am` so the nested libs —
`common-web/servlet`, `black76-math`, `strategy-engine` — are rebuilt, never a
stale `.m2` copy):

```bash
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/backtest-service,libs/black76-math -am package -DskipTests
```

```powershell
.\ay.ps1 up dev-tools      # or: powershell -ExecutionPolicy Bypass -File .\ay.ps1 up dev-tools
.\ay.ps1 status
```

**PASS when:** all containers `(healthy)` — note the two new Stage-D services
`ay-backtest-service` (:8083) and `ay-optimizer-service` (:8084) — and
`ay-flyway-init` `Exited (0)`. The `dev-tools` profile is required below (it
forwards `market-data-service:8081` on host loopback for the benchmark backfill).

Confirm the backtest schema migrations landed (jobs + runs/trades + optimization
trials):

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select version, description from backtest.flyway_schema_history order by installed_rank"
```

**PASS when:** the list ends `... 002 jobs, 003 runs trades, 004 optimization trials`.

Log in + grab a published strategy (Git-Bash). The cookie jar + CSRF token are
reused below:

```bash
curl -s -c /tmp/ay.jar -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'password=MyPassword123' -o /dev/null -w "login HTTP:%{http_code}\n"
XSRF=$(grep XSRF-TOKEN /tmp/ay.jar | awk '{print $NF}')
SID=$(curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/strategies?status=published&limit=1' | jq -r '.items[0].id')
VER=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID" | jq -r '.currentVersion // .version')
SYM=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID" | jq -r '.config.universe.instruments[0].tradingsymbol')
echo "strategy=$SID version=$VER primary=$SYM xsrf=$XSRF"
```

**Derive the covered backtest window** (the mock feed only has the last few
trading days; pick the fully-covered ones — 375 1m bars = a full NSE session):

```bash
DAYS=$(docker exec ay-timescaledb psql -U artha -d artha -tA -c \
  "select (bucket at time zone 'Asia/Kolkata')::date from marketdata.candles where tradingsymbol='$SYM' and interval='1m' group by 1 having count(*)>=375 order by 1")
FROM=$(echo "$DAYS" | head -1)
TO=$(date -d "$(echo "$DAYS" | tail -1) +1 day" +%F)   # `to` is exclusive
echo "FROM=$FROM TO=$TO  (full days: $(echo "$DAYS" | wc -l))"
```

**Back-fill the daily benchmark history** the regime pre-flight requires (guard 6
needs ~272 daily benchmark sessions before the window; a fresh mock stack has
none). The cache-first GET auto-fetches + persists from the deterministic mock
historical gateway. **Note the base path `/api/v1/market/candles`** and that this
hits `market-data-service` directly on the dev-tools loopback `:8081`:

```bash
curl -s "http://127.0.0.1:8081/api/v1/market/candles?exchange=NSE&tradingsymbol=NIFTY%2050&interval=1d&from=2025-01-01T00:00:00%2B05:30&to=${FROM}T00:00:00%2B05:30&limit=5" \
  -o /dev/null -w "benchmark backfill HTTP:%{http_code}\n"
docker exec ay-timescaledb psql -U artha -d artha -tA -c \
  "select count(*) from marketdata.candles where tradingsymbol='NIFTY 50' and interval='1d'"
```

**PASS when:** login `200`/`204`, `$XSRF` a UUID, `$SID`/`$VER`/`$SYM` populated,
`$FROM`/`$TO` a 3+ trading-day window, and the NIFTY 50 1d count is **≥ 272**.

---

## 1. Phase 28 — jobs spine + crash-safe dispatch (6 min)

Submit a backtest; watch it run through the Postgres `jobs` spine (truth) with
Redis Streams as transport only (ADR D12).

```bash
JID=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/backtests/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"seed\":42}" \
  | jq -r '.jobId')
echo "JID=$JID"
for i in $(seq 1 30); do
  curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/jobs/$JID" | jq -c '{status,progress}'
  curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/jobs/$JID" | jq -e '.status=="completed" or .status=="failed"' >/dev/null && break
  sleep 2
done
# the results endpoints are keyed by the RUN id, exposed as resultRef on the job:
RUN=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/jobs/$JID" | jq -r '.resultRef')
echo "RUN=$RUN"
```

**PASS when:** the POST returns a `jobId` (`202 {jobId,status:"queued"}`); polling
`jobs/{id}` walks `queued → … → completed` with progress 0→100; once completed the
job carries a non-null **`resultRef`** (the backtest run id — used by every
results endpoint below). *(Live WS: log into the SPA and watch `/topic/jobs/{jobId}`
— progress frames stream.)* **→ ticks exit-checklist box 1.**

Crash recovery (optional): `docker restart ay-backtest-service` while a job is
`running` → `StreamBootstrap` re-queues the stale-`running` row on startup via
`XAUTOCLAIM` and it completes exactly once (Redis is never the source of truth).

---

## 2. Phases 29–30 — replay, metrics, A9 fills, parity (8 min)

Fetch the completed run's results + trades (**use `$RUN`, not `$JID`; metrics are
nested under `.metrics`**):

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/results" \
  | jq '{premiumSource, dataHash, seed, metrics:(.metrics|{totalReturn,sharpe,maxDrawdown,winRate,tradeCount})}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/trades" \
  | jq '{count:(.items|length), first:(.items[0]|{side,qty,touchBasis,entryPrice,exitPrice})}'
```

**PASS when:** `.metrics.*` are exact decimal **strings** (NUMERIC, no float drift,
e.g. `"sharpe":"-1.853355"`); `tradeCount` matches the trades count; every closed
trade carries a non-NULL `touchBasis` (`INTRABAR_1M` / `BAR_HL_WORSTOF` /
`CLOSE_EVAL`); `dataHash` is a 64-hex string; `premiumSource` is `NA` for an equity
run. Re-submitting with the same `seed` reproduces an identical `dataHash`
(reproducibility triple `strategyChecksum,seed,dataHash`):

```bash
J2=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/backtests/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"seed\":42}" | jq -r '.jobId')
sleep 6; R2=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/jobs/$J2" | jq -r '.resultRef')
H1=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/results" | jq -r '.dataHash')
H2=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$R2/results" | jq -r '.dataHash')
[ "$H1" = "$H2" ] && echo "REPRODUCIBLE ✓" || echo "MISMATCH ✗"
```

**Parity (D15 headline) + A9 fills** are pinned by Testcontainers ITs (run on the
2-core CI runner on every PR; re-run locally if you want):

```bash
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/backtest-service -am test -Dtest='ParityGoldenIT,FillVectorTest'
```

**PASS when:** parity IT + A9 fill vectors green (futures cost legs, `at_close`,
intra-bar touch, BTST pre-close view). **→ ticks exit-checklist boxes 2 + 5.**

Pre-flight gap: submit a window outside coverage (e.g. `"from":"2020-01-06"`) →

**PASS when:** `422 DATA_GAP` naming the missing windows / context series. **→
ticks box 6.**

---

## 3. Phase 30A — options fidelity + synthetic premium (5 min)

backtest-service stamps `premiumSource` on every run (`NA` for equities,
`SNAPSHOT` / `SYNTHETIC_B76` for options). The fidelity contract + the
`libs/black76-math` byte-identity are pinned by ITs/goldens:

```bash
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl libs/black76-math,services/market-data-service -am test
```

**PASS when:** an options run on mock snapshots records `premiumSource=SNAPSHOT`;
an archive gap → `422 DATA_GAP`; synthetic mode completes flagged `SYNTHETIC_B76`
(never masquerading as snapshot-grade); market-data Greeks goldens stay
byte-identical after the hoist. **→ ticks exit-checklist box 7.**

> Note: a hand-driven options backtest needs an options strategy + a mock
> `options_chain_snapshots` archive for the window. The seeded sample is equity-only
> (`premiumSource=NA`), so the live walk for SNAPSHOT/SYNTHETIC is the IT above.

---

## 4. Phases 31–32 — walk-forward folds, regime, stress guard (6 min)

A plain run is full-window (no folds):

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/folds" | jq 'length'
```

**PASS when:** a plain run's `/folds` is `[]` (and its `sharpe_degradation` /
`fold_metrics` are NULL). Fold structure only forms for fold-context (optimization
/ stress) runs over a window long enough to fit train+test in trading days — on the
~3-day mock window folds stay empty; the fold engine (rolling/anchored, partial-tail
drop, `min_trades` exclusion) is pinned by `WalkForwardExpanderTest` / `FoldsIT`.

Stress guard — submit `purpose:stress_test` over a window overlapping a prior job:

```bash
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/backtests/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"purpose\":\"stress_test\"}" \
  -w "\nHTTP:%{http_code}\n"
```

**PASS when:** `422 WINDOW_CONTAMINATED` listing the intersecting jobIds (the §1
run already covered this window); `GET /api/v1/backtests/stress-window` returns a
clean range; the holdout-reuse counter increments.

---

## 5. Phase 32A — benchmark metrics + Monte Carlo (5 min)

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/results" \
  | jq '{benchmarkCoverage:.metrics.benchmarkCoverage, alpha:.metrics.alpha, beta:.metrics.beta, hasBenchmarkCurve:(.benchmarkCurve!=null), caveats}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/montecarlo?n=1000&seed=7" \
  | jq '{mcSeed, n, trades, equityBands:(.equityBands|keys), riskOfRuin, ci:(.ci|keys)}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$RUN/montecarlo?n=1000&seed=7" | jq '.mcSeed'
```

**PASS when:** when benchmark coverage is present, `.metrics` carries
alpha/beta/informationRatio/excessCagr + a top-level `benchmarkCurve` overlaying
`equityCurve`; when **absent** they are omitted with `benchmarkCoverage:"absent"`
and a caveat (never 0). The Monte Carlo call returns seeded p5/p50/p95
`equityBands` + `riskOfRuin` + `ci`, **persists `montecarlo_summary` on first call
and serves the byte-identical summary on the second** (same `n,seed`); a
zero-closed-trade run → `422`. **→ ticks exit-checklist box 8.**

> On the short mock window the benchmark daily returns rarely overlap the intraday
> equity curve, so `benchmarkCoverage` is typically `absent` here — the NULL-flag
> path. Coverage-present metrics are pinned by `BenchmarkMetricsTest`.

---

## 6. Phase 33/34 — optimizer sweeps, pruning, leaderboard, promote (12 min)

The optimizer is reached through the gateway at `/api/v1/optimizations/**`. The
seeded sample strategy has **no `optimize` block**, so pass an explicit
`parameters` override in the request (a closed-grammar path that resolves against
the version config — here the RSI indicator's `period`).

**6a. Grid sweep (Phase 33).**

```bash
SWID=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/optimizations/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"method\":\"grid\",\"maxTrials\":8,\"seed\":7,\"parameters\":[{\"path\":\"indicators[0].params.period\",\"range\":[8,15],\"step\":1}]}" \
  | jq -r '.jobId')
echo "SWID=$SWID"
for i in $(seq 1 40); do
  curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$SWID" | jq -c '{status,progress,trialsCompleted}'
  curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$SWID" | jq -e '.status=="completed" or .status=="failed"' >/dev/null && break
  sleep 2
done
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWID/trials?state=COMPLETE&limit=20" | jq '.items | length'
```

**PASS when:** every grid point becomes a COMPLETE `optimization_trials` row
(each with `params`, `objectiveValues` and a `backtestRunId`), and a re-run with the
same `seed` reproduces the identical trial sequence.

**6b. TPE + NSGA-II (Phase 34).** TPE (constant-liar, single-objective) and NSGA-II
(`method:"nsga2"`, multi-objective return↑/drawdown↓):

```bash
for M in tpe nsga2; do
  J=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
    -X POST http://127.0.0.1:8080/api/v1/optimizations/run \
    -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"method\":\"$M\",\"maxTrials\":12,\"seed\":1,\"parameters\":[{\"path\":\"indicators[0].params.period\",\"range\":[5,20],\"step\":1}]}" | jq -r '.jobId')
  for i in $(seq 1 40); do curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$J" | jq -e '.status=="completed" or .status=="failed"' >/dev/null && break; sleep 2; done
  echo "== $M =="; curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$J/trials?state=COMPLETE&limit=20" | jq -c '{completed:(.items|length), objs:(.items[0].objectiveValues)}'
done
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWID/best?top=5" \
  | jq '{metric,sort,items:[.items[]|{trialNumber,objective,plateauObjective,neighborCount}]}'
```

**PASS when:** TPE trials carry a single `sharpe`; NSGA-II trials carry **both**
`cagr` and `maxDrawdown` (a Pareto front, never collapsed to one number); the
`/best` leaderboard default sort is **plateau-adjusted** (`plateauObjective` per
row; `?sort=raw` flips to raw objective). **→ contributes to box 3.**

**6c. Fold-fed pruning (S3 gate).** Pruning fires only when trials report enough
per-fold OOS objectives — i.e. a walk-forward window long enough to produce
≥ `n_warmup_folds` (3) folds across ≥ `n_startup_trials` (5) trials. The ~3-day
mock window can't form that many folds, so no trials are `PRUNED` here (correctly —
no false prunes). The pruning decision table (MedianPruner fed by OOS fold medians
only, never train/OOS divergence; S3 defaults `n_startup_trials=5`,
`n_warmup_folds=3`, `n_min_trials=2` from `docs/design/DECISIONS_LOG.md`) is pinned
by `tests/test_pruning.py` and the S3 spike. On a production multi-month window:

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWID/trials?state=PRUNED" | jq '.items | length'
```

**PASS when:** pruned trials (if any) are excluded from `/best` and from
`min`/`mean_minus_std` aggregations. **→ ticks exit-checklist box 4 (S3 gate,
recorded + configured).**

**6d. Trial folds + promote.**

```bash
BEST=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWID/best?top=1" | jq -r '.items[0].trialNumber')
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWID/trials/$BEST/folds" | jq 'length'
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/v1/optimizations/$SWID/promote" \
  -d "{\"trialId\":$BEST,\"notes\":\"stage-d manual walk\"}" -w "\nHTTP:%{http_code}\n"
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID/versions" | jq -c '.items[0] | {version,status,notes}'
```

**PASS when:** `/folds` is `[]` for a full-window trial (a fold-context trial over a
long window returns its array); promote returns `201 {strategyId,newVersion,status:"draft"}`;
the new version is a **draft** (inert until the owner publishes — the optimizer
proposes, never deploys); its `notes` record `created_by=optimizer:{sweepId}`
provenance. Promoting a FAILED/PRUNED trial → `409`. **→ completes box 3.**

---

## 7. Ranking run at scale (gate demo, ~3 min)

```bash
BIG=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/optimizations/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"$FROM\",\"to\":\"$TO\",\"method\":\"tpe\",\"maxTrials\":30,\"seed\":1,\"parameters\":[{\"path\":\"indicators[0].params.period\",\"range\":[5,25],\"step\":1}]}" \
  | jq -r '.jobId')
for i in $(seq 1 90); do curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$BIG" | jq -e '.status=="completed" or .status=="failed"' >/dev/null && break; sleep 3; done
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$BIG" | jq -c '{status,trialsCompleted}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$BIG/best?top=10" | jq '.items|length'
```

**PASS when:** the sweep completes and ranks configs (plateau sort; winner
promotable). Bump `maxTrials` to `200` for the full headline acceptance — each
trial is one fast backtest, ~1–2 s on the mock window. This is the headline
Stage-D acceptance — **box 3 fully green.**

---

## 8. Automated suites (reference)

The hands-on walk above mirrors what CI gates:

```bash
# backtest-service (Java) — unit + Testcontainers ITs (parity, fills, folds, options, MC)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" "$MVN" -pl services/backtest-service -am verify
# optimizer-service (Python) — samplers, pruning, plateau, promote
cd services/optimizer-service && python -m pytest -q --cov=app --cov-fail-under=75 && cd -
```

**PASS when:** Java JaCoCo ≥60% line + all ITs green; optimizer 49 pytest pass at
≥75% coverage (82% as of Phase 34), ruff clean.

---

## Appendix — live mode

Stage D needs no Kite credentials; all of the above is mock-green. Live mode only
changes the *data source* the replay reads (real candles/snapshots instead of the
mock fixtures) — the engine, fills, metrics, optimizer and parity contract are
identical by construction (same JAR). With years of real NIFTY history, §0's
benchmark backfill is unnecessary and §4/§6c's fold + pruning paths exercise fully.
Run §6/§7 unchanged once a live session has backfilled the target window.
