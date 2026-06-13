# Stage D — Manual Testing Guide (Backtesting + Optimization, Phases 28–34)

Everything Stage D built, testable by hand on the **mock stack with zero Kite
credentials** (the Stage-D testing posture, plan §15.2). The whole pass takes
~45–60 min. Stage D adds two services — `backtest-service` (Java, :8083) and
`optimizer-service` (Python/FastAPI/Optuna, :8084) — plus the `libs/black76-math`
hoist. The optimizer never evaluates a strategy itself: it proposes parameter
vectors that backtest-service scores through the **same engine JAR as live** (D6).

This guide is also the **Stage-D exit-gate walk** (`PHASE_GATES.md` Phase-3 row):
each section ends with the PASS criterion that ticks a checklist box.

> **Shell labels — read this first.** Every fenced block is tagged `powershell`
> (PowerShell at repo root `C:\Trading\ArthaYantra\artha-yantra-2`) or `bash`
> (Git-Bash/WSL — POSIX `curl`/cookie-jar/`grep`/here-doc; will NOT work in
> PowerShell). Don't mix them.

> **Machine notes (carried from Stage A/B/C):**
> - Maven: `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` in the
>   environment (TLS-intercepting AV blocks the wrapper download — build with the
>   cached `mvn`, see §0).
> - `.env` Argon2id hash: every `$` escaped `$$`.
> - `docker compose -f deploy/docker-compose.yml ...` directly: always add
>   `--env-file .env` or compose blanks the owner hash and login 401s. `.\ay.ps1`
>   always passes it.
> - Owner password in this setup is `MyPassword123` — substitute yours.
> - **Git-Bash `/tmp` vs Windows `python`:** never have Windows `python` OPEN a
>   `/tmp/...` path (it resolves to `C:\tmp\...`); pipe via stdin instead. `curl`,
>   `cat`, `grep`, `jq` read `/tmp` fine.
> - Everything under `/api/v1/**` needs the session cookie — send `-b /tmp/ay.jar`
>   on every curl; mutating calls also need `-H "X-XSRF-TOKEN: $XSRF"`.

---

## 0. Build + bring-up + login (8 min)

Build both new services into the compose images. Java first (full reactor + `-am`
so the nested libs — `common-web/servlet`, `black76-math`, `strategy-engine` — are
rebuilt, never a stale `.m2` copy):

```bash
MVN=$(ls ~/.m2/wrapper/dists/apache-maven-*/*/bin/mvn | head -1)
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/backtest-service,libs/black76-math -am package -DskipTests
```

```powershell
.\ay.ps1 up dev-tools
.\ay.ps1 status
```

**PASS when:** all containers `(healthy)` — note the two new Stage-D services
`ay-backtest-service` (:8083) and `ay-optimizer-service` (:8084) — and
`ay-flyway-init` `Exited (0)`.

Confirm the backtest schema migrations landed (jobs + runs/trades + optimization
trials):

```powershell
docker exec ay-timescaledb psql -U artha -d artha -c "select version, description from backtest.flyway_schema_history order by installed_rank"
```

**PASS when:** the list ends `... 002 jobs, 003 runs trades, 004 optimization trials`.

Log in (Git-Bash) — the cookie jar + CSRF token are reused below:

```bash
curl -s -c /tmp/ay.jar -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data 'password=MyPassword123' -i | head -3
XSRF=$(grep XSRF-TOKEN /tmp/ay.jar | awk '{print $NF}')
echo "XSRF=$XSRF"
```

Grab a published strategy id to backtest (the Stage-C repeatable seeds one):

```bash
SID=$(curl -s -b /tmp/ay.jar 'http://127.0.0.1:8080/api/v1/strategies?status=published&limit=1' | jq -r '.items[0].id')
VER=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID" | jq -r '.currentVersion // .version')
echo "strategy=$SID version=$VER"
```

**PASS when:** `204` on login, `$XSRF` a non-empty UUID, `$SID`/`$VER` populated.

---

## 1. Phase 28 — jobs spine + crash-safe dispatch (6 min)

Submit a backtest; watch it run through the Postgres `jobs` spine (truth) with
Redis Streams as transport only (ADR D12).

```bash
JOB=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/backtests/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"2026-01-05\",\"to\":\"2026-01-09\",\"seed\":42}" \
  | tee /dev/stderr | jq -r '.jobId')
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/jobs/$JOB" | jq '{status,progress}'
```

