# Signal analysis — per-session rejection/signal forensics (method + cadence)

**Status:** ACTIVE standing procedure. Started 2026-07-03 (owner directive).
**Folder contract:** every market session gets a dated findings file here
(`YYYY-MM-DD-session-findings.md`, named by the DATA date, not the analysis date). After a few
sessions, a cross-session rollup (`YYYY-MM-DD-multi-session-rollup.md`) consolidates them and feeds
the periodic strategy-tuning pass. Related but distinct:
[`../superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`](../superpowers/plans/2026-06-30-live-signal-analysis-runbook.md)
tunes EXIT bands from **executed paper trades**; THIS folder tunes ENTRY gates from **rejections**
(what the gate blocked and why). The two meet at the multi-session tuning pass.

**One-command agent:** the repo skill **`/session-analysis`** (`.claude/skills/session-analysis/`)
runs this method as an agent — `post [date]` (EOD forensics → findings file → PR), `live`
(read-only in-session data-health + counterfactual watch), `rollup` (multi-session consolidation).
The skill defers to THIS doc as the method authority, so extending §3 upgrades the agent for free.

**This method is deliberately OPEN-ENDED.** The dimensions below are the v1 checklist, not a cage —
each session may surface new data points; add them as new numbered dimensions here (with their SQL)
and as new sections in the findings template, so later sessions measure them too.

---

## 1. Cadence

| when | what |
|---|---|
| **During market hours (optional, live)** | Data-health spot-checks + live counterfactual watch (§4). Read-only — never restart services / never write to live tables during a session. |
| **After every session (EOD)** | FIRST run the two mechanical pre-checks (below), then the §3 standard pass → write `YYYY-MM-DD-session-findings.md` from the template (§5). ~30 min. |
| **EOD pre-check 1** | `python tools/ledger-consistency-check.py` — cross-greps the ledger, newest pickup sheet and recent findings docs for contradictions (an item claimed startable that already shipped, a chip OPEN here and DONE there, a promotion that never landed). Copy any REVIEW lines into findings §6 and resolve or explain them. Exists because a false STARTABLE sat in the ledger for 19 days (§9-03/B17) and cost a work lane before being caught by hand. |
| **EOD pre-check 2** | `python tools/published-config-drift.py` — every published strategy's tags + exit_rules vs the repo YAML, plus latest-row≠published (the #1016 signal). Findings go to §7 as republish PROPOSALS with the GAINS/LOSES diff — the routine never publishes. Exists because task_76d8f2a4 ran a months-stale config silently. |
| **Every ~5 sessions (or on owner ask)** | Multi-session rollup: stack the per-session tables, look for rails/dots that are dead or binding across ALL sessions (structural) vs day-dependent (regime). Structural → tune now; regime → collect more. |
| **Periodic tuning pass (owner-gated)** | Apply the tunes (YAML `scalper.params.*` / `artha.scalper.oi.*` props / code where needed) one PR per knob-family, then measure the NEXT sessions against the tuned baseline. Record before/after in the rollup. |

## 2. Data sources (extensible list)

All live DB (`artha`, container `ay-timescaledb`, `docker exec ay-timescaledb psql -U artha -d artha`).
**IST trap:** in-container `now()`/`::date` is UTC — always bound by explicit `+05:30` ISO timestamps
or `AT TIME ZONE 'Asia/Kolkata'`.

