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
| **08:05** | **Kite TOTP auto-login — ARMED 2026-08-28.** Also runs ON BOOT (08:00–15:30 window) because a cron never backfills. Judge a morning on `ay_kite_session_valid`, never on log lines | market-data |
| **08:15** | Auto-login watchdog — pages if the session is still not CONNECTED | market-data |
| 08:30 | SwingCanary · NotifierHealthCheck (trailing-24h delivery failure-rate, alarms BOTH channels, #689) | strategy-signal |
| **08:35** | **SwingBatchCatchUp — the only AUTOMATIC swing ENTRY path.** The evening settles are EXITS-ONLY. ⚠️ Manual `POST /api/v1/signals/<batch>-swing/run` also takes entries, so reason about entry paths as TWO | strategy-signal |
| ~08:40 | Futures roll re-resolve (dated front contract for live signals) | strategy-signal |
| 08:45 | IngestCoverageCanary — previous trading day per-source `ingest_runs` coverage (#689, live-only) | market-data |
| **08:50** | PaperReconciliationScheduler — V5 position↔order-leg + V16 TAKEN↔position (#701). **MORNING since #1358, not 21:15** | strategy-signal |
| **08:52** | Past-expiry reconciliation | strategy-signal |
| 09:15 | Market open — tick feed, 1m bars, live scalper engine | market-data / strategy-signal |
| 09:42 | **live-data-health-check** (scheduled Claude task, read-only) | scheduled task |
| **:13/:28/:43/:58**, 08–15 | day-context snapshot refresh. ⚠️ Its 120 s LEAD over the consumer's :00/:15/:30/:45 sweep is load-bearing — moving either cron alone silently reinstates the defect (`DayContextRefreshPhaseTest`) | market-data |
| **09:50 / 11:50 / 14:50** | NSE FII retry — **absent from CLAUDE.md's list too**, found 2026-08-28 | market-data |
| every 10 min, 09:00–15:59 | Session heartbeat | strategy-signal |
| all session | 3-min full OI chain capture (NIFTY+SENSEX); rejections accrue; PartialBucketCanary sweeps 60s (#683) | market-data / strategy-signal |
| 15:30 | Market close |  |
| ~15:44 | Intraday paper square-off (swing books excluded) — settles on last REAL tick, never breakeven (#694); expect a daily `ay_paper_stale_settle_total` baseline (post-close ticks), alert on the REFUSE counter | strategy-signal |
| 15:47 | **post-market-session-analysis** (scheduled Claude task → [session-analysis]) | scheduled task |
| 16:05 | bhavcopy close prefetch | market-data |
| 18:20 | Upstox canary | market-data |
| **18:45** | **bhavcopy EOD — the anchor.** Most cash equities have NO intraday 1d bar; their session bar is written HERE, which is why the swing settles moved after it (H27) | market-data |
| **18:46** | NseEod pulls (FII/DII cash, participant-OI, FII-derivative) → `ingest_runs`. **NOT 19:00 — this table said 19:00 until 2026-08-28** | market-data |
| 18:47 / 18:48 | Minervini screen · Manas screen | market-data |
| 18:49 / 18:50 / 18:51 | Market context · Data quality · Equity breadth | market-data |
| **18:52 / 18:53** | Minervini · Manas swing SETTLE. ⚠️ **EXITS ONLY — 0 candidates AND 0 exits is a LEGITIMATE result.** Judge on the STALE-bar count and `exit_skipped`, NEVER on the exit count | strategy-signal |
| 18:54 / 18:55 | Heartbeat swing · Graduation promotion (flag-gated) | strategy-signal |
| 18:56 / 18:57 | Insights: strategy-evidence · sell-decision | both |
| 18:58 | bhavcopy close | market-data |
| **18:59** | Evening chain check (**absent from CLAUDE.md's list too**) · Minervini buyable-alerts — the latter is **DISABLED** (`ARTHA_MINERVINI_BUYABLE_ALERTS_ENABLED=false`, so the bean does not exist and "it did not run" is CORRECT, not a fault) | market-data |
| night | pg_dump backup (db-backup container) — **contends with long backtests** | deploy |

⚠️ **RE-READ `docker inspect`, DO NOT TRUST THIS TABLE.** Every time above is `computed` 2026-08-28 from the DEPLOYED env of `ay-market-data-service` / `ay-strategy-signal-service`.
Never quote the `application.yml` `${ENV:default}` values — they differ from what is deployed.

```bash
docker inspect ay-market-data-service     --format '{{range .Config.Env}}{{println .}}{{end}}' | grep CRON=
docker inspect ay-strategy-signal-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep CRON=
```

⚠️ **THIS TABLE WAS ~11 DAYS STALE WHEN CORRECTED ON 2026-08-28, AND IT IS THE RUNBOOK A DAILY-OPS SESSION FOLLOWS.** It still described the PRE-#1358 world: NseEod at 19:00 (really 18:46), the swing batches at 20:00/20:05 (really 18:52/18:53, and EXITS-ONLY), graduation at 21:00 (18:55), the paper reconciler at 21:15 (**08:50 — it moved to MORNING**), and a Kite token expiring at ~06:00 with no mention that auto-login has been ARMED since 08-28. An operator following it would have hunted a 21:15 job for three hours after it had already run. A stale cron list here has ALREADY produced a false "Friday's screens were never consumed" alarm (2026-08-17).
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
