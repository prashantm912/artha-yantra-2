---
name: session-analysis
description: Run the ArthaYantra signal/rejection analysis agent — post-market session forensics, live in-session data-health + counterfactual watch, or a market-open signal-liveness gate (catches the silent "capture healthy but engine emitting nothing" starvation). Use when asked to "analyze the session", "analyze rejections", "run session analysis", "/session-analysis", "check signals are firing / the engine is alive at open", or to check live whether strategy data is being gathered correctly.
---

# session-analysis

You are the **signal-analysis agent**. The method authority is
`docs/signal-analysis/README.md` — **READ IT FIRST, every run** (it is open-ended and grows new
dimensions; never run from memory of an old version). This skill only tells you which mode to run
and the guardrails; the README owns the how.

Arguments: `post [YYYY-MM-DD]` (default: the most recent completed session) · `live` ·
`open` (market-open signal-liveness gate) · `rollup` (multi-session).

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
   **ALSO append one row to §Session regime** (G15) from the day's `NIFTY 50` daily bar — net %
   (open→close), range %, `|close−open|/(high−low)`, and the label off that section's derived cuts.
   ⚠️ Use the INTRADAY open→close move, never close-over-prior-close: a gap is not something a
   30-minute time stop can capture, and the two disagree (2026-07-29 reads +1.10% close-over-close
   but only +0.30% intraday). **If the label is `chop`, say so in the report and re-read ledger row
   G11** — that observation is the row's blocker, and this stamp is the only thing that will ever
   announce it.
7. If you found a NEW analysis dimension or data point, append it to README §3 (numbered,
   append-only) with its SQL in §6 — that is how the method is designed to grow.
8. Ship as a docs-only PR (squash-merge normally — ⚠️ corrected 2026-08-04: docs PRs used to be
   told they could bypass the gate; retracted when `lock_branch` was lifted 2026-07-26). Report the headline verdict +
   tuning candidates to the owner. **Propose tunes; never arm/change a strategy knob without the
   owner's explicit OK.**

## Mode: live — in-session watch

**HARD guardrails: read-only.** No deploys, no service restarts, no writes to live tables, no
config changes, no `ay` verbs that touch containers. SELECTs + `docker logs` only.

1. README §4.1 data-health pass: rejections flowing this session (rows + max generated_at vs wall
   clock), context nulls/zeros on TODAY's rows (compare against the findings ledger — flag anything
   newly-dead the same day), capture liveness (1m candle max bucket, snapshot counts).
2. README §4.2 live counterfactual watch: intraday would-have-fired rows → resolve leg → premium
   path so far → provisional WOULD-WIN/LOSE. Keep results in the scratchpad during the session.
3. Report anomalies immediately (that is the point of live mode); fold the counterfactual outcomes
   into that evening's `post` findings file rather than writing a separate doc.

## Mode: open — market-open signal-liveness gate

A fast, read-only PASS / FAIL / **INCONCLUSIVE** run ~15–20 min after the open (or on ask) that catches the silent
**starvation class**: capture healthy but the engine genuinely not evaluating. ⚠️ Note the gate below
does **not** equate that with "zero rejections" — that equation is what produced a false escalation
(07-17) and a needless restart (07-20); the run decides on a positive liveness read. **HARD
guardrails: read-only** — no
deploys, no restarts, no writes; SELECTs + `docker logs` + in-container health GETs only.

Follow README **§4.3** exactly (it owns the queries + the gate):
1. Confirm it's a trading day + inside 09:15–15:30 IST (clock trap: containers are UTC).
2. Stack healthy + today's Kite login + market-data canary GREEN.
3. Capture fresh — the signal future's 1m series tracks minutes-since-open.
4. **THE GATE** (three outcomes, not two — INCONCLUSIVE is a legitimate result): rejections flowing? `>0` recent ⇒ **PASS**. ⚠️ **`0` is INCONCLUSIVE, NOT a FAIL**
   (corrected 2026-07-26): `recordRejection` only runs PAST the chart gate, and every scalper shares
   two required scorers on one 3m series, so SuperTrend-DOWN silences all of them on ordinary bearish
   tape — this rule cost a false starvation escalation (07-17) and a needless restart (07-20), and the
   canary that automated it was retired. On `0`, demand **POSITIVE** proof of life — never infer it
   from an absence. **The definitive read (shipped 2026-07-26, task_0bed1621):**
   `docker exec ay-strategy-signal-service sh -c 'wget -qO- http://127.0.0.1:8082/actuator/prometheus'
   | grep ay_signal_bar_` — read **BOTH** gauges, received alone is not enough: bars can keep arriving
   while `signal-eval` is wedged. received fresh (≲1–2 bar intervals) AND evaluated fresh ⇒
   **PASS-QUIET**; received fresh + evaluated GROWING ⇒ **FAIL, eval stall**; received growing while
   capture is healthy ⇒ **FAIL, receive-side stall**. ⚠️ A NEGATIVE value is NOT a small age: `-1` =
   no bar ever received/evaluated this boot (the stamps are boot-seeded, so a plain age would read ~0
   and look identical to a fresh bar), below `-1` = the clock stepped backwards — a clock fault,
   deliberately surfaced rather than clamped. A MISSING series is **FAIL/unobservable**, not proof
   the process is down (engine-enabled=false, an older artifact, or an unreachable actuator all look
   the same) — inspect health, build and config. Corroborate with the LATEST
   `strategy.signal_eval_outcomes` bucket (never a session-wide `sum()`). ⚠️ A missing `receive-stall`/`eval-stall` row in `strategy.subscriber_health_events` is
   NOT a PASS — that table is write-only fail-soft forensics, so a disabled or failed sweep also
   leaves it empty; a row that IS there is strong FAIL evidence, its absence proves nothing. Never
   judge from `strategy.signals` (mixes the swing BATCH engine), and never ALARM on
   `ay_signal_eval_outcome_total` being flat (window- and position-dependent — legitimately zero when
   every strategy is out-of-window or in-position). A NON-ZERO value is positive evidence; a zero one
   is not evidence of anything.
5. On FAIL: localize read-only (`subscriber_health_events` eval-stall, the eval-thread dump, dot-health);
   **snapshot `docker logs` to a file BEFORE proposing any recreate**. Propose a fix; NEVER restart/
   redeploy mid-session — the owner/architect acts (post-market or pre-open).
6. Report **PASS / FAIL / INCONCLUSIVE** + evidence — INCONCLUSIVE is a first-class result, never
   round it to PASS; fold a FAIL **or an INCONCLUSIVE** into that evening's `post` findings file.

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
  nothing either. **Quiet-session liveness IS now provable** — read BOTH `ay_signal_bar_*_age_seconds`
  gauges (task_0bed1621): received fresh + evaluated fresh = alive; received fresh + evaluated GROWING
  = eval stall; any NEGATIVE value is not a valid age (-1 = no bar ever this boot, below -1 = clock
  fault) and must never be read as healthy;
  zero rows off-hours/holidays = normal (check `libs/market-calendar` holidays before alarming).
- Single-session numbers never justify a tune by themselves UNLESS the threshold is outside the
  operand's physical range (structural) — say which class each candidate is.
- Findings files are immutable; the ledger (§7 table) is the only thing that changes status.
- Never print secrets (Upstox token, password hashes). Never run heavy unbounded scans on
  hypertables mid-session (bound candle queries to the session window).
