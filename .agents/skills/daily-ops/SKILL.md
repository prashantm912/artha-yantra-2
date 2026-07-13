---
name: daily-ops
description: Use for ArthaYantra daily operations — what runs when (IST calendar of batches, captures, scheduled checks), what to check morning/evening, the paper books, and how to schedule durable future verifications. Use when asked "what runs today", "did the batch fire", or when a change's proof arrives later.
---

# daily-ops

The operational clock and the watch routine. All times IST; the DB stores UTC
(bound queries by explicit `+05:30` — [live-verify]).

## The daily calendar (trading weekdays)

| IST | What | Where it runs |
|---|---|---|
| ~06:00 | Kite token expires → ticker DISCONNECTED until owner re-logins | Kite |
| 08:30 | NotifierHealthCheck — trailing-24h delivery failure-rate, alarms BOTH channels (#689) | strategy-signal |
| ~08:40 | Futures roll re-resolve (dated front contract for live signals) | strategy-signal |
| 08:45 | IngestCoverageCanary — previous trading day per-source `ingest_runs` coverage (#689, live-only) | market-data |
| 09:15 | Market open — tick feed, 1m bars, live scalper engine | market-data / strategy-signal |
| 09:42 | **live-data-health-check** (scheduled Codex task, read-only) | scheduled task |
| all session | 3-min full OI chain capture (NIFTY+SENSEX); rejections accrue; PartialBucketCanary sweeps 60s (#683) | market-data / strategy-signal |
| 15:30 | Market close |  |
| 15:45 | Intraday paper square-off (swing books excluded) — settles on last REAL tick, never breakeven (#694); expect a daily `ay_paper_stale_settle_total` baseline (post-close ticks), alert on the REFUSE counter | strategy-signal |
| 15:47 | **post-market-session-analysis** (scheduled Codex task → [session-analysis]) | scheduled task |
| 19:00 | NseEod pulls (FII/DII cash, participant-OI, FII-derivative) → `ingest_runs` rows (also ~2/day incl. boot pull) | market-data |
| 20:00 | Minervini swing EOD batch (`MinerviniSwingEngine`) | strategy-signal |
| 20:05 | Manas Arora swing EOD batch | strategy-signal |
| 21:00 | Graduation promotion scheduler (flag-gated) | strategy-signal |
| 21:15 | PaperReconciliationScheduler — V5 position↔order-leg + V16 TAKEN↔position → `paper_reconciliation_runs` + ntfy (#701) | strategy-signal |
| night | pg_dump backup (db-backup container) — **contends with long backtests** | deploy |

Morning board: `/data-ops/ingest-health` (per-source last-run/verdicts/missing-days, #699)
before hand-digging any "batch missed" report.

Daily-bar swing batches can legitimately run on non-trading days too (they analyse the
last close); "batch fired Saturday" is by owner doctrine, not a bug.

## The books (all paper, ₹1.5L each, book = first strategy tag)

`scalper` (intraday options) · `minervini` (swing) · `manas-arora` (swing) — plus the
shadow book (`strategy.shadow_positions`) where rejected entries trade virtually.
**Forward paper is the reliability test**; backtests are the historical estimate.

## Watch surfaces (in priority order)

```bash
# batch/system health
curl -s http://127.0.0.1:8081/api/v1/market/health/data
curl -s http://127.0.0.1:8082/api/v1/signal-rejections/dot-health
# swing daily workflow
curl -s http://127.0.0.1:8082/api/v1/signals/minervini-swing/sell-decisions
curl -s http://127.0.0.1:8082/api/v1/signals/manas-arora-swing/sell-decisions
```
Plus: `/signal-rejections` page (0 live signals = strict AND-gate, not a bug — every
block is persisted), paper positions per book, the F7 graduation dashboard, and
`docs/signal-analysis/rollup.md` accrual (league table + proposals at ≥5 sessions).
Batch failures ntfy via the P0-4 canary — silence + no rows = check
`docker logs ay-strategy-signal-service` around 20:00–20:10.

## Scheduling durable verifications (the deferred-proof pattern)

When a change's first real exercise happens later (a batch, next session's rejections),
DO NOT claim success — schedule the check:

- **Durable (survives session end): `mcp__scheduled-tasks__create_scheduled_task`** with
  `fireAt` (one-shot) or a cron. **CronCreate is session-only even with durable:true** —
  known trap; don't use it for cross-session checks.
- Write the prompt **self-contained** (paths, SQL, expected values — the future session
  has no context), **read-only guardrails stated**, and with an explicit
  **bug-vs-correct discriminator** (e.g. "an eligible winner ≥+6% with no add-lot = BUG;
  no holding ≥+6% and no add = CORRECT").
- Standing tasks already exist for 09:42 health + 15:47 post-market — check
  `mcp__scheduled-tasks__list_scheduled_tasks` before adding overlapping ones; disable
  one-shots after they fire.

## Weekly / occasional

- `ay backup` / `ay restore` — whole-DB pg_dump -Fc + globals (never per-schema `-n`).
- Market-calendar horizon: bundled years currently 2024–2026; a canary test goes red ~45
  days before coverage ends → refresh the yearly CSV.
- Rollup pass ([session-analysis] `rollup` mode) once ≥5 sessions accrued.