- `strategy.signal_rejections` (#404, V015) — the primary source. One row per BLOCKED chart-entry,
  LIVE-only. Columns: `blocking_rail` (FIRST failing rail) + operand/threshold/margin,
  `composite_score`/`composite_threshold`, and the `diagnostic` JSONB:
  - `checks[]` — every rail evaluated up to the block (pass **and** fail, with `failPolicy` since #432).
    Short-circuit caveat: rails AFTER the blocking point are absent, so "only rail X failed" claims are
    about *evaluated* rails.
  - `confluence.dots[]` — per-dot `{dot, weight, supports, reason}` for the Connect-the-Dots composite
    (scorer: `ConnectTheDotsScorer`, Σ(w·s)/Σw, VWAP w=2.5 / futures-OI 1.5 / IV dots 0.8 / rest 1.0).
  - `context.{chart,oi,macro}` — the raw operands (RSI/VWAP/volume, OI deltas/quadrants/spurt,
    VIX/IV/breadth/FII). This is where data-health problems show as nulls/zeros.
- `strategy.signals` (+ `scalper_detail` side-channel) — what DID fire (so far: nothing; when it does,
  cross-check entry context vs the rejection population).
- `strategy.paper_positions` / paper trades — executed-outcome side (the runbook's domain).
- `strategy.shadow_positions` (V016, `variant` since V017) — **the shadow book**: every
  composite-passing rejection opened as a virtual 1-lot long-premium position (leg from the gate's
  own StrikePicker, entry at the candidate LTP) and closed by `ShadowExitMonitor` (premium brackets
  from the YAML premium_pct rules / structural stop on the signal future / 15:12 square-off / STALE
  for prior-day leftovers). One row per rejection (FK `rejection_id`) **per book**, PnL in points
  + %. `variant='champion'` is the original book; **challenger variants (roadmap F1)** re-score the
  same diagnostic under a config diff (rail threshold override / rail disable / composite floor —
  `ShadowVariants`, env `ARTHA_SCALPER_SHADOW_BOOK_VARIANTS_JSON`) and trade what THEIR config would
  have accepted, so a proposed knob change earns per-variant PnL on identical market data. League
  table: `GET /api/v1/signal-rejections/shadow-summary` + the /signal-rejections page strip. Dedup =
  one OPEN per strategy+side+variant; flag `ARTHA_SCALPER_SHADOW_BOOK_ENABLED` kills all books. **Exit-fidelity caveat: indicator-driven exits
  (trend-flip / signal-exit) are NOT replicated — brackets/structural/square-off only; state this
  in every findings file.** Rejections blocked before the leg resolved (time-window, chain, straddle
  path) never shadow. The rejection JSON also carries `wouldBeLeg` for non-shadowed analysis.
- `marketdata.candles` — signal-future 1m (roll to 3m to mirror the engine) for operand ground truth
  (e.g., what bar volumes are physically possible).
- `marketdata.options_chain_snapshots` — real captured per-strike OI/LTP (3-min) for counterfactual
  premium paths (§4.2).
- Rail registry ground truth: `RailPolicies.java` (48 rails, CLOSED/OPEN), `ScalperGates.java`
  (hardcoded defaults), `ScalperOiProps.java` (`artha.scalper.oi.*` tunables + documented scales —
  note IV values are 0..1 FRACTIONS), per-strategy YAML `scalper.params.*`.
- FE mirror: `/signal-rejections` page (rail filter + rollup + expandable breakdown) — good for live
  eyeballing; SQL for anything quantitative.
- *(add new sources here as they appear — e.g., big-oi-log events, rejection↔spurt joins, …)*

## 3. Standard analysis pass (v1 dimensions — extend freely)

Run in order; each answers one question. Canned SQL in §6.

1. **Volume + coverage** — rows by IST day, strategies seen, fired-signals contrast. Sanity: is the
   engine evaluating at all? Is TODAY's data present?
2. **First-blocking-rail histogram** — where does the funnel die first? (`blocking_rail` counts,
   margins). Caveat: first-rail masks later rails.
3. **All-failed-rails expansion** — unnest `checks[]` where `pass=false`: true failure frequency per
   rail + avg operand vs avg threshold. **A rail whose avg operand is an order of magnitude from its
   threshold is mis-calibrated or reading dead data — flag it.**
4. **Composite distribution** — histogram of `composite_score` by side (CE/PE); near-miss mass just
   under threshold; rows where composite PASSED but something else blocked.
5. **Would-have-fired set** — composite ≥ threshold AND no failed check other than rail X. These are
   the entries knob X alone vetoed → the direct evidence for tuning X. Feed them to §4.2
   counterfactual P&L.
6. **Dot support rates** — per-dot `supports` % across all rows. **0% or ~100% across a whole session
   = dead or free dot** → check whether the cause is (a) missing data (nulls/zeros in `context`),
   (b) threshold outside the operand's physical range, (c) genuinely rare event (fine for a dot,
   suspect for a hard rail). Compute the dead-weight cap: max achievable composite =
   (Σw − Σw_dead)/Σw — if threshold is close to the cap, the composite is structurally starved.
7. **Data-health nulls/zeros** — count nulls per `context.macro`/`oi` field (ivRank, dowUp, fiiLongPct,
   advances/declines=0/0, spurtPricePct=0…). Distinguish honest-null (insufficient history),
   broken-feed (0/0), and by-design (Dow un-armed).
8. **Operand-vs-threshold ground truth** — for any flagged rail, pull the operand's REAL distribution
   from source data (e.g., 3m futures volume percentiles from `candles`) and place the threshold on it.
   A threshold above p100 is unpassable; above p95 is a near-never.
9. **Time noise** — time-window/time-of-day rejection share (known-blocked bars logged repeatedly).
   Doesn't affect tuning; affects table signal-density.
10. **Strategy-coverage ratio** (added 2026-07-17) — `count(DISTINCT strategy_slug)` in the session's
    rejections vs `count(*)` of published+enabled strategies, PLUS the engine's boot line
    (`signal engine loaded N published strategies (M dropped on an unresolved universe)`) pasted into the
    findings file. A silently shrinking numerator is a load/resolution failure that every other health
    signal misses: on 2026-07-17 only **17 of 63** strategies emitted anything (35 → 33 → 17 over three
    sessions, the 38 sensex CE variants totally silent) while capture, chain, futures and eval-loop were
    all healthy. Read the boot line the SAME day — the container's logs are lost on the next restart, which
    is exactly what made 07-17's cause unverifiable. Standing check from 2026-07-16 §6.3 applies: the honest
    health signal is `unresolved == 0`, never `loaded > 0`.
11. **Interior coverage buckets** (added 2026-07-20) — NEVER certify "full session coverage" from
    `min(bar_time)`/`max(bar_time)` alone. Bucket the session's rejections by 15 minutes and read the
    interior: on 2026-07-20 the endpoints spanned 10:19–15:19 while the interior held a 64-minute hole
    after the open and a full hour empty 11:45–12:45, with capture healthy (60–64 one-minute bars) in
    both. Re-running the same query for 2026-07-17 exposed two interior holes there too — which means
    that file's "FULL session, no eval stall, first clean coverage since 07-06" claim was **wrong**,
    drawn from min/max only. Pair the buckets with `strategy.subscriber_health_events` for the same day.
    **Honesty limit:** an empty bucket is NOT by itself proof of a dead engine — `recordRejection` sits
    downstream of the chart-gate early return, so bars dying earlier in the gate write no row. Confirm a
    stall against the eval counters (`ay_signal_eval_outcome_total`, actuator port 8082) and read them
    BEFORE any post-close deploy recreates the container and resets them.
12. **OI quadrant liveness** (added 2026-07-20) — count the share of rows whose
    `context.oi.futuresQuadrant` / `underlyingQuadrant` is `NEUTRAL`. `OiInterpretation.classify` is a
    **total** function over four states with no dead zone (`OiInterpretation.java:16-23`), so NEUTRAL is
    NEVER a market outcome — the strategy-side mirror documents it as "data missing"
    (`OiQuadrant.java:10-25`) and it is the declared fallback on every read failure. **A high
    NEUTRAL share is a defect signal, never a flat-market artifact.** ⚠️ **AMENDED 2026-07-28 — there
    is exactly ONE non-defect cause: a MONTHLY index-expiry day, where `MarketOiClient.oi()` skips the
    whole OI block by design (S24). Apply §3.19's two discriminators before calling NEUTRAL a defect.**
    On 2026-07-20 it was 748/748,
    killing `futures_oi` (w 1.5), `underlying_oi` (1.0) and `oi_spurt` (1.0) and dropping the composite
    cap 0.816 → 0.7181. Worse than a dead IV dot: NEUTRAL dots are added with `absent=false`
    (`ConnectTheDotsScorer.java:207-214`) so they stay in the denominator and score zero, actively
    dragging the composite down, whereas null `iv_rank` is withheld from it. Cross-check
    `marketdata.futures_oi_snapshots` cadence (distinct minutes vs ~375) — a gappy 1-minute capture
    leaves a 3-minute `latestPair` read with no prior bucket, which yields a null interpretation.
13. **Single-rail P&L attribution uses the would-have-fired set, NEVER the shadow book's
    `blocking_rail` bucket** (added 2026-07-21) — the champion shadow book opens on ANY
    composite-passing rejection regardless of how many rails failed, so grouping its positions by
    `blocking_rail` answers "what happened to trades where X blocked *first*", not "what would
    unblocking X alone have done". The two disagreed **in sign, on the same rail, on the same day**:
    on 2026-07-21 `volume-floor` carried +₹2,872.77 in the champion per-rail bucket while the §3.5
    would-have-fired set for the same rail resolved **6 losers out of 6**. To judge a knob, take the
    §3.5 rows (composite ≥ threshold AND no failed check other than X), resolve each via its
    challenger shadow row where one exists and by hand from `options_chain_snapshots` where dedup
    suppressed it, and report that. Keep the per-rail bucket only as context.
14. **Verify the armed knob in the PUBLISHED config, not just in the rejection rows** (added
    2026-07-21) — tag-gated gates (`cfg.has("relative-volume-floor")`,
    `ScalperConfluenceGate.java:422`) silently revert when a strategy is re-published from a stale
    seeder draft. The rejection row shows the *effect* (a flat threshold where a banded one used to
    be); only the registry shows the cause. Standing check each session:
    ```sql
    SELECT (v.config->'tags') ? 'relative-volume-floor' AS armed,
           (v.created_at AT TIME ZONE 'Asia/Kolkata')::date pubdate, count(*),
           string_agg(s.slug, ', ' ORDER BY s.slug)
    FROM strategy.strategies s JOIN strategy.strategy_versions v ON v.id = s.published_version_id
    WHERE s.enabled AND s.slug LIKE 'scalp-%' GROUP BY 1,2 ORDER BY 2,1;
    ```
    A publish stamp newer than the last findings file is the thing to look at. Pair it with a
    per-slug `min(blocking_threshold)`/`max(blocking_threshold)` on the session's `volume-floor`
    rows: `min = max` on a family that used to band is the fingerprint.
15. **Filter 1m candle queries to MINUTE-ALIGNED buckets, and count the misaligned rows** (added
    2026-07-22) — the post-outage gap backfill writes 1m bars at the **tick-gap's second offset**
    (`12:51:38`, `12:52:38`, …) instead of the minute boundary. `marketdata.candles` is keyed
    `(exchange, tradingsymbol, interval, bucket)`, so those are **distinct phantom rows**, not
    upserts: the backfill never replaces the bars it was meant to repair, and every
    `time_bucket` rollup sums the phantom *and* the real bar for the same minute. Measured on
    2026-07-22: **308 rows across 22 instruments** (12:51:38–13:04:39), and it recurs — 403 rows on
    07-20, 887 on 07-15. Impact is not cosmetic: 3m reads are a read-time rollup over the 1m base
    (`CandleRepository.rangeRolledFromOneMinute`), so the live **`volume-floor` operand**,
    `volume-pump`, `rising-volume` and the `volume` dot all inflate after any feed outage (session
    median 13,520 → 14,885 on 07-22, far larger inside the outage window). Only
    `source='BACKFILL'` rows are ever misaligned. Every §3.8-class query must carry
    `EXTRACT(second FROM bucket) = 0`, and the misaligned count is itself a per-session
    data-integrity probe.
16. **Check whether the slug actually configures a premium exit BEFORE resolving any counterfactual**
    (added 2026-07-23) — ⚠️ **AMENDED 2026-07-29: the 21-of-63 statement below is HISTORICAL and no longer
    describes the fleet.** T21 (owner-approved 2026-07-25, #990) added the block to every scalper YAML;
    verified 2026-07-29 by direct count, **63 of 63**.
    ⚠️⚠️ **STOP — READ THE 2026-08-02 CORRECTION BELOW BEFORE USING THE NEXT SENTENCE. Its time-stop
    half is FALSE, and this is the doc every future counterfactual inherits.** The BRACKETS are uniform;
    the TIME STOP is not — the fleet spans FIVE horizons and only 18 of 63 configs (12 of 38 live) run
    30 minutes. The struck text is kept only so the correction has something to point at:
    ~~The shipped shape is `take_profit premium_pct 35` +
    `stop_loss premium_pct 25` + `signal_exit (close < vwap)` + `trailing_stop (supertrend_line)` +
    `time_stop max_bars 10`, so the correct §4.2 model **from 2026-07-25 forward** is a UNIFORM
    +35% TP / −25% SL / 10-bar (30-minute) time stop / 15:12 square-off — the time stop matters most
    (on 2026-07-29 not one of 41 counterfactual legs touched either bracket; every one resolved at the
    time stop).~~
    ⚠️⚠️ **CORRECTION 2026-08-02 (task_2735acfb) — the `time_stop max_bars 10` half of that "shipped shape"
    is FALSE, and this is the doc every future counterfactual reads, so fix the model here before running
    one.** The brackets ARE uniform (T21/#990 put `premium_pct` 35/25 on 63/63). The **time stop is not**:
    every scalper runs a `3m` primary, so `max_bars` is wall-clock, and the fleet spans FIVE horizons —
    **10 = 30 min on 18 configs (12 enabled), 12 = 36 min on 12 (8), 16 = 48 min on 3 (2), 20 = 60 min on
    18 (12), 30 = 90 min on 9 (4)**, plus 3 BTST on `max_holding_days: 1`. Only 18 of 63 configs (12 of 38
    live) run 30 min, and #990 did not set any of them (`max_bars` in its merge diff `64f9caaa`: 0 added,
    0 removed, 30 context). **The engine honours each strategy's own value** — verified in code
    (`ExitEvaluator.java:692-700`, `SignalEngine.java:1672-1684`) and live (8/8 `TIME_STOP` exits held
    exactly `max_bars × 3` min; on 07-29 14:03 two strategies entered the same bar on the same instrument
    and exited 6 minutes apart). **And note what the counterfactual's time stop actually is:** the harness
    (`CounterfactualService`) pins variants with **no strategy version**, and `ExitKnobs.timeStopBars`
    counts **wall-MINUTES on the option's own 1m premium series** — a different rule, instrument and
    resolution from the engine's per-strategy 3m index-future `max_bars`. So a uniform 30-min model is
    still a legitimate MODELLING choice, but **it is a harness parameter and must never be described as
    "the armed fleet-wide stop"** — that conflation is what wrongly promoted T1/T7/G13/G10 to FINAL
    (ledger G11). **When you run a §4.2 counterfactual: state the modelled horizon as a choice, and note
    that after the §3.24 `(bar_time, tradingsymbol)` dedupe 86.2% of legs are claimed by strategies at 2+
    horizons — so a deduped leg has no single owning horizon to match the model to.** Full working:
    `docs/signal-analysis/2026-08-02-tune-verdicts-vs-time-stop-spread.md`.
    ⚠️ The shadow book replicates brackets + structural + square-off but **NOT** the time stop
    or `signal_exit`, so shadow P&L and a §4.2 counterfactual on the same legs legitimately disagree — on
    2026-07-29 they disagreed **in sign** (+₹15,260.87 vs −538.50 pts). Say which model produced which
    number. Findings files written **between 07-23 and 07-25** carry the downward-scope bias the original
    text describes; files before 07-23 carry the upward bias. Original text follows, for those files.
    Only **21 of the 63** YAMLs under
    `services/strategy-signal-service/src/main/resources/scalper-strategies/` carry a `premium_pct`
    block — the `gap-theory`, `market-movers`, `hero-zero`, `btst-stbt` and `straddle` families. The
    `golden-crossover`, `connect-the-dots`, `two-candle`, `trending-oi`, `trend-change` and
    `open-high-low` families have **no take-profit and no premium stop**; they exit only on an
    indicator signal-exit (which the shadow book does NOT replicate), a structural stop where the
    config sets one, or the 15:12 square-off. The shadow book mirrors it exactly: those positions
    carry `take_profit IS NULL` / `stop_loss IS NULL`, and **all 8 `TAKE_PROFIT` closes in the book's
    entire history belong to `gap-theory` / `market-movers`. Standing check each session:
    ```sql
    SELECT strategy_slug, count(*) n, count(take_profit) tp_set, count(stop_loss) sl_set,
           count(structural_stop) ss_set, count(*) FILTER (WHERE close_reason='TAKE_PROFIT') tp_hits
    FROM strategy.shadow_positions GROUP BY 1 ORDER BY 3, 1;
    ```
    Applying a universal +35% TP inflates the win side: on 2026-07-23 it turned a true −451.2 pts
    (6W/16L, square-off only) into −284.4 pts by scoring three phantom TP hits — falsified directly
    by shadow position `id 209`, the same slug on the same leg at the same bar, which had no
    take-profit and closed `SQUARE_OFF`. Earlier files in this folder that applied the +35% rule to
    a bracket-less slug (07-22 §5.1 among them) carry that upward bias.
17. **Count `PartialBucketCanary` WARNs per session and bucket their magnitudes** (added 2026-07-24;
    **mechanism CORRECTED 2026-07-25** — see `2026-07-25-weekly-bug-queue.md` §B2) — the canary
    compares the live engine's last completed **3m** bar volume against the sum of the three **1m**
    bars of the same bucket. Both objects live in `LiveSeriesStore`, but their provenance differs —
    the 07-24 "both in-memory, two-aggregation race" reading was WRONG: **the 3m side is a
    REST-pulled SQL rollup of DB 1m rows that the 10-minute recency window authoritatively replaces
    with broker-official Kite bars at every boundary** (DB-probed: session minutes are
    `source='KITE'`, `fetched_at` in exact 3-minute steps), while the 1m side is live tick-agg and
    is never revised. So the comparison is tick-agg vs broker-official, and **the tick-agg side is
    the diverging one** — the rails (`volume-floor`, `volume-pump`, `rising-volume`, the `volume`
    dot) read the broker-corrected 3m rollup, so the 07-24 "4.7% operand error at the open" was in
    the tick-agg mirror, not in the floor's operand. The exact-lot ± pairs (65…6,110, consecutive
    buckets) are the sub-second boundary straddle between ~1 Hz cumulative-volume snapshots and the
    broker's trade-timestamped attribution — structural residue, ≤8 lots on 35 of 37 measured
    events. The unpaired opening +94-lot error was a real tick-agg defect (warm-process day-rollover
    baselined at zero, folding the pre-open auction into the 09:15 bar) — FIXED with the same B2
    change that set the shipped tolerance default to **650** (10 NIFTY lots). Standing check each
    session:
    ```bash
    docker logs ay-strategy-signal-service --since <today>T03:40:00Z --until <today>T10:00:00Z 2>&1 \
      | grep -oE "canary: [A-Z]+:[A-Z0-9]+@3m|shortfall -?[0-9]+" | paste - -
    ```
    Post-B2, any WARN is signal: the benign ≤10-lot residue is absorbed by the default tolerance
    (650 absolute AND ≤10% of the expected sum — a thin frozen bar still fires), so a surviving WARN
    means either the frozen-partial regression (persistent one-directional ~⅔ shortfall) or a new
    attribution defect. Investigate, don't tolerate-away. **The ± PAIR is the benign fingerprint —
    keep reading unpaired as the alarming shape.**
    **PAIR-AWARE since G9 (2026-08-01):** that fingerprint is now read by the canary itself, not
    only by you — a non-benign bucket is held one bucket, and when the next one carries the
    equal-and-opposite lot-multiple partner **both halves are suppressed together** and logged once
    as `partial-bucket straddle:` at INFO (meter `ay_signal_partial_bucket_straddle_total`). Neither
    half is ever suppressed alone, so **an unpaired WARN still means exactly what this section says
    it means** — the grep above is unchanged and still counts only real, uncorroborated events (the
    INFO line deliberately carries neither the `canary:` nor the `shortfall` token, so it cannot
    inflate the count). **The real latency bounds**, measured from a bucket completing at T (60 s
    sweep, 3 m bucket): a >25%-of-bucket skew — the frozen-partial signature — is never deferred and
    still WARNs by **T+60 s, unchanged**; a same-sign sustained drift is released when the next
    bucket is classified, so its first WARN lands by **T+4 m, one full bucket later**; and a partner
    that never arrives surfaces by **T+5 m, up to ~4 minutes later than before**. Budget for that
    when reading a live session. **After a strategy-signal restart expect MORE WARNs, not fewer:**
    a fresh process starts with an empty lot-size cache and cannot prove a pair is lot-quantised, so
    the first pair it sees is reported as two unpaired events. That is the fail-closed design, not a
    defect — do not chase it. (A half deferred by the previous process is carried across the restart
    in Redis and still reported; if Redis is unavailable the canary simply never defers, i.e. it
    behaves exactly as it did before G9.) Note too that a session's honest health line is now
    **WARNs + straddles**, not WARNs alone. A straddle count that climbs while WARNs stay at zero is the
    benign residue doing what §3.17 describes; a straddle count that collapses to zero while WARNs
    appear is the real signal.
    ⚠ G9 also shipped a bar-size scaling arm (`...volume-tolerance-pct`) but it **stays dormant at
    0 — today's per-event gate is unchanged**, and pair awareness does NOT make it safe to arm: the
    pair's halves are wildly different fractions of their OWN buckets (07-29's ±16,835 pair: 3.7% of
    the 460,005 opening bucket, 11.9% of the 141,245 next one), so any pct that quiets the thick
    half leaves the thin half WARNing with no partner left to corroborate it — manufacturing the
    unpaired shape instead of removing it. Pinned by test; leave it at 0.
18. **Identify the SIGNAL CONTRACT from the data before running any ground-truth query** (added
    2026-07-27) — the live scalper signal series is the **dated front future**, and
    `FuturesUniverseResolver` rolls it at the ~08:40 IST re-resolve near monthly expiry. On 2026-07-27
    every rejection evaluated `NFO:NIFTY26AUGFUT@3m` while every prior file in this folder measured
    `NIFTY26JULFUT`. **Nothing in `signal_rejections` names the contract** — `diagnostic.context.chart`
    has no `signalSymbol` field — so the roll is silent, and a §3.8/§3.15 ground-truth query run against
    last session's contract silently mis-places every threshold. The two series differ materially: on
    2026-07-27 AUGFUT ran p90 47,320 / max 117,000 against JULFUT's p90 57,785 / max 222,560 over the
    same session. Derive it, don't assume it: take any context-bearing row's
    `context.chart.close` and match it against the candidate contracts' 1m ranges for the day (SQL in
    §6). State the contract in the findings file, and **never compare volume percentiles or floor
    thresholds across a roll** without saying so. Rolls are monthly (last Tuesday); the `docker logs`
    line `scalper confluence blocked entry: <slug> NFO:<contract> rail=…` also names it directly and is
    the cheapest confirmation while the container is still up.
19. **On a MONTHLY index-expiry day, expect EVERY OI-derived dot to be inert — and prove it is the
    by-design suppression, not an outage** (added 2026-07-28) — `MarketOiClient.oi()`
    (`MarketOiClient.java:287-295`) tests
    `ScalperCalendars.forUnderlying(underlying).isMonthlyIndexExpiryDay(tradeDate)` and on a hit
    **skips the entire OI block** (S24 — the expiring series' writers are unwinding, so chain OI is
    corrupted), returning an inert `Oi`: both quadrants `NEUTRAL`, sentiment / trending / spurt all
    null, **`futuresBasis` kept**. Measured 2026-07-28: `spurtPricePct` and `spurtOiPct` NULL on
    **26/26** rows (0 nulls on 07-27's 909 and 07-24's 1,100), both quadrants NEUTRAL 16/16, and all
    seven OI-derived dots (`futures_oi`, `underlying_oi`, `oi_spurt`, `drastic_oi`, `sentiment`,
    `sentiment_slope`, `trending_cross`) at **0/16** supports.
    **The two discriminators vs a real outage:** (a) `futuresBasis` stays LIVE while the quadrants go
    NEUTRAL — S24 deliberately keeps the price-derived basis, an outage would not; (b)
    `marketdata.futures_oi_snapshots` keeps full ~1/minute cadence (2,760 snaps / 40 distinct minutes
    by 09:55 on 07-28) — capture is healthy, the gate is choosing not to read it.
    **Suppression is PER OI ROOT, not per day:** NSE monthly (last Tuesday) for a NIFTY-rooted read,
    BSE Thursday monthly for a SENSEX-rooted one. They rarely coincide, so on most expiry days ONE
    root is suppressed and the other is LIVE — a dead OI dot on the non-expiring root is a REAL
    outage. Read the root from `diagnostic->'context'->>'underlying'`, never from the slug name: the
    `sensex-niftyoi` variants read **NIFTY** OI by design (1,069/1,069 rows were `NIFTY 50` across
    07-25…07-28). `DotHealthCanary` labels the affected probes `inert by design` and drops their
    `required` flag, per root (#1073 — before it, the label was missing for `oi_spurt_price` and the
    keying was a blanket NSE-OR-BSE calendar test that could silence a genuine outage on the
    non-expiring root).
    **⚠️ Tuning consequence: NEVER draw an entry-gate calibration conclusion from an expiry session.**
    NEUTRAL dots stay in the denominator with `absent=false` (§3.12), so the composite is
    structurally starved — max **0.3457** against a 0.600 threshold on 2026-07-28. Zero fires is the
    mechanical outcome, not evidence. Mark such a session REGIME in the rollup, never STRUCTURAL.
