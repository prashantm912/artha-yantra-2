# Session findings — 2026-07-06 (data date)

**Analysis date:** 2026-07-06 (post-market, scheduled agent run). **Analyst:** Claude (automated).
**Data:** `strategy.signal_rejections` — **643 rows on 2026-07-06**, 17 strategies, spanning the FULL
session **09:19–15:19 IST** (no mid-session stall — contrast 2026-07-03's 12:40 CandleBuilder-poison
truncation; the #482 fix held). `strategy.signals` fired: **0** (still never). Paper trades: 0.
**Shadow book: champion 15 CLOSED + vol-off 2 + vol-12k5 1** (first NET-₹ session, F8 live).
Method: [README.md](README.md) §3 pass + shadow-book PnL.
**Session character:** **trending-UP day** — front future NIFTY26JULFUT 24,383 → 24,480 close
(**+97 pts, ~185-pt range** 24,330–24,515), a real directional impulse — the *inverse* of 07-03's flat
+33/2h theta-grind. RSI(3m) hot again (rsi-band operand avg ~79). Front future NIFTY26JULFUT. No expiry
(weekly is NSE Tuesday, 07-07).

**Headline verdict:** the volume-floor question flips. 07-03 (grind) the floor's veto **saved** money
(20/20 shadowed losers). 07-06 (trend) the same veto **cost** money: the 15 composite-passing entries it
vetoed made **+312 pts / +₹19,274 net (10 wins/15)** — including 2 take-profits and 9 profitable
square-offs. **This is the trend session the 2026-07-03 ledger explicitly asked for** ("need a trend
session to prove it blocks winners"). It confirms the *relative-floor* proposal exactly: a fixed 125k floor
filters chop AND blocks impulse indiscriminately; a `k×rolling-median` floor would keep 07-03's protection
while admitting 07-06's winners. **Second headline: breadth is now ALIVE** (#486 shipped) — advances/declines
non-zero on every row (44.9% dot support), which lifted the dead-weight composite cap from **0.765 → 0.816**
(the 0.8 bucket is populated for the first time, 42 rows).

---

## 1 Funnel numbers

Rows 643 (09:19–15:19 IST, full session). First-blocking-rail histogram:

| blocking_rail | rows | strategies | avg margin | note |
|---|---|---|---|---|
| volume-floor | 525 | 14 | −118,670 | operand avg ≈ 6.3k vs threshold 125,000 (unchanged structurally) |
| time-window | 106 | 5 | — | known-blocked bars re-logged = noise |
| time-of-day-preference | 12 | 4 | — | same class |

All-failed-rails expansion (unnest `checks[]`, pass=false; top rows):
volume-floor 525 (6,330 vs 125,000) · confluence-composite 234 (avg 0.500 vs 0.6) · time-window 106 ·
rsi-band 74 (**79.35** — hot) · divergence-vol-gate 66 · trend-change 66 · directional-vix-gate 65
(FAIL_OPEN, 11.9) · volume-pump 63 (FAIL_OPEN) · pct-price-move 63 (0.543 vs 1.0, FAIL_OPEN) ·
two-candle 63 · oi-divergence-magnitude 60 (**23.9 vs 20** — operand ABOVE threshold this regime) ·
oi-cross-required 60 · directional-change-gate 37 · open-high-low 37 · rising-volume 35 ·
oi-slope-agree 32 · max-oi-sr-gate 32 (FAIL_OPEN) · strike-pick 21 · psar-durability 16 (0.032 vs 0.05) ·
rsi-5m-cap 13 (83.8) · rsi-cooloff 10 (83.1) · hero-zero 6 · call-put-delta 3 (42.5 vs 50) · misc ≤6.

## 2 Rail findings

### 2.1 `volume-floor` — UNPASSABLE; today it vetoed WINNERS (P0, evidence now genuinely two-sided)

- Same root cause: hardcoded `NIFTY_VOL = 125000` (`ScalperGates.java:35`), YAML
  `scalper.params.volume_floor` null in every shipped strategy. 3m operand avg ~6.3k — floor sits above
  the physical range (525/643 = 82% of first-blocks; 31 rows passed every OTHER evaluated rail — see §5).
- **NEW two-sided close-out (shadow book, §5):** the 15 composite-passing entries this rail vetoed were
  realized virtually and made **+312 pts / +₹19,274 net, 10 wins/15** on this trending day — the DIRECT
  counter to 07-03's "20/20 losers, −513 pts". Both facts now coexist: the floor's absolute calibration is
  wrong (it never passes on OUR tick-agg series), and its *effect* is **regime-flipped** — protective on
  chop (07-03), costly on impulse (07-06). This is exactly the case for a **relative** floor
  (bar ≥ k × rolling-median volume): it filters the flat chop that bled 07-03 while admitting the impulse
  volume that ran 07-06. A fixed lower number cannot separate the two; the relative form can. Status:
  **PROPOSED — urgency RESTORED** (07-03 downgrade lifted; we now have a trend session showing lost winners).
- The vol-off / vol-12k5 shadow variants make this measurable going forward: vol-off (no floor) took the
  same day and made **+₹4,051 (2/2)**; vol-12k5 (12.5k floor) took 1 and lost −₹160. One session each is not
  yet decisive, but the direction agrees with the champion counterfactual. Keep accruing.

### 2.2 Working-as-designed / regime rails (no tune)

- `rsi-band`: operand ~79 (hot) — did its job; regime-responsive, not dead.
- `oi-divergence-magnitude`: avg operand **23.9 vs 20** today — operand ABOVE the threshold this session
  (vs 16.5 on 07-03, 5.98 on 07-02). Strongly regime-dependent; the 20 threshold is clearly reachable in an
  active-OI regime. Continue to watch across the rollup; do not touch off one session.
- `time-window` / `time-of-day-preference`: 118 rows are logging noise (bars outside windows re-logged).
  Cosmetic only.

## 3 Composite + dots

Composite distribution (0.1 buckets): 0.3→8 · 0.4→60 · 0.5→95 · **0.6→185 · 0.7→98 · 0.8→42**. The
**0.8 bucket is populated for the first time** (max observed composite **0.783**, p95 0.765) — direct
confirmation the dead-weight cap rose. **254 rows ≥ 0.6 threshold**, ALL CE (0 PE rows again — up-day
mirror silence continues; 155 rows blocked pre-side-resolution have empty side).

Dot support rates (488 confluence-evaluated rows):

| dot | w | support % | vs 07-03 | verdict |
|---|---|---|---|---|
| oi_spurt | 1.0 | **0%** | 0% | dead-by-calibration (price% floor 50 unreachable) |
| iv_pair | 0.8 | **0%** | 0% | dead — 0.10 gap between 6-strike avgs never occurs |
| volume | 1.0 | **0%** | 0% | dead — same 125k floor (§2.1) |
| iv_rank | 0.8 | **0%** | 0% | dead — `ivRank` NULL 643/643 (honest-null, scores against) |
| vix | 1.0 | **2.3%** | 99.7% | **regime-flipped** — near-total support on 07-03's low-VIX grind, near-zero today; NOT dead (see note) |
| trending_cross | 1.0 | 42.0% | 11.7% | regime (recovered from 07-03's collapse) |
| breadth | 1.0 | **44.9%** | 0% (dead) | **REVIVED — #486 live**: advances/declines non-zero on every row (was 0/0 all prior sessions) |
| sentiment_slope | 1.0 | 49.8% | 62.7% | healthy |
| underlying_oi | 1.0 | 59.2% | 47.3% | healthy |
| futures_oi | 1.5 | 59.6% | 54.8% | healthy |
| rsi | 1.0 | 66.4% | 80.1% | healthy (hot-RSI regime) |
| sentiment | 1.0 | 74.8% | 67.2% | healthy |
| psar | 1.0 | 91.0% | 78.0% | healthy |
| supertrend | 1.0 | 91.4% | 99.7% | healthy |
| vwma | 1.0 | 96.7% | 87.0% | healthy |
| drastic_oi | 1.0 | 96.9% | 56.0% | free again today (swings hard — hold for rollup) |
| vwap | 2.5 | 100% | 100% | by construction (side chosen BY vwap) |
| basis | 1.0 | 100% | 99.7% | healthy |
| iv_slope (E4, n=66) | 0.8 | 47.0% | 5.6% | straddle-path-only; regime |
| iv_abs_band (E4, n=66) | 0.8 | 100% | 0% | ATM IV inside band today (was outside 07-03) |
| premium_skew (n=6) | 1.0 | 83.3% | — | straddle-path-only, tiny n |

**Dead-weight cap RE-COMPUTED — breadth revival lifted it.** Main-composite Σw = 19.6. Dead dots now
**4, not 5**: volume 1.0 + iv_rank 0.8 + iv_pair 0.8 + oi_spurt 1.0 = **3.6** (breadth 1.0 no longer dead).
Max composite = (19.6 − 3.6)/19.6 = 16.0/19.6 ≈ **0.816** (was 0.765). Confirmed by the newly-populated
0.8 bucket and max observed 0.783. **vix caveat:** its 2.3% support today is a REGIME read (a hot/rising
trend day flips the VIX dot's directional test), not a dead feed — it was 99.7% on the low-VIX grind; do
NOT add it to the dead list. `drastic_oi` again swings hard (56%→97%) — the multi-session rule stands.

## 4 Data health (2026-07-06 rows)

| field | state | classification | vs 07-03 |
|---|---|---|---|
| macro.advances/declines | **NON-ZERO on all 488 confluence rows** (34/16, 33/17, …) | **FIXED — #486 live** | was 0/0 (dead) — **NEWLY ALIVE** |
| macro.ivRank | NULL 643/643 | honest-null (insufficient IV history); scores against | same |
| macro.dowUp | NULL 643/643 | by-design (Dow un-armed); null = NEUTRAL | same |
| macro.fiiLongPct | NULL 643/643 | fii-bias dot un-armed | same |
| macro.spurtPricePct | populated but 0 dot support (below floor 50) | floor unreachable (calibration) | same effect |
| macro.vix | NULL 643/643 in `context.macro` | vix DOT reads a separate path (2.3% support) — macro mirror field un-populated | **watch (see §6)** |
| everything else (chart/oi) | populated, plausible | ✓ capture pipeline healthy full session | same |

**NEW-ALIVE this session:** breadth (advances/declines) — the single biggest structural change; it moved
breadth from a dead 0-weight to a live 44.9%-support dot AND lifted the composite ceiling. **NEW-WATCH:**
`context.macro.vix` is NULL on every row even though the vix DOT evaluated (2.3%) — the dot's operand comes
from a different code path than the macro mirror; not a gate defect (the dot works) but the macro snapshot
field is blind. Flag for the §7 data-health-flags build. Nothing that was alive went dead.

## 5 Shadow-book outcomes (first NET-₹ session — F8 live)

**Exit-fidelity caveat (README §2):** the shadow book replicates premium brackets / structural stop on the
signal future / 15:12 square-off / STALE only — indicator-driven exits (trend-flip / signal-exit) are NOT
replicated, so these outcomes are a *lower-fidelity floor* on the real strategy's exits. Judge on **NET ₹**
(F8, V018 `pnl_net` — costs through the fill model); points are scale-free comparison only.

**Champion book — 15 CLOSED, 10 wins, +312.1 pts, +₹19,274.61 net.** All on rail `volume-floor` (the only
rail that lets composite pass then vetoes). Close-reason breakdown:

| close_reason | n | avg % | sum pts | read |
|---|---|---|---|---|
| STRUCTURAL_STOP | 4 | −2.5 | −16.0 | small stops early |
| TAKE_PROFIT | 2 | +35.0 | +105.6 | +35% premium TP hit (E9 default) — the trend paid |
| SQUARE_OFF | 9 | +15.5 | +222.5 | held to 15:12 **in profit** (+15% avg) — directional drift, not theta bleed |
| **total** | **15** | **~+13** | **+312.1** | **10 wins / 15** |

**Variant league (this session):**

| variant | closed | wins | pts | net ₹ | read |
|---|---|---|---|---|---|
| champion (would-have-fired) | 15 | 10 | +312.1 | **+19,274.61** | volume-floor vetoed a strongly profitable set today |
| vol-off (no floor) | 2 | 2 | +64.5 | +4,051.27 | loosest config also profitable (small n) |
| vol-12k5 (12.5k floor) | 1 | 0 | −1.4 | −160.15 | one trade, marginal loss (small n) |
| composite-070 | 0 | — | — | — | still ZERO rows all-time (composite rarely ≥0.70 alone-blocks; unfalsified) |

**Interpretation:** on a trending-up day with real directional impulse, ATM CE premium WON — +35% TPs hit
and even square-off exits closed +15%. The 31 §3.5 would-have-fired rows dedup to these 15 champion shadow
positions (one OPEN per strategy+side). **The shadow book IS the §4.2 counterfactual** — no manual
premium-path replay needed. This is the mirror image of 07-03 and the exact evidence the rollup needed to
resolve the volume-floor debate: the floor's effect is regime-dependent, so the fix must be a **relative**
floor, not a fixed number.

**Latency (F8):** shadow entry vs signal-bar close p50 **87s**, p95 **105s** — well over the README's ~5s
staleness flag. Likely structural to the 3m-bar → next-tick-eval → leg-resolution cadence (an 87s lag still
sits inside the 3m bar), but it means the entry LTP is a minute-plus stale vs the bar the gate scored. On a
trending day this still captured winners; flag it to watch on a fast-reversal day where 90s of drift could
flip a fill. **NEW watch-item.**

## 6 New data points / anomalies

- **NO mid-session stall today** — full 09:19–15:19 IST coverage (643 rows), engine ran the whole session.
  The 07-03 CandleBuilder future-tick poison (#482) did not recur; this is the first clean full-session
  sample since the fix. Read this session as a **complete** day (unlike 07-03's morning-only truncation).
- **breadth revival (#486) — the structural headline.** First session with live advances/declines; it
  lifted the composite dead-weight cap 0.765→0.816. Rollup watchlist item "breadth FIXED #486 — expect ALIVE
  from 07-06" is **CONFIRMED**.
- **Live-eyeball "champion pnlNet/pnlPoints sign anomaly" — RESOLVED, not a bug.** The 2026-07-06 midday
  eyeball flagged champion pnlPoints −423.5 vs pnlNet +5418 (2W/24L) as a possible aggregation bug. By EOD:
  all-time champion = 35 closed, 10 wins, **−201.0 pts, +₹19,274.61 net**. The disagreement is fully
  explained: (a) mid-session timing — at 13:45 today's take-profits/square-offs had not closed, so only
  07-03's 20 losers dominated the points; by EOD today's +312 pts pulled all-time points up to −201; (b)
  pre-F8 nulls — 07-03's 20 closes carry `pnl_net = null`, so the net sum counts ONLY 07-06's winning
  closes. Points span both regimes and net-covers one → sign disagreement is a data-coverage artifact, not a
  reconcile-loop bug. **No action.**
- **`oi-divergence-magnitude` operand 23.9 (ABOVE the 20 threshold)** — third distinct value in three
  sessions (5.98 → 16.5 → 23.9). Confirms strong regime dependence; the rollup must aggregate, never tune.
- **`context.macro.vix` NULL** while the vix dot evaluates (§4) — new data-health gap; the macro snapshot
  field is blind even though the dot's operand path works. Candidate for the §7 data-health-flags build.
- **composite-070 variant still zero rows all-time** (flagged in the 07-06 live eyeball) — configured
  (`docker-compose.yml`, `compositeThreshold:0.70`) but never opens because composite rarely ≥0.70 as the
  SOLE block. With the cap now 0.816, more rows CAN reach 0.70 — watch whether it starts opening; if it
  stays empty across the next few sessions it is effectively dead config (owner: loosen or drop).

## 7 Tuning candidates (status ledger — carried forward from 2026-07-03)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `scalper.params.volume_floor` / `ScalperGates.NIFTY_VOL` | null → 125,000 | relative `k×rolling-median` (pref) over a fixed number | §2.1/§5: 07-03 vetoed 20/20 losers (−513pts) BUT 07-06 vetoed +312pts/+₹19,274 winners — regime-flipped ⇒ a relative floor separates chop from impulse | **PROPOSED — urgency RESTORED (trend session now shows blocked winners; vol-off shadow +₹4,051 agrees)** |
| 2 | `artha.scalper.oi.ivPairMinGap` | 0.10 | 0.01–0.02 | §3: 0% dot support all three sessions | PROPOSED |
| 3 | `artha.scalper.oi.spurtPricePct` | 50 | 5–10 | §3: 0% dot support all three sessions | PROPOSED |
| 4 | breadth live producer | EOD bhavcopy → 0/0 intraday | live A/D | §4: **SHIPPED #486 — ALIVE 2026-07-06** (44.9% support, cap 0.765→0.816) | **SHIPPED #486 (verified live 07-06)** |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | §3/§4: honest-null punished all three sessions | PROPOSED (code) |
| 6 | composite threshold 0.6 | keep | keep (fix inputs) | §3 cap now 0.816 (breadth revived) — more headroom, threshold fine | DECIDED-KEEP |
| 7 | `DataHealthCanary` live staleness watcher | BUILT #484/#491 | — | §6: no stall today; watcher live | SHIPPED (07-03) |
| 8 | candle upsert provenance preserve | BUILT | — | 07-03 A1 | SHIPPED (07-03) |
| 9 | covered-range 1m re-fetch treadmill | FIXED (10-min tail) | — | 07-03 A1 | SHIPPED (07-03) |
| 10 | `context.macro.vix` NULL while vix dot works | macro mirror blind | populate macro.vix or add a data-health flag | §4/§6: NULL 643/643 today, dot path OK | **PROPOSED (data-health, low urgency)** |
| 11 | shadow entry latency p95 ~105s vs bar close | structural 3m cadence | investigate whether leg-resolution can open nearer bar close | §5: p50 87s / p95 105s (F8 first measure) | **PROPOSED (measure across sessions; may be inherent to 3m)** |
| 12 | composite-070 variant never opens | configured, 0 rows all-time | owner: loosen floor or drop the variant | §6: unfalsified; cap now 0.816 may let it start | **WATCH (owner)** |

**Three-session caveat:** items 1–3 are structural (threshold outside the operand's physical range). Item 1
is now **fully two-sided with both regimes sampled**: chop (07-03, veto saved 513pts) and trend (07-06,
veto cost +₹19,274) — the relative-floor form is the only fix that honours both. Dot rates
(`drastic_oi` 56↔97%, `trending_cross` 12↔42%, `vix` 2↔100%, `oi-divergence-magnitude` 6→16→24) swing hard
across the three sessions — hold ALL dot-threshold moves for the multi-session rollup (now 3 of ~5 needed).

---

## Addendum (2026-07-06, post-close ~19:35 IST) — gate/screener changes SHIPPED AFTER this session's data window
Recorded so the NEXT session's forensics (2026-07-07, which carries this ledger forward per README §1) reads the
shifted population as EXPECTED, not as a data anomaly:

- **§7 item 1 — volume-floor: RELATIVE FLOOR ARMED, status PROPOSED → SHIPPED/ARMED (#605).** All 21 published
  NIFTY scalpers now carry the `relative-volume-floor` tag: the §0B floor is `k × median(prior-N bar volumes)`
  (k=1.5 / N=20 / minBars=10, `artha.scalper.oi.relativeVolume*`), scale-invariant. **From 2026-07-07 expect
  volume-floor to block FAR fewer bars** (was 525/643 first-blocks) and the composite-passing entries it used to
  veto (today's shadow-book +312pts/+₹19,274 class) to **FIRE FOR REAL → real paper positions, not shadow rows.**
  For 07-07: (a) re-run §3.2 to find the NEXT binding rail once the wall is relaxed, (b) judge whether k=1.5 fires
  too much/little, (c) compare real fills vs the shadow promise. **Do NOT re-propose lowering a fixed floor.**
- **F1 shadow-league null-net misreport — FIXED + LIVE.** The league now returns `unpriced` (count of CLOSED rows
  with null pnl_net); champion reads `unpriced:20`, so its +₹19,274 net is no longer mis-read as covering all 35
  closed. The §6 "pnlNet vs pnlPoints sign clash" is confirmed a coverage artifact, now surfaced in the API.
- **Screener #607 DEPLOYED** — Manas liquidity 25×→50× (M35) + deterministic RS tie-break (M12) + M39 depth/65wk
  caps active with the min-base-weeks floor DISABLED. Affects the equity swing funnel, NOT this scalper session.

This addendum is informational (file stays immutable otherwise). See memory `relative-vol-floor-armed.md`.
