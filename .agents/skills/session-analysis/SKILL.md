---
name: session-analysis
description: Run the ArthaYantra signal/rejection analysis agent — post-market session forensics or live in-session data-health + counterfactual watch. Use when asked to "analyze the session", "analyze rejections", "run session analysis", "/session-analysis", or to check live whether strategy data is being gathered correctly.
---

# session-analysis

You are the **signal-analysis agent**. The method authority is
`docs/signal-analysis/README.md` — **READ IT FIRST, every run** (it is open-ended and grows new
dimensions; never run from memory of an old version). This skill only tells you which mode to run
and the guardrails; the README owns the how.

Arguments: `post [YYYY-MM-DD]` (default: the most recent completed session) · `live` ·
`rollup` (multi-session).

## Mode: post — post-market forensics (default)

1. Read `docs/signal-analysis/README.md` (method §3, SQL §6, template §5) and the LATEST prior
   findings file in `docs/signal-analysis/` (so you inherit its "watch across sessions" items and
   its §6 new-data-points — check each of them this session).
2. Confirm the data date: live DB `artha` via `docker exec ay-timescaledb psql -U artha -d artha`.
   **IST trap:** bound every query by explicit `+05:30` timestamps, never `::date = CURRENT_DATE`.
3. Run the full §3 pass (all dimensions, including any added since v1) + §4.2 counterfactuals for
   every would-have-fired row (premium path from `options_chain_snapshots`; state the honesty
   caveats).
4. Ground-truth any flagged rail against source data (§3.8) and against the code defaults
   (`ScalperGates.java`, `ScalperOiProps.java`, per-strategy YAML `scalper.params`) — a rejection
   row proves the gate fired; only the code/config proves WHY.
5. Write `docs/signal-analysis/YYYY-MM-DD-session-findings.md` from the §5 template (named by DATA
   date; immutable — dated addendum if correcting). Update the §7 tuning-candidate ledger statuses
   (carry forward prior PROPOSED rows; never silently drop one).
6. **Append ONE session row to `docs/signal-analysis/rollup.md` (roadmap F2 accrual):** the session
   log gets a row (rejections/strategies/fired/first-block/composite-≥thr/shadow-books/dead-dots),
   the per-variant league table is refreshed from the §6 league SQL (NET ₹ first), and the
   structural-vs-regime watchlist gains/clears items. Session log rows are append-only. When the
   log holds ≥5 sessions and §Proposals is still empty, run the rollup pass (below) in the same
   sitting and fill it.
7. If you found a NEW analysis dimension or data point, append it to README §3 (numbered,
   append-only) with its SQL in §6 — that is how the method is designed to grow.
8. Ship as a docs-only PR (squash, admin-merge allowed for docs). Report the headline verdict +
   tuning candidates to the owner. **Propose tunes; never arm/change a strategy knob without the
   owner's explicit OK.**

## Mode: live — in-session watch

**HARD guardrails: read-only.** No deploys, no service restarts, no writes to live tables, no
config changes, no `ay` verbs that touch containers. SELECTs + `docker logs` only.

1. README §4.1 data-health pass: rejections flowing this session (rows + max generated_at vs wall
   clock), context nulls/zeros on TODAY's rows (compare against the findings ledger — flag anything
   newly-dead the same day), capture liveness (1m candle max bucket, snapshot counts).
   ⚠️ **Zero rejections is INCONCLUSIVE, never "starvation"** (corrected 2026-07-26):
   `recordRejection` runs only PAST the chart gate, and every scalper shares two required scorers on
   one 3m series, so a SuperTrend-DOWN leg silences all of them on ordinary bearish tape. Judge
   liveness POSITIVELY from the read surface shipped 2026-07-26 (task_0bed1621):
   `docker exec ay-strategy-signal-service sh -c 'wget -qO- http://127.0.0.1:8082/actuator/prometheus'
   | grep ay_signal_bar_`. `ay_signal_bar_received_age_seconds` inside ~1–2 bar intervals ⇒ alive;
   growing while capture is healthy ⇒ receive-side stall. A missing series means the process is down,
   not "no data yet". ⚠️ A missing `receive-stall`/`eval-stall` row in
   `strategy.subscriber_health_events` is NOT proof of health — that table is write-only fail-soft
   forensics, so a disabled or failed sweep leaves it empty too; a row that IS present is strong
   evidence of a fault. See README §4.3 step 4.
2. README §4.2 live counterfactual watch: intraday would-have-fired rows → resolve leg → premium
   path so far → provisional WOULD-WIN/LOSE. Keep results in the scratchpad during the session.
3. Report anomalies immediately (that is the point of live mode); fold the counterfactual outcomes
   into that evening's `post` findings file rather than writing a separate doc.

## Mode: rollup — multi-session consolidation

After ~5 sessions (or on ask): `docs/signal-analysis/rollup.md` already holds the accrued session
log + league + watchlist (post mode appends per session) — verify it against the findings files,
then fill its **§Proposals** with ranked tune proposals as LITERAL config diffs (knob → exact
YAML/env diff → evidence citations by session date + shadow/variant NET-₹ numbers → risk note).
Judge variant keep/cut on `pnl_net`, never raw points. Every proposal must be reproducible from
the cited findings files alone; no auto-apply — applying one remains an owner-approved PR.
Separate STRUCTURAL findings (present every session) from REGIME ones (day-dependent).
Cross-reference the exit-band runbook
(`docs/superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`) — entry-gate tunes (this
track) and exit-band tunes (that track) land as ONE coordinated owner decision.

## Shared guardrails

- Rejections are LIVE-only rows; **zero rows during market hours is INCONCLUSIVE, not a problem**
  (corrected 2026-07-26 — this line used to say it was a real problem). `recordRejection` runs only
  PAST the chart gate, so an ordinary SuperTrend-DOWN leg silences every scalper at once. Prove
  liveness POSITIVELY from the LATEST `strategy.signal_eval_outcomes` bucket having `eval_count > 0`
  — a fresh ALL-ZERO latest bucket only proves the rollup thread is alive, not the eval loop, so it
  is INCONCLUSIVE and merely NARROWED (not settled) by a thread dump: a thread parked on
  `queue.take()` stays INCONCLUSIVE, and one dump inside a legitimately-blocking `fetch` proves
  nothing either. **On a fully quiet session liveness is currently UNPROVABLE** (chip task_0bed1621);
  zero rows off-hours/holidays = normal (check `libs/market-calendar` holidays before alarming).
- Single-session numbers never justify a tune by themselves UNLESS the threshold is outside the
  operand's physical range (structural) — say which class each candidate is.
- Findings files are immutable; the ledger (§7 table) is the only thing that changes status.
- Never print secrets (Upstox token, password hashes). Never run heavy unbounded scans on
  hypertables mid-session (bound candle queries to the session window).
