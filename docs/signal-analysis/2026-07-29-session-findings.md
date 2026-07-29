# Session findings — 2026-07-29 (data date)

Analysis date: 2026-07-29 (scheduled post-market agent, ran 15:50–16:35 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,293** (bar times 09:18–15:12), signals **20** (12 ENTRY + 8 EXIT),
paper positions opened **4** (all closed), shadow positions opened **24** (all closed).

**2026-07-29 is an ordinary Wednesday — no NSE or BSE monthly expiry** (NSE was Tue the 28th, BSE is Thu
the 30th). The whole OI bloc is live again and yesterday's S24 suppression is gone. Per ledger row **G1**
this is the **SECOND clean forward session** after the 2026-07-25 D-wave, so — unlike 07-28 — this session
**does** carry entry-gate calibration weight.

**Signal contract: `NFO:NIFTY26AUGFUT@3m`, confirmed DIRECTLY from the engine log** (1,308 occurrences of
`NFO:NIFTY26AUGFUT` in the rail lines, zero of any other future), not merely derived. Unchanged since the
07-27 roll, so cross-session volume comparisons against 07-27 and 07-28 are like-for-like. ⚠ Note that the
§3.18 range test alone was **not decisive today** — `context.chart.close` spans 24,258.60–24,278.00, which
falls inside `NIFTY26AUGFUT`'s day range (24,222.40–24,346.60) **and** inside `NIFTY 50`'s
(24,141.75–24,282.45); only `NIFTY26SEPFUT` (24,319.50–24,460.00) is excluded by range. The log line is
what settles it.

Session character: **a clean trend-up day.** `NIFTY 50` 24,176.65 → **24,241.00** (high 24,282.45, low
24,141.75); against 07-28's 23,987.60 close that is **+253.40 pts, +1.06%**. `SENSEX` 77,423.77 →
77,632.89. The composite population is almost entirely CE (971 of 983 scored rows), which is what a
one-directional up-tape should produce.

This file folds in the three earlier read-only runs of the same date —
[`2026-07-29-open-gate.md`](2026-07-29-open-gate.md) (PASS),
[`2026-07-29-midday-gate.md`](2026-07-29-midday-gate.md) (PASS) and
[`2026-07-29-live-health.md`](2026-07-29-live-health.md) (GREEN) — and discharges the last file's §9
carry-list in §6.5.

---

## 0 Read this first — the session's headline

1. **The single biggest finding is an EXIT finding, not an entry finding: the 10-bar (30-minute)
   `time_stop` converted a large winner into a large loser on a trend day, and both books measured it
   independently.** The champion shadow book, which does **not** model `time_stop` and holds to the 15:12
   square-off, closed **24 positions for +810.00 pts on its 16 SQUARE_OFF rows and +₹15,260.87 NET — its
   best session on record.** The same session's §4.2 counterfactual over the would-have-fired legs, modelled
   **with** the YAML's 30-minute time stop, returns **5W / 36L and −538.50 pts.** Same tape, same legs,
   opposite sign; the only difference is the exit model. The live paper book confirms it on real fills:
   **3 of its 4 positions closed `TIME_STOP`/`STRUCTURAL_STOP` for losses on a leg that kept going** (§5.2).
2. **The armed relative volume floor sat at roughly the p97–p98 of its own operand for the entire
   09:45–10:12 block, and it is the opening surge that puts it there.** The floor is a *median* over a
   rolling window (`ScalperGates.relativeVolumeFloor`, read this run), so it is robust to one outlier — but
   the first ten 3m bars contain **four** bars ≥100,000 (476,840 / 153,075 / 124,410 / 105,560) against a
   session median of **15,015**, so the median itself is genuinely high. Thresholds ran **133,185 at
   09:45–09:57**, decaying monotonically to ~26,000 by 11:21, while the operand sat at 11,000–34,000
   throughout. **326 of the 756 `volume-floor` blocks (43%) landed before 11:00** — a 1h15m slice of a
   5h45m session (§2.1). New row **T27**.
3. **T1 finally has its counterfactual, and it says DO NOT LOOSEN.** The §3.5 would-have-fired set for
   `volume-floor` is 11 distinct legs; resolved against real premium paths they are **2W / 9L, −121.95
   pts** (§4.2). Lowering `k` 1.5 → 1.2 would have admitted those rows. Combined with 07-21 (0/6) and
   07-27 (4-for/5-against), **T1 is now REJECTED on forward evidence, not merely blocked.**
4. **`iv_pair`'s tune (T3) is dead on arrival, and the ground-truth query that has been outstanding for
   ten sessions finally ran.** The CE-vs-PE 6-strike IV gap on 983 rows: p50 **0.00010**, p90 **0.00050**,
   **max 0.00070**. The live threshold (0.02) is **28× the session maximum**; the *proposed* 0.005 is still
   **7×** it. The dot's operand cannot express the intended signal at any usable threshold — put-call
   parity pins the two ATM-band averages together by construction. **T3 is RE-SCOPED from a knob turn to a
   dot-redefinition-or-drop decision** (§3.3).
5. **`iv_abs_band`'s "revival" is an artefact of a FROZEN input, not IV dynamics.** `macro.atmIv` carries
   **exactly one distinct value per session** — 0.130859 (07-24), 0.135577 (07-27), 0.121736 (07-28),
   **0.118781 (07-29)** — so the 10–12 band test is a per-day step function. 07-28's stamp sat just outside
   0.12 (0/180); today's sits inside (**133/133 = 100%**). The live-health run flagged this as
   revival-or-free-dot; it is **neither — it is a frozen operand**. New row **T28** (§3.2). Note the
   neighbouring `ceIvAvg6`/`peIvAvg6` **are** live (41/44 distinct values), so this is specific to `atmIv`.
6. **Every liveness oracle is clean.** `confluence-blocked` = **1,293** = the rejection row count exactly;
   `fired` = **12** = the ENTRY signal count exactly; `ay_signal_eval_duration_seconds_count` = **375** (the
   full minute grid); `subscriber_health_events` **empty**; **0 ERROR** lines in both services; **0**
   misaligned candles; OI capture **375/375** minutes; clock drift **<0.2 s**; `RestartCount 0`.
7. **Coverage is 34 of 38 and the four silent slugs are all `-pe` variants** — `golden-crossover-nifty-pe`,
   `golden-crossover-sensex-niftyoi-pe`, `open-high-low-nifty-pe`, `open-high-low-sensex-niftyoi-pe`. On a
   +1.06% trend-up tape the PE side dies at the chart gate (upstream of `recordRejection`), which writes no
   row. Consistent with the 971/983 CE skew, but see §8 for what this does and does not prove (§1.1).
8. **T23 is at its quietest since the counter was tracked: 6 WARNs, all three as exact ± pairs on
   consecutive buckets, every shortfall an exact ×65 multiple, all inside the first hour** (§6.1). No
   unpaired event at either end for the first time in the series.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-24 | 2026-07-27 | 2026-07-28 | **2026-07-29** |
|---|---|---|---|---|
| rejections | 1,366 | 1,253 | 1,350 | **1,293** |
| distinct strategies emitting | 36 | 38 | 36 | **34** |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| **coverage ratio (§3.10)** | 36/38 | 38/38 | 36/38 | **34/38** (the four `-pe` variants — §1.1) |
| signals | 0 | 3 ENTRY + 3 EXIT | 0 | **12 ENTRY + 8 EXIT** |
| paper positions opened | 0 | 0 | 0 | **4** (scalper book — a first) |
| bar-time coverage | 09:18–14:57 | 09:18–15:18 | 09:18–14:57 | **09:18–15:12** |
| scored rows | 1,100 | 909 | 1,068 | **983** |
| composite ≥ threshold rows | 418 | 253 | 0 | **311** |
| max composite | 0.7447 | 0.8511 | 0.4521 | **0.9118** |
| **max ACHIEVABLE composite** | 0.8511 | 0.9043 | 0.5479 ⚠ | **0.9574** |

**Eval counters (actuator :8082, read 16:04 IST).** The container booted **2026-07-29 01:54 IST**
(`StartedAt 2026-07-28T20:24:41Z`, `RestartCount 0`) — before the open and not restarted since, so the
cumulative counters ARE today's session totals:

| outcome | 2026-07-29 |
|---|---|
| `chart-gate-failed` | **2,003** |
| `confluence-blocked` | **1,293** |
| `composite-below-threshold` | **162** |
| `fired` | **12** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 |
| **Σ** | **3,470** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = **1,293** = today's rejection row count **exactly**, and `fired` = **12** = the
ENTRY-signal row count **exactly**. Two independent reconciliations.

`ay_signal_eval_duration_seconds_count` = **375** — the complete 09:15→15:29 minute grid, matching 07-28.
Sum 1,932.26 s ⇒ mean **5.15 s**/eval (07-28: 5.54 s; 07-27: 4.84 s). The three-session upward drift
partially reverses; still far inside any stall reading.

**Liveness gauges at 16:04 IST:** `ay_signal_bar_received_age_seconds` =
`ay_signal_bar_evaluated_age_seconds` = **1525.712** — non-negative, mutually equal, consistent with the
15:30 close. The last bar received was also evaluated.

**First-blocking-rail histogram** (1,293 rows, **18** distinct rails — the widest spread in the folder):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **756 (58.5%)** | 14,345.9 | 39,552.3 | −25,206.4 |
| time-window | 252 (19.5%) | — | — | — |
| time-of-day-preference | 42 | — | — | — |
| pct-price-move | 38 | 0.370 | 1.000 | −0.630 |
| volume-pump | 38 | 31,039.2 | — | — |
| two-candle | 38 | — | — | — |
| divergence-vol-gate | 36 | 30,304.4 | — | — |
| max-oi-sr-gate | 19 | 24,200.0 | — | — |
| rsi-band | 16 | 60.21 | — | — |
| oi-cross-required | 14 | 72.84 | — | — |
| option-side-constraint | 14 | — | — | — |
| call-put-delta-filter | 8 | 23.08 | 50.00 | −26.92 |
| directional-change-gate | 8 | 0.335 | — | — |
| confluence-composite | 6 | 0.542 | 0.600 | −0.058 |
| psar-durability | 2 | 0.031 | 0.050 | −0.019 |
| open-high-low | 2 | — | — | — |
| hero-zero | 2 | — | — | — |
| constituent-gate | 2 | −0.070 | — | — |

**`confluence-composite` as a first-blocker collapsed 20 → 6**, and its avg margin is −0.058 — near-misses,
not structural starvation. That is the shape of a healthy, un-suppressed composite.

**All-failed-rails expansion (§3.3)** — top 20:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | **756** | 14,345.9 | 39,552.3 |
| confluence-composite | FAIL_CLOSED | **672** | 0.469 | 0.600 |
| time-window | FAIL_CLOSED | 252 | — | — |
| trend-change | FAIL_CLOSED | 166 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 164 | 17,723.6 | — |
| constituent-gate | FAIL_OPEN | 164 | −0.070 | — |
| rsi-band | FAIL_CLOSED | 134 | 64.74 | — |
| pct-price-move | FAIL_OPEN | 130 | 0.310 | 1.000 |
| volume-pump | FAIL_OPEN | 130 | 19,421.0 | — |
| two-candle | FAIL_CLOSED | 130 | — | — |
| oi-divergence-magnitude | FAIL_CLOSED | 96 | 8.097 | 20.000 |
| oi-cross-required | FAIL_CLOSED | 96 | 44.85 | — |
| open-high-low | FAIL_CLOSED | 88 | — | — |
| directional-change-gate | FAIL_CLOSED | 82 | 0.204 | — |
| max-oi-sr-gate | FAIL_OPEN | 81 | 24,200.0 | — |
| rising-volume | FAIL_CLOSED | 72 | 14,639.4 | — |
| oi-slope-agree | FAIL_CLOSED | 54 | −0.572 | — |
| call-put-delta-filter | FAIL_OPEN | 48 | 21.81 | 50.000 |
| time-of-day-preference | FAIL_CLOSED | 42 | — | — |
| psar-durability | FAIL_OPEN | 36 | 0.028 | 0.050 |

✅ **`strike-pick` is ABSENT from the failing-rail table entirely** — 534 fails on 07-28, 264 on 07-27,
550 on 07-24, and **zero** today. The four-session carry ("still un-bucketed by slug/time") is discharged
by the rail simply not failing on a live-chain day; the expiry-day chain was the plausible cause named on
07-28 and today is consistent with it. Not proven, but the carry is closed as no-longer-observable —
re-open only if it returns on a non-expiry session.

⚠ **`oi-divergence-magnitude` now carries a real operand (8.097 vs 20.000)** where 07-28's was NULL under
the S24 suppression. The gate is reading live OI and failing on merit, at roughly 40% of its threshold.
Noted for the rollup; one session is not a calibration case.

### 1.1 Coverage — four `-pe` slugs silent on a one-directional up tape

Set-difference against the registry returns exactly `scalp-golden-crossover-nifty-pe`,
`scalp-golden-crossover-sensex-niftyoi-pe`, `scalp-open-high-low-nifty-pe` and
`scalp-open-high-low-sensex-niftyoi-pe`. All four are PE-side variants; the session was +1.06% with **971
of 983** scored rows CE.

**Honesty limit (§3.11's rule, applied to coverage):** zero rows is consistent with a chart-stage block
upstream of `recordRejection`, but it does **not prove** the block was directional. No runtime trace was
taken. What *is* established is that the engine loaded all 38 (boot line: `signal engine loaded 38
published strategies (0 dropped on an unresolved universe, 0 failed to load)`), so this is not a T9 load
shortfall.

### 1.2 Interior coverage (§3.11) — no hole, 24/24 buckets populated

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 10 | 80 | 68 | 90 | 90 | 70 | 61 | 65 | 40 | 90 | 67 |
| bucket | 12:15 | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | 15:00 |
| n | 75 | 40 | 34 | 38 | 65 | 47 | 12 | 56 | 65 | 52 | 60 | 10 |

Every 15-minute bucket from 09:15 to 15:00 is populated — **the first fully-covered session since 07-27**,
and better than 07-28 (two holes). The thin 09:15/09:30 buckets are the pre-09:45 trade window (only the
`morning-trade` pair in-window, 2 slugs) and the thin 15:00 bucket is window narrowing; both are the
documented shapes, and the eval grid completed all 375 cycles regardless.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ⚠ NEW (T27) — the relative floor is mis-calibrated for the first 90 minutes, and the opening surge is why

**Registry state, standing §3.14 query:**

| carries `relative-volume-floor` | published on | count |
|---|---|---|
| yes | 2026-07-28 | **38** |
| no | — | **0** |

**38 of 38 armed**; `count(*) WHERE blocking_threshold = 125000` = **0**. T16 stays closed. (The 07-28
publish stamp is the #1084/#1086 wave republish; the tag survived it.)

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned bars only (§3.15):**

| bars | min | p25 | p50 | p90 | p99 | max | bars ≥125,000 |
|---|---|---|---|---|---|---|---|
| 125 | 3,900 | 9,750 | **15,015** | 51,220 | 153,075 | **476,840** | **3 (2.4%)** |

**The threshold's own time series is the finding** (per-bar, identical across every armed slug):

| IST | 09:45 | 09:57 | 10:03 | 10:09 | 10:15 | 10:21 | 10:27 | 10:39 | 10:51 | 11:03 | 11:21 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| threshold | **133,185** | 133,185 | 87,799 | 76,001 | 65,569 | 51,090 | 46,118 | 39,975 | 33,394 | 27,934 | 26,130 |
| avg operand | 32,760 | 16,055 | 20,865 | 28,730 | 21,840 | 12,285 | 28,145 | 26,260 | 11,180 | 21,320 | 6,565 |

Place those on the session's own distribution: **133,185 is exceeded by 2 of 125 bars (p98.4)** and
**87,799 by 4 of 125 (p96.8)**. For the whole 09:45–10:12 block — the first half-hour in which the bulk of
the fleet is in-window at all — the armed floor sat at roughly the **p97–p98** of the operand it tests.

**Mechanism, read in code this run.** `ScalperGates.relativeVolumeFloor`
(`ScalperGates.java:188-202`) resolves the floor as `multiplier × MEDIAN(priorVolumes)`, falling back to
the absolute floor below `minBars`. A median is robust to *one* outlier — but the opening surge is not one
bar. The first ten 3m buckets are **476,840 / 124,410 / 153,075 / 64,025 / 53,040 / 49,010 / 46,995 /
52,325 / 105,560 / 32,760**: four of the ten are ≥100,000 against a session median of 15,015, so the
*median itself* is legitimately high and stays high until the surge bars roll out of the window.

**Consequence, measured:** **326 of the 756 `volume-floor` blocks (43%) occurred before 11:00**, which is
a ~1h15m slice of a ~5h45m session. The rail self-corrects by ~11:00 and the second half of the session
runs on a floor of 13,211–30,000 against a p50 of 15,015 — which is what a k=1.5 relative floor *should*
look like.

**⚠ Reconciliation caveat — stated because it limits the claim.** Two thresholds reconcile *exactly* to
`1.5 × median` of a 10-bar DB window (87,799 = 1.5 × median{09:15…09:42}; 72,004 = 1.5 × median{09:24…09:51}),
but a third (46,118 at 10:27) does **not** match any window I tried. The engine reads its own in-memory
`LiveSeriesStore` series, which `PartialBucketCanary` proves diverges from the DB rollup (§6.1), so an
exact SQL reconstruction of the window is **not achievable from the database alone**. The finding above
does not depend on it — the *observed* thresholds and the *observed* operand distribution are both
measured directly. **What is NOT established is the exact window length/offset**, and a fix proposal must
start by reading `priorVolumes` in `ScalperConfluenceGate` rather than trusting this reconstruction.

**Proposed (T27, a BUILD not a knob):** exclude the opening N bars from the relative window, or seed it
from the PRIOR session's median, or normalise by time-of-day. See §7.

### 2.2 The would-have-fired set is large, and it is a LOSER — T1 is answered

The §3.5 query (composite ≥ threshold AND no failed check other than the blocking rail) returns **106 rows
across 6 rails**, collapsing to **41 distinct `(bar_time, leg)` pairs**. Full counterfactual in §4.2. Only
**6 of the 106** carry a shadow row (dedup suppressed the rest: one OPEN per strategy+side+variant), so
§3.13's "resolve by hand from `options_chain_snapshots` where dedup suppressed it" is what was done.

| blocking rail | wf rows | distinct legs | W | L | **pts** |
|---|---|---|---|---|---|
| volume-pump | 28 | 28 | 3 | 25 | **−392.80** |
| two-candle | 28 | 28 | 3 | 25 | **−392.80** |
| pct-price-move | 18 | 18 | 2 | 16 | **−250.60** |
| volume-floor | 16 | 11 | **2** | **9** | **−121.95** |
| max-oi-sr-gate | 14 | 14 | 2 | 12 | **−94.50** |
| hero-zero | 2 | 2 | 0 | 2 | **−23.75** |
| **union (distinct legs)** | 106 | **41** | **5** | **36** | **−538.50** |

⚠ **Rails share legs, so the per-rail rows are NOT additive** — `volume-pump` and `two-candle` blocked the
identical 14 bars × 2 legs and therefore have identical numbers. The union row is the honest total.

**Every one of the six rails blocked a losing set.** No rail in the session has a positive case for
loosening. `volume-floor` specifically — the T1 knob — is **2W/9L, −121.95 pts**.

### 2.3 The `volume` dot tracked the tape again, exactly as §3.20 predicts

`volume` supported **227 of 983 (23.1%)**, its third consecutive non-zero session and its highest reading
(07-28: 3.6%, 07-27: 0%). Per README §3.20 the dot resolves the **static** 125,000 floor at
`ConnectTheDotsScorer.java:141`, never the armed relative floor.

⚠ **But the arithmetic does NOT reconcile this session the way it did on 07-28, and that is worth
recording.** Only **3 of 125** 3m bars (2.4%) cleared 125,000 today, yet the dot supported on 23.1% of
rows. On 07-28 the two matched closely (4.8% of bars ↔ 3.6% of rows). Two candidate explanations — the
dot reads a different underlying for the `sensex-*` slugs (whose static default is **50,000**, not
125,000), or the in-memory series differs from the DB rollup — were **not discriminated this run**. T24's
root cause (the call-site divergence) is unaffected either way; what is unresolved is which *floor value*
each slug's dot is testing. Added to T24 as an open sub-question.

### 2.4 Rails with no evidence of miscalibration

`rsi-band` (avg 64.74 on failures — the exhaustion cap biting on a strong up-day, which is the design),
`pct-price-move` (0.310 vs 1.000), `constituent-gate` and `volume-pump` (both FAIL_OPEN),
`psar-durability` (0.028 vs 0.050), `call-put-delta-filter` (21.81 vs 50.00) all read plausibly.
`max-oi-sr-gate` fails 48 → **81**, every one at operand **24,200** — a round strike level, the same
pinning signature as 07-28's 24,000, one strike higher on a market that moved up 253 points. Consistent,
not flagged.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (983 scored rows):

| bucket | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 | 0.9 |
|---|---|---|---|---|---|---|---|---|
| n | 6 | 60 | 190 | 248 | 289 | 108 | 32 | 50 |
| CE | 6 | 60 | 190 | 248 | 277 | 108 | 32 | 50 |
| PE | 0 | 0 | 0 | 0 | 12 | 0 | 0 | 0 |

Max **0.9118**; **311 rows (31.6%) at or above the 0.600 threshold**, and a healthy right tail (50 rows in
the 0.9 bucket). **971 of 983 rows are CE** — the up-tape signature.

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_rank` | 0.8 | 0/983 | 0.0 | dead-data, **withheld from Σw** (#676) — 11th session |
| `iv_pair` | 0.8 | **0/983** | **0.0** | ⚠ **structurally impossible — see §3.3.** In Σw, so it costs headroom |
| `trending_cross` | 1.0 | 57/983 | 5.8 | ✅ **alive** — the live-health run's 0/722 was a mid-session read |
| `oi_spurt` | 1.0 | 84/983 | 8.5 | alive; 9.9% on 07-27 — #991 floors holding |
| `iv_slope` | 0.8 | 21/133 | 15.8 | alive |
| `vwap` | 2.5 | 160/983 | 16.3 | alive (07-27: 17.5%) — the #990 ≥15 bps condition behaving |
| `volume` | 1.0 | **227/983** | **23.1** | ⚠ highest ever; arithmetic unreconciled — §2.3 |
| `sentiment_slope` | 1.0 | 470/983 | 47.8 | |
| `futures_oi` | 1.5 | 491/983 | 49.9 | ✅ live (0% on 07-28 under S24) |
| `underlying_oi` | 1.0 | 513/983 | 52.2 | ✅ live |
| `premium_skew` | 1.0 | 16/30 | 53.3 | ⚠ **first appearance in this folder** — §3.4 |
| `rsi` | 1.0 | 544/983 | 55.3 | |
| `sentiment` | 1.0 | 661/983 | 67.2 | |
| `psar` | 1.0 | 696/983 | 70.8 | |
| `drastic_oi` | 1.0 | 851/983 | 86.6 | |
| `vwma` | 1.0 | 853/983 | 86.8 | |
| `vix` | 1.0 | 971/983 | 98.8 | |
| `breadth` | 1.0 | 971/983 | 98.8 | ✅ recovered from 07-28's one-constituent near-miss (T18 stays closed) |
| `basis` | 1.0 | 971/983 | 98.8 | |
| `iv_abs_band` | 0.8 | **133/133** | **100.0** | ⚠ **FREE dot on a FROZEN input** — §3.2 |
| `supertrend` | 1.0 | 983/983 | **100.0** | 4th 100% session in the folder |

### 3.1 Dead-weight cap

Weights per README §2. On the 18-dot roster (983 rows): Σw = 19.60, `iv_rank` withheld (0.80) ⇒ 18.80
denominator; the only dead-in-denominator dot is `iv_pair` (0.80).

**Cap = (18.80 − 0.80) / 18.80 = 0.9574**, against an observed max of **0.9118**. No structural
starvation — this is a session where the gate had full headroom and the market decided the outcome. (For
the 133-row IV roster the cap is 19.60/20.40 = 0.9608.)

### 3.2 ⚠ NEW (T28) — `iv_abs_band` at 100% is a FROZEN operand, not a revival

`macro.atmIv` carries **exactly one distinct value per session**, four sessions running:

| session | distinct `atmIv` values | value | `iv_abs_band` |
|---|---|---|---|
| 2026-07-24 | **1** | 0.130859 | 0/180 (outside the 0.10–0.12 band) |
| 2026-07-27 | **1** | 0.135577 | 0% |
| 2026-07-28 | **1** | 0.121736 | 0/180 — **just outside 0.12** |
| **2026-07-29** | **1** | **0.118781** | **133/133 = 100%** — just inside |

The band test (`props.ivAbsBandLow()` ≤ atmIv ≤ `props.ivAbsBandHigh()`, 0.10–0.12 on the 0..1 fraction
scale, `ConnectTheDotsScorer.java:210-213`) is therefore a **per-day step function**: the dot is 100% or
0% for a whole session depending on where one stamped number lands. README §3.6 flags 0% and ~100% as the
same class of finding, and this is the ~100% case with a mechanism.

**This is specific to `atmIv`, not to the IV feed as a whole** — the neighbouring `ceIvAvg6` (41 distinct
values) and `peIvAvg6` (44) move normally within the session, as do `ceIvSlope` (100), `vixLevel` (27) and
`premiumSkewPct` (100). So the finding is narrow and actionable: **one field is stamped once and reused**,
and 0.8 of composite weight is a coin-flip on it.

**Not established this run:** *where* `atmIv` is stamped (no producer-side code read was done), and
whether the freeze is intentional (a session-open ATM IV reference) or a caching defect. T28 names the
observation, not the cause.

### 3.3 ⚠ `iv_pair` (T3) — the ground-truth query finally ran, and it kills the proposed tune

The gap distribution the rollup has been demanding since 2026-07-15, over 983 non-expiry rows:

| n | min | p50 | p90 | p99 | **max** | rows ≥ 0.02 | rows ≥ 0.005 |
|---|---|---|---|---|---|---|---|
| 983 | 0.00000 | 0.00010 | 0.00050 | 0.00070 | **0.00070** | **0** | **0** |

- The **live** threshold `ivPairMinGap = 0.02` is **28× the session maximum**.
- The **proposed** 0.005 is still **7× the session maximum** — it would revive nothing.
- The operands are `ceIvAvg6` ≈ `peIvAvg6` ≈ 0.104, tracking each other within 0.0007. **This is put-call
  parity**: two 6-strike averages taken around the same ATM cannot diverge materially.

**Verdict: T3 is RE-SCOPED.** It is not a threshold that is too high; the *operand* cannot express the
intended "IV skew favours this side" signal. The choices are (a) drop `iv_pair` from Σw (freeing 0.8 of
denominator, raising the cap 0.9574 → 1.0000), or (b) redefine the dot on an operand that actually skews
(e.g. across-strike skew, or CE-vs-PE at *different* strikes). Either is a BUILD decision, not a knob.
⚠ 07-28's "first life ever, 18/1,068 = 1.7%" was an expiry-day observation, was excluded from T3's
evidence base at the time, and this non-expiry ground truth confirms that exclusion was right.

### 3.4 ⚠ `premium_skew` — a dot this folder has never recorded

`premium_skew` (weight 1.0) appears on **30 rows** and supports on **16 (53.3%)**, reason `not chasing the
richer side without cues`. It is the E7 Hero-Zero warning dot (`ConnectTheDotsScorer.java:215-227`),
emitted only when `premiumSkewDot` is armed on the strategy — which is why it shows on 30 rows, not 983.
Its input `premiumSkewPct` is live (100 distinct values).

Recorded as a first sighting. **30 rows is far too small a base for any calibration reading**; it goes on
the watchlist, not the tuning ledger.

## 4 Data health (§3.7)

| field | 2026-07-27 | 2026-07-28 | **2026-07-29** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 0 NEUTRAL | 1,068/1,068 NEUTRAL (S24) | **0 / 983 NEUTRAL** | ✅ **fully live — the control that proves 07-28 was suppression, not outage** |
| `spurtOiPct` / `spurtPricePct` | 0 null | 1,068/1,068 NULL (S24) | **0 / 983 NULL** | ✅ live |
| `futuresBasis` | — | 1,068/1,068 LIVE | **983/983 LIVE** | ✅ |
| `advances` / `declines` | 0 zero-pairs | 0 zero-pairs (dot regime-dead) | **0 zero-pairs, 0 nulls** | HEALTHY (dot 98.8%) |
| `fiiLongPct` | NULL 100% | 8.78 on 1,068/1,068 | **0 nulls / 983** | ✅ **#1050 holds a 2nd full session** |
| `atmIv` | 1 distinct value | 1 distinct value | **1 distinct value (0.118781)** | ⚠ **NEW: frozen — T28, §3.2** |
| `ivRank` | NULL 100% | NULL 100% | **NULL 983/983** | dead-data (carried since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 983/983** | by design (un-armed) |

**The 310 context-less rows reconcile exactly**: 252 `time-window` + 42 `time-of-day-preference` + 14
`option-side-constraint` + 2 `hero-zero` = **310** = 1,293 − 983. Every context-bearing row carries live
macro data.

**Capture (minute-aligned bars only):**

| series | bars | misaligned | day range | last bar |
|---|---|---|---|---|
| **`NIFTY26AUGFUT` 1m (today's signal series)** | **375** | **0** | 24,222.40–24,346.60 | 15:29 |
| `NIFTY26SEPFUT` 1m | 375 | 0 | 24,319.50–24,460.00 | 15:29 |
| `NIFTY 50` 1m | 375 | 0 | 24,141.75–24,282.45 | 15:29 |
| `SENSEX26JULFUT` 1m (BFO) | 372 | 0 | 77,300.00–77,733.00 | 15:29 |
| `futures_oi_snapshots` | 25,875 rows / **375** distinct minutes | — | — | 15:30 |

✅ **T19 quiet for a FIFTH consecutive session** — the §3.15 misaligned-bucket probe returns the **empty
set** session-wide (`source` breakdown: zero rows of any source).
✅ **T12 at its best reading ever — 375 of 375 OI minutes (100%)**, up from 374/375 (07-28) and 372/375
(07-27).

**`dot-health` canary at 15:56 IST** (200 scanned / 40 context-bearing):

```
breadth        alive=true  required=true
iv_rank        alive=false required=false  input dead across 40 context-bearing rejections
dow            alive=false required=false  input dead across 40 context-bearing rejections
fii            alive=true  required=false
oi_spurt_price alive=true  required=false   <-- recovered; 07-28's dead reading was S24
vix            alive=true  required=false
futures_oi     alive=true  required=true    <-- recovered
underlying_oi  alive=true  required=true    <-- recovered
```

**Dead set = the standing pair `ivRank` + `dowUp`. Nothing newly dead.** ⚠ The canary has **no probe for
`atmIv`**, which is why T28 (§3.2) was invisible to it — a frozen-but-non-null input passes an
alive/dead test by construction. Proposed as a probe-registry extension in §7.

**Error channels.** `ay-strategy-signal-service`: **0 ERROR** lines all session.
`ay-market-data-service`: **0 ERROR** lines. `strategy.subscriber_health_events`: **0 rows**.

**Host-clock guard (B8).** Host UTC `2026-07-29T10:33:41.795` vs container `now()` `10:33:41.964` —
**~0.17 s** apart. No drift. B8 remains a free-running-CMOS watch item.

## 5 Shadow-book outcomes

### 5.1 The champion book's best session on record

Opened today (all four books writing), **all positions closed by EOD, zero left OPEN**:

| variant | opened | closed | wins | pts | **net ₹** | null `pnl_net` |
|---|---|---|---|---|---|---|
| **champion** | **24** | 24 | **14** | **+688.85** | **+15,260.87** | 0 |
| vol-12k5 | 3 | 3 | 1 | +42.50 | **+187.28** | 0 |
| vol-off | 5 | 5 | 1 | +27.40 | **−559.95** | 0 |
| composite-055 | 3 | 3 | 1 | −76.40 | **−2,952.21** | 0 |

`unpriced`/null-`pnl_net` = **0** across all four books (the F8 lot-size check).

**Champion by close reason — this is the §0.1 finding in one table:**

| close reason | n | pts | **net ₹** | bar-time span |
|---|---|---|---|---|
| **SQUARE_OFF** (held to 15:12) | **16** | **+810.00** | **+19,547.61** | 09:48–14:30 |
| STRUCTURAL_STOP | 8 | −121.15 | −4,286.74 | 09:48–12:06 |

**All-time league (refreshed 16:20 IST):**

| variant | closed | wins | pts | **net ₹** | movement today |
|---|---|---|---|---|---|
| champion | 243 | 106 | +442.35 | **−19,892.76** | **+₹19,547.61** (was −39,440.37 at 12:43) |
| vol-12k5 | 41 | 13 | −175.80 | **−9,144.03** | +₹187.28 |
| vol-off | 53 | 15 | −361.50 | **−17,574.05** | −₹559.95 |
| composite-055 | 14 | 4 | −86.60 | **−4,494.36** | −₹2,952.21 |

✅ **`composite-055` finally took rows (3) after two silent sessions — and lost on all-but-one, −₹2,952.21
on its first live outing since #991.** The loosened-composite book is now the *worst* per-close book of the
four. Early, but it is the first real evidence against lowering the composite threshold.
✅ The **`vol-12k5` > `vol-off`** ordering survived a sixth session where both traded.

**Exit-fidelity caveat (standing, §3.16):** the shadow book replicates brackets + structural stop + 15:12
square-off. It does **not** replicate indicator signal-exits **or the YAML `time_stop`** — which is
precisely why §5.2's comparison is possible and why it matters.

### 5.2 ⚠⚠ The exit-model divergence — the session's most decision-relevant number

The live paper book (scalper, first fires ever under the ₹15,000 budget) took **4 positions, all closed,
net −₹2,435.95**:

| id | leg | qty | entry | closed | reason | realized ₹ | leg at 15:00 |
|---|---|---|---|---|---|---|---|
| 41 | `SENSEX26JUL77200CE` | 40 | 482.05 | 11:37 | **TIME_STOP** | **−971.06** | **555.00** |
| 42 | `SENSEX26JUL77200CE` | 40 | 498.05 | 12:19 | **STRUCTURAL_STOP** | **−926.32** | **555.00** |
| 43 | `SENSEX26JUL77200CE` | 40 | 518.05 | 13:40 | **TIME_STOP** | **−736.07** | **555.00** |
| 44 | `SENSEX26JUL77300CE` | 40 | 479.20 | 14:34 | TIME_STOP | **+197.50** | 473.45 |

`SENSEX26JUL77200CE`'s own path: 11:00 **441.40** → 13:30 500.55 → 14:30 **580.25** → 15:00 555.00. **All
three losing exits were premature on a leg that kept rising**; only id 44's stop was correct (that leg
peaked at 14:30 and faded).

⚠ **`qty 40` on a 20-lot SENSEX contract is the documented pyramiding merge, not a sizing bug** — two
slugs emitted the same `(book, exchange, tradingsymbol, side)` in the same wave and
`PaperService.openPosition` averages the second into the first (CLAUDE.md). Confirmed unchanged from the
midday reading.

**The two books disagree by construction, and the disagreement is the evidence:**

| model | exits | result |
|---|---|---|
| champion shadow (no `time_stop`, holds to 15:12) | 16 SQUARE_OFF + 8 STRUCTURAL_STOP | **+₹15,260.87** |
| live paper (YAML `time_stop: 10 bars` armed) | 3 TIME_STOP + 1 STRUCTURAL_STOP | **−₹2,435.95** |
| §4.2 counterfactual (30-min time stop modelled) | 41 legs, none hit ±35%/−25% | **−538.50 pts** |

⚠ **This is ONE trend day and the two books do not trade the same entries** (shadow opens on
composite-*passing rejections*; paper opens on *fires*), so this is not a controlled comparison. What it
does establish is that **on 2026-07-29 the 30-minute time stop was the dominant term in the P&L, larger
than any entry-gate knob under discussion.** New row **T29**, and it belongs to the **exit-band track**
(`docs/superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`), landing as one coordinated owner
decision with the entry-gate tunes per README §5.

### 5.3 The #1075 budget evidence, over the full session

The midday run recorded 2 unfundable NIFTY legs; the full session has **4** — every one of the four
`scalp-golden-crossover-nifty` ENTRY fires (11:06, 12:12, 13:09, 14:03) resolved a leg and then took **no**
paper position, `suggested_qty` NULL. The SENSEX legs funded 1 lot each on all 8 of their fires.

| root | fires | funded | reason |
|---|---|---|---|
| NIFTY (`NIFTY2680424000CE`) | 4 | **0** | premium ~285–307 × lot 65 = **₹18,541–19,955 > ₹15,000** |
| SENSEX (`SENSEX26JUL77200/77300CE`) | 8 | 8 | premium ~479–518 × lot 20 = ₹9,584–10,361 |

**Session split: 8 of 12 fires funded; the NIFTY-rooted third of the fires was structurally unfundable.**
This is the live evidence the owner deferred [#1075](https://github.com/prashantm912/artha-yantra-2/pull/1075)
(₹15,000 → ₹20,000) to **2026-08-12** to collect — task `revisit-scalper-budget-inr-2026-08-12`.
**Reported, not acted on**; the concurrency cliff argument (above ₹15,000 each ₹30,000 sub-account holds 1
not 2, concurrency 8 → 5) is unchanged and cuts the other way. ⚠ **Note the funded legs LOST money today
(−₹2,435.95), so "more of them" is not self-evidently better** — the decision needs the exit question
(§5.2) settled first.

## 6 New data points / anomalies

### 6.1 T23 — the quietest session in the series: 6 WARNs, all paired, all in the first hour

`PartialBucketCanary` WARNed **6** times (10 on 07-28, 3 on 07-27, 37 on 07-24), all on
`NFO:NIFTY26AUGFUT@3m`:

| IST bucket | engine 3m | Σ(3×1m) | shortfall | lots (÷65) | shape |
|---|---|---|---|---|---|
| 09:15 | 476,840 | 460,005 | **−16,835** | **259** | exact ± pair |
| 09:18 | 124,410 | 141,245 | **+16,835** | **259** | exact ± pair |
| 09:24 | 64,025 | 60,645 | −3,380 | 52 | exact ± pair |
| 09:27 | 53,040 | 56,420 | +3,380 | 52 | exact ± pair |
| 09:33 | 46,995 | 41,470 | −5,525 | 85 | near-pair |
| 09:36 | 52,325 | 58,045 | +5,720 | 88 | near-pair |

**Every shortfall is an exact ×65 multiple and every one arrives as a ± pair on consecutive buckets** — the
documented benign boundary straddle (README §3.17). **Zero unpaired events**, a first for the series: the
07-28 pattern (two unpaired events at the session's thickest buckets) did **not** recur, and neither did
the 09:15 signature #981 targeted.

⚠ **The 259-lot magnitude is the largest single shortfall recorded**, but as a fraction of the 476,840
opening bar it is **3.5%** — comparable to 07-28's 2.5%/2.4% and 07-27's 2.7%. The absolute-650 tolerance
is simply trivial to exceed on a bar 32× the session median. **T23 stays PROPOSED, re-confirming 07-28's
proposal that the tolerance should scale with bar size rather than being absolute** — this session is the
cleanest possible argument for it, since every event is provably benign and every one still WARNed.

### 6.2 The S24 control experiment is complete

07-28 was a monthly expiry with all seven OI dots at 0/1,068 and both quadrants NEUTRAL 1,068/1,068.
Today, same code, same contract, non-expiry: **quadrants NEUTRAL 0/983**, `spurtPricePct` NULL **0/983**,
and the seven OI dots at **5.8% / 8.5% / 47.8% / 49.9% / 52.2% / 67.2% / 86.6%**. README §3.19's
by-design reading is now confirmed by a matched control on the immediately following session. `oi_spurt`
at 8.5% also re-verifies #991's floors against 07-27's 9.9%, discharging the T22 watch item.

### 6.3 `trending_cross` was NOT dead — the midday 0/722 was a sampling artefact

The live-health run flagged `trending_cross` at 0/722 on a fully-live OI day and asked the EOD run whether
it was threshold or data. **Neither: it is 57/983 (5.8%) over the full session.** The mid-session read
sampled a window in which the CE-over-PE dOI cross never occurred; the afternoon supplied it.

**Method note worth carrying:** a dot at 0% in a *partial*-session read is not a finding. The §3.6 support
rate is only interpretable over a complete session, and the live watch should say "0 so far" rather than
"dead". Folded into README §4.1.

### 6.4 §3.16 is STALE and is corrected by this run

README §3.16 (added 2026-07-23) states that only **21 of the 63** scalper YAMLs carry a `premium_pct`
block, and instructs the counterfactual to apply a +35% take-profit *only* to those families.

**All 63 now carry it** (verified this run: `total=63 with_premium_pct=63`). T21 (owner 2026-07-25, #990)
added the block fleet-wide; the shipped shape is:

```yaml
exit_rules:
  - { type: take_profit, params: { basis: premium_pct, value: 35 } }
  - { type: stop_loss,   params: { basis: premium_pct, value: 25 } }
  - { type: signal_exit, params: { rule: "close < vwap", min_volume: 125000 } }
  - { type: trailing_stop, params: { basis: indicator, alias: supertrend_line } }
  - { type: time_stop,   params: { max_bars: 10 } }
```

So the §4.2 counterfactual model is now **+35% TP / −25% SL / 30-minute time stop / 15:12 square-off**, applied
uniformly — which is what §4.2 below does. The 07-23 finding remains historically correct and the files
written between 07-23 and 07-25 keep the bias it describes; **from 07-25 forward the universal model is the
right one**. README §3.16 amended.

### 6.5 The 07-29 live-health carry-list, discharged

| # | carry item | outcome |
|---|---|---|
| 1 | Chase `volume-floor` = 75.3% of first-blocks with §3.8 ground truth | ✅ **Done and it produced T27** (§2.1). Full-session share settled at **58.5%**; the floor sat at p97–p98 of its own operand for 09:45–10:12, and 43% of blocks are pre-11:00. §3.14 armed check: **38/38, 0 flat floors** |
| 2 | Resolve `iv_abs_band` 103/103 — revival or free dot? | ✅ **Neither — FROZEN operand.** `atmIv` has exactly 1 distinct value per session, 4 sessions running (§3.2). New row **T28** |
| 3 | `trending_cross` 0/722 on a live-OI day — threshold or data? | ✅ **Neither — a partial-session sampling artefact.** 57/983 = 5.8% over the full session (§6.3) |
| 4 | Fold §6.3 into the #1075 evidence base | ✅ **Full-session split: 4 NIFTY fires unfunded, 8 SENSEX funded** (§5.3). Not acted on; ⚠ the funded legs lost money, which complicates the "raise it" reading |
| 5 | Reconcile the paper closes against the champion shadow rows | ✅ **Done — and the disagreement IS the finding** (§5.2). Shadow +₹15,260.87 holding to square-off vs paper −₹2,435.95 under the 30-min time stop. New row **T29** |
| 6 | Re-run the §3.15 phantom-candle probe at EOD | ✅ **0 rows session-wide** — 5th clean session (§4) |
| 7 | Re-check the host-clock guard at EOD | ✅ **~0.17 s** drift (§4) |

### 6.6 Method addenda → README §3.21, §3.22, §3.16, §4.1

Four promotions, all earned this session:

- **§3.21 (new):** *a dot at 0% or 100% in a PARTIAL-session read is not a finding* — §3.6 support rates
  are only interpretable over a complete session (§6.3, `trending_cross`).
- **§3.22 (new):** *a "dead" or "free" dot may have a FROZEN operand, which no alive/dead canary probe can
  see* — count `DISTINCT` values of the operand across the session, not just its null rate (§3.2, `atmIv`).
- **§3.16 (amend):** all 63 YAMLs carry `premium_pct` since T21/#990; the universal +35%/−25% model plus a
  10-bar time stop is now correct (§6.4).
- **§4.1 (amend):** the same partial-read caveat, on the live-watch side.

## 7 Tuning candidates

Carried forward from 07-28 plus this session's movement. **Nothing here is applied** — every open row is a
PROPOSAL. ✅ **This IS the second clean forward session ledger row G1 was waiting for**, so entry-gate rows
below carry real weight for the first time since the D-wave.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T29** | scalper `time_stop` (`max_bars: 10` = 30 min on 3m) | armed fleet-wide by T21/#990 | owner review: lengthen, make it trend-conditional, or drop it in favour of the trailing SuperTrend | §5.2: on the same tape the champion shadow book (no time stop, holds to 15:12) made **+₹15,260.87** while the live paper book made **−₹2,435.95**, with 3 of 4 losing exits on a leg that ran from 441 to 580. §4.2's 41-leg counterfactual under the 30-min stop: **5W/36L, −538.50 pts**, **zero** TP and **zero** SL touches | **EXIT-BAND (owner)** | **PROPOSED — NEW, highest decision value. Coordinate with the exit-band runbook, not this track alone. ONE trend day; needs a chop-day counter-observation before any change** |
| **T27** | relative-volume-floor window (`ScalperGates.relativeVolumeFloor` / `priorVolumes`) | `1.5 × median(prior N bars)`, window starts at the session open | exclude the opening ~30 min from the window, or seed from the PRIOR session's median, or normalise by time-of-day | §2.1: threshold **133,185** at 09:45–09:57 = **p98.4** of the session's own 3m distribution, decaying to ~26,000 by 11:21 while the operand ran 11,000–34,000. **326 of 756 blocks (43%) are pre-11:00**, a 1h15m slice of a 5h45m session. Cause is the opening surge: 4 of the first 10 bars ≥100,000 vs a session median of 15,015 | **STRUCTURAL (code)** | **PROPOSED — NEW. A BUILD row (README §5), not a knob. ⚠ Read `priorVolumes` first: the exact window length/offset was NOT reconstructible from SQL (§2.1 caveat)** |
| **T28** | `macro.atmIv` freshness (producer side) + a `DotHealthCanary` probe for it | 1 distinct value per session, 4 sessions running | find the stamp site; if the freeze is unintended, refresh intraday. Add a **frozen-operand** probe (DISTINCT-count, not null-count) to the canary registry | §3.2: 0.130859 / 0.135577 / 0.121736 / **0.118781** — one value per day. `iv_abs_band` (w 0.8) is therefore a per-day coin flip: 0/180 on 07-28 (just outside 0.12), **133/133 on 07-29** (just inside). Neighbouring `ceIvAvg6`/`peIvAvg6`/`vixLevel`/`premiumSkewPct` are all live, so the defect is narrow | **STRUCTURAL (data + canary blind spot)** | **PROPOSED — NEW. Cause NOT established (no producer-side code read this run)** |
| **T24** | the `volume` **dot**'s floor resolution (`ConnectTheDotsScorer.java:141`) | the static `VOL_FLOOR` default via the 2-arg `ScalperGates.volume` overload | thread the resolved floor into the scorer | root cause unchanged (07-28, code-read). ⚠ **NEW open sub-question:** today only **3 of 125** bars (2.4%) cleared 125,000 yet the dot supported **23.1%** of rows — 07-28's clean arithmetic match did NOT reproduce. Candidates (undiscriminated): the `sensex-*` slugs resolve the **50,000** non-NIFTY default, or the in-memory series differs from the DB rollup (§2.3) | **STRUCTURAL (code defect)** | **PROPOSED — needs a BUILD row (§5 ledger rule). HIGHEST PRIORITY among entry-gate rows** |
| **T23** | `artha.signals.partial-bucket-canary.volume-tolerance` (absolute 650) | 6 WARNs, **all** exact ± pairs on consecutive buckets, **all** ×65 multiples, **zero unpaired** — the cleanest session in the series | make the tolerance scale with bar size instead of absolute | §6.1: the largest shortfall (16,835 = 259 lots) is **3.5%** of a 476,840 opening bar — provably benign by shape, and it still WARNed. Every one of the 6 events is benign; every one alarmed | **STRUCTURAL (defect)** | **PROPOSED — this session is the strongest argument yet for the scaling change** |
| **T3** | `iv_pair` min-gap (`ivPairMinGap`) | 0.02 | ~~0.005~~ → **RE-SCOPE: drop the dot from Σw, or redefine its operand** | §3.3, the ground-truth query outstanding since 07-15, on a NON-expiry session: gap p50 **0.00010**, p90 0.00050, **max 0.00070**. Live threshold is **28×** the max; the proposed 0.005 is still **7×** it. Put-call parity pins the two 6-strike ATM averages together — the operand cannot express the signal at any usable threshold | **STRUCTURAL** | **RE-SCOPED — the knob turn is REJECTED; the replacement is a BUILD decision (drop = cap 0.9574 → 1.0000)** |
| **T1** | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | §2.2/§4.2, **the first real counterfactual for this knob**: the `volume-floor` would-have-fired set is **2W/9L, −121.95 pts**. Every one of the six rails' would-have-fired sets loses. Prior tally 07-21 (0/6) and 07-27 (4-for/5-against) | **REGIME → now forward-evidenced** | **REJECTED on this session — do NOT apply. ⚠ Note T27 argues the SHAPE of the floor is wrong even though its LEVEL is vindicated; these are different fixes and must not be conflated** |
| T25 | scalper→paper routing | ✅ **a scalper paper book now exists and traded** | — | 4 positions opened and closed today, `subaccount_idx` 1–4, `realized_pnl` populated. The 07-27 "fires lapse EXPIRED" state is gone | — | ✅ **CLOSED — resolved by observation** |
| T26 | ENTRY-path emit latency | ~17 s bar-close→emit on entries, ~0.3 s on exits (07-27, n=3) | measure across more fires | **the 07-27 split does NOT hold.** 20 emissions today, `ay_signal_bar_to_emit_seconds_sum/count` = **17.0 s mean**; per-signal `emit_latency_ms` runs **11.9–21.9 s on EXITS too**, with exactly one fast exit (606 ms). So it is a uniform ~17 s emit cost, not an entry-specific one | **STRUCTURAL (measurement)** | **PROPOSED — re-characterised: uniform, not entry-specific** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source `ivRank` or drop from Σw | NULL 100% an 11th session; withheld from Σw (#676) so it costs no headroom | **STRUCTURAL** | **PROPOSED (carried)** |
| T5 | `iv_abs_band` band | 10–12 | **superseded by T28** | the band is not the problem — the operand is frozen (§3.2). Re-evaluate the band only after `atmIv` moves intraday | **REGIME** | **SUPERSEDED by T28** |
| T7 | composite threshold | 0.600 | no change | 311 of 983 rows cleared it, max 0.9118, cap 0.9574 — ample headroom, no starvation. ⚠ **First real evidence AGAINST lowering it:** `composite-055` took 3 rows on its first live outing since #991 and lost **−₹2,952.21**, the worst per-close book of the four | — | **REJECTED — and now with forward evidence, not just a re-baseline note** |
| T10 | stale OPEN paper positions | **19** OPEN (12 `minervini`, 7 `manas-arora`) | square off / age out | unchanged from 07-28; the scalper book opened and closed 4 cleanly and left **0** OPEN | ops | **OWNER — chronic, flat this session** |
| T14 | rejection-row margin invariant | global `blocking_margin < 0` | sign-aware per rail | **0 self-contradicting rows** on a session where 311 rows passed composite — a NON-vacuous clean reading, unlike 07-28's | **STRUCTURAL (diagnostic)** | **PROPOSED — carried; first non-vacuous clean session** |
| T8 | shadow entry latency | p50 ~80 s | stamp entry at bar close | 24 entries today; not re-measured this run | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T15 | engine boot-line durability | V046 `strategy.engine_reloads` (#987) | — | not exercised — `RestartCount 0`, boot line still readable | **STRUCTURAL (data-model)** | **SHIPPED #987 — still unverified against a restart** |
| T16 / T12 / T19 / T22 / T18 / T21 / T6 / T17+T13 / T20 | — | — | — | §2.1 (38/38 armed, 0 flat floors); §4 (T12 **375/375** — best ever; T19 0 misaligned, 5th clean); §6.2 (T22 `oi_spurt` 8.5% vs 07-27's 9.9%); §3 (T18 `breadth` back to 98.8%); §6.4 (T21 63/63 YAMLs) | — | ✅ **remain CLOSED** |

**Ledger note (README §5 rule).** **T24, T27, T28 and the T3 re-scope are all BUILD proposals, not knob
turns**, so per the README §5 rule each needs a row in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
**§0 group G**. They are not created in this docs-only PR (that ledger is the forward-work authority and
this run is read-only analysis) — they are flagged here as the next action, together with 07-28's still-
outstanding T24 row.

Group **G1**'s "two clean forward sessions post-D-wave" condition is **MET** — 07-27 was the first and
**2026-07-29 is the second**. The blocked tunes resolve as follows on that evidence: **T1 REJECTED**
(§4.2), **T7 REJECTED** (§5.1), **T3 RE-SCOPED to a build** (§3.3), **T5 SUPERSEDED by T28** (§3.2), **T2
carried** (still null). **G1 can be closed** — not because the tunes were applied, but because the forward
evidence resolved every one of them.

## 8 Honesty caveats

- **§4.2's counterfactual model is uniform (+35% TP / −25% SL / 30-min time stop / 15:12 square-off) and
  that uniformity is now CORRECT** (§6.4: 63/63 YAMLs carry `premium_pct` since T21/#990) — but the model
  still omits the two exits the shadow book also omits: `signal_exit` (`close < vwap`) and the trailing
  SuperTrend. Both would have cut some of the 36 losers earlier and some of the 5 winners short. **The
  −538.50 pts is therefore a bound on a specific model, not a prediction of realised P&L.**
- **3-minute LTP granularity** means a 1-minute touch of the +35%/−25% brackets can be missed. In this
  session it almost certainly changes nothing: **not one of the 41 legs came within 60% of its take-profit
  or 90% of its stop** — every path resolved at the time stop with the leg still inside the band.
- **§5.2's two books do not trade the same entries.** The shadow book opens on composite-passing
  *rejections*; the paper book opens on *fires*. The comparison establishes that the exit model dominated
  today's P&L; it does **not** establish that removing the time stop would have produced +₹15,260.87 on the
  paper book.
- **T29 rests on ONE trend day.** The time stop's whole purpose is to cut losers on chop days, and this
  session offers no chop-day counter-observation. Do not act on it without one.
- **T27's mechanism is measured; its exact window is NOT.** Two thresholds reconcile exactly to
  `1.5 × median` of a 10-bar DB window, a third does not, and the engine reads an in-memory series that
  provably diverges from the DB (§6.1). The finding stands on the observed thresholds and the observed
  operand distribution; the window reconstruction is explicitly not part of it.
- **T28 names an observation, not a cause.** `atmIv` is frozen per session across four sessions. No
  producer-side code was read this run, so whether that is a deliberate session-open reference or a caching
  defect is **unknown**.
- **T24's arithmetic did NOT reproduce this session** (§2.3) — 2.4% of bars cleared 125,000 but the dot
  supported 23.1% of rows. The code-read root cause (call-site divergence) is unaffected, but the
  *quantitative* confirmation offered on 07-28 does not extend to today, and the two candidate explanations
  were not discriminated.
- **The four silent `-pe` slugs are attributed to the up-tape, not proven** (§1.1). Zero rows is consistent
  with a chart-stage block but no runtime trace was taken.
- **`premium_skew`'s 53.3% rests on 30 rows** and is recorded as a first sighting only (§3.4).
- **`strike-pick`'s disappearance is an absence, not a fix.** It failed 534 times on 07-28 and zero times
  today; the expiry-chain explanation is consistent but untested.
- **The champion book's +₹15,260.87 is a single session on a +1.06% trend day**, and its all-time position
  is still **−₹19,892.76**. One good session does not reverse the book.
- This run was **read-only**: SELECTs, `docker logs`, in-container actuator/health GETs, and source reads.
  No restart, deploy, write or config change. No strategy knob was altered.

---

## Addendum — 2026-07-29, added while opening the group-G ledger rows (corrects §2.3 and T24)

**§2.3 and T24's "open sub-question" are WRONG and are withdrawn. T24 was FIXED, MERGED and DEPLOYED on
2026-07-28 ([#1082](https://github.com/prashantm912/artha-yantra-2/pull/1082) `b98244ed`), and 2026-07-29
is its FIRST LIVE SESSION** — which is exactly what the "unreconciled arithmetic" was measuring. The main
body reasoned from the *pre-fix* code path and never asked whether the fix had shipped; ledger row **G6**
already carried it as DONE + deployed, with a one-time 16:20 IST task scheduled to verify it on this very
session.

**Verification, run for this addendum:**

- The running jar carries the fix — `unzip -p /app/*.jar …/ConnectTheDotsScorer.class | strings | grep -c
  volumeFloor` = **1** (the threaded resolved floor). The container booted 2026-07-29 01:54 IST,
  **after** the 07-28 deploy.
- **The discriminating query.** Split today's 983 scored rows by whether the `volume` dot supported, and
  read the bar volume behind each group:

  | `volume` dot | rows | min vol | median vol | max vol | rows ≥125,000 |
  |---|---|---|---|---|---|
  | supports | **227** | **14,040** | 28,015 | 139,360 | **5** |
  | does not | 756 | 3,900 | 11,570 | 64,025 | 0 |

  Under the **pre-fix** static NIFTY floor of 125,000, at most **5** of those 227 rows could have
  supported. **222 of the 227 supports are reachable only with the banded relative floor.** Verified on
  data, not on a jar fingerprint alone.
- **Dot and rail are now the same test:** the 756 non-supporting rows are exactly the 756 `volume-floor`
  first-blocks (§1). Before #1082 the two used different thresholds by construction; they now agree
  row-for-row.

**The correct reading of §2.3 therefore inverts.** `volume` at **23.1%** is not an anomaly needing an
explanation — it is **T24's fix working**, and this is the first session in this folder where the dot could
reach its own operand's real distribution. The two "undiscriminated candidates" offered in §2.3 (a
`sensex-*` 50,000 default, or an in-memory/DB divergence) are **unnecessary** and are withdrawn. **G6 is
VERIFIED, not open.**

**Method lesson → README §3.23:** *check what is DEPLOYED before explaining live behaviour from source.*
The main body read `ConnectTheDotsScorer.java` on the branch, found the 07-28 root cause still described
there, and reasoned forward — without asking whether the fix for it had already shipped. The jar
fingerprint is the cheap generic check and it is one command. (Same family as the standing
deploy-verify-by-jar-fingerprint trap, applied to *analysis* rather than to deploys.)

⚠ **This addendum disturbs nothing else in the file.** T24 was not load-bearing for T1, T3, T27, T28 or
T29, and no other section depends on §2.3.

### Addendum, part 2 — the §7 group-G rows now EXIST

§7's ledger note says the BUILD rows "are not created in this docs-only PR … flagged here as the next
action". **They have since been created**, in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
**§0 group G**, and that ledger — not this table — is the authoritative status from here:

| this file | ledger row | tier | status |
|---|---|---|---|
| **T27** relative-floor window | **G10** `T27-relative-floor-opening-surge` | HOLD (changes which signals fire) | OPEN |
| **T29** scalper `time_stop` | **G11** `T29-scalper-time-stop-cuts-winners` | OWNER (exit doctrine) | OPEN, **BLOCKED-DATA on a chop-day observation** |
| **T28** frozen `atmIv` + canary probe | **G12** `T28-frozen-atmiv-and-canary-blind-spot` | clean (diagnosis first) | OPEN |
| **T3** re-scope (drop-or-redefine `iv_pair`) | **G13** `T3-iv-pair-dot-drop-or-redefine` | HOLD | OPEN |
| **T24** | **G6** — already existed | HOLD | ✅ **VERIFIED, closed** (part 1 above) |
| **T26** | **G8** — already existed | data | OPEN, re-characterised (uniform ~17 s) |
| **T23** | **G9** — already existed | clean | OPEN, re-narrowed to a tolerance row |
| **T1 / T2 / T3 / T5 / T7** | **G1** | data | ✅ **CLOSED — quota met, all five resolved** |

**G1 is closed.** T1 REJECTED, T7 REJECTED, T3 re-scoped into G13, T5 superseded by G12, T2 carried inside
the row. None was resolved by being applied.
