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
- `strategy.shadow_positions` (V016) — **the shadow book**: every composite-passing rejection opened
  as a virtual 1-lot long-premium position (leg from the gate's own StrikePicker, entry at the
  candidate LTP) and closed by `ShadowExitMonitor` (premium brackets from the YAML premium_pct rules
  / structural stop on the signal future / 15:12 square-off / STALE for prior-day leftovers). One
  row per rejection (FK `rejection_id`), PnL in points + %. Dedup = one OPEN per strategy+side;
  flag `ARTHA_SCALPER_SHADOW_BOOK_ENABLED`. **Exit-fidelity caveat: indicator-driven exits
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
10. *(new dimensions land here — keep numbering append-only so findings files can cite "§3.6" stably)*

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
4. Apply the strategy's exit grammar approximately: +35% premium take-profit (E9 default), the YAML
   stop, 15:12 square-off — mark the row `WOULD-WIN / WOULD-LOSE / UNRESOLVED` + points.
5. Record per-row outcomes in the session findings file (§5 template has a table) — over sessions this
   becomes the gate-tuning evidence base.

Honesty caveats (state them in every findings file): 3-min LTP granularity (a 1m stop/TP touch can be
missed), no slippage/fees, strike-pick approximated (the real `StrikeLegPicker` may pick ±1 strike),
exit approximated. **v2 (exact) needs a small build:** persist the would-be leg
(strike/expiry/entry-LTP) on each rejection row at evaluation time — then the counterfactual is exact
and mechanical (see §7 improvement list).

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
