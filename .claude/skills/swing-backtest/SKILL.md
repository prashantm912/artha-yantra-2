---
name: swing-backtest
description: Use when asked to run, re-run, or compare the Minervini or Manas Arora SWING backtests (the ~11-year candles@1d event-driven sims with RS/turnover/pyramiding variants, portfolio CAGR/DD/Sharpe, slot sweeps) — trigger, poll, read, and write the doc-of-record.
---

# swing-backtest

The swing families have **their own in-service backtests** (NOT the job-based
backtest-service): `MinerviniBacktestService` / `ManasAroraBacktestService` in
market-data, event-driven over `candles`@1d (~11 yr, ~1,800 NSE symbols), self-seeding
geometry, same exit doctrine as live (8%-stop + 50d-MA trail / 2×ATR trail).

**Live-path changes do NOT move these backtests** — live engine (strategy-signal) and sim
(market-data) are separate implementations. Before running "to see the effect of X",
check which plane X touched; if live-only (e.g. F1 #611, F2 #612), the sim reproduces
byte-identically and the run only proves determinism.

## Run

Via the dev-tools socat sidecar (no auth, loopback): market-data = `127.0.0.1:8081`.
If it's down: `docker compose -f deploy/docker-compose.yml --env-file .env --profile dev-tools up -d mds-publish`.

```bash
BASE="http://127.0.0.1:8081/api/v1/market/screener"
curl -s -X POST "$BASE/manas-arora/swing-backtest"        # kicks a background run; returns current report
curl -s -X POST "$BASE/minervini/swing-backtest"          # same shape for Minervini
```
- **Single-permit gated — run the two families sequentially**, ~30 min each. Under the
  nightly `pg_dump` it stretches to ~40+ min (the sim does ~1,800 sequential per-symbol
  `readSeries` reads — the audit-LOW N+1). A long quiet spell with `symbolsScanned:0` is
  usually I/O contention, not a hang; confirm with a thread dump ([live-verify]) before
  restarting anything.
- Poll with a background loop (don't foreground-sleep):
  `curl -s "$BASE/manas-arora/swing-backtest" | jq '.status'` until it leaves `running`.

## Read

```bash
curl -s "$BASE/manas-arora/swing-backtest"            # headline report (latest run)
curl -s "$BASE/manas-arora/swing-backtest/compare"    # per-variant portfolio + annual returns + slot/turnover sweeps
```
Reading doctrine (learned across #556/#557/#606/#607 — apply every time):
- **Per-trade edge vs portfolio are different questions.** 8-slot books are
  capacity-bound (~750 of ~40k signals get slots) — WHICH trades get picked dominates.
  A filter can lower expectancy/trade yet raise portfolio CAGR, or vice versa.
- **`rs-turnover` is the live analogue** (the funnel applies both); `technical` is the
  optimistic upper bound. Quote RS-priority-NET (net-of-cost) as the realistic number.
- **`technical` is the control**: it should reproduce across runs when the sim is
  unchanged. If the control moved, the sim changed — find the PR before interpreting.
- Sweeps (slots, turnover) are **noisy and capacity-dependent** — a non-monotonic sweep
  is not an optimum. Doctrine caps beat sweep peaks (Manas §2.2 = 5–7 names; the 16-slot
  "peak" was rejected on exactly this).
- Drawdowns 40–60% are normal for these sleeves; judge on Sharpe + DD together, and
  remember the standing caveat: survivorship + illiquid small-caps inflate unfiltered
  variants. The FORWARD paper book, not these sims, is the reliability test.

## Doc-of-record (mandatory closeout)

Results go in `docs/strategies/swing-backtest-latest-YYYY-MM-DD.md` (new dated file, or a
dated section appended to the latest): full variant table, a **comparison vs the previous
run explaining every delta by the code change that caused it**, and the verdict. Ship as a
docs PR. Update the memory topic if the headline numbers moved.
