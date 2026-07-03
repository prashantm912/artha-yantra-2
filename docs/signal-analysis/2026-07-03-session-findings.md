# Session findings — 2026-07-03 (data date)

**Analysis date:** 2026-07-03 (post-market, scheduled agent run). **Analyst:** Claude (automated).
**Data:** `strategy.signal_rejections` — **438 rows on 2026-07-03**, 12 strategies, spanning only
**09:19–12:40 IST** (see the NEW alarm below — the engine stopped evaluating at 12:40). `strategy.signals`
fired: **0** (still never). Paper trades: 0. **Shadow book: 20 positions, all CLOSED** (first live session
with shadow outcomes). Method: [README.md](README.md) §3 pass + shadow-book PnL.
**Session character:** slow grind-UP day — spot 24,414 → 24,447 over the observed window (+~33 pts / 2h,
no scalp impulse). VIX dot 99.7% supportive (low/steady VIX). RSI(3m) ran HOT (rsi-band operand avg ~82,
opposite of 2026-07-02's cool ~55). Front future NIFTY26JULFUT. No expiry (weekly is NSE Tuesday).

**Headline verdict:** the structural picture is unchanged from 2026-07-02 — the same FOUR/FIVE dead inputs
and the same unpassable `volume-floor` (357/438 first-blocks; operand avg ~5.9k vs 125,000 threshold).
BUT the shadow book adds decisive new evidence: **every one of the 20 shadowed entries volume-floor vetoed
today LOST money** (0 wins, −513 pts, avg −10.8%). On a slow theta-bleeding grind, the gate's veto was
*correct*. Two headline items: (1) the volume-floor tune is still structurally justified but its urgency is
tempered — on THIS regime loosening it would have lost; (2) a **NEW ops alarm: the SignalEngine silently
stalled at 12:40:16 IST** and evaluated nothing for the afternoon while the container stayed up.

---

## 1 Funnel numbers

Rows 438 (09:19–12:40 IST only). First-blocking-rail histogram:

| blocking_rail | rows | strategies | avg margin | note |
|---|---|---|---|---|
| volume-floor | 357 | 11 | −119,053 | operand avg ≈ 5.9k vs threshold 125,000 (unchanged from 07-02) |
| time-window | 76 | 3 | — | known-blocked bars re-logged = noise |
| time-of-day-preference | 5 | 1 | — | same class |

All-failed-rails expansion (unnest `checks[]`, pass=false; top rows):
volume-floor 357 (5,946 vs 125,000) · confluence-composite 194 (avg 0.504 vs 0.6) · time-window 76 ·
divergence-vol-gate 48 · trend-change 48 · oi-divergence-magnitude 43 (16.5 vs 20 — closer than 07-02's 5.98) ·
oi-cross-required 43 · two-candle 39 · volume-pump 39 (FAIL_OPEN) · pct-price-move 39 (0.099 vs 1.0, FAIL_OPEN) ·
open-high-low 34 · directional-change-gate 34 · rising-volume 24 · call-put-delta 22 (28.4 vs 50) ·
oi-slope-agree 18 · psar-durability 10 (0.037 vs 0.05) · rsi-5m-cap 8 (77.7) · rsi-band 4 (**81.95** — hot) ·
misc ≤5.

## 2 Rail findings

### 2.1 `volume-floor` — UNPASSABLE, but today it vetoed LOSERS (P0, evidence now two-sided)

- Same root cause as 07-02: hardcoded `NIFTY_VOL = 125000` (`ScalperGates.java:35`), YAML
  `scalper.params.volume_floor` null in every shipped strategy. Observed 3m operand avg ~5.9k, max last
  bar 10,465 — floor is above the physical range on a normal day, so it passes 0% and is the day's binding
  constraint (357/438 = 82% of first-blocks; 27 rows passed every OTHER evaluated rail — see §5).
- **NEW two-sided evidence (shadow book, §5):** the 20 composite-passing entries this rail vetoed were
  realized virtually and **all 20 lost** (−513 pts). On this slow grind-up, long CE premium bled theta
  faster than the +33-pt drift added — the veto was *right today*. So the **structural** critique (the
  floor cannot pass on a normal-volume day, which is a mis-calibration to OUR tick-agg series) stands, but
  the *urgency* argument ("it's costing us winners") is not supported by 2026-07-03. Verdict: keep the tune
  PROPOSED, downgrade urgency until a trend/impulse session shows volume-floor blocking a winner.
- Proposed number unchanged: relative floor (bar ≥ k × rolling-median volume) preferred over a fixed
  ~15–20k, because a fixed floor that would pass today would also pass every chop bar. Status: **PROPOSED**.

### 2.2 Working-as-designed / regime rails (no tune)

- `rsi-band`: operand ran ~82 today (hot) vs ~55 on 07-02 (cold). The band did its job at both extremes —
  confirms the rail is regime-responsive, not dead. No tune.
- `oi-divergence-magnitude`: avg operand 16.5 vs 20 today (much closer than 07-02's 5.98) — the 20 threshold
  is reachable in a more active OI regime; continue to watch, do not touch.
- `time-window` / `time-of-day-preference`: 81 rows are logging noise (bars outside windows re-logged;
  straddle's "11:00–13:00 sideways block" fired repeatedly). Cosmetic only.

## 3 Composite + dots

Composite distribution (0.1 buckets): 0.1→1 (the only PE row) · 0.3→8 · 0.4→36 · 0.5→79 · **0.6→133 ·
0.7→75**. Ceiling is the 0.7 bucket — consistent with the **0.765 dead-weight cap** (Σw 19.6, dead
dots 4.6). **208 rows sat ≥0.6 threshold**, ALL CE (PE mirror silent on the up-day, 1 PE row total). The
composite was NOT the binding constraint; volume-floor was.

Dot support rates (332 confluence-evaluated rows):

| dot | w | support % | vs 07-02 | verdict |
|---|---|---|---|---|
| volume | 1.0 | **0%** | 0% | dead — same 125k floor (§2.1) |
| iv_rank | 0.8 | **0%** | 0% | dead — `ivRank` NULL 438/438 (honest-null, scores against) |
| iv_pair | 0.8 | **0%** | 0% | dead — 0.10 gap between 6-strike avgs never occurs |
| breadth | 1.0 | **0%** | 0% | dead — advances/declines 0/0 in all 332 rows (EOD-bhavcopy source, 422 intraday) |
| oi_spurt | 1.0 | **0%** | 0% | dead-by-calibration (needs price% ≥50; spurt_zero 99 rows) |
| iv_abs_band (E4) | 0.8 | 0% (n=48) | 0% | ATM IV outside 10–12 band again |
| iv_slope (E4) | 0.8 | 0% (n=48) | 5.6% | rare by nature |
| trending_cross | 1.0 | **11.7%** | 53% | regime — collapsed on the grind (was healthy 07-02) |
| underlying_oi | 1.0 | 47.3% | 53% | healthy |
| futures_oi | 1.5 | 54.8% | 44% | healthy |
| drastic_oi | 1.0 | **56.0%** | 96% | **much less free today** — supports the "regime-dependent, don't set drasticFloor off one session" note |
| sentiment_slope | 1.0 | 62.7% | 41% | healthy |
| sentiment | 1.0 | 67.2% | 69% | healthy |
| psar | 1.0 | 78.0% | 74% | healthy |
| rsi | 1.0 | 80.1% | 43% | healthy (hot-RSI regime) |
| vwma | 1.0 | 87.0% | 82% | healthy |
| vix / supertrend / basis | 1.0 | 99.7% | 94% | healthy |
| vwap | 2.5 | 100% | 100% | by construction (side chosen BY vwap) |

**Dead-weight cap unchanged:** 5 dead dots (volume 1.0 + iv_rank 0.8 + iv_pair 0.8 + breadth 1.0 +
oi_spurt 1.0 = 4.6) → max composite = 15.0/19.6 ≈ **0.765** vs threshold 0.6. Confirmed by the 0.7-bucket
ceiling. `drastic_oi` swinging 96%→56% between two sessions is the clearest sign these dot rates need
multi-session aggregation before any dot threshold moves.

## 4 Data health (2026-07-03 rows)

| field | state | classification | vs 07-02 |
|---|---|---|---|
| macro.ivRank | NULL 438/438 | honest-null (insufficient IV history); scores against | same |
| macro.dowUp | NULL 438/438 | by-design (Dow un-armed); null = NEUTRAL (correct) | same |
| macro.fiiLongPct | NULL 438/438 | fii-bias dot un-armed | same |
| macro.advances/declines | 0/0 in all 332 confluence rows | broken-for-intraday (EOD bhavcopy) | same |
| oi.spurtPricePct | 0.00 in 99 rows | floor 50 unreachable | same |
| everything else (chart/oi) | populated, plausible | ✓ capture pipeline healthy (morning only — see §6) | same |

No NEW dead-data fields vs 2026-07-02. All five known-dead inputs remain dead; nothing that was alive
went dead (within the observed 09:19–12:40 window).

## 5 Shadow-book outcomes (first live session)

**Exit-fidelity caveat (README §2):** the shadow book replicates premium brackets / structural stop on the
signal future / 15:12 square-off / STALE only — indicator-driven exits (trend-flip / signal-exit) are NOT
replicated, so these outcomes are a *lower-fidelity floor* on the real strategy's exits.

20 positions, all CLOSED, opened 09:22–11:55, last close 15:12 (square-off). **Every position on rail
`volume-floor`** (the only rail that both let composite pass and vetoed the entry). PnL:

| close_reason | n | avg % | sum pts | read |
|---|---|---|---|---|
| STRUCTURAL_STOP | 13 | −2.1 | −64.4 | golden-crossover ×5, market-movers ×8 — small stops as the grind chopped |
| SQUARE_OFF | 7 | −26.9 | −448.7 | held to 15:12, premium bled −25 to −29% each (theta on a flat grind) |
| **total** | **20** | **−10.8** | **−513.1** | **0 wins / 20** |

Per-strategy: golden-crossover CE (5, structural, −17 pts), market-movers CE (8, structural, −47 pts),
and one SQUARE_OFF each for gap-theory / trend-change (−69.8), open-high-low / two-candle (−65.3),
trending-oi (−63.6), morning-trade (−57.3), connect-the-dots (−57.8).

**Interpretation:** on a slow grind-up with no impulse, buying ATM CE premium is a losing trade regardless
of a "bullish" composite — theta wins. The 20-for-20 loss is the counterfactual answer to "would loosening
volume-floor have made money on 2026-07-03?": **no, it would have lost 513 points.** This is exactly the
kind of chop the volume floor is *meant* to filter (even if its 125k calibration is wrong in absolute
terms). The 27 §3.5 would-have-fired rows dedup to these 20 shadow positions (one OPEN per strategy+side;
repeat rows collapse until close) — the shadow book IS the §4.2 counterfactual for this session, so no
manual premium-path replay is needed.

## 6 New data points / anomalies

- **NEW OPS ALARM — SignalEngine silent stall at 12:40:16 IST.** All 12 strategies stop emitting rejections
  at 12:31–12:40 IST; `SignalEngine`'s last log line is `07:10:16Z` (=12:40:16 IST) and the logger is
  **completely silent for the rest of the session** (hour 08 UTC = 0 lines). Meanwhile: `marketdata.candles`
  ingested the front future to **15:29 IST** (feed alive), the `ShadowExitMonitor` `@Scheduled` thread kept
  firing to 15:12, and the container did **NOT restart** (`RestartCount=0`, original boot 08:56 IST). No
  exception, WARN, reconnect, or redis/stream log anywhere in the session. Signature = the **bar-event-driven
  entry-evaluation path stalled silently while the scheduled-thread pool stayed alive** (why exits kept
  working but entries died). **Consequence: the entire afternoon (12:40–15:30) is UNOBSERVED by the gate** —
  today's rejection counts, would-have-fired set, and "0 signals" cover only the morning half. Do NOT read
  this session as a full-day sample. This is an ops/reliability defect, not a strategy finding; it is a
  strong argument for the README §7.8 `DataHealthCanary` (a live watcher that pings when the newest
  rejection's `generated_at` falls too far behind wall-clock would have caught this at ~13:00). **Owner
  action item: investigate why the SignalEngine bar consumer stalls mid-session (likely a blocked/dead
  subscription thread); a service restart clears it but the root cause recurs risk is unknown.** Read-only
  run — no restart performed.
- Strategy count 12 today vs 16 on 07-02 — partly the 12:40 stall (later-triggering strategies never logged),
  partly no SENSEX-variant rows in the observed window. Not alarming on its own; re-check on a full session.
- `oi-divergence-magnitude` operand 16.5 (vs 5.98 on 07-02) and `drastic_oi` 56% (vs 96%) confirm these two
  are strongly regime-dependent — the rollup must aggregate them, never tune off one session.

## 7 Tuning candidates (status ledger — carried forward from 2026-07-02)

| # | knob | current | proposed | evidence | status |
|---|---|---|---|---|---|
| 1 | `scalper.params.volume_floor` / `ScalperGates.NIFTY_VOL` | null → 125,000 | relative k×median (pref) or ~15–20k | §2.1: floor > session max, 27 entries vetoed — BUT §5 shadow book: all 20 vetoed entries LOST on 07-03 | **PROPOSED (urgency downgraded — vetoed only losers on 07-03; need a trend session to prove it blocks winners)** |
| 2 | `artha.scalper.oi.ivPairMinGap` | 0.10 | 0.01–0.02 | §3: 10-pt gap between 6-strike avgs never occurs (0% both sessions) | PROPOSED |
| 3 | `artha.scalper.oi.spurtPricePct` | 50 | 5–10 | §3: 0% dot support both sessions; spurt_zero 99 rows | PROPOSED |
| 4 | breadth live producer | EOD bhavcopy → 0/0 intraday | live A/D from N50 batch quotes | §3/§4: dead in every session (0/0 both) | PROPOSED (build) |
| 5 | iv_rank null semantics | null scores against | null = neutral / excluded | §3/§4: honest-null punished both sessions | PROPOSED (code) |
| 6 | composite threshold 0.6 | keep | keep (fix inputs) | §3 cap math (0.765 ceiling) | DECIDED-KEEP |
| 7 | `DataHealthCanary` live staleness watcher (README §7.8) | not built | ping when newest rejection lags wall-clock / a field goes newly dead | §6: 12:40 SignalEngine stall went undetected until EOD | **PROPOSED (build) — NEW, promoted from §6** |

**Two-session caveat:** items 1–3 are structural (threshold outside the operand's physical range — not
day-dependent). Item 1's *tuning urgency* is now genuinely two-sided: structurally the floor is mis-calibrated,
but on 07-03 it vetoed 20/20 losers, so the fix must be a **relative** floor (filters chop, admits impulse
volume), not merely a lower fixed number. Dot rates (`drastic_oi`, `trending_cross`, `oi-divergence-magnitude`)
swing hard between the two sessions — hold all dot-threshold moves for the multi-session rollup.