20. **A dot and its namesake RAIL may not share a threshold — read the scorer's call site before
    attributing a dead dot to data, regime, or the rail's own tuning** (added 2026-07-28) — the
    `volume` dot sat at 0% support for nine consecutive sessions under the standing explanation
    "mechanically dead behind the 125,000 floor". That explanation survived the floor being fixed
    (T16/#980 armed the relative floor on 38/38 scalpers on 07-25) and the dot **still** read 0/909 on
    07-27, which falsified it. The cause is a **call-site divergence**, visible only in code:
    `ConnectTheDotsScorer.java:141` calls the **two-argument** `ScalperGates.volume(underlying, volume)`
    overload, which delegates to `volume(underlying, volume, null)` and resolves the floor via
    `volumeFloorFor(underlying, null)` → the **static per-index default** (`VOL_FLOOR`: NIFTY
    **125,000** / other indices 50,000, `ScalperGates.java:173-175`). The `relative-volume-floor` tag
    substitutes the banded floor **only at the rail's call site** (`ScalperConfluenceGate.java:422`,
    `cfg.has(...)`-gated), which passes an explicit override. **The dot never sees it**, on any
    strategy, armed or not. The arithmetic is the confirmation: on 2026-07-28 (a thick expiry tape) 6 of
    125 3m bars cleared 125,000 (4.8%) and the dot supported 38/1,068 rows (3.6%) — its first non-zero
    reading ever; on 07-27 the same series' max was 117,000, so **zero** bars could clear it and the dot
    read 0/909. Net effect: 1.0 of composite weight permanently gated at roughly the **p95** of its own
    operand. **Standing check when a dot reads 0% (or 100%) across sessions:** find its `add(dots, …)`
    line in `ConnectTheDotsScorer`, follow the gate call it makes, and confirm which threshold that
    overload actually resolves — the rejection row records the dot's *verdict*, only the code records
    which threshold produced it. The same trap is available to any dot/rail pair sharing a name
    (`volume`, `rsi`, `vwap`, `oi_spurt`).
21. **A dot at 0% (or 100%) in a PARTIAL-session read is not a finding — §3.6 support rates are only
    interpretable over a COMPLETE session** (added 2026-07-29) — the 07-29 midday live run recorded
    `trending_cross` at **0/722** on a fully-live-OI day and escalated it to the EOD run as
    "threshold or data?". It was neither: over the full session it is **57/983 (5.8%)**. The CE-over-PE
    dOI cross simply had not occurred yet in the sampled window. The same trap runs the other way — a dot
    at 100% mid-session can fall back over the afternoon. **In a `live` run, write "0 so far" and give the
    denominator + the wall-clock; never write "dead".** Only a session-complete rate belongs in a §3.6
    table or a tuning row.
22. **A "dead" or "free" dot may have a FROZEN operand, and no alive/dead canary probe can see it —
    count DISTINCT operand values across the session, not just the null rate** (added 2026-07-29) —
    `iv_abs_band` (w 0.8) read **0/180 on 07-28** and **133/133 = 100% on 07-29**, which looks like a
    revival. It is not: `diagnostic.context.macro.atmIv` carries **exactly ONE distinct value per
    session** — 0.130859 (07-24) / 0.135577 (07-27) / 0.121736 (07-28) / **0.118781 (07-29)** — so the
    10–12 band test (`ConnectTheDotsScorer.java:210-213`, 0.10–0.12 on the 0..1 fraction scale) is a
    **per-day step function**: 07-28's stamp landed just OUTSIDE 0.12, today's just inside. The dot is a
    coin flip on one stamped number, all session, every session.
    **Why the canary is blind to it:** `DotHealthCanary` tests whether the input is null/non-null, and a
    frozen value is emphatically non-null — it reports `alive`. A frozen operand is a *third* state
    alongside live and dead, and only a `count(DISTINCT …)` finds it. **Standing check whenever a dot sits
    at 0% or ~100% for a whole session:** run the DISTINCT-count over its operand for the session (SQL in
    §6) *before* reaching for a threshold explanation. Scope it — on 2026-07-29 the neighbouring
    `ceIvAvg6` (41 distinct), `peIvAvg6` (44), `ceIvSlope` (100), `vixLevel` (27) and `premiumSkewPct`
    (100) were all moving normally, so the freeze was ONE field, not the IV feed.