**PASS when:** the POST returns `202 {jobId,status:"queued"}`; polling
`jobs/{id}` walks `queued → running → completed` with progress 0→100. *(Live WS:
log into the SPA and watch `/topic/jobs/{jobId}` — progress frames stream.)*
**→ ticks exit-checklist box 1.**

Crash recovery (optional): `docker restart ay-backtest-service` while a job is
`running` → `StreamBootstrap` re-queues the stale-`running` row on startup via
`XAUTOCLAIM` and it completes exactly once (Redis is never the source of truth).

---

## 2. Phases 29–30 — replay, metrics, A9 fills, parity (8 min)

Fetch the completed run's results + trades:

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/results" \
  | jq '{totalReturn,sharpe,maxDrawdown,winRate,tradeCount,dataHash,engineVersion,premiumSource}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/trades" | jq '.items[0] | {side,qty,touchBasis,entryPrice,exitPrice}'
```

**PASS when:** metrics are exact decimal **strings** (NUMERIC, no float drift);
every closed trade carries a non-NULL `touch_basis` (`INTRABAR_1M` /
`BAR_HL_WORSTOF` / `CLOSE_EVAL`); a second identical submit (`seed=42`) reproduces
a byte-identical trade list (reproducibility triple `strategyChecksum,seed,dataHash`).

**Parity (D15 headline):** the engine-parity test asserts the live signal engine
and the replay produce **byte-identical** signal lists incl. per-indicator
`ScoreBreakdown`. Verify it green:

```bash
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl services/backtest-service -am test -Dtest=ParityGoldenIT,FillVectorTest
```

**PASS when:** parity IT + A9 fill vectors green (futures cost legs, `at_close`,
intra-bar touch, BTST pre-close view). **→ ticks exit-checklist boxes 2 + 5.**

Pre-flight gap (Git-Bash): submit a window with no candle coverage →

**PASS when:** `422 DATA_GAP` naming the missing windows / context series. **→
ticks box 6.**

---

## 3. Phase 30A — options fidelity + synthetic premium (5 min)

Backtest an options strategy on the mock snapshot archive, then again outside the
archive window to force synthetic mode.

**PASS when:** the in-archive run's results carry `premiumSource=SNAPSHOT`; an
archive gap → `422 DATA_GAP` against snapshot coverage; the out-of-archive run
completes flagged `SYNTHETIC_B76` with the flat-IV caveat (never masquerading as
snapshot-grade); and the market-data Greeks goldens stay byte-identical after the
hoist:

```bash
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
  "$MVN" -pl libs/black76-math,services/market-data-service -am test
```

**→ ticks exit-checklist box 7.**

---

## 4. Phases 31–32 — walk-forward folds, regime, stress guard (6 min)

A plain run is full-window (no folds). An optimization/stress run splits.

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/folds" | jq 'length'
```

**PASS when:** a plain run's `/folds` is `[]` and its `sharpe_degradation` /
`fold_metrics` are NULL; a walk-forward run persists N folds with both train +
OOS metric sets and the `t-1` regime mix (entry-day close never consulted); a
`min_trades`-failing fold is flagged excluded, never silently ranked.

Stress guard — submit `purpose:stress_test` over a window overlapping a prior job:

**PASS when:** `422 WINDOW_CONTAMINATED` listing the intersecting jobIds; the
suggested-window endpoint (`GET /api/v1/backtests/stress-window`) returns a clean
range; the holdout-reuse counter increments.

---

## 5. Phase 32A — benchmark metrics + Monte Carlo (5 min)

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/results" \
  | jq '{alpha,beta,informationRatio,excessCagr,hasBenchmarkCurve:(.benchmarkCurve!=null)}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/montecarlo?n=1000&seed=7" \
  | jq '{equityBands:.equityBands|keys, riskOfRuin, ci}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/backtests/$JOB/montecarlo?n=1000&seed=7" | jq '.mcSeed'
