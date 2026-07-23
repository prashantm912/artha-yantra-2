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
| **After every session (EOD)** | Run the §3 standard pass → write `YYYY-MM-DD-session-findings.md` from the template (§5). ~30 min. |
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
    NEUTRAL share is a defect signal, never a flat-market artifact.** On 2026-07-20 it was 748/748,
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
    (added 2026-07-23) — §4.2 step 4 below says "+35% premium take-profit (E9 default)". **That default
    does not exist for most live scalpers.** Only **21 of the 63** YAMLs under
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
17. *(new dimensions land here — keep numbering append-only so findings files can cite "§3.6" stably)*

## 4. Live in-session analysis

Safe: everything here is read-only SELECTs on small tables / bounded candle ranges. Do NOT deploy,
restart services, or write during market hours.

### 4.1 Data-health watch (any time mid-session)
- Rejections flowing? `SELECT count(*), max(generated_at) FROM strategy.signal_rejections WHERE
  generated_at > <today 09:15 IST>` — 16 strategies × ~every 3m bar in-window ⇒ tens of rows/hour.
  Zero mid-session = engine or feed problem, check `docker logs ay-strategy-signal-service`.
- Context nulls (dimension §3.7) on TODAY's rows — catches a dead feed the same day it dies, not at EOD.
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

A fast, read-only PASS/FAIL check run ~15–20 min after the open (or anytime in-session) that catches the
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
   - **>0 and max within a few min of now ⇒ PASS** (the strict ~30-rail gate blocking every bar is
     normal; zero *fires* is fine, zero *rejections* is not).
   - **0 while step 3 shows healthy capture ⇒ FAIL = STARVATION.** Alert the owner immediately.
5. **On FAIL, localize (read-only):** `strategy.subscriber_health_events` for an `eval-stall` today; the
   eval-thread dump in `docker logs ay-strategy-signal-service` (look for a frame parked in
   `MarketDataCandlesClient.fetch` / `LiveSeriesStore.refreshFromRest`); `GET
   /api/v1/signal-rejections/dot-health`. **Snapshot `docker logs` to a file BEFORE proposing any
   recreate** (a post-incident recreate destroys the evidence — burned us twice).
6. **Report PASS/FAIL + evidence.** A FAIL fold into that evening's `post` findings file. **Never restart
   or redeploy to fix it mid-session** — propose; the owner/architect acts (a live fix waits for
   post-market or pre-open).

Durable follow-up (owner-gated code): a `SignalStarvationCanary` (FeedWatchdog pattern) that auto-alarms
on "session open + capture fresh + zero rejections for >N min" would make this gate automatic — this
routine is the interim manual/scheduled version.

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
3. **All-eval mode (diagnostic completeness)** — evaluate ALL rails per bar instead of short-circuiting
   at the first CLOSED failure (the straddle path already does this), so `checks[]` is the full matrix
   and "only X failed" needs no caveat. Costs a few extra reads per bar.
4. **Data-health flags on the row** — precomputed booleans (ivRank-null, breadth-zero, dow-null…) so
   the health query is an index scan and the FE can badge degraded rows.
5. **Per-session eval-denominator row** — bars-evaluated per strategy per day (rejections + fires +
   skips), so rates have a denominator (today it's inferred from the 3m grid).
6. **Dot-null semantics unification** — decide null = NEUTRAL-supports vs null = withhold vs
   exclude-from-denominator, ONCE, for all dots (today: dow null→supports, ivRank/fii null→against).
   Analysis keeps mis-reading dead-data dots as bearish evidence until this is uniform.
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