23. **Check what is DEPLOYED before explaining live behaviour from source — fingerprint the jar, do not
    read the branch** (added 2026-07-29) — the 07-29 post run explained the `volume` dot's 23.1% support
    rate by reasoning forward from the code path the 07-28 file had root-caused, and filed an "open
    sub-question" about arithmetic that would not reconcile. The arithmetic did not reconcile because
    **the fix had already shipped**: [#1082](https://github.com/prashantm912/artha-yantra-2/pull/1082)
    merged and deployed 2026-07-28, and 07-29 was its first live session. A session file's own tuning
    ledger is written against the code as it stood THAT day; a later session that re-reads the same source
    on a branch — or worse, re-reads the prior file's narrative — will re-derive a stale explanation.
    **Standing check before attributing any live behaviour to a code path:**
    ```bash
    docker exec ay-strategy-signal-service sh -c \
      'unzip -p /app/*.jar BOOT-INF/classes/<pkg>/<Class>.class' | strings | grep -c <newSymbol>
    docker inspect ay-strategy-signal-service --format '{{.State.StartedAt}} {{.RestartCount}}'
    ```
    and confirm the boot time PRECEDES the session but FOLLOWS the deploy. **Then discriminate on data,
    not on the fingerprint alone** — split the session's rows by the dot's verdict and read the operand
    behind each group: on 07-29, 222 of the 227 supporting rows carried a bar volume BELOW the old static
    125,000 floor, which is impossible under the pre-fix code and is therefore positive proof the new
    path ran. Cross-check the forward ledger (`2026-07-02-remaining-items.md` §0) for the row before
    filing a new one — G6 already carried this as DONE.
24. **DEDUPE the shadow book by `(bar_time, tradingsymbol)` before quoting any count, W/L or per-close
    figure — slug fan-out inflates every raw total** (added 2026-07-29) — every live scalper evaluates the
    **same 3m signal series** and resolves its leg through the **same `StrikeLegPicker`**, so one
    qualifying bar opens the *same option leg at the same entry LTP* across every slug whose rails agreed.
    On 2026-07-29 the champion book's **24 closes collapse to 6 bar times / 12 distinct
    `(bar, leg, entry_ltp)` events**, and the **09:48 cluster alone carried +₹15,444.70 of the +₹19,547.61
    square-off gain (79%) and +₹14,625.93 of the +₹15,260.87 session net (95.8%)**. Reported raw, that
    session reads as "24 closes, 14W/10L, the book's best ever"; deduped it is **one bar** carrying the
    session, on an effective independent sample of ~6. **A raw shadow total is a fan-out count, not an
    observation count.**
    ```sql
    SELECT to_char(bar_time AT TIME ZONE 'Asia/Kolkata','HH24:MI') bar, tradingsymbol, entry_ltp,
           count(*) rows, count(DISTINCT close_reason) exits,
           string_agg(DISTINCT close_reason,' / ') reasons, round(sum(pnl_net),2) net
    FROM strategy.shadow_positions
    WHERE variant='champion' AND opened_at >= :d0915 GROUP BY 1,2,3 ORDER BY 1,2;
    ```
    ✅ **The same fan-out is an ASSET for exit analysis, and this is the reusable half.** A cluster where
    `count(DISTINCT close_reason) > 1` on ONE `(bar, leg, entry_ltp)` is a **controlled exit experiment**:
    entry is held constant to the paisa and the slugs' exit configs are the only variable. 2026-07-29's
    09:48 cluster had `scalp-market-movers-*` stopping out at 09:52 while five other slugs held to the
    15:12 square-off — `NIFTY2680423950CE` @318.60 → **−3.80 vs +16.85 ×5**, `SENSEX26JUL77000CE` @613.90
    → **−20.85 vs +107.70 ×5**. That is stronger evidence than a shadow-vs-paper comparison, which is
    always confounded by the two books trading different entries (§2). **Query the multi-exit clusters
    first whenever an exit question is open** — they cost nothing and they are already in the table.

25. **Session REGIME classification** (added 2026-07-30, G15) — stamp every session with a regime so a
    data-gated ledger row (G11 needs a chop day) can ever be told its observation arrived. Metric:
    **intraday directional efficiency** `|close−open| / (high−low)` on the `NIFTY 50` **daily** bar.
    ⚠️ **Intraday, NOT close-over-prior-close** — a 30-minute `time_stop` cannot capture an overnight
    gap, and the two disagree exactly where it matters (2026-07-29 reads +1.10% close-over-close but
    only +0.30% intraday, which is how it was mis-filed as a trend day). ⚠️ **Efficiency, NOT
    close-position-in-range** — the latter saturates (14 of 21 days ≥0.65) and rates a −0.03%-on-0.87%
    chop day at 0.676. Cuts are DERIVED from the largest gaps in the sorted distribution, not picked:
    chop <0.29, mixed 0.29–0.61, trend ≥0.61. Table + method live in `rollup.md` §Session regime; SQL
    in §6.
26. **The entry-knob counterfactual PIPELINE** (added 2026-07-30 — the method that settled T1, G13 and
    G10) — **a pass-rate delta is NOT a result.** Four steps, and skipping any one has produced a wrong
    answer:
    1. **rows newly passing** the knob under test;
    2. **keep only rows where that rail was the SOLE blocker** — G13's +21.7% headline collapsed to 6
       legs here, because `volume-floor` binds 88% of blocks and `confluence-composite` binds 0.9%;
    3. **dedupe by `(bar_time, tradingsymbol)`** (§3.24) — slug fan-out inflates raw counts;
    4. **price** each leg and **test the sign's robustness + subtract costs** — G10 was +324.87 gross
       and −305.88 excluding its top 5 legs of 265, break-even at ~0.35% round-trip.
    Everything needed to price a counterfactual is ALREADY on the rejection row (operand, the floor
    actually applied, every dot's weight+supports, `wouldBeLeg.entryLtp`) — the live side never needs
    modelling. ⚠️ **Standing result: all four measured loosenings of the scalper entry gate LOST money**
    (T1, T7, G13, G10). Treat that as the prior. All four are conditional on the 30-minute `time_stop`,
    so if G11 changes the exit they must be re-run.
27. **On an expiry day the suppression lands on whichever instrument ROLE touches the expiring chain —
    §3.19's OI-root query answers ONLY the OI question** (added 2026-07-30) — a scalper carries up to
    three independent instrument roles (ADR-0003: `signal_underlying` / `strike_reference` /
    `underlying`), so "which root is expiring" has a different answer per role. On the **BSE monthly
    expiry of 2026-07-30** every §3.19 discriminator read *no suppression*, correctly: `context.underlying`
    was `NIFTY 50` on **814/814** rows (the 16 `sensex-niftyoi` slugs read NIFTY OI by design), quadrants
    NEUTRAL **0/814**, `spurtPricePct` NULL **0/814**, `futuresBasis` LIVE **814/814** — NSE was not
    expiring, so no NIFTY-rooted OI read could be suppressed. **The expiry hit the EXECUTION root
    instead, through `strike-pick`:** 405 fails, reason `no strike met the delta/premium band`, on **16 of
    16** sensex-rooted slugs and **ZERO** NIFTY-rooted ones. The three-session control is clean —
    07-28 (NSE monthly) **534 fails, all NIFTY-rooted**; 07-29 (non-expiry) **ZERO**; 07-30 (BSE monthly)
    **405, all SENSEX-rooted**. **So on an expiry day, check `strike-pick` and the `wouldBeLeg` symbols,
    not only the quadrants** — and mark the expiring root's family REGIME for that session even when the
    OI bloc is fully live. ⚠️ The rule is *an expiry saturates the expiring root's `strike-pick`*, **not**
    *only an expiry can*: 2026-07-24 was a Friday with no expiry on either exchange and 550 sensex-rooted
    fails (a thin or freshly-rolled chain is the unexcluded alternative).
    ```sql
    SELECT (r.generated_at AT TIME ZONE 'Asia/Kolkata')::date d,
           CASE WHEN r.strategy_slug LIKE '%sensex%' THEN 'sensex-rooted' ELSE 'nifty-rooted' END fam,
           count(*) strike_pick_fails, count(DISTINCT r.strategy_slug) slugs
    FROM strategy.signal_rejections r, jsonb_array_elements(r.diagnostic->'checks') c
    WHERE r.generated_at >= :d0 AND c->>'rail'='strike-pick' AND (c->>'pass')::boolean=false
    GROUP BY 1,2 ORDER BY 1,2;
    ```
28. **A dot at 0% (or 100%) on a LIVE, MOVING operand is a FOURTH state — "never crosses" — that neither
    the alive/dead nor the frozen probe can see; check the operand's own min/max against the dot's
    threshold before classifying** (added 2026-07-30) — `breadth` (w **1.0**, the canary's only required
    non-OI probe) read **0/814** on 2026-07-30 with the input emphatically healthy: 0 nulls, 0 zero-pairs,
    **10 distinct values**, range **23–32**. Its own reason string is the rule — `advances/declines > 32` —
    and the session maximum was **exactly 32** against a strictly-greater test, the **second** such session
    (07-28's max was also exactly 32; 07-21's was 31). **The cross-session shape is the finding:** over
    2026-07-21…07-30 the dot is **0% on five sessions, ~100% on two, 0.2% on one — never in between**,
    because `advances` is a market-wide scalar shared by every row of a session and moves slowly. A fixed
    threshold on such an operand is therefore **not a per-bar discriminator but a per-session bias** —
    here ±**1.0/18.80 = 5.3 pp** on every composite in the session. `DotHealthCanary` reports
    `alive=true, frozen=false, required=true`, which is correct on both axes and still misses it (§3.22's
    frozen probe needs ONE distinct value; this operand has ten). **Standing check when a dot sits at 0%
    or ~100% over a COMPLETE session (§3.21) and §3.22's DISTINCT-count comes back >1:** pull the operand's
    session min/max and place the dot's own threshold on it, then repeat across sessions — an operand that
    sits wholly on one side of the threshold every session is a step function, not a signal.
    ```sql
    -- the dot's rule is in its own reason string; read it, then bracket the operand
    SELECT (r.generated_at AT TIME ZONE 'Asia/Kolkata')::date d,
           min((r.diagnostic->'context'->'macro'->>:field)::numeric) mn,
           max((r.diagnostic->'context'->'macro'->>:field)::numeric) mx,
           count(*) FILTER (WHERE (r.diagnostic->'context'->'macro'->>:field)::numeric > :threshold) crossing,
           count(*) rows
    FROM strategy.signal_rejections r
    WHERE r.generated_at >= :d0 AND r.diagnostic->'context'->'macro'->>:field IS NOT NULL
    GROUP BY 1 ORDER BY 1;
    ```
29. **UNEXERCISED-PATH audit** (added 2026-07-31) — which ARMED exit rules and gates have NEVER
    fired? An armed-but-never-fired path is unverified code sitting on the money path: T24's exit
    radius was armed for weeks with zero `CONFLUENCE_FLIP` closes platform-wide, and on 2026-07-30
    `take_profit` was armed on **36** published strategies with **ZERO** TP closes since 07-01 (the
    T21 +35% bracket never once paid in a month — that is a strategy-data finding, not a bug).
    Compare armed exit types (published configs) against the fired vocabulary:

    ```sql
    -- fired vocabulary over the window
    SELECT close_reason, count(*) FROM strategy.paper_positions
    WHERE closed_at >= :d0 GROUP BY 1 ORDER BY 2 DESC;
    -- armed exit PATHS across ENABLED published configs: (type, basis), DISTINCT strategies.
    -- Per (type,basis), not bare type — one fired STOP_LOSS must not mask an unexercised
    -- premium/index/structural stop path (review finding, 2026-07-31).
    SELECT r->>'type' AS armed_type, r->'params'->>'basis' AS basis,
           count(DISTINCT s.id) AS strategies
    FROM strategy.strategies s
    JOIN strategy.strategy_versions v ON v.id = s.published_version_id,
         jsonb_array_elements((v.config->'exit_rules')::jsonb) r
    WHERE s.enabled GROUP BY 1, 2 ORDER BY 3 DESC;
    -- TAG-armed exits are NOT in exit_rules and the exit_rules query CANNOT see them —
    -- oi-confluence-exit (fires as CONFLUENCE_FLIP) is a TAG:
    SELECT count(DISTINCT s.id) AS confluence_exit_armed
    FROM strategy.strategies s
    JOIN strategy.strategy_versions v ON v.id = s.published_version_id
    WHERE s.enabled AND (v.config->'tags')::jsonb ? 'oi-confluence-exit';
    ```

    Report the SET DIFFERENCE (armed path with zero fires) every post-market run, with the day's
    delta. Known type→`close_reason` map (VERIFY the fired vocabulary each run — do not assume;
    the engine uppercases the exit TYPE at `SignalEngine:1466`, so every armed type maps):
    `stop_loss`→`STOP_LOSS`/`STRUCTURAL_STOP` (by basis), `trailing_stop`→`TRAILING_STOP`,
    `time_stop`→`TIME_STOP`, `take_profit`→`TAKE_PROFIT`, `signal_exit`→`SIGNAL_EXIT`,
    `square_off`→`SQUARE_OFF`, tag `oi-confluence-exit`→`CONFLUENCE_FLIP`; `MANUAL` maps to no
    armed path. ⚠️ An incomplete map here labels a HEALTHY armed path "never fired" — the first
    draft omitted `signal_exit` (armed on 38) and `square_off` (armed on 2), which the review
    caught; a wrong never-fired verdict is exactly the false alarm this dimension must not raise. A path that has never fired is either
    (a) genuinely unreachable this regime (say so, with the nearest-miss distance), (b) mis-wired
    (the T24 class), or (c) shadowed by an earlier rule that always wins (`time_stop` at 30 min ate
    every exit in the G11 analysis) — distinguish, never just count.

    ⚠️ **The armed granularity is FINER than the fired vocabulary — do not report a (type, basis)
    row as exercised or as never-fired when it is neither** (added 2026-07-31 after the E2E audit
    caught the 07-31 run reporting 8 armed rows where its own query returns 10). The query groups by
    `(type, basis)`; `strategy.paper_positions` carries **no column naming the rule that fired** —
    `close_reason` is the entire exit vocabulary (verified: the table has no rule/basis column). So
    several armed bases collapse onto one `close_reason` and become mutually indistinguishable:

    | armed type | bases armed (live, 2026-07-31) | close_reasons available |
    |---|---|---|
    | `stop_loss` | `premium_pct` 30 · `index_points` 8 · `percent` 4 · `atr_multiple` 2 | `STOP_LOSS` + `STRUCTURAL_STOP` — 4 bases onto 2 reasons |
    | `trailing_stop` | `indicator` 42 · `atr_multiple` 2 | `TRAILING_STOP` — 2 bases onto 1 reason |

    Classification rule: a `(type, basis)` row is **exercised** only when its type fired AND no
    sibling basis could have produced that fire; **never-fired** only when the TYPE itself has zero
    fires; otherwise **INDETERMINATE — not attributable from `close_reason`**. The two
    `atr_multiple` rows (2 strategies each) are the standing INDETERMINATE pair: their types fire
    constantly via other bases, so neither "exercised" nor "never fired" is defensible. Report them
    as INDETERMINATE every run rather than dropping them — silently omitting a row is how the 8-vs-10
    discrepancy happened, and a dropped row reads as "covered everything" when it was not.

30. **Sub-account FREEZE telemetry** (added 2026-07-31) — the #1086 capital governors freeze a
    sub-account for the rest of the session after a loss threshold; on their FIRST live day 3 of 5
    froze by 13:40 and a 6th entry would have found 4/5 frozen. Whether that is protection or
    capacity starvation is a data question, and it needs a daily row, not an anecdote:

    ```sql
    -- ENTRIES come from paper_events, not positions (review finding, 2026-07-31): pyramiding
    -- AVERAGES INTO the open row — qty and avg price move, opened_at does NOT — while
    -- paper_events records one OPENED per open-or-average-in (V038). Position-row counting
    -- under-counts entries and mis-times the freeze.
    SELECT p.subaccount_idx,
           count(e.id) AS entries,
           to_char(max(e.created_at) AT TIME ZONE 'Asia/Kolkata','HH24:MI') AS last_entry_ist
    FROM strategy.paper_events e
    JOIN strategy.paper_positions p ON p.id = e.position_id
    WHERE e.kind = 'OPENED' AND e.created_at >= :d0 AND p.subaccount_idx IS NOT NULL
    GROUP BY 1 ORDER BY 1;
    -- day PnL SEPARATELY (a position joined through its events would count once per event)
    SELECT subaccount_idx, round(sum(realized_pnl), 2) AS day_pnl
    FROM strategy.paper_positions
    WHERE closed_at >= :d0 AND subaccount_idx IS NOT NULL
    GROUP BY 1 ORDER BY 1;
    ```

    Track `frozen-by` time per idx across sessions. If the median freeze lands before 13:00 for a
    week, the governors are the binding entry constraint — that goes to the owner as a
    capacity-vs-protection decision with the counterfactual P&L of the entries they blocked
    (§3.26 pipeline), not as a tuning proposal.

31. **A sub-account discipline freeze TRUNCATES the rejection stream — discriminate it from a stall
    via the eval-outcome buckets, and treat every post-freeze table as partial-session** (added
    2026-07-31) — the §12.7 five-account discipline (`ScalperAccountModel.scalperEntryAllowed`:
    an account freezes for the day on its FIRST losing close OR on banking ~1% of its ₹30,000
    allocation; all 5 frozen ⇒ no fresh scalper entry) is consulted in `SignalEngine.scalperEntry`
    **BEFORE the confluence gate**, returning `DISCIPLINE_PAUSED` — so a fully-frozen fleet writes
    **zero rejection rows** for the rest of the session while the engine is completely alive. First
    live observation 2026-07-31: all 5 subs frozen by 13:34 (2 profit-locks + 3 first-losses),
    `discipline-paused` counter 224 (first non-zero ever), rejections end 13:34 sharp, gauges fresh
    to the close. **The discriminator vs a stall:**

    ```sql
    SELECT to_char(bucket_time AT TIME ZONE 'Asia/Kolkata','HH24:MI') ist,
           sum(eval_count) FILTER (WHERE outcome='discipline-paused')  paused,
           sum(eval_count) FILTER (WHERE outcome='confluence-blocked') confl,
           sum(eval_count) FILTER (WHERE outcome='chart-gate-failed')  chart
    FROM strategy.signal_eval_outcomes
    WHERE bucket_time >= :d0 GROUP BY 1 ORDER BY 1;
    ```

    `paused > 0` while `chart` keeps advancing = freeze (healthy); everything at 0 with stale
    gauges = stall. ⚠️ Consequences for analysis: (a) every §3.3/§3.6 table on a freeze day is a
    **partial session** (§3.21 class) — say so; (b) §3.10 coverage counts shrink mechanically;
    (c) the freeze is ALSO §3.30's telemetry subject — log frozen-by times per sub and which rail
    (profit-lock vs first-loss) froze each. A profit-lock freeze banking a green day is the design
    working, not starvation.
32. **Verify the daily bar against the DERIVATIVE COMPLEX before stamping regime — a single bogus
    closing tick can rewrite the day** (added 2026-08-03) — the `NIFTY 50` 1m stream froze at
    24,573.35 for 15:20–15:28, then the 15:29 `TICK_AGG` bar printed **24,774.30 (+200.95 pts in
    one minute)**; the session high excluding that bar was 24,609.45. Three independent markets
    refuted the print: the front future ticked freely and closed 24,650 (a real +200 index move
    cannot leave the future 124 pts UNDER spot), the near-ATM CE closed at intrinsic-plus-time for
    spot ~24,573, and SENSEX (unfrozen) drifted DOWN across the same minutes. The tick poisoned
    THREE surfaces: the 1m bar, **the 1d daily bar** (`source=KITE, fetched_at 15:45` — Kite's own
    REST carried it at fetch time; high AND close), and `options_chain_snapshots.spot_price` for
    the 15:28–15:30 captures. Consequences: the G15 regime stamp flipped **trend-up 0.778 →
    chop 0.007** on correction; the swing books' RS benchmark and any backtest window covering the
    day inherit the bar. ⚠️ **It does NOT self-heal**: cache-first reads re-fetch only the 10-min
    trailing tail (a present, past bucket is skipped) and bhavcopy is DO-NOTHING — repair needs an
    authoritative re-fetch or hand UPDATE (ledger G18). **Standing check before writing any regime
    row:**
    ```sql
    -- (a) is the daily close reachable from the 1m series ex-the-last-bar?
    SELECT max(high) FROM marketdata.candles
    WHERE tradingsymbol='NIFTY 50' AND exchange='NSE' AND interval='1m'
      AND EXTRACT(second FROM bucket)=0 AND bucket >= :d0915 AND bucket < :d1529;
    -- (b) does the future corroborate the close? (index close far above spot-implied future = bad tick)
    SELECT close FROM marketdata.candles WHERE tradingsymbol=:front_fut AND interval='1m'
      AND bucket = :d1529 AND EXTRACT(second FROM bucket)=0;
    -- (c) the option market's verdict: near-ATM CE ltp at 15:30 vs intrinsic under the claimed close
    SELECT ltp, spot_price FROM marketdata.options_chain_snapshots
    WHERE underlying=:u AND expiry=:front_exp AND strike=:atm AND option_type='CE'
      AND ts >= :d1528 ORDER BY ts DESC LIMIT 3;
    ```
    A daily high/close that exists ONLY in the final bar, against a flat future and an option chain
    pricing the old spot, is a bad tick — compute the regime from the 1m series ex-that-bar, state
    both values, and file the repair.
    ⚠️⚠️ **AMENDED 2026-08-04 — the 2026-08-03 print this section was written about was NOT a bad
    tick, and the "file the repair" instruction above is RETRACTED for that class.** The shape it
    describes (1m closes frozen near 15:15, a large final print, futures basis "breaking" at the
    close) is the **CAS official close** (§3.33) — NSE/BSE's Closing Auction Session launched
    2026-08-03, the very day this section was written, which is why the shape had never been seen
    before. The three SQL discriminators above remain USEFUL — but they now DETECT the CAS shape;
    they no longer prove poison. A genuine bad tick (the class this section should still catch)
    would appear MID-session, not in the 15:15–15:30 auction window. Do not repair an official
    close; do not quarantine it at capture. Full evidence: `2026-08-04-session-findings.md` §6.1.
33. **CAS-era close semantics — continuous close vs official auction close** (added 2026-08-04) —
    since **2026-08-03**, NSE/BSE run a **Closing Auction Session**: continuous trading in
    F&O-enabled (Category I) stocks ends **15:15 IST**, a call auction 15:15–15:30 sets the
    official closing price (finalised 15:30–15:35), and the index official close derives from CAS
    constituent closes. Every session now has **TWO closes**, and the data shows both:
    - the **continuous close** — the last level before the ~15:14/15:15 freeze in the index 1m
      stream (the freeze IS continuous trading ending; both indices pin simultaneously);
    - the **official (CAS) close** — arrives as a late 1m print ~15:28–15:29 (measured +151.45 on
      08-04, +200.95 on 08-03), lands in the 1d daily bar, and flips
      `options_chain_snapshots.spot_price` from 15:28 onward. The two can differ by 150–200 pts,
      and the futures (which trade continuously to 15:30) track the continuous market, so the
      apparent close-time basis inverts — that is mechanics, not corruption.
    **Rules:** (a) the **G15 regime stamp uses the CONTINUOUS session** (open → continuous close) —
    an intraday time stop cannot trade the auction; stamp both values in the rollup row. (b) daily
    bars, RS-rank and backtests keep the OFFICIAL close — never "repair" it. (c) expiry-day option
    settlement follows the official close — measured 08-04: five expiring strikes converged to
    settlement 24,613–24,615 against an official close of 24,614.90 while the last continuous
    level was 24,463.45. (d) session-TAIL analytics (VWAP, last-30-min reads, 3m rollups touching
    15:15+) now mix two price regimes — bound them at 15:15 or say which close they use. Ledger
    row **G18** (rescoped) tracks any build fallout (e.g. parity on post-CAS replay data).
    ```sql
    -- the two closes, side by side (freeze detector + official print)
    SELECT to_char(bucket AT TIME ZONE 'Asia/Kolkata','HH24:MI') t, close, source
    FROM marketdata.candles
    WHERE tradingsymbol='NIFTY 50' AND exchange='NSE' AND interval='1m'
      AND EXTRACT(second FROM bucket)=0 AND bucket >= :d1510 AND bucket < :d1530 ORDER BY bucket;
    -- continuous close = the pinned value before the late jump; official = the 1d bar's close.
    ```
34. **Heat-gate evaluability on every funded fire** (added 2026-08-05) — the paper book's F9
    heat-cap is FAIL-SOFT: `PaperMarginClient.margin()` (→ market-data `POST /api/v1/market/margin`)
    returns an `unpriced` quote on ANY failure and `RiskService` logs
    `heat-cap enforcement ON but heat unassessable — gate inert this entry` and lets the entry
    through. That is the designed degrade — but it means a wire defect silently disables a
    money-path guard, and NOTHING else surfaces it. First live instance 2026-08-05 11:04: the
    session's ONLY funded entry got `Error while extracting response ... content type
    [application/octet-stream]`, twice (entry path + notifier). Standing check on every session WITH
    a funded fire:
    ```bash
    docker logs ay-strategy-signal-service --since <open-UTC> 2>&1 \
      | grep -cE "heat call failed|heat unassessable"
    ```
    Non-zero = the F9 heat check never ran on those entries — report it and count it against the fire
    count. (On a zero-fire session the grep proves nothing — the gate only runs at entry.)

    ⚠️ **CORRECTED 2026-08-05 (same day, after the finding was written) — read
    [`2026-08-05-f9-heat-cap-inert.md`](2026-08-05-f9-heat-cap-inert.md) before acting on this row.**
    Two things above are wrong. **(1) The cause is not a content-type/wire defect.** It is
    `PaperMarginClient`'s 2000 ms read timeout against `UpstoxFnoMasterClient`'s lazy 5 MB+ gzip
    master load, which is itself budgeted 60 s — the day's first `keyFor()`. Both WARNs fired at
    exactly 2000 ms and the master completed 535 ms later. Deterministic per container start / per
    12 h refresh lapse. **(2) A ZERO grep does NOT mean the cap covered the entry.** Heat is computed
    as `spanMargin / equity`, and every scalper position is a long option BUY, which carries no SPAN
    — 10 of 10 priced snapshots are `0.00`, so the normal path yields `0.00%` against a `60%` cap and
    blocks nothing. Zero here means "the call succeeded", not "the control worked". Treat this §3.34
    row as an evaluability probe only; **coverage is an open owner question (ledger N23-A)**.
35. *(new dimensions land here — keep numbering append-only so findings files can cite "§3.6" stably)*

## 4. Live in-session analysis

Safe: everything here is read-only SELECTs on small tables / bounded candle ranges. Do NOT deploy,
restart services, or write during market hours.

### 4.1 Data-health watch (any time mid-session)
- Rejections flowing? `SELECT count(*), max(generated_at) FROM strategy.signal_rejections WHERE
  generated_at > <today 09:15 IST>` — tens of rows/hour when the chart gate is passing.
  ⚠️ **Zero mid-session is NOT an engine-or-feed problem by itself** (corrected 2026-07-26 — the old
  text here said it was): `recordRejection` runs only PAST the chart gate, so an ordinary
  SuperTrend-DOWN leg silences every scalper at once. Confirm liveness POSITIVELY via a fresh
  `strategy.signal_eval_outcomes` row (§4.3 step 4) before calling anything a problem.
- ⚠️ **Before ~09:45 IST only a handful of strategies are in-window, so a FLAT Σ
  `ay_signal_eval_outcome_total` there is the trade WINDOW, not a stall** (added 2026-07-27). Most
  scalper YAMLs open after 09:45 (the cross-strategy "after 09:45" rule); the `morning-trade` family
  is the deliberate exception (`window: { from: "09:16", to: "15:00" }`, owner-confirmed in its YAML
  header). Measured 2026-07-27: Σ sat at **36 across two reads spanning 09:43:45–09:45:33** with
  **2** slugs emitting, then jumped to **72 with 16 slugs by 09:46:54** as the 09:45 bar brought the
  rest in-window — a +36 step in ~1.4 min, after ~2 min of apparent flatness. Both
  `ay_signal_bar_*_age_seconds` gauges read fresh throughout, which is what actually settled it.
  Practical rule: read the gauges for liveness, and if you want a counter DELTA that means anything,
  space the two reads across a bar boundary **after 09:45**. Σ is an attribution primitive (§4.3
  step 4), never a liveness one — flatness inside the opening half-hour is the single easiest way to
  re-manufacture the 07-17 false escalation.
- Context nulls (dimension §3.7) on TODAY's rows — catches a dead feed the same day it dies, not at EOD.
  ⚠️ **`iv_rank` / `dow` / `fii` dead in a MORNING dot-health read is the standing state, not "too
  early to populate"** (added 2026-07-27, correcting the reading carried in `2026-07-27-open-gate.md`
  §6): the 07-24 ledger already has `ivRank` NULL 100%, `fiiLongPct` NULL 100% (both dead-data, carried
  since 07-02) and `dowUp` NULL by design (un-armed). All three are `required: false`. Only a CHANGE
  in that set — one of them alive, or a fourth dot joining them — is news.
- ⚠️ **Dot support rates read mid-session are PROVISIONAL — write "0 so far (n=…, as of HH:MM)", never
  "dead"** (added 2026-07-29, §3.21). The 07-29 midday run reported `trending_cross` **0/722** on a
  fully-live-OI day; the full session was **57/983 (5.8%)**. Escalating a partial-read 0% costs the EOD
  run a carry item and can manufacture a phantom tuning row.
- ⚠️ **The G12 `frozen` flag is likewise UNINTERPRETABLE in a morning read — `frozen:false` before
  ~8 distinct bars is an un-evidenced default, not a refutation of a known freeze** (added
  2026-07-30, the same partial-read trap as §3.21 one field deeper). The frozen-operand probe
  (#1111, closing §3.22/T28) needs `MIN_FROZEN_BARS = 8` distinct operand-bearing bars — ~24 minutes
  on the 3m primary — before one distinct value may be called frozen, and **below that it reports
  `frozen:false` rather than "unknown", deliberately: "the flag is an assertion, and an un-evidenced
  assertion must read false"** (`DotHealthCanary.java:63-67`). Measured 2026-07-30 09:43 IST: all
  nine dots `frozen:false` on `rowsInspected=6` — consistent with T28's frozen `atmIv` and with a
  genuine thaw alike, so it discriminates neither. **In a `live` run, quote `rowsInspected` next to
  any `frozen` verdict and treat a sub-8 reading as no-data.** Only a session-complete read (or the
  §6 `count(DISTINCT …)` over the operand) settles a freeze.
- ⚠️ **Fingerprint the JAR before attributing live behaviour to a code path** (added 2026-07-30,
  §3.23 — this entry was PROMISED by the 07-29 rollup row and never written; the dangling reference
  is what surfaced it). A deploy can report SUCCESS, go healthy and pass its own probe while shipping
  a jar that omits other merged work. `docker exec ay-<svc> sh -c 'unzip -l /app/*.jar | grep -c
  <NewClass>'` is the cheap generic check. ⚠️ Never probe a `*Test` class (never in the service jar),
  and remember a nested `BOOT-INF/lib/*.jar` class returns 0 from the OUTER jar and looks like a
  failed deploy. Also fingerprint a RECENT unrelated fix, to rule out a silent revert.
- **Session REGIME classification** (added 2026-07-30, §3.25 — G15). Stamp every session row with a
  regime so a data-gated row (G11 needs a chop day) can ever be told its observation arrived.
  Metric: **intraday directional efficiency** `|close−open| / (high−low)` on `NIFTY 50` daily.
  ⚠️ **Intraday, NOT close-over-prior-close** — a 30-minute time stop cannot capture an overnight
  gap, and the two disagree exactly where it matters (2026-07-29 reads +1.10% close-over-close but
  only +0.30% intraday, which is why it was mis-filed as a trend day). ⚠️ **Efficiency, NOT
  close-position-in-range** — the latter saturates (14 of 21 days ≥0.65) and rates a −0.03%-on-0.87%
  chop day at 0.676. Cuts are DERIVED from the largest gaps in the sorted distribution, not picked.
  Table + method live in `rollup.md` §Session regime.
- **The entry-knob counterfactual PIPELINE** (added 2026-07-30, §3.26 — the method that settled T1,
  G13 and G10). A pass-rate delta is NOT a result. Four steps, and skipping any one has produced a
  wrong answer:
  1. **rows newly passing** the knob under test;
  2. **keep only rows where that rail was the SOLE blocker** — G13's +21.7% headline collapsed to 6
     legs here, because `volume-floor` binds 88% of blocks and `confluence-composite` binds 0.9%;
  3. **dedupe by `(bar_time, tradingsymbol)`** — slug fan-out inflates raw counts;
  4. **price** each leg and **test the sign's robustness + subtract costs** — G10 was +324.87 gross
     and −305.88 excluding its top 5 legs of 265, break-even at ~0.35% round-trip.
  ⚠️ **Standing result: all four measured loosenings of the scalper entry gate LOST money** (T1, T7,
  G13, G10). Treat that as the prior. All four are conditional on the 30-minute `time_stop`, so if
  G11 changes the exit they must be re-run.
- ⚠ **On an expiry day the suppression lands on whichever instrument ROLE touches the expiring
  chain — and §3.19's OI-root query CANNOT see it** (added 2026-07-30, §3.27; this entry was claimed
  as promoted by the 07-30 findings and was NOT actually written — the second dangling promotion in
  two sessions, after §3.23). §3.19 answers only the OI question. On 2026-07-30 (BSE monthly) every
  S24 discriminator read *no suppression* and was CORRECT — `context.underlying` was `NIFTY 50` on
  814/814, quadrants NEUTRAL 0/814, basis live 814/814 — because the `sensex-niftyoi` slugs read
  NIFTY OI **by design**. The expiry instead surfaced in `strike-pick`: 405 fails on **16 of 16
  SENSEX-rooted slugs and ZERO NIFTY-rooted ones**. Matched root-swap control across three sessions:
  07-28 (NSE monthly) 534 fails, all NIFTY-rooted → 07-29 (non-expiry) ZERO → 07-30 (BSE monthly) 405,
  all SENSEX-rooted. **Check the EXECUTION root, not just the OI root** — they are different
  instruments under ADR-0003's three-way decoupling. ⚠ 07-24 (Friday, no expiry, 550 SENSEX fails)
  does not fit, so the claim is *an expiry saturates the expiring root*, NOT *only an expiry can*.
- ⚠ **A dot at 0% is THREE explanations deep now, and the third one HAS a probe since G16** (added
  2026-07-30, §3.28; probe added 2026-08-01): a dead input (null — the canary sees it), a **frozen**
  input (one distinct value — #1111's probe sees it), or a **live, moving operand that never crosses
  its threshold** — now the `neverCrossing` NEAR-MISS state on `/api/v1/signal-rejections/dot-health`
  (`DotHealthCanary.nearMiss`): supports one-sided within a 2% minority tolerance AND the operand's
  extremum within epsilon of the dot's rule, judged only for fixed-global-threshold dots (breadth
  today). ⚠ Its evidence is **session-wide, deduped per (bar, side)** — NOT the bounded newest-N
  window the `alive`/`frozen` probes read, which under fan-out can cover a handful of bars and would
  let an early crossing age out of a state that claims "session max".
  `breadth` on 2026-07-30 was the discovered case: 0/814 with 10 distinct values over 23–32, against
  a `> 32` rule whose session max was exactly 32. ⚠ The probe deliberately does NOT flag a 0% dot
  far from its line (the 07-31 `oi_spurt` conjunct-starved reading — regime, not telemetry), and it
  is telemetry-only (never pages; the threshold is doctrine). In a `live` run do not classify at all
  (§3.21) — the flag reads "so far today"; at EOD still place the dot's own threshold on the
  operand's session min/max **before** reaching for a data explanation.
- Capture liveness: `max(bucket)` on 1m candles + snapshot counts vs wall clock.

### 4.2 Live counterfactual — "would loosening knob X have made money TODAY?"
v1 (approximate, works now, zero code):
1. Take the §3.5 would-have-fired rows (or re-run the query intraday) → each has `bar_time`, side,
   spot, and the full context.
2. Pick the leg the engine would pick: ATM strike from `context.chart.close` (nearest 50 for NIFTY),
   the side's option, front weekly expiry.
3. Pull the premium path from `options_chain_snapshots` (3-min LTP series for that strike/side) from
   `bar_time` forward.
4. Apply **that slug's** exit grammar approximately — **first check §3.16**: a +35% premium
   take-profit applies ONLY to a slug whose YAML carries a `premium_pct` block (gap-theory /
   market-movers / hero-zero / btst-stbt / straddle). For every other slug the model is the
   structural stop (where configured) and the 15:12 square-off, with **no take-profit**. Mark the
   row `WOULD-WIN / WOULD-LOSE / UNRESOLVED` + points.
5. Record per-row outcomes in the session findings file (§5 template has a table) — over sessions this
   becomes the gate-tuning evidence base.

Honesty caveats (state them in every findings file): 3-min LTP granularity (a 1m stop/TP touch can be
missed), no slippage/fees, strike-pick approximated (the real `StrikeLegPicker` may pick ±1 strike),
exit approximated. **v2 (exact) needs a small build:** persist the would-be leg
(strike/expiry/entry-LTP) on each rejection row at evaluation time — then the counterfactual is exact
and mechanical (see §7 improvement list).

### 4.3 Market-open signal-liveness gate (the `open` routine)

A fast, read-only **PASS / FAIL / INCONCLUSIVE** check run ~15–20 min after the open (or anytime in-session) that catches the
**silent starvation class** — capture healthy but the engine emitting nothing (the 2026-07-14 incident:
zero signals/rejections all session while candles captured fine, because the single-threaded eval loop
was parked on an unbounded market-data fetch; fixed #866). No canary alarmed on "healthy feed + zero
rejections," so this gate closes that gap. Run it every trading morning (schedule it, or on ask).

Steps (all read-only — never restart/deploy/write mid-session):

1. **Time + trading-day** — `TZ='Asia/Kolkata' date`; confirm 09:15–15:30 IST and not a holiday
   (`libs/market-calendar`). Off-hours/holiday ⇒ STOP (zero is normal). **Clock trap:** the containers
   run UTC — the `market/health/data` `asOf` is UTC; `10:41Z` = 16:11 IST.
2. **Stack + login** — the four signal containers healthy; `marketdata.kite_session.last_validated_at`
   is today (daily login done); market-data canary GREEN (`GET /api/v1/market/health/data`, in-container
   `wget` on `:8081` bypasses gateway auth).
3. **Capture fresh** — the scalper signal future has a full recent 1m series:
   `SELECT count(*), max(bucket AT TIME ZONE 'Asia/Kolkata') FROM marketdata.candles WHERE exchange='NFO'
   AND interval='1m' AND tradingsymbol=<front-fut> AND bucket >= <today 09:15 +05:30>;` — count should
   track minutes-since-open, max ≈ now−1m.
4. **THE GATE — rejections flowing?**
   `SELECT count(*), max(generated_at AT TIME ZONE 'Asia/Kolkata') FROM strategy.signal_rejections
    WHERE generated_at >= <today 09:15 +05:30>;`
   - **>0 and max within a few min of now ⇒ PASS.** Rejections are sufficient evidence of a live
     engine — their presence proves the gate ran.
   - **0 ⇒ INCONCLUSIVE, *not* FAIL.** ⚠️ **CORRECTED 2026-07-26 — the old rule here ("0 while
     capture is healthy ⇒ FAIL = STARVATION") is WRONG and cost a false live-starvation escalation
     on 2026-07-17 plus a needless restart on 07-20.** `recordRejection`'s call sites both sit
     inside the chart-gate-passed branch, so a chart-stage no-entry writes NOTHING. Every published
     scalper shares the same two required scorers on one 3m NIFTY-future series, so **SuperTrend
     DOWN + RSI < 58 silences every scalper simultaneously on ordinary bearish tape.** An 84-minute
     hole was escalated as starvation and the engine was healthy the whole time — a thread dump
     taken during it showed `signal-eval` parked on its own empty queue (an idle worker, not a
     stall). The `SignalStarvationCanary` that automated this exact rule was **retired** on
     2026-07-26 for the same reason.
   - **On 0, demand POSITIVE proof of life — never infer it from an absence.** Inspect the **LATEST
     BUCKET ONLY**, never a session-wide sum:
     ```sql
     SELECT bucket_time AT TIME ZONE 'Asia/Kolkata' AS ist, sum(eval_count) AS evals
       FROM strategy.signal_eval_outcomes
      WHERE bucket_time >= <today 09:15 +05:30>
      GROUP BY bucket_time ORDER BY bucket_time DESC LIMIT 3;
     ```
     ⚠️ **`sum()` over the whole session is WRONG** and was the first version of this query: once any
     evaluation happens the running total stays positive while later all-zero buckets keep advancing
     `max(bucket_time)`, so **an engine that died at 10:00 would PASS all afternoon.** Read the top
     row only. If the PREVIOUS bucket is missing or late, stay INCONCLUSIVE — a recovered delta can
     span several windows, so one bucket's number is not attributable to one window.
     `SignalEvalOutcomeRollupJob` writes a row every **3 minutes** in-session
     (cron `0 */3 9-15 * * MON-FRI` IST) **unconditionally — an idle bucket writes zeros**, and
     V045's own note says so: *"Zero is meaningful — it proves the process was alive."*
     - **A fresh bucket with `sum(eval_count) > 0` ⇒ PASS-QUIET.** A non-zero delta is *positive*
       proof the eval loop ran in that window. (Zero is not proof of the opposite — it is legitimate
       when every strategy is out-of-window or in-position — which is why this is asserted one-way
       only.)
     - **A fresh bucket that is ALL ZEROS ⇒ still INCONCLUSIVE — "process alive, evaluation
       unproven".** ⚠️ Freshness alone does NOT prove the engine is evaluating: the rollup runs on
       its own dedicated scheduler thread and merely *snapshots counters*
       (`SignalEvalOutcomeRollupJob:135-140`, and its repository is by design never written from the
       `signal-eval` thread). **A stuck eval thread or a dropped subscriber keeps producing fresh
       all-zero buckets forever.** Escalate — do not PASS.
     - **No fresh row while capture is healthy ⇒ FAIL — escalate.** (The job is fail-soft and
       `@ConditionalOnProperty`, so absence is "engine dead **or** the writer is down/disabled" —
       both warrant a look; unlike a missing stall row, it is never routine.)
     - **What a thread dump does and does not settle.** `docker exec ay-strategy-signal-service sh -c
       'kill -3 1'` → read `docker logs`.
       - `signal-eval` inside `MarketDataCandlesClient.fetch` / `LiveSeriesStore.refreshFromRest`, or
         showing CPU activity, is **NOT by itself a stall** — `refreshFromRest` is ordinary
         coarse-bucket work and its HTTP fetch is *deliberately* allowed to block for up to ~10 s, so
         a perfectly healthy evaluation can be caught in that exact frame. **Take REPEATED dumps
         (≥2, spaced past that timeout) and compare:** no forward progress across them ⇒ **real
         stall, FAIL**; different frames, or the same frame within the timeout ⇒ still
         **INCONCLUSIVE**. (One dump in `fetch` was called an immediate FAIL in an earlier version of
         this text — wrong for the same reason the rest of this section was wrong: a single sample of
         a legitimately-blocking call is not evidence.)
       - `signal-eval` parked on `LinkedBlockingQueue.take()` with low cumulative CPU ⇒ **the eval
         thread is unstuck, but receipt liveness is UNPROVEN — still INCONCLUSIVE.** ⚠️ It is *candle
         receipt* that submits the drain onto that single-thread executor, so **a dropped Redis
         listener submits nothing and leaves the thread parked in exactly the same stack as a healthy
         idle worker.** Quiet tape and a dead subscriber are indistinguishable here. (An earlier
         version of this runbook called this stack "healthy" and "definitive". It is neither.)
     - ✅ **The definitive read (task_0bed1621, shipped 2026-07-26): two Micrometer gauges on the
       strategy-signal actuator.** These publish the ages of the ONLY two unconfounded oracles, so a
       quiet session is no longer unprovable:
       ```bash
       docker exec ay-strategy-signal-service sh -c          'wget -qO- http://127.0.0.1:8082/actuator/prometheus' | grep ay_signal_bar_
       ```
       - `ay_signal_bar_received_age_seconds` — seconds since the engine last RECEIVED a closed bar.
         Stamped as the FIRST line of `onCandleMessage`, before any universe / session-window /
         position / loaded logic, so it is direction-, window- and position-independent.
       - `ay_signal_bar_evaluated_age_seconds` — its evaluation-side twin.

       **Verdict needs BOTH gauges — received alone is not enough.** Bars can keep arriving while
       `signal-eval` is wedged, which holds received-age fresh while evaluated-age grows; reading
       received only would call that PASS.

       | received-age | evaluated-age | verdict |
       |---|---|---|
       | fresh (≲ 1–2 bar intervals) | fresh | **PASS-QUIET** — engine alive, tape simply bearish |
       | fresh | **growing** | **FAIL — eval stall.** Bars arrive, evaluation does not keep up |
       | **growing** (capture healthy) | any | **FAIL — receive-side stall** |
       | **negative** | any | **NOT a valid age — never read as healthy** (see below) |

       ⚠️ **A NEGATIVE age is not a small age.** `-1` means no bar has EVER been received or
       evaluated on this boot — the stamps are seeded at construction for the canary's boot grace,
       so a plain age would read ~0 there and look identical to a bar that just landed. Anything
       **below** `-1` means the clock stepped BACKWARDS past the stamp — a clock fault, exactly the
       class that produced an 87-minute host drift in July 2026, deliberately surfaced instead of
       clamped away.

       A MISSING series is **FAIL / unobservable**, not proof the process is down: the engine may be
       running with `artha.signals.engine-enabled=false`, the container may be an older artifact
       without these gauges, or the actuator may simply be unreachable. Inspect container health,
       the deployed build and the config before concluding anything.
   - ⚠️ **Do NOT read "no `receive-stall`/`eval-stall` row in `strategy.subscriber_health_events`"
     as PASS.** That table is write-only, fail-soft forensics — a disabled sweep, a failed sweep or
     a failed insert produces no row either, so absence there can silently pass a dead engine. A row
     that IS present is strong evidence of FAIL; its absence proves nothing. (This correction was
     itself caught in cross-vendor review of the retirement PR — the first replacement doctrine
     traded one absence-based inference for another.)
   - The underlying invariant: the only unconfounded oracles are `SignalEngine.lastBarReceivedAtMs`
     (stamped as the first line of `onCandleMessage`, before any universe/window/position/loaded
     logic) and `lastBarEvaluatedAtMs` — direction-, window- and position-independent, and what
     `SubscriberHealthCanary` and `SessionLivenessHeartbeat` (#941) key on. **Both are now
     readable** via the gauges above (task_0bed1621, 2026-07-26); the `signal_eval_outcomes` bucket
     remains a useful corroborator, no longer the only proxy.
   - **Never** conclude starvation from `strategy.signals` (it mixes the swing BATCH engine, whose
     rows are stamped `00:00:00`, and will fake liveness on a dead tick engine) or from
     `ay_signal_eval_outcome_total` (window- AND position-dependent: it is structurally flat
     15:00–15:30 IST every non-expiry day — an ATTRIBUTION primitive, never a liveness one).
5. **On FAIL, localize (read-only):** `strategy.subscriber_health_events` for an `eval-stall` today; the
   eval-thread dump in `docker logs ay-strategy-signal-service` (look for a frame parked in
   `MarketDataCandlesClient.fetch` / `LiveSeriesStore.refreshFromRest`); `GET
   /api/v1/signal-rejections/dot-health`. **Snapshot `docker logs` to a file BEFORE proposing any
   recreate** (a post-incident recreate destroys the evidence — burned us twice).
6. **Report PASS / FAIL / INCONCLUSIVE + evidence.** ⚠️ **INCONCLUSIVE is a first-class result and
   must be reported AS SUCH — never rounded to PASS.** Since the liveness gauges landed
   (task_0bed1621) a fully quiet session is normally decidable at step 4, so INCONCLUSIVE should be
   RARE — it now means the gauges themselves were unreadable, which is its own finding. It remains a
   legal verdict precisely because rounding an unproven case up to PASS is how the 2026-07-17 false
   escalation and the 2026-07-20 needless restart both happened, in opposite directions. Fold a FAIL **or an INCONCLUSIVE** into that evening's `post` findings file —
   an INCONCLUSIVE should now be RARE — the liveness gauges above answer the quiet-session case
   directly; a run of them means the gauges are unreadable and that itself is the finding. **Never restart
   or redeploy to fix it mid-session** — propose; the owner/architect acts (a live fix waits for
   post-market or pre-open).

⚠️ **Do NOT propose a canary that auto-alarms on "session open + capture fresh + zero rejections for
>N min".** That was proposed here, built as `SignalStarvationCanary` (#868) — and **RETIRED on
2026-07-26 without ever being armed**, because the predicate fires on ordinary bearish tape (see the
step-4 correction above). The 2026-07-17 false alarm was that canary's exact signature, executed by
hand. Automation of this gate must key on the receipt/evaluation heartbeats, which is what
`SubscriberHealthCanary` and `SessionLivenessHeartbeat` (#941) already do. The remaining coverage gap
— *which* strategies are dark, as opposed to *is the engine alive* — is a LOAD-time question tracked
as ledger row **G2** (`T9-strategy-coverage-watchdog`) in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md).

## 5. Findings-file template

```markdown
# Session findings — <YYYY-MM-DD> (data date)
Analysis date: <date>. Analyst: owner / Claude. Data: signal_rejections rows N (IST bounds), signals fired N, paper trades N.
Session character: <VIX level, index range/trend, expiry day?, notable events>.
## 1 Funnel numbers        (§3.1–3.2 tables)
## 2 Rail findings          (§3.3/3.5/3.8 — per flagged rail: evidence, verdict, proposed tune)
## 3 Composite + dots       (§3.4/3.6 — distribution, dead dots, cap math)
## 4 Data health            (§3.7 — nulls/zeros table, new-vs-known)
## 5 Shadow-book outcomes   (per-rail PnL attribution + per-position table; §4.2 manual
##                           counterfactuals only for rejection classes the shadow book skips)
## 6 New data points / anomalies   (anything not covered by current dimensions → promote to §3)
## 7 Tuning candidates      (knob → current → proposed → evidence → status[PROPOSED/OWNER-OK/SHIPPED PR#])
```

Keep every session file immutable after write (append a dated addendum if corrected). The rollup
reads them all.

⚠️ **The §7 tune table (the `T`-namespace) is regenerated in every session file and has NO durable
register of its own** — so a `PROPOSED` tune is invisible to any session that reads only the forward
ledger, and one has already been reported as "queue empty" while five tunes sat open (2026-07-25).
**Rule:** when a tune survives a rollup as STRUCTURAL, or a `T`-row proposes a BUILD rather than a
knob turn (e.g. T8 latency stamping, T9 coverage watchdog), give it a row in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
**§0 group G** in the same PR. The ledger row is the authoritative status; this table stays the
evidence. Tunes blocked on forward sessions are tier `data` there, **not** `OWNER` — the distinction
matters, because an `OWNER` row is skipped forever by autonomous sessions while a `data` row tells
them to re-check the gate.

## 6. SQL toolkit (copy-paste; adjust dates)

```sql
-- §3.1 volume by day
SELECT (generated_at AT TIME ZONE 'Asia/Kolkata')::date d, count(*), count(DISTINCT strategy_slug)
FROM strategy.signal_rejections GROUP BY 1 ORDER BY 1;

-- §3.2 first-rail histogram
SELECT blocking_rail, count(*), round(avg(blocking_margin),3), round(min(blocking_margin),3)
FROM strategy.signal_rejections WHERE (generated_at AT TIME ZONE 'Asia/Kolkata')::date = :d
GROUP BY 1 ORDER BY 2 DESC;

-- §3.3 all-fails expansion
SELECT c->>'rail', c->>'failPolicy', count(*),
       round(avg((c->>'operand')::numeric),3) avg_operand,
       round(avg((c->>'threshold')::numeric),3) avg_threshold
FROM strategy.signal_rejections, jsonb_array_elements(diagnostic->'checks') c
WHERE (c->>'pass')::boolean=false GROUP BY 1,2 ORDER BY 3 DESC;

-- §3.4 composite histogram by side
SELECT round(composite_score,1), count(*),
       count(*) FILTER (WHERE side='CE') ce, count(*) FILTER (WHERE side='PE') pe
FROM strategy.signal_rejections WHERE composite_score IS NOT NULL GROUP BY 1 ORDER BY 1;

-- §3.5 would-have-fired (blocked ONLY by :rail among evaluated rails, composite passed)
SELECT strategy_slug, bar_time AT TIME ZONE 'Asia/Kolkata', composite_score, side
FROM strategy.signal_rejections r
WHERE composite_score >= composite_threshold
  AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(r.diagnostic->'checks') c
                  WHERE (c->>'pass')::boolean=false AND c->>'rail' <> :rail);

-- §3.6 dot support rates
SELECT d->>'dot', count(*) FILTER (WHERE (d->>'supports')::boolean) supports, count(*) total,
       round(100.0*count(*) FILTER (WHERE (d->>'supports')::boolean)/count(*),1) pct
FROM strategy.signal_rejections, jsonb_array_elements(diagnostic->'confluence'->'dots') d
GROUP BY 1 ORDER BY pct;

-- §3.7 data-health nulls
SELECT count(*),
  count(*) FILTER (WHERE diagnostic->'context'->'macro'->>'ivRank' IS NULL) ivrank_null,
  count(*) FILTER (WHERE (diagnostic->'context'->'macro'->>'advances')::int=0
               AND (diagnostic->'context'->'macro'->>'declines')::int=0) breadth_zero,
  count(*) FILTER (WHERE diagnostic->'context'->'macro'->>'fiiLongPct' IS NULL) fii_null,
  count(*) FILTER (WHERE diagnostic->'context'->'macro'->>'dowUp' IS NULL) dow_null
FROM strategy.signal_rejections;

-- §3.8 example ground truth: 3m signal-future volume percentiles for a session
WITH b AS (SELECT time_bucket('3 minutes', bucket) b3, sum(volume) vol FROM marketdata.candles
  WHERE tradingsymbol=:front_fut AND exchange='NFO' AND interval='1m'
    AND bucket >= :d0915 AND bucket < :d1530 GROUP BY 1)
SELECT count(*), min(vol), percentile_disc(0.5) WITHIN GROUP (ORDER BY vol) p50,
       percentile_disc(0.9) WITHIN GROUP (ORDER BY vol) p90, max(vol) FROM b;

-- §3.10 strategy-coverage ratio (emitting vs published+enabled) — pair with the engine boot line
SELECT (generated_at AT TIME ZONE 'Asia/Kolkata')::date d,
       count(DISTINCT strategy_slug) emitting,
       count(DISTINCT strategy_slug) FILTER (WHERE strategy_slug LIKE '%sensex%') sensex,
       count(DISTINCT strategy_slug) FILTER (WHERE strategy_slug LIKE '%-pe') pe
FROM strategy.signal_rejections WHERE generated_at >= :d0 GROUP BY 1 ORDER BY 1;
SELECT count(*) published_enabled FROM strategy.strategies
WHERE enabled AND published_version_id IS NOT NULL;
-- boot line (READ IT THE SAME DAY — a restart destroys it):
--   docker logs ay-strategy-signal-service 2>&1 | grep -E "loaded [0-9]+ published"

-- §3.11 interior coverage buckets (NEVER certify coverage from min/max alone)
SELECT to_char(time_bucket('15 minutes', generated_at) AT TIME ZONE 'Asia/Kolkata','HH24:MI') ist,
       count(*) n, count(DISTINCT strategy_slug) slugs
FROM strategy.signal_rejections
WHERE generated_at >= :d0915 AND generated_at < :d1540 GROUP BY 1 ORDER BY 1;
-- pair with the canary telemetry for the same day:
SELECT occurred_at AT TIME ZONE 'Asia/Kolkata' ist, kind, left(detail,140)
FROM strategy.subscriber_health_events WHERE occurred_at >= :d0 ORDER BY occurred_at;

-- §3.12 OI quadrant liveness (NEUTRAL share = defect signal, never regime)
SELECT (generated_at AT TIME ZONE 'Asia/Kolkata')::date d,
       diagnostic->'context'->'oi'->>'futuresQuadrant' fq,
       diagnostic->'context'->'oi'->>'underlyingQuadrant' uq, count(*)
FROM strategy.signal_rejections WHERE generated_at >= :d0 GROUP BY 1,2,3 ORDER BY 1,4 DESC;
-- and the capture cadence behind it (expect ~375 distinct minutes on a full session):
SELECT (ts AT TIME ZONE 'Asia/Kolkata')::date d, count(*) snaps,
       count(DISTINCT date_trunc('minute',ts)) minutes
FROM marketdata.futures_oi_snapshots WHERE tradingsymbol=:front_fut AND ts >= :d0
GROUP BY 1 ORDER BY 1;

-- §3.15 misaligned (phantom) 1m candles — expect ZERO; any row here inflates every 3m rollup
SELECT source, count(*) rows, count(DISTINCT tradingsymbol) syms,
       min(bucket AT TIME ZONE 'Asia/Kolkata') first, max(bucket AT TIME ZONE 'Asia/Kolkata') last
FROM marketdata.candles WHERE interval='1m' AND bucket >= :d0915 AND bucket < :d1535
  AND EXTRACT(second FROM bucket) <> 0 GROUP BY 1;
-- and the aligned-only form of the §3.8 ground-truth query:
WITH b AS (SELECT time_bucket('3 minutes', bucket) b3, sum(volume) vol FROM marketdata.candles
  WHERE tradingsymbol=:front_fut AND exchange='NFO' AND interval='1m'
    AND EXTRACT(second FROM bucket)=0 AND bucket >= :d0915 AND bucket < :d1530 GROUP BY 1)
SELECT count(*), percentile_disc(0.5) WITHIN GROUP (ORDER BY vol) p50,
       percentile_disc(0.99) WITHIN GROUP (ORDER BY vol) p99, max(vol) FROM b;

-- §3.17 the misaligned-vs-aligned check behind the PartialBucketCanary reading: the engine's 3m bar
-- should equal the epoch-aligned 3m rollup of the store's own 1m bars for the same bucket.
SELECT to_char(time_bucket('3 minutes', bucket) AT TIME ZONE 'Asia/Kolkata','HH24:MI') b3, sum(volume) vol
FROM marketdata.candles WHERE exchange='NFO' AND interval='1m' AND tradingsymbol=:front_fut
  AND EXTRACT(second FROM bucket)=0 AND bucket >= :d0915 AND bucket < :d1530 GROUP BY 1 ORDER BY 1;
-- compare the flagged bucket's value against the canary's logged "3m bar volume" (they matched on
-- 2026-07-24 — it was the in-memory 1m sum that diverged).

-- §3.18 which contract did the engine actually signal off? (the roll is SILENT in the row)
-- take a context-bearing close, then see which candidate contract's day range contains it.
SELECT DISTINCT (diagnostic->'context'->'chart'->>'close')::numeric close_seen
FROM strategy.signal_rejections
WHERE generated_at >= :d0915 AND diagnostic->'context'->'chart'->>'close' IS NOT NULL LIMIT 5;
SELECT tradingsymbol, (array_agg(open ORDER BY bucket))[1] o, max(high) h, min(low) l,
       (array_agg(close ORDER BY bucket DESC))[1] c
FROM marketdata.candles WHERE interval='1m' AND EXTRACT(second FROM bucket)=0
  AND tradingsymbol IN ('NIFTY26JULFUT','NIFTY26AUGFUT')   -- adjust to the live pair
  AND bucket >= :d0915 AND bucket < :d1530 GROUP BY 1;
-- cheapest confirmation while the container is up (the rail log names the contract):
--   docker logs ay-strategy-signal-service --since <T> 2>&1 | grep -oE "NFO:[A-Z0-9]+" | sort -u

-- §3.19 monthly-expiry OI suppression: is the dead OI bloc BY DESIGN or a real outage?
-- (a) the fingerprint — quadrants NEUTRAL + spurt NULL while futuresBasis stays LIVE:
SELECT count(*) ctx,
       count(*) FILTER (WHERE diagnostic->'context'->'oi'->>'futuresQuadrant'='NEUTRAL') fq_neutral,
       count(*) FILTER (WHERE diagnostic->'context'->'oi'->>'spurtPricePct' IS NULL) spurt_null,
       count(*) FILTER (WHERE diagnostic->'context'->'oi'->>'futuresBasis' IS NOT NULL) basis_live
FROM strategy.signal_rejections
WHERE generated_at >= :d0915 AND diagnostic->'context'->'oi' IS NOT NULL;
-- basis_live = ctx while the other two are saturated ⇒ S24 suppression, NOT an outage.
-- (b) which ROOT is suppressed? (never infer it from the slug — sensex-niftyoi reads NIFTY OI)
SELECT diagnostic->'context'->>'underlying' oi_root, count(*)
FROM strategy.signal_rejections WHERE generated_at >= :d0915 GROUP BY 1;
-- (c) capture must still be healthy underneath the suppression:
SELECT count(*) snaps, count(DISTINCT date_trunc('minute',ts)) minutes, max(ts AT TIME ZONE 'Asia/Kolkata') last_ist
FROM marketdata.futures_oi_snapshots WHERE ts >= :d0915;

-- §3.20 dot-vs-rail threshold divergence: does a dot's support rate track its namesake RAIL?
-- If the rail blocks on a BANDED threshold while the dot is pinned at 0% (or 100%), the two are not
-- reading the same floor — go read the scorer's call site (the SQL only shows you WHERE to look).
SELECT d->>'dot' dot, d->>'reason' reason,
       round(100.0*count(*) FILTER (WHERE (d->>'supports')::boolean)/count(*),1) dot_support_pct,
       min(r.blocking_threshold) rail_min_thr, max(r.blocking_threshold) rail_max_thr
FROM strategy.signal_rejections r, jsonb_array_elements(r.diagnostic->'confluence'->'dots') d
WHERE r.generated_at >= :d0915 AND d->>'dot' = :dot AND r.blocking_rail = :namesake_rail
GROUP BY 1,2;
-- then place the SUSPECTED static default on the operand's own distribution (§3.8/§3.15 form):
WITH b AS (SELECT time_bucket('3 minutes', bucket) b3, sum(volume) vol FROM marketdata.candles
  WHERE tradingsymbol=:front_fut AND exchange='NFO' AND interval='1m' AND EXTRACT(second FROM bucket)=0
    AND bucket >= :d0915 AND bucket < :d1530 GROUP BY 1)
SELECT count(*) bars, count(*) FILTER (WHERE vol >= :suspected_static_floor) clearing,
       round(100.0*count(*) FILTER (WHERE vol >= :suspected_static_floor)/count(*),1) pct FROM b;
-- `clearing` matching the dot's distinct supporting BUCKET count is the fingerprint.
-- code side (the actual proof): ConnectTheDotsScorer's add(dots,"<dot>",…) line -> the ScalperGates
-- overload it calls -> whether that overload takes a floor override at all.

-- §3.22 FROZEN-operand probe: a dot stuck at 0% or ~100% may have a constant input, which every
-- null/non-null canary reports as `alive`. Run this BEFORE reaching for a threshold explanation.
SELECT count(DISTINCT diagnostic->'context'->'macro'->>'atmIv')          atmiv_vals,
       count(DISTINCT diagnostic->'context'->'macro'->>'ceIvAvg6')      ce_vals,
       count(DISTINCT diagnostic->'context'->'macro'->>'peIvAvg6')      pe_vals,
       count(DISTINCT diagnostic->'context'->'macro'->>'vixLevel')      vix_vals,
       count(DISTINCT diagnostic->'context'->'macro'->>'premiumSkewPct') skew_vals,
       count(DISTINCT diagnostic->'context'->'oi'->>'futuresBasis')     basis_vals
FROM strategy.signal_rejections WHERE generated_at >= :d0915;
-- `1` on a field while its neighbours show tens = a FROZEN operand (2026-07-29: atmIv = 1, four
-- sessions running, while ceIvAvg6/peIvAvg6/vixLevel/premiumSkewPct all moved).
-- AUTOMATED 2026-07-29 (G12): DotHealthCanary now runs this per dot and reports `DotState.frozen`
-- on GET /api/v1/signal-rejections/dot-health, so the hand-run below is now a cross-check, not the
-- only way to see it. TWO DIFFERENCES from the canary, both deliberate:
--   (a) this SQL counts DISTINCT ROWS; the canary counts DISTINCT BARS. One 3m bar fans out across
--       many scalpers, so over a NARROW window row-counting can read `1` off a single bar and call
--       a live input frozen. Safe here only because it runs over a whole session (:d0915).
--   (b) atmIv's freeze is CORRECT — it resolves to the latest `iv_daily_summary` row (`iv_30d`
--       PREFERRED, `atm_iv` only as a fallback — atm_iv is NULL on 2026-07-28 and 07-21 while both
--       sessions still read a value), written once a day at 16:00 IST, so intraday it is the
--       previous session's scalar. See docs/signal-analysis/2026-07-29-g12-frozen-operand.md.
--       Do not "fix" the feed.
--   (c) `iv_rank` and `fii` are the same EOD shape and are classified DAILY in the canary too;
--       only CONTINUOUS operands (breadth/vix/oi_spurt_price) page on a freeze.
-- Cross-session form:
SELECT (generated_at AT TIME ZONE 'Asia/Kolkata')::date d,
       count(DISTINCT diagnostic->'context'->'macro'->>'atmIv') vals,
       min((diagnostic->'context'->'macro'->>'atmIv')::numeric) mn,
       max((diagnostic->'context'->'macro'->>'atmIv')::numeric) mx
FROM strategy.signal_rejections WHERE generated_at >= :d0 GROUP BY 1 ORDER BY 1;

-- §3.25 session REGIME (G15): intraday directional efficiency on the `NIFTY 50` DAILY bar.
-- (NOT the front future -- the regime stamp is an index-level read; an earlier revision of this
--  block carried a futures-volume CTE that the SELECT never referenced. Removed 2026-07-30.)
-- Cuts derived from the sorted distribution's largest gaps: chop <0.29, mixed 0.29-0.61, trend >=0.61.
SELECT to_char(bucket AT TIME ZONE 'Asia/Kolkata','YYYY-MM-DD') d,
       round(((close-open)/open*100)::numeric,2)            net_pct,
       round(((high-low)/open*100)::numeric,2)              range_pct,
       round((abs(close-open)/NULLIF(high-low,0))::numeric,3) efficiency
FROM marketdata.candles
WHERE tradingsymbol='NIFTY 50' AND interval='1d' AND bucket >= :d0 ORDER BY bucket;
-- ⚠️ close-over-PRIOR-close is the WRONG operand here (a 30-min stop cannot capture a gap).

-- §3.26 the counterfactual PIPELINE. Step 2 is the one people skip -- which rail actually BINDS:
SELECT blocking_rail, count(*) FROM strategy.signal_rejections
WHERE generated_at >= :d0 AND diagnostic ? 'confluence' GROUP BY 1 ORDER BY 2 DESC;
-- Everything needed to price a counterfactual is ALREADY on the row -- no modelling of the live side:
--   diagnostic->>'operand'    the bar's operand as the engine saw it
--   diagnostic->>'threshold'  the floor the engine ACTUALLY applied
--   diagnostic->'confluence'->'dots'  every dot's weight + supports (composite is recomputable;
--                             ⚠️ ABSENT dots are IN this array and must be excluded by hand --
--                             validated 983/983 against the stored composite_score)
--   diagnostic->'wouldBeLeg'  tradingsymbol / strike / expiry / optionType / entryLtp
-- Then dedupe by (bar_time, tradingsymbol) and price via §4.2 below.

-- §4.2 counterfactual premium path for one would-have-fired row
SELECT captured_at AT TIME ZONE 'Asia/Kolkata', last_price
FROM marketdata.options_chain_snapshots
WHERE underlying=:u AND expiry=:e AND strike=:k AND option_type=:side
  AND captured_at >= :bar_time ORDER BY captured_at;

-- SHADOW BOOK: per-rail PnL attribution — "does rail X block winners or losers?"
SELECT s.blocking_rail, count(*) n,
       count(*) FILTER (WHERE s.pnl_points > 0) wins,
       round(avg(s.pnl_points),2) avg_pts, round(sum(s.pnl_points),2) total_pts,
       round(avg(s.pnl_pct),1) avg_pct
FROM strategy.shadow_positions s
WHERE s.status='CLOSED' AND s.pnl_points IS NOT NULL
GROUP BY 1 ORDER BY total_pts DESC;

-- SHADOW BOOK: per-strategy + close-reason breakdown for a session
SELECT strategy_slug, side, close_reason, count(*), round(avg(pnl_pct),1) avg_pct
FROM strategy.shadow_positions
WHERE (opened_at AT TIME ZONE 'Asia/Kolkata')::date = :d
GROUP BY 1,2,3 ORDER BY 1;

-- SHADOW BOOK: variant league — champion vs challenger configs on identical data (roadmap F1).
-- Interpret pnl per book: a challenger's edge over champion = the trades ONLY it took.
-- pnl_net (F8, V018) = 1-lot INR through the engine fill model (statutory costs + Rs20/lot
-- brokerage) — judge keep/cut on NET; points are scale-free comparison only. Paper realized_pnl
-- has been net-of-costs since Phase 43 (same FillSimulator) — never cost-adjust it twice.
SELECT variant, count(*) FILTER (WHERE status='OPEN') open,
       count(*) FILTER (WHERE status='CLOSED') closed,
       count(*) FILTER (WHERE status='CLOSED' AND pnl_points > 0) wins,
       count(*) FILTER (WHERE status='CLOSED' AND pnl_net > 0) net_wins,
       round(sum(pnl_points) FILTER (WHERE status='CLOSED'),2) total_pts,
       round(sum(pnl_net) FILTER (WHERE status='CLOSED'),2) total_net_inr,
       round(sum(cost) FILTER (WHERE status='CLOSED'),2) total_cost_inr
FROM strategy.shadow_positions GROUP BY 1 ORDER BY 1;

-- LATENCY (F8): signal-bar close → shadow entry stamp, per session. p95 > ~5s means the entry LTP
-- is stale vs the bar the gate scored — flag it in the findings file.
SELECT (opened_at AT TIME ZONE 'Asia/Kolkata')::date d,
       percentile_disc(0.5) WITHIN GROUP (ORDER BY opened_at - bar_time) p50,
       percentile_disc(0.95) WITHIN GROUP (ORDER BY opened_at - bar_time) p95, count(*)
FROM strategy.shadow_positions GROUP BY 1 ORDER BY 1;

-- SHADOW BOOK: challenger-only entries (rows a variant took that champion did not — the true delta)
SELECT v.variant, v.tradingsymbol, v.bar_time AT TIME ZONE 'Asia/Kolkata' t, v.pnl_points
FROM strategy.shadow_positions v
WHERE v.variant <> 'champion' AND NOT EXISTS (
  SELECT 1 FROM strategy.shadow_positions c
  WHERE c.variant='champion' AND c.rejection_id = v.rejection_id)
ORDER BY v.bar_time;

-- SHADOW BOOK ⨯ REJECTION dots: PnL by whether a given dot supported at entry
SELECT d->>'dot' dot, (d->>'supports')::boolean supported,
       count(*), round(avg(s.pnl_points),2) avg_pts
FROM strategy.shadow_positions s
JOIN strategy.signal_rejections r ON r.id = s.rejection_id,
     jsonb_array_elements(r.diagnostic->'confluence'->'dots') d
WHERE s.status='CLOSED' AND s.pnl_points IS NOT NULL
GROUP BY 1,2 ORDER BY 1,2;
```

## 7. Data-model improvement backlog (owner-gated builds; keeps this method getting sharper)

Proposed 2026-07-03 from the first pass — each is small and parity-safe (rejections are LIVE-only):

1. ~~Persist the would-be option leg on every rejection~~ — **SHIPPED as the shadow book**: the
   diagnostic JSON carries `wouldBeLeg` and eligible rejections open real-time-managed
   `shadow_positions` (see §2). Supersedes this item AND item 2 with better data (live exits, not
   sampled offsets).
2. ~~Nightly forward-outcome stamper~~ — **superseded by the shadow book** (real exit labels beat
   fixed-offset sampling). Revisit only if a non-shadowed rejection class needs outcomes too.
3. ~~All-eval mode (diagnostic completeness)~~ — **ALREADY SHIPPED WHEN THIS ROW WAS FILED; the
   residual is deliberate and PINNED. Do not build it.** The row's premise ("the straddle path
   already does this", implying the directional path does not) was stale on arrival: #404
   (`00172811`, **2026-07-01**) introduced the diagnostic and the all-eval sweep together, two days
   before this file was created (#477, `c38b710c`, 2026-07-03). Verified by reading the gate AT
   `c38b710c` — its short-circuit set is the same one standing today. Every rail past the chain
   fetch records via `Diag.fails/failsBool/failsScore` and falls through; only a terminal
   `diag.anyFailed()` blocks, so `blockingRail` is the FIRST failure while `checks[]` carries the
   whole matrix. Pinned by `ScalperConfluenceGateTest
   .diagnosticAllEvalRecordsDownstreamRailsEvenAfterAnEarlierFailure` (asserts the OI context and
   `confluence-composite` are still scored after an `rsi-band` failure).

   Six `return diag.block()` sites remain. Three are structurally forced — nothing downstream is
   computable: `chain-unavailable`, `context-unavailable`, and the `morning-opening-formation`
   opening-bar case (the 2nd candle does not exist yet). Three are the deliberate
   "block before the fan-out" pre-flight: `time-window`, `time-of-day-preference`,
   `option-side-constraint`. **Removing those three is a Critical, not a completion:**
   * `ShadowBookService.maybeOpen` returns early on `d.pick() == null`, with a comment naming
     time-window as the case it excludes. Resolve the pick on an out-of-window bar and the shadow
     book — the evidence base the entry tunes are judged on — starts opening virtual positions on
     bars no strategy could ever have traded. `ShadowVariants.accepts` iterates `d.checks()`, so a
     variant carrying a `time-window` disable override would accept them outright.
   * V054 (row 4 below, 2026-08-02) **deliberately encodes the opposite decision**: the pre-fetch
     rows are `contextBearing=false` — "UNINFORMATIVE, not degraded … T17's lesson (a context-less
     row cannot testify about dot liveness) applied per row instead of per window". Building this
     row would partly undo a decision taken after it, with a written rationale.
   * Measured, not argued: deleting the `time-window` short-circuit reds two existing tests —
     `ScalperConfluenceGateTest.blocksInTheMiddayWindowBeforeAnyChainFetch:1195` and
     `.openingTickStrategyPassesTheOpenWindowWhileADefaultStrategyIsBlocked:1645`, both on
     `verifyNoInteractions(marketOiClient)` (74 tests, 2 failures; 74/74 green unmodified).
   * Cost it would add: `MarketOiClient`'s memo TTL is 45 s against a 180 s primary bar, so it
     amortizes across strategies within a bar but never across bars — every affected bar pays a
     fresh chain + macro/context fan-out. Upper bound ~50 of the 125 3m bars per session
     (09:15–09:45 plus the 11:00–13:00 midday block), restricted to bars where the chart gate had
     already fired.
4. ~~Data-health flags on the row~~ — **SHIPPED (F5 unit U3, [#1193](https://github.com/prashantm912/artha-yantra-2/pull/1193)
   @ `5071a0b8`, V054 `signal_rejection_data_health`)**: `RejectionWriter` computes `DataHealthFlags`
   in memory from the same `ScalperGateContext` the diagnostic is serialized from (pure function, no
   new read, on the writer's existing bounded async thread) and the INSERT carries
   `data_health JSONB {degraded, contextBearing, oiSuppressed, flags[]}` plus an indexable `degraded
   BOOLEAN`, with a partial index `WHERE degraded`. A flag means the input was **ABSENT**, never that
   its value was unremarkable; the S24 monthly-expiry OI inertness is exempt **per OI root** (NSE last
   Tuesday / BSE last Thursday), and `DataHealthFlagsTest` reflects over every `Macro`/`Oi` component
   so adding one without classifying it is a build failure. Read surfaces: the `degraded` query param
   on `GET /api/v1/signal-rejections` and the FE badge (word + flag count, absent inputs named on
   expand — `RejectionsPage`). ⚠️ `degraded` reads TRUE on nearly every context-bearing row today
   because `ivRank` and `dowUp` are absent on 100% of them; that is the column working, not a bug —
   see the `V054__signal_rejection_data_health.sql` header, which is the authority on the semantics.
5. ~~Per-session eval-denominator row~~ — **SHIPPED (F5 unit U2, V053 `strategy_eval_denominator`)**:
   the engine's `Outcome` counters now carry a per-strategy dimension, flushed on the EXISTING V045
   3m rollup tick to a day-keyed table — one row per `(session_date, boot_id, strategy_slug,
   outcome)`, ≤ 441/day/boot. Cumulative values REPLACED on conflict (not deltas added), so a double
   flush or a mid-day restart cannot double count; `boot_id` in the key means a restart adds rows
   rather than overwriting, and a day's true total is the SUM across boots. Rates now have a real
   denominator instead of one inferred from the 3m grid:
   ```sql
   SELECT strategy_slug,
          SUM(eval_count)                                  AS evaluations,
          SUM(eval_count) FILTER (WHERE outcome = 'fired') AS fired,
          COUNT(DISTINCT boot_id)                          AS boots
     FROM strategy.strategy_eval_denominator
    WHERE session_date = DATE '2026-08-01'
    GROUP BY strategy_slug ORDER BY evaluations DESC;
   ```
   Full protocol + guarantee boundary: the `V053__strategy_eval_denominator.sql` header.
6. **Dot-null semantics unification** — **DECIDED 2026-08-03, shipped DEFAULT-OFF, not armed.**
   Owner adopted `null = withhold from BOTH numerator and denominator` for all dots, paired with a
   data-coverage floor. Mechanism: `NullPolicy.WITHHELD` (tag `dot-null-withheld`) + the §5.3
   coverage floor (tag `dot-coverage-floor`, which the policy tag implies — never the policy alone).
   Arming is an owner call and needs a republish.
   **Do not restate the per-dot map here — `NullPolicy.java`'s javadoc is the authority** and
   reproduces exactly against `ConnectTheDotsScorer`: three classes (withheld / supports /
   opposes-in-denominator), with **15 of the 18 default dots** scoring a missing input as evidence
   AGAINST the side. Prose drifts; that javadoc sits next to the code it describes.
   ⚠️ **This row previously read "today: dow null→supports, ivRank/fii null→against". Two of those
   three were wrong**, which is why it now points at code:
   `dow`→supports is true (`ConnectTheDotsScorer:390-393`) but the dot is behind default-OFF
   `dow-confluence`, armed on 0 strategies and present in 0 of 13,192 live rejection rows;
   `ivRank`→against went stale on 2026-07-10 (#676 — `:342-350` marks it `absent`, i.e. already on
   the target semantic); and there is no `fii` **dot** at all — `fii` is a RAIL
   (`ScalperGates.fiiBias:805-808`) whose null DEGRADES TO PASS, plus a `DotHealthCanary` probe name.
   "exclude-from-denominator" is also **not a distinct third option**: it is identical to withhold
   wherever `supports=false`, and where a null yields `supports=true` it is the most loosening of the
   three. Evidence, per-dot table and the coverage-floor derivation:
   [`2026-08-03-dot-null-semantics-decision.md`](2026-08-03-dot-null-semantics-decision.md).
7. **FE funnel view** on /signal-rejections — per-strategy Sankey/waterfall (bars → rail₁ → … →
   composite → fired) per day; the §3 pass at a glance.
8. **`DataHealthCanary` (code, FeedWatchdog pattern)** — **v1 SHIPPED 2026-07-03 (roadmap F4)**:
   `marketdata.canary` runs a 60 s in-session sweep for per-instrument tick/bar divergence (the
   2026-07-03 CandleBuilder-poison signature — ticks flowing, no 1m bars closing) plus
   options/futures OI capture-freshness probes, ntfy-alerting with a 15 min cooldown, feed-wide
   aggregation and recovery notes. Read surface: `GET /api/v1/market/health/data` (also the
   dashboard "Data health" tile). **Agents: read that endpoint FIRST and deep-dive only on
   non-GREEN.** **v2 SHIPPED same day (#491):** `DotHealthCanary` (strategy-signal) watches the
   GATE'S inputs — per-dot liveness over today's newest rejections (probe registry mirrors §3.7:
   breadth/iv_rank/dow/fii/vix/oi_spurt_price), REQUIRED dots (`artha.canary.required-dots`,
   default `breadth`) ntfy-page once per day on newly-dead + a recovery note; read surface
   `GET /api/v1/signal-rejections/dot-health` (agents read it instead of hand-running the §3.7
   SQL; grow the probe registry when §3 grows). Fault drill: `artha.canary.drill-suppress-key`
   suppresses one instrument's bar recording end-to-end; `artha.canary.force-open` un-gates the
   session check for off-hours drills.