```

**PASS when:** results carry alpha/beta/IR/excess-CAGR (NULL — never 0 — when
benchmark coverage is absent) + a `benchmark_curve` whose timestamps overlay
`equityCurve`; the Monte Carlo call returns seeded p5/p50/p95 bands +
risk-of-ruin + CIs, **persists `montecarlo_summary` on first call and serves the
byte-identical summary on the second** (same `n,seed`); a trade-less run → `422`.
**→ ticks exit-checklist box 8.**

---

## 6. Phase 33/34 — optimizer sweeps, pruning, leaderboard, promote (12 min)

The optimizer is reached through the gateway at `/api/v1/optimizations/**`.

**6a. Grid/random sweep (Phase 33).** Submit a sweep over the strategy's
`optimize.parameters` (the seeded sample carries an `optimize` block; if not, the
optimizer 422s "no tunable parameters"):

```bash
SWEEP=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/optimizations/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"2026-01-05\",\"to\":\"2026-01-09\",\"method\":\"grid\",\"maxTrials\":12,\"seed\":7}" \
  | jq -r '.jobId')
# poll to completion
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/jobs/$SWEEP" | jq '{status,progress,trialsCompleted}'
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/trials?state=COMPLETE&limit=20" | jq '.items | length'
```

**PASS when:** every grid point becomes a COMPLETE `optimization_trials` row, the
sweep's TRIAL `jobs` count equals the dispatched trials, and a re-run with the
same `seed` reproduces the identical trial sequence.

**6b. TPE + NSGA-II (Phase 34).** Run a TPE sweep (constant-liar; default 150
trials — use `maxTrials` smaller for a quick walk) and an NSGA-II sweep
(`method:"nsga2"`, default objectives return↑/drawdown↓):

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/best?top=5" \
  | jq '{metric,sort,items:[.items[]|{trialNumber,objective,plateauObjective,neighborCount}]}'
```

**PASS when:** the leaderboard default sort is **plateau-adjusted** (a broad ridge
outranks a lone spike; `?sort=raw` flips to raw objective one query away); an
NSGA-II sweep's results expose a **Pareto front, never collapsed** to one winner;
pruned trials never appear in the ranking. **→ contributes to box 3.**

**6c. Fold-fed pruning (S3 gate).** With `walkForward` set, the TRIAL workers stream
per-fold OOS objectives; the fold-fed `MedianPruner` (S3 defaults
`n_startup_trials=5`, `n_warmup_folds=3`, `n_min_trials=2` —
`docs/design/DECISIONS_LOG.md`) marks weak trials `PRUNED`:

```bash
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/trials?state=PRUNED" | jq '.items | length'
```

**PASS when:** some trials are `PRUNED` (excluded from `min`/`mean_minus_std` +
the leaderboard); pruning is fed by OOS fold medians only, never train/OOS
divergence. **→ ticks exit-checklist box 4 (S3 gate, recorded + configured).**

**6d. Trial folds + promote.** Inspect one trial's folds and promote a winner:

```bash
BEST=$(curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/best?top=1" | jq -r '.items[0].trialNumber')
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/trials/$BEST/folds" | jq 'length'
curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST "http://127.0.0.1:8080/api/v1/optimizations/$SWEEP/promote" \
  -d "{\"trialId\":$BEST,\"notes\":\"stage-d manual walk\"}" | jq
```

**PASS when:** promote returns `201 {strategyId,newVersion,status:"draft"}`; the
new version is a **draft** (inert until the owner publishes — the optimizer
proposes, never deploys); its diff vs the source shows **exactly the trial's
parameter deltas**; the version's `created_by`/notes record
`optimizer:{sweepId}` provenance. Promoting a FAILED/PRUNED trial → `409`. **→
completes box 3.**

```bash
# confirm the promoted draft exists and carries provenance
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/strategies/$SID/versions" | jq '.items[0] | {version,status,notes}'
```

---

## 7. 200-trial ranking run (gate demo, ~5 min)

```bash
BIG=$(curl -s -b /tmp/ay.jar -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/v1/optimizations/run \
  -d "{\"strategyId\":\"$SID\",\"strategyVersion\":\"$VER\",\"from\":\"2026-01-05\",\"to\":\"2026-01-09\",\"method\":\"tpe\",\"maxTrials\":200,\"seed\":1}" \
  | jq -r '.jobId')
# ... poll jobs/$BIG to completed, then:
curl -s -b /tmp/ay.jar "http://127.0.0.1:8080/api/v1/optimizations/$BIG/best?top=10" | jq '.items|length'
```

**PASS when:** the 200-trial sweep completes and ranks configs (plateau sort;
winner promotable). This is the headline Stage-D acceptance — **box 3 fully
green.**

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
≥75% coverage, ruff clean.

---

## Appendix — live mode

Stage D needs no Kite credentials; all of the above is mock-green. Live mode only
changes the *data source* the replay reads (real candles/snapshots instead of the
mock fixtures) — the engine, fills, metrics, optimizer and parity contract are
identical by construction (same JAR). Run §6/§7 unchanged once a live session has
backfilled the target window.
