# Session findings — 2026-07-28 (data date)

Analysis date: 2026-07-28 (scheduled post-market agent, ran 15:54–16:30 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,350** (bar times 09:18–14:57 IST), signals fired **0**, paper positions
opened **0**, shadow positions opened **0**.

⚠️⚠️ **2026-07-28 is an NSE MONTHLY index-expiry day (last Tuesday).** Per README **§3.19** the gate
suppresses the entire OI block by design (S24), which starved the composite below its own threshold.
**Zero fires today is an arithmetic outcome, not a market or calibration outcome — no entry-gate tuning
conclusion may be drawn from this session, and it enters the rollup as REGIME, never STRUCTURAL.**

**Signal contract, derived per §3.18 (not assumed):** `context.chart.close` values 24,055.60 / 24,063.00 /
24,064.20 fall inside `NIFTY26AUGFUT`'s day range (24,050.00–24,150.00) and outside both `NIFTY26JULFUT`
(23,947.60–24,040.00) and `NIFTY 50` (23,954.70–24,041.00). **The signal series is `NFO:NIFTY26AUGFUT@3m`**,
the same contract as 07-27 (rolled at the 07-27 ~08:40 re-resolve), so cross-session volume comparisons
against 07-27 ARE like-for-like — and against 07-24 and earlier are NOT.

Session character: **flat, narrow expiry day.** `NIFTY 50` 23,971.25 → **23,987.60** (high 24,041.00, low
23,954.70, range **86.3 pts**); against the 07-27 close of 24,002.10 that is **−14.50 pts, −0.06%**.
`SENSEX` 76,831.75 → 76,748.31. Signal series `NIFTY26AUGFUT` 24,100.00 → 24,120.10, range 100.0 pts, but
on a **much thicker tape than 07-27** — 3m volume median **30,420** vs 22,620, max **184,275** vs 117,000
(expiry-day churn in the front month).

This file folds in the two earlier read-only runs of the same date:
[`2026-07-28-open-gate.md`](2026-07-28-open-gate.md) and [`2026-07-28-live-health.md`](2026-07-28-live-health.md)
(GREEN, 09:43–09:57 IST), and discharges that file's §9 carry-list.

---

## 0 Read this first — the session's headline

1. **A fire was ARITHMETICALLY IMPOSSIBLE today, and the number is exact.** With the seven OI-derived dots
   inert by S24 design (7.5 of weight) **and** `breadth` at 0/1,068 on a regime near-miss (1.0 more), the
   maximum achievable composite was **10.3 / 18.8 = 0.5479** against a **0.600** threshold. The observed
   session max, **0.4521**, is a real market reading well under that ceiling — but even a row on which
   *every* live dot supported could not have cleared the gate (§3.4). "Zero fires" carries no information
   about calibration this session.
2. **T24 is ROOT-CAUSED — and it is a one-line call-site divergence, not a data problem.**
   `ConnectTheDotsScorer.java:141` calls the **two-argument** `ScalperGates.volume(underlying, volume)`
   overload, which resolves the floor via `volumeFloorFor(underlying, null)` — the **static per-index
   default (NIFTY 125,000)**. The `relative-volume-floor` tag substitutes the banded floor at the **rail**
   call site only; **the dot never sees it.** Today's data confirms it arithmetically: 6 of 125 3m bars
   (4.8%) cleared 125,000, and the dot supported on **38 of 1,068** rows (3.6%) — its first non-zero
   reading in nine sessions, produced purely by expiry-day volume (§2.3).
3. **The whole OI bloc is inert and it is provably by design, on both README §3.19 discriminators.**
   `futuresQuadrant` / `underlyingQuadrant` NEUTRAL on **1,068/1,068**, `spurtPricePct` / `spurtOiPct` NULL
   on **1,068/1,068**, while **`futuresBasis` stays live on 1,068/1,068** and
   `futures_oi_snapshots` kept **374 of 375 minutes (99.7%)** of capture. Capture is healthy; the gate
   chose not to read it (§4).
4. **Coverage is 36 of 38, and the two silent slugs are explained by their own YAML.** The `hero-zero` pair
   emitted nothing — and `scalp-hero-zero-nifty.yaml:6-15` states that on a **monthly** expiry the inert OI
   snapshot **degrades to a block** by design ("the deck's *ignore OI on monthly expiry* caveat = the S24
   suppression"). The one strategy family built for expiry day is the one the expiry-day suppression
   silences. Not a load failure (§1).
5. **`fii` held ALIVE all session — #1050 confirmed on a full session.** `fiiLongPct` = 8.78 on
   **1,068/1,068** context-bearing rows (was NULL 100% since 07-02). The standing dead set is now the
   **pair** `ivRank` + `dowUp`, not the trio (§4).
6. **#1073 was NOT yet deployed at analysis time (16:07 IST), and the defect it fixes reproduced live at
   EOD** — `oi_spurt_price` still reports `"input dead across 40 context-bearing rejections"` while the two
   quadrant dots correctly report `NEUTRAL by design`. That is exactly the labelling asymmetry #1073
   closes, observed on the live stack on the only day it is directly observable until the next monthly
   expiry (§6.3).
7. **T23 changed shape: 10 WARNs (was 3), but the opening-bucket signature did NOT recur.** No 09:15 WARN
   at all today; the two unpaired events are +2,730 (42 lots) at 09:18 and −2,600 (40 lots) at 15:15, with
   four ± pairs on consecutive buckets in between. Every shortfall is an exact multiple of 65 (§6.1).
8. **Every liveness oracle is clean.** Σ eval outcomes **3,570** with `confluence-blocked` = **1,350** =
   the rejection row count exactly; `ay_signal_eval_duration_seconds_count` = **375** — the full minute
   grid, one better than 07-27's 374; `subscriber_health_events` **empty**; **0 ERROR** lines in either
   service; **0** misaligned candles; clock drift **<0.3 s**.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-23 | 2026-07-24 | 2026-07-27 | **2026-07-28** |
|---|---|---|---|---|
| rejections | 1,430 | 1,366 | 1,253 | **1,350** |
| distinct strategies emitting | 38 | 36 | 38 | **36** |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| **coverage ratio (§3.10)** | 38/38 | 36/38 | 38/38 | **36/38** (the `hero-zero` pair, by YAML design — §1.1) |
| signals fired | 0 | 0 | 3 ENTRY + 3 EXIT | **0** |
| paper positions opened | 0 | 0 | 0 | **0** |
| bar-time coverage | 09:18–15:19 | 09:18–14:57 | 09:18–15:18 | **09:18–14:57** |
| scored rows | 1,120 | 1,100 | 909 | **1,068** |
| composite ≥ threshold rows | 634 | 418 | 253 | **0** |
| max composite | 0.8511 | 0.7447 | 0.8511 | **0.4521** |
| **max ACHIEVABLE composite** | 0.8511 | 0.8511 | 0.9043 | **0.5479** ⚠ below the 0.600 threshold |

**Eval counters (actuator :8082, read 15:58 IST).** The container booted **2026-07-28 03:10 IST**
(`StartedAt 2026-07-27T21:40:01Z`, `RestartCount 0`) — before the open and not restarted since, so the
cumulative counters ARE today's session totals:

| outcome | 2026-07-28 |
|---|---|
| `chart-gate-failed` | **1,982** |
| `confluence-blocked` | **1,350** |
| `composite-below-threshold` | **238** |
| `fired` | **0** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 |
| **Σ** | **3,570** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = **1,350** = today's rejection row count **exactly**, and the same Σ reconciles
against `strategy.signal_eval_outcomes`. Three independent views agree.

`ay_signal_eval_duration_seconds_count` = **375** — the complete 09:15→15:29 minute grid, one cycle better
than 07-27's 374. Sum 2,078.03 s ⇒ mean **5.54 s**/eval (07-27: 4.84 s; 07-24: 3.92 s). The upward drift
across three sessions is noted, not diagnosed; it is far inside any stall reading.

**Liveness gauges at 15:58 IST:** `ay_signal_bar_received_age_seconds` = `ay_signal_bar_evaluated_age_seconds`
= **1544.59** — both non-negative, mutually equal, and consistent with the 15:30 close. The last bar the
engine received was also evaluated.

**First-blocking-rail histogram** (1,350 rows, **16** distinct rails):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **760 (56.3%)** | 23,232.5 | 46,469.8 | −23,237.2 |
| time-window | 248 (18.4%) | — | — | — |
| rsi-band | 92 | 47.80 | — | — |
| **flat-oi-stand-aside** | **32** | — | — | — |
| oi-cross-required | 28 | — | — | — |
| pct-price-move | 28 | 0.063 | 1.000 | −0.937 |
| two-candle | 28 | — | — | — |
| divergence-vol-gate | 26 | 63,865.0 | — | — |
| volume-pump | 26 | 73,445.0 | — | — |
| time-of-day-preference | 24 | — | — | — |
| directional-change-gate | 20 | — | — | — |
| confluence-composite | 20 | 0.229 | 0.600 | −0.371 |
| option-side-constraint | 10 | — | — | — |
| trend-change | 4 | — | — | — |
| morning-opening-formation | 2 | — | — | — |
| psar-durability | 2 | 0.047 | 0.050 | −0.003 |

**`flat-oi-stand-aside` is a first sighting as a first-blocker** — 32 rows, all four `connect-the-dots`
variants (12 + 12 + 4 + 4). It is the direct expiry-day consequence: with both quadrants NEUTRAL the OI
reads flat, and that family stands aside. Not a defect.

**All-failed-rails expansion (§3.3)** — top 20:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| confluence-composite | FAIL_CLOSED | **1,068** | 0.225 | 0.600 |
| volume-floor | FAIL_CLOSED | 760 | 23,232.5 | 46,469.8 |
| strike-pick | FAIL_CLOSED | **534** | — | — |
| rsi-band | FAIL_CLOSED | 320 | 47.43 | — |
| time-window | FAIL_CLOSED | 248 | — | — |
| trend-change | FAIL_CLOSED | 180 | — | — |
| flat-oi-stand-aside | FAIL_CLOSED | 180 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 174 | 31,581.0 | — |
| pct-price-move | FAIL_OPEN | 128 | 0.027 | 1.000 |
| two-candle | FAIL_CLOSED | 128 | — | — |
| volume-pump | FAIL_OPEN | 126 | 35,265.1 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 104 | — | 20.000 |
| oi-cross-required | FAIL_CLOSED | 104 | — | — |
| oi-slope-agree | FAIL_CLOSED | 104 | — | — |
| open-high-low | FAIL_CLOSED | 82 | — | — |
| directional-change-gate | FAIL_CLOSED | 82 | — | — |
| constituent-gate | FAIL_OPEN | 78 | 0.930 | — |
| rising-volume | FAIL_CLOSED | 60 | 24,723.8 | — |
| max-oi-sr-gate | FAIL_OPEN | 48 | 24,000.0 | — |
| supertrend-15m | FAIL_OPEN | 46 | — | — |

**`confluence-composite` failed on every single scored row (1,068/1,068)** — the mechanical signature of
§0.1. The three OI rails with NULL operands (`oi-divergence-magnitude`, `oi-cross-required`,
`oi-slope-agree`, 104 fails each) are the same suppression showing up on the rail side.

⚠ **`strike-pick` fails REVERSED their improvement: 550 (07-24) → 264 (07-27) → 534 today.** Third-largest
failing rail again, and **still un-bucketed by slug/time for a fourth session**. Carried; the expiry-day
chain is a plausible cause but was not tested this run.

### 1.1 Coverage — the two silent slugs are documented behaviour

Set-difference against the registry returns exactly **`scalp-hero-zero-nifty`** and
**`scalp-hero-zero-sensex-niftyoi`**. Both are the expiry-day family, and their own YAML header answers it
(`scalp-hero-zero-nifty.yaml:6-15`):

> …on a MONTHLY expiry the prior-month chain-OI is corrupt so the gate IGNORES OI and blocks … an inert OI
> snapshot (the monthly-expiry suppression, or any unavailable read) degrades to a block.

They also carry a `14:30–15:20` window, so they had only ~27 minutes of eligible tape before the rejection
stream ended at 14:57 in any case. Zero rows is the specified outcome, and the block happens at the chart
stage (upstream of `recordRejection`), which is why nothing was written. **The irony is worth recording in
the rollup: the one strategy family designed for expiry day is silenced by the expiry-day suppression.**

### 1.2 Interior coverage (§3.11) — two holes, both explained, neither a stall

15-minute buckets, 09:15 → 15:30:

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 4 | **0** | 38 | 80 | 88 | 60 | 80 | 90 | 90 | 60 | 80 |
| bucket | 12:00 | 12:15 | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 |
| n | 70 | 80 | 50 | 40 | 38 | 70 | 76 | 80 | 26 | 30 | 50 |
| bucket | 14:45 | 15:00 | 15:15 | | | | | | | | |
| n | 70 | **0** | **0** | | | | | | | | |

Both holes are ruled out as stalls against the eval counters, per §3.11's honesty limit:

- **09:24–09:42 (the 09:30 bucket):** only the `morning-trade` pair is in-window before 09:45 (bars 09:18,
  09:21 then nothing until 09:45). `signal_eval_outcomes` ran **4 evals** at 09:39 and 09:42 and stepped to
  **36** at 09:45 — the documented trade-window ramp (README §4.1), reproduced for a second session.
- **15:00–15:30:** the last rejection is `bar_time 14:57` / `generated_at 14:58:01`. `signal_eval_outcomes`
  drops from **30**/bucket through 14:57 to **2**/bucket for 15:00–15:18 and **0** from 15:21 — a
  window-narrowing effect, with the eval grid nonetheless completing all 375 cycles. Same shape as 07-24.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 The relative volume floor stayed armed and tracked a much thicker tape

**Registry state, standing §3.14 query:**

| carries `relative-volume-floor` | published on | count |
|---|---|---|
| yes | 2026-07-25 21:44 IST | **36** |
| yes | 2026-07-27 17:5x IST (the #1050 republish) | **2** |
| no | — | **0** |

**38 of 38 armed**; zero slugs on a flat floor; `count(*) WHERE blocking_threshold = 125000` = **0**.
T16 stays closed.

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned bars only (§3.15):**

| bars | min | p50 | p90 | p99 | max |
|---|---|---|---|---|---|
| 125 | 6,500 | **30,420** | 98,280 | 156,715 | **184,275** |

Session thresholds ran **25,057–68,835** (avg 46,470) against a p50 of 30,420 — the floor sat between
roughly **p40 and p85** of the operand's own distribution, which is what a k=1.5 relative floor on a
thick, choppy tape should do. Compare 07-27 (p55–p92 on a thinner tape): the band self-calibrated, exactly
the scale-invariance the `relativeVolumeFloor` javadoc claims.

### 2.2 The would-have-fired set is EMPTY — there is nothing to attribute to any rail

The §3.5 query (composite ≥ threshold AND no failed check other than rail X) returns **0 rows for every
rail**, because **no row passed composite at all** (§0.1). Consequently:

- **No §4.2 counterfactual exists for this session.** No would-have-fired row, no premium path, no
  WOULD-WIN/WOULD-LOSE table. This is stated explicitly rather than omitted.
- **`volume-floor`'s 760 first-blocks generate no single-rail P&L evidence** — unblocking the floor alone
  would still have left every one of those rows below 0.600. **T1 gains nothing today, in either
  direction.** The 07-27 reading (4-for/5-against on the first correctly-calibrated floor) stands unchanged
  and still needs its second clean forward session, which today was not.

### 2.3 The `volume` DOT fired for the first time — and that is what root-caused T24

`volume` supported **38 of 1,068** rows (3.6%), its first non-zero reading in nine sessions (0/909 on
07-27, 0% on every prior session in this folder). The reason string is `volume floor` — the same words the
rail uses — but the two do **not** share a threshold.

**Code path (read this run, not inferred):**

- `ConnectTheDotsScorer.java:141` — `ScalperGates.volume(ctx.signalIndex(), c.volume()).pass()`, the
  **two-argument** overload.
- `ScalperGates.java:152-159` — that overload delegates to `volume(underlying, volume, null)`.
- `ScalperGates.java:173-175` — `volumeFloorFor(underlying, override)` returns
  `override != null ? override : VOL_FLOOR.getOrDefault(underlying, INDEX_VOL)`, and the javadoc names the
  defaults: **NIFTY 125k / other indices 50k**.
- The `relative-volume-floor` substitution happens at the **rail's** call site
  (`ScalperConfluenceGate.java:422`, tag-gated), which passes an explicit override. **The dot's call site
  passes none.**

**The arithmetic confirms it exactly.** 3m bars clearing the static 125,000 today: **6 of 125 (4.8%)** — on
07-27 the AUGFUT max was 117,000, i.e. **zero** bars could clear it, which is precisely why the dot read
0/909 then and 3.6% now. Nothing about the dot changed; the tape got thick enough for one session.

**T24 is therefore root-caused, and it is not a data problem, a threshold problem or a regime problem** —
it is a call-site divergence that leaves 1.0 of composite weight gated on a floor sitting at roughly the
**p95** of its own operand, permanently, on every strategy including the 38 that are armed on the relative
floor. The fix (thread the resolved floor into the scorer, or have the scorer resolve it the same way the
rail does) is a code change, not a knob, and is proposed as such in §7.

### 2.4 Rails with no evidence of miscalibration

`rsi-band` (avg 47.43 on failures — a flat-to-soft tape), `pct-price-move` (0.027 vs 1.000),
`constituent-gate` and `volume-pump` (both FAIL_OPEN), `psar-durability` (0.047 vs 0.050, a genuine
near-miss on 2 rows) all read plausibly. `max-oi-sr-gate` grew from 1 fail on 07-27 to **48** today, every
one with operand 24,000 — a round strike level, consistent with expiry-day max-OI pinning; noted, not
flagged.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (1,068 scored rows):

| bucket | 0.1 | 0.2 | 0.3 | 0.4 | 0.5 |
|---|---|---|---|---|---|
| n | 90 | 576 | 388 | 2 | 12 |
| CE | 64 | 364 | 202 | 0 | 0 |
| PE | 26 | 212 | 186 | 2 | 12 |

Max **0.4521**; mean 0.2271 (18-dot rows) / 0.2140 (20-dot rows). **Zero rows reached 0.600.** The
distribution is crushed into 0.2–0.3 — the shape of a composite whose entire OI bloc scores zero while
staying in the denominator.

**Dot support rates** (1,068 scored rows unless noted):

| dot | w | supports | % | read |
|---|---|---|---|---|
| `futures_oi` | 1.5 | 0/1,068 | 0.0 | **inert by S24 design** (expiry) |
| `underlying_oi` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** |
| `oi_spurt` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** (was 9.9% on 07-27) |
| `drastic_oi` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** |
| `sentiment` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** (was 68.8%) |
| `sentiment_slope` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** (was 59.3%) |
| `trending_cross` | 1.0 | 0/1,068 | 0.0 | **inert by S24 design** (was 19.9%) |
| `breadth` | 1.0 | 0/1,068 | 0.0 | ⚠ **regime near-miss** — see §3.2 |
| `iv_rank` | 0.8 | 0/1,068 | 0.0 | dead-data, **withheld from Σw** (#676) |
| `iv_abs_band` | 0.8 | 0/180 | 0.0 | dead (8th session) |
| `vwap` | 2.5 | 16/1,068 | **1.5** | alive but scarce — flat tape, ≥15 bps rarely reached |
| `iv_pair` | 0.8 | **18/1,068** | **1.7** | ⚠ **FIRST LIFE EVER** — see §3.3 |
| `volume` | 1.0 | **38/1,068** | **3.6** | ⚠ **first non-zero** — the T24 root cause, §2.3 |
| `rsi` | 1.0 | 330/1,068 | 30.9 | |
| `iv_slope` | 0.8 | 72/180 | 40.0 | alive |
| `basis` | 1.0 | 538/1,068 | 50.4 | ✅ the S24 positive control — price-derived, deliberately kept |
| `psar` | 1.0 | 664/1,068 | 62.2 | |
| `vwma` | 1.0 | 902/1,068 | 84.5 | |
| `vix` | 1.0 | 924/1,068 | 86.5 | |
| `supertrend` | 1.0 | 1,068/1,068 | **100.0** | 3rd 100% session in the folder |

### 3.1 The dead-weight cap — and the proof that no row could have fired

Weights per README §2: `vwap` 2.5, `futures_oi` 1.5, the three IV dots 0.8, everything else 1.0.
`iv_rank` is **withheld** from both numerator and denominator when null (#676).

| dots on row | rows | Σw | withheld | dead weight | **cap** | observed max |
|---|---|---|---|---|---|---|
| 18 | 888 | 19.60 | 0.80 (`iv_rank`) | **8.50** | **0.5479** | **0.4521** |
| 20 (+`iv_abs_band`, `iv_slope`) | 180 | 21.20 | 0.80 | 9.30 | **0.5441** | 0.4167 |

Dead weight on the 18-dot roster = the seven S24-inert OI dots (1.5 + 1.0 × 6 = **7.5**) + `breadth`
(**1.0**). Cap = (18.80 − 8.50) / 18.80 = **0.547872…**

**Ground-truthed against a real row, not asserted.** The session's top row, `id 11467` (composite 0.4521),
carries exactly seven supporting dots — `basis`, `psar`, `rsi`, `supertrend`, `vix`, `vwap` (2.5), `vwma` —
summing to **8.5**, and 8.5 / 0.4521 = **18.80**, confirming the denominator convention. Its only two
non-supporting live dots were `volume` (1.0) and `iv_pair` (0.8); had both supported it would have scored
10.3 / 18.8 = **0.5479** — the cap itself, still **0.0521 short of the 0.600 threshold.**

**So the strongest bar of the session, with every single live dot supporting, could not have fired.** This
is the sharpest statement of §0.1 and the reason nothing in this file may be read as calibration evidence.

For completeness: without `breadth`'s regime death the cap would have been 11.3 / 18.8 = **0.6011** — a
margin of 0.0011 over threshold. Even a *perfect* non-expiry-OI day would have been a coin-flip.

### 3.2 ⚠ `breadth` is a one-constituent near-miss again — the 07-21 signature, repeated

`breadth` supported **0 of 1,068**, reason `advances/declines > 32`. Ground truth on the operand:
advances ranged **23–32** across 10 distinct values, and `declines` is its complement out of 50.
**The session maximum is exactly 32, and the rail is strictly-greater** — so the dot missed by one
constituent, all session.

This is the same shape as 2026-07-21 (threshold `>32`, session max **31**), which the folder resolved as
**T18 = REGIME, no change**, and which then read 96.6% / 38.7% / 79.5% on the three following sessions.
Two near-misses in six sessions on a threshold that sits at the exact edge of the observed range is worth
recording, but it does not overturn a resolution reached on five sessions of evidence. **T18 stays CLOSED;
this is logged as a second near-miss data point for the rollup, not a reopening.**

### 3.3 ⚠ `iv_pair` showed life for the first time in this folder's history

`iv_pair` supported **18 of 1,068** (1.7%), reason `iv pair gap favors side`. Every prior session in this
folder recorded **0.0%** — nine sessions, ~7,000 rows — and it is the standing evidence for **T3**
(`ivPairMinGap` 0.02 → ~0.005).

**Honest reading: this is an expiry-day observation and proves very little.** Monthly-expiry IV behaviour
in the front chain is exactly where a ≥0.02 gap between the two 6-strike averages is most likely to open
up, so 1.7% on this day is the weakest possible support for "0.02 is reachable in general". **T3 stays
PROPOSED and explicitly does NOT count today as evidence** — the ground-truth distribution query it has
been waiting for still needs a non-expiry session.

## 4 Data health (§3.7)

| field | 2026-07-24 | 2026-07-27 | **2026-07-28** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 0 NEUTRAL | 0 NEUTRAL | **1,068/1,068 NEUTRAL** | ⚠ **by design (S24 expiry)** — §3.19, not the 07-20 defect |
| `spurtOiPct` / `spurtPricePct` | 0 null (of ctx rows) | 0 null | **1,068/1,068 NULL** | ⚠ **by design (S24 expiry)** |
| `futuresBasis` | — | — | **1,068/1,068 LIVE** | ✅ the S24 discriminator |
| `advances` / `declines` | 0 zero-pairs | 0 zero-pairs | **0 zero-pairs, 0 nulls** (adv 23–32) | HEALTHY (dot regime-dead, §3.2) |
| `fiiLongPct` | NULL 100% | NULL 100% | **8.78 on 1,068/1,068** | ✅ **REVIVED — #1050, first full session** |
| `ivRank` | NULL 100% | NULL 100% | **NULL 100%** | dead-data (carried since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 100%** | by design (un-armed) |
| `vix` (macro mirror) | NULL 100% | NULL 100% | **NULL 100%** | known mirror gap (the `vix` **dot** is fine — 86.5%) |

**The 282 context-less rows reconcile exactly**: 248 `time-window` + 24 `time-of-day-preference` + 10
`option-side-constraint` = **282** = 1,350 − 1,068. Every context-bearing row carries live macro data.

**S24 discriminators, both satisfied (README §3.19):**

1. **`futuresBasis` live on 1,068/1,068 while both quadrants are NEUTRAL** — a genuine OI outage would take
   the basis with it.
2. **Capture healthy underneath:** `futures_oi_snapshots` **25,806 snapshots / 374 distinct minutes**
   (99.7% of ~375), last at 15:30:00 IST. Compare 07-27's 372/375 — **T12 stays CLOSED**, and the gate is
   demonstrably choosing not to read data that is there.
3. **Root check (never infer from the slug):** `diagnostic.context.underlying` = **`NIFTY 50` on all 1,068**
   context-bearing rows. NSE is the expiring root today; **BSE monthly is Thursday the 30th**, so the two
   do not coincide. There were no SENSEX-rooted rows to be wrongly silenced — the exposure #1073 closes was
   reachable but not realised (§6.3).

**Capture (minute-aligned bars only):**

| series | bars | misaligned | last bar |
|---|---|---|---|
| **`NIFTY26AUGFUT` 1m (today's signal series)** | **375** | **0** | 15:29 |
| `NIFTY26JULFUT` 1m (expiring) | 375 | 0 | 15:29 |
| `SENSEX26JULFUT` 1m (BFO) | 375 | 0 | 15:29 |
| `NIFTY 50` / `SENSEX` 1m | 375 / 375 | 0 | 15:29 |
| `futures_oi_snapshots` | 25,806 rows / **374** distinct minutes | — | 15:30 |

✅ **T19 quiet for a FOURTH consecutive session** — the §3.15 misaligned-bucket probe returns the **empty
set** session-wide. Four clean negative controls.

**`dot-health` canary at 16:07 IST** (pre-#1073-deploy — see §6.3):

```
rowsScanned 200 / rowsInspected 40
breadth        alive=true  required=true   input live in the last 40 context-bearing rejections
iv_rank        alive=false required=false  input dead across 40 context-bearing rejections
dow            alive=false required=false  input dead across 40 context-bearing rejections
fii            alive=true  required=false  input live in the last 40 context-bearing rejections
oi_spurt_price alive=false required=false  input dead across 40 context-bearing rejections   <-- the #1073 defect
vix            alive=true  required=false  input live in the last 40 context-bearing rejections
futures_oi     alive=false required=false  NEUTRAL by design — monthly index-expiry day, OI reads S24-suppressed
underlying_oi  alive=false required=false  NEUTRAL by design — monthly index-expiry day, OI reads S24-suppressed
```

The sampling half of #983 is working (`rowsScanned 200 / rowsInspected 40` — it scanned past the
context-less tail), and `breadth` is correctly `alive` even though its **dot** scored 0% — the probe tests
the *input*, not the dot's verdict, which is the right distinction and is behaving.

**Error channels.** `ay-strategy-signal-service`: **0 ERROR** lines all session. `ay-market-data-service`:
**0 ERROR** lines all session (07-27 had 3). `strategy.subscriber_health_events` **empty**.

**Host-clock guard (B8).** Host UTC `2026-07-28T10:28:09.879` vs container `now()` `10:28:10.164` —
**<0.3 s** apart. No drift. B8 remains a free-running-CMOS watch item, not an active problem.

## 5 Shadow-book outcomes

**Zero shadow positions opened today, and that is mechanically correct** — the book opens on
composite-passing rejections and nothing passed composite (§0.1). Zero OPEN positions in any book at EOD.

**Exit-fidelity caveat (standing, §3.16):** indicator-driven exits are not replicated; brackets +
structural stop + 15:12 square-off only. Not exercised today.

**League (unchanged from 07-27 — no book traded):**

| variant | closed | net wins | pts | **net ₹** |
|---|---|---|---|---|
| champion | 219 | 88 | −246.50 | **−35,153.63** |
| composite-055 | 11 | 3 | −10.20 | **−1,542.15** |
| vol-12k5 | 38 | 12 | −218.30 | **−9,331.31** |
| vol-off | 48 | 14 | −388.90 | **−17,014.10** |

⚠ **A second consecutive session in which `composite-055` took nothing**, and now a session in which *no*
book took anything. Its 11 closes all predate #991 and are getting staler, not more decisive. The
**`vol-12k5` > `vol-off`** ordering is untested today.

**No §4.2 counterfactual table exists for this session** (§2.2) — the would-have-fired set is empty for
every rail. Stated explicitly so a future rollup does not read the absence as an oversight.

## 6 New data points / anomalies

### 6.1 T23 — 10 canary WARNs, but the opening-bucket signature did NOT recur

`PartialBucketCanary` WARNed **10** times (3 on 07-27, 37 on 07-24, 48 on 07-23), all on
`NFO:NIFTY26AUGFUT@3m`:

| IST bucket | engine 3m | Σ(3×1m) | shortfall | lots (÷65) | shape |
|---|---|---|---|---|---|
| **09:18** | 111,345 | 114,075 | **+2,730** | **42** | ⚠ unpaired |
| 09:30 | 39,390 | 38,480 | −910 | 14 | near-pair with the next |
| 09:33 | 37,375 | 38,415 | +1,040 | 16 | near-pair |
| 11:30 | 19,240 | 18,005 | −1,235 | 19 | exact ± pair |
| 11:33 | 17,485 | 18,720 | +1,235 | 19 | exact ± pair |
| 13:39 | 15,600 | 14,495 | −1,105 | 17 | near-pair |
| 13:42 | 9,620 | 10,595 | +975 | 15 | near-pair |
| 14:42 | 31,005 | 30,030 | −975 | 15 | exact ± pair |
| 14:45 | 32,175 | 33,150 | +975 | 15 | exact ± pair |
| **15:15** | 110,045 | 107,445 | **−2,600** | **40** | ⚠ unpaired |

**Every shortfall is an exact multiple of 65** (the NIFTY lot), and **eight of the ten arrive as ± pairs on
consecutive buckets** — the documented benign boundary straddle between ~1 Hz cumulative-volume snapshots
and the broker's trade-timestamped attribution (README §3.17).

**Two things changed versus 07-27, in opposite directions:**

- ✅ **The 09:15 unpaired opening-bucket shortfall — #981's target signature, still present at 49 lots on
  07-27 and 94 on 07-24 — did NOT fire at all today.** The container was warm across the day rollover
  (booted 03:10 IST), which is the condition the fix addresses, so this is a genuine negative control on
  that specific signature.
- ⚠ **The WARN count tripled** and the two unpaired events moved to the **09:18** and **15:15** buckets, at
  42 and 40 lots. Both are the session's two thickest buckets (111,345 and 110,045), so as a *fraction*
  they are 2.5% and 2.4% — comparable to 07-27's opening 2.7%, and the 650-absolute tolerance is simply
  easier to exceed on an expiry tape whose median bar is 35% larger.

**Impact is nil this session** (no row could fire, and both flagged buckets clear every slug's floor by
1.6–4×), but the count rising while the fixed signature disappears is a genuinely new pattern.
**T23 stays PROPOSED**, re-narrowed from "the opening bucket" to "**the session's thickest buckets, either
end**", with the post-#981 rollover-baseline code read still outstanding.

### 6.2 The expiry-day suppression is now measured end-to-end, on a full session

The 09:43–09:57 live run measured the suppression on 16 context-bearing rows. This run confirms it on
**1,068** — 1,068/1,068 on all four probes, with `futuresBasis` live on 1,068/1,068 and capture at 374/375
minutes. **README §3.19, written this morning from a 16-row sample, holds at 67× the sample size with no
exception.** No row in the session breaks the pattern in either direction.

The one number worth promoting out of it is §3.1's: the suppression's cost is not "some dots are dead", it
is **7.5 of 18.8 denominator weight scoring zero while staying in the denominator**, which lands the cap at
0.5479 — *below* the threshold. That is a stronger statement than "the composite is starved", and it is
what makes the session uninformative rather than merely quiet.

### 6.3 ⚠ #1073 was NOT deployed at analysis time — and its defect reproduced live at EOD

`docker inspect ay-strategy-signal-service` reads `StartedAt 2026-07-27T21:40:01Z` (**03:10 IST today**)
with `RestartCount 0`, so the running artifact **predates** [#1073](https://github.com/prashantm912/artha-yantra-2/pull/1073)
(merged `edd2a2b1`). The scheduled post-close deploy task (`16:00 IST`) had not completed as of the 16:07
IST read.

**The pre-fix behaviour is therefore captured on the live stack, at EOD, on a real monthly expiry:**

- `futures_oi` / `underlying_oi` → `NEUTRAL by design — monthly index-expiry day, OI reads S24-suppressed`
  ✅ correct
- `oi_spurt_price` → `input dead across 40 context-bearing rejections` ⚠ **wrong** — it is fed by the *same*
  skipped read and is equally inert by design

That is precisely the asymmetry #1073 closes (the exemption set at `DotHealthCanary.java:105` held only the
two quadrant dots). **This is the last direct observation of the pre-fix behaviour until the next monthly
expiry**, so it is recorded here in full.

The second, worse half of #1073 — the blanket `nse || bse` calendar keying, where the suppression actually
keys on the row's own OI root — **was reachable today but not realised**: NSE is expiring while BSE is not
(BSE monthly is Thursday the 30th), and a SENSEX-rooted OI outage would have been mislabelled by-design.
All 1,068 context-bearing rows were `NIFTY 50`-rooted, so no such outage existed to be silenced.

**Verifying the deploy and the post-fix `inert by design` label on `oi_spurt_price` belongs to the
scheduled deploy task, not to this analysis run** (this run is read-only and does not restart containers).
If the post-14:45 context-less tail makes every probe read uninformative after the restart, that must be
recorded as **UNOBSERVABLE**, not as a pass.

### 6.4 The 07-28 live-health carry-list, discharged

| # | carry item | outcome |
|---|---|---|
| 1 | `fii` ALIVE — update the dead-dot ledger | ✅ **Confirmed on 1,068/1,068 rows, value 8.78 all session.** Standing dead set is now the pair `ivRank` + `dowUp` (§4) |
| 2 | Mark the session REGIME for anything OI/composite-related | ✅ Done — §0.1 and the rollup row; the cap math (§3.1) makes it quantitative rather than a caveat |
| 3 | Re-check `oi_spurt` at EOD against 07-27's 9.9% | ✅ **0/1,068 — S24-inert, says nothing about #991.** Re-check on the next NON-expiry session (§7 T22-watch) |
| 4 | Re-run the §3.15 phantom-candle probe at EOD | ✅ **0 rows session-wide** — 4th clean session (§4) |
| 5 | Derive the signal contract properly per §3.18 | ✅ Done — `NIFTY26AUGFUT`, derived from `context.chart.close` against candidate day-ranges (header) |
| 6 | Confirm the #1073 post-close deploy + the `inert by design` label | ⚠ **NOT deployed at 16:07 IST**; pre-fix defect captured live instead (§6.3). Verification passes to the deploy task |
| 7 | Re-check the host-clock guard at EOD | ✅ **<0.3 s** drift (§4) |

### 6.5 Method addendum → README §3.20

Promote to the standard pass: **a dot and its namesake rail may not share a threshold — verify the
scorer's call site before attributing a dead dot to data, regime or the rail's own tuning.** T24 sat open
for two sessions on the theory that the dot was "mechanically dead behind the 125k floor", then survived
the floor being fixed, and was only resolved by reading `ConnectTheDotsScorer`'s actual call into
`ScalperGates` (§2.3). The rejection row shows the dot's verdict; only the code shows which threshold
produced it.

## 7 Tuning candidates

Carried forward from 07-27 plus this session's movement. **Nothing here is applied** — every open row is a
PROPOSAL. ⚠ **Today is a MONTHLY EXPIRY session and contributes no entry-gate calibration evidence** (§0.1,
§3.1); rows below are updated for *mechanism* and *diagnosis*, not for tuning weight.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T24** | the `volume` **dot**'s floor resolution (`ConnectTheDotsScorer.java:141`) | the **static** `VOL_FLOOR` default (NIFTY **125,000**) via the 2-arg `ScalperGates.volume` overload — the `relative-volume-floor` tag never reaches it | thread the **resolved** floor into the scorer (the 3-arg overload with the same override the rail uses), so dot and rail agree by construction | §2.3, **ROOT-CAUSED IN CODE**: `ScalperGates.java:173-175` returns the per-index default when `override == null`; the tag substitutes only at `ScalperConfluenceGate.java:422`. Arithmetic confirms it — 6 of 125 3m bars cleared 125,000 today (4.8%) and the dot supported 38/1,068 (3.6%); on 07-27 the series max was 117,000, so **zero** bars could clear it and the dot read 0/909. 1.0 of weight has been gated at ~p95 of its own operand on every strategy, every session | **STRUCTURAL (code defect)** | **PROPOSED — root-caused 07-28; needs a BUILD row (§5 ledger rule), not a knob turn. HIGHEST PRIORITY** |
| **T23** | 3m-vs-1m volume attribution (+ `artha.signals.partial-bucket-canary.volume-tolerance`) | post-#981: 10 WARNs today (3 on 07-27); **the 09:15 opening signature did NOT recur**; the 2 unpaired events moved to the session's two thickest buckets (09:18 +2,730 / 15:15 −2,600, 42 and 40 lots, 2.5% and 2.4%) | code-read the post-#981 rollover baseline; consider whether the tolerance should scale with bar size rather than being absolute | §6.1: all 10 shortfalls are exact ×65 multiples, 8 of 10 in ± pairs on consecutive buckets. Count tripled on a tape whose median 3m bar grew 35%, while the fixed signature disappeared | **STRUCTURAL (defect, re-narrowed)** | **PROPOSED — re-narrowed from "the opening bucket" to "the thickest buckets, either end"** |
| **T25** | scalper→paper routing | no scalper paper book; the 07-27 fires lapsed `EXPIRED` | owner: confirm intentionally un-armed, or arm it | no fires today, so no new evidence. `paper_positions` still holds only `minervini` (12 OPEN / 6 CLOSED) and `manas-arora` (7 OPEN / 6 CLOSED) | **OWNER (arming question)** | **PROPOSED — carried, no new evidence** |
| **T26** | ENTRY-path emit latency | ~17 s bar-close→emit on entries vs ~0.3 s on exits | measure across more fires | `ay_signal_bar_to_emit_seconds_count` = **0** today (no fires). Still a 3-observation sample from 07-27 | **STRUCTURAL (measurement)** | **PROPOSED — collect more fires** |
| T14 | rejection-row margin invariant | global `blocking_margin < 0` | sign-aware per rail direction; record the operand that actually failed on optional-gate composite blocks | **0 self-contradicting rows** a 2nd session (all 20 `confluence-composite` first-blocks carry a genuinely failing operand, avg 0.229 vs 0.600). But no row passed composite at all today, so the mechanism could not be exercised — this is a **vacuous** clean session, not a second real one | **STRUCTURAL (diagnostic)** | **PROPOSED — carried; today's cleanliness is vacuous** |
| T3 | `iv_pair` min-gap (`ivPairMinGap`) | 0.02 | ~0.005 | §3.3: **first-ever non-zero support, 18/1,068 (1.7%)** after nine sessions at 0.0% — **but on a monthly expiry, exactly where front-chain IV skew is most likely to open a ≥0.02 gap.** Explicitly NOT counted as evidence that 0.02 is reachable in general | **STRUCTURAL** | **PROPOSED — carried; needs the ground-truth gap distribution on a NON-expiry session** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source `ivRank` or drop from Σw | NULL 100% a 10th session; currently withheld from Σw (#676), so it costs headroom only in the sense that the dot can never help | **STRUCTURAL** | **PROPOSED (carried)** |
| T5 | `iv_abs_band` band | 10–12 | widen to 10–13 | 0/180 again (8th session) | **REGIME** | **PROPOSED, collect more** |
| T1 | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | §2.2: **the would-have-fired set is EMPTY for every rail** — unblocking the floor alone would still have left every row below 0.600. **No evidence gained in either direction.** Tally stands at 07-27's 4-for/5-against, with the base still effectively restarting 2026-07-27 | **REGIME** | **PROPOSED — still do NOT apply; today contributes nothing** |
| T7 | composite threshold | 0.600 | no change | §3.1: the cap fell to **0.5479 (below threshold)** on the expiry suppression. A pure regime artifact; says nothing about the threshold. `composite-055` took 0 rows a 2nd session | — | **REJECTED — re-baseline on post-#991 NON-expiry sessions only** |
| T10 | stale OPEN paper positions | **19** OPEN (was 18), 12 CLOSED | square off / age out, or subscribe the swing holdings | `manas-arora` OPEN went 6 → 7; the drain has now reversed two sessions running | ops | **OWNER — chronic, re-accumulating** |
| T15 | engine boot-line durability | V046 `strategy.engine_reloads` (#987) | — | not exercised — `RestartCount 0` all session, so the log line was still readable. The table's value shows up on the **next** restart | **STRUCTURAL (data-model)** | **SHIPPED #987 — still unverified against a restart** |
| T8 | shadow entry latency | p50 ~80 s | stamp entry at bar close | no shadow entries today | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T18 | `breadth` dot threshold | `advances/declines > 32` | **no change** | §3.2: 0/1,068 with session-max advances **exactly 32** — a one-constituent near-miss, the same shape as 07-21 (max 31). Second near-miss in six sessions on a threshold sitting at the edge of the observed range, but the 07-22/07-24/07-27 readings (96.6% / 38.7% / 79.5%) settled it as regime | **REGIME (resolved)** | **CLOSED — logged as a 2nd near-miss data point, NOT reopened** |
| T22 | `oi_spurt` floors | (15, 3) (#991) | — | 0/1,068 today, **S24-inert — the session says nothing about #991**. Re-verify against 07-27's 9.9% on the next non-expiry session | **STRUCTURAL** | ✅ **CLOSED 07-27 — watch item only, do not reopen on expiry-day data** |
| T16 / T21 / T6 / T17+T13 / T12 / T19 / T20 | — | — | — | §2.1 (38/38 armed, 0 flat floors), §4 (T12 374/375 minutes; T19 0 misaligned, 4th clean session) | — | ✅ **remain CLOSED** |

**Ledger note (README §5 rule).** **T24 is now a BUILD proposal, not a knob turn**, so per the README §5
rule it needs a row in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
**§0 group G**. It is not created in this docs-only PR (that ledger is the forward-work authority and this
run is read-only analysis) — it is flagged here as the next action.

Group **G1** says the blocked tunes (T1/T2/T3/T5/T7) unblock "after **2 clean forward sessions**
post-D-wave (earliest Tue 2026-07-28)". **07-27 was the first. Today is NOT the second** — a monthly-expiry
session with a sub-threshold composite cap is not a clean forward session for entry-gate evidence.
**G1 stays BLOCKED-DATA; the second clean session is now earliest Wednesday 2026-07-29.**

## 8 Honesty caveats

- **Nothing in this file is entry-gate calibration evidence.** The composite cap (0.5479) sat below the
  0.600 threshold all session, so every rail's block is unfalsifiable: unblocking it would have changed no
  outcome. This is stated once in §0, once in §2.2 and once in §7, deliberately.
- **The cap arithmetic is derived from the dot weights documented in README §2 and reconciled against one
  real row** (`id 11467`: 8.5 supporting weight / 0.4521 = 18.80 denominator). It was **not** verified
  against `ConnectTheDotsScorer`'s weight table in code this run. The reconciliation makes an error
  unlikely but does not exclude one.
- **T24's root cause is a code read, and it is the strongest claim in this file.** The three cited lines
  (`ConnectTheDotsScorer.java:141`, `ScalperGates.java:152-159`, `:173-175`) were read directly this run.
  What was **not** done is a runtime proof — no instrumented run confirms the dot evaluated against 125,000
  on a specific bar. The arithmetic agreement (6 of 125 bars ≥125,000; dot supports on 38 rows / 6 distinct
  buckets) is strong circumstantial confirmation, not a direct one.
- **`iv_pair`'s first life is an expiry-day observation** and is explicitly excluded from T3's evidence
  base (§3.3). Reading it as "0.02 is reachable" would be the same error as reading today's dead OI dots as
  a defect.
- **`breadth`'s 0% is a regime near-miss, not a defect** — the input is live (0 zero-pairs, 0 nulls, 10
  distinct values) and the canary correctly reports it `alive`. The dot's verdict and the probe's verdict
  disagree *by design*.
- **The `hero-zero` silence is attributed from the strategy's own YAML header**, not from a runtime trace.
  The pair wrote zero rejection rows, which is consistent with a chart-stage block (upstream of
  `recordRejection`) but does not prove the specific block was the inert-OI one.
- **§6.1's "the fixed signature did not recur" is one session.** A single clean negative control on the
  09:15 bucket does not close T23, and the count tripling is the countervailing observation in the same
  data.
- **#1073's status is reported as observed at 16:07 IST** (`RestartCount 0`, pre-fix labels live). It may
  well have deployed minutes later; this file makes no claim about the post-deploy state and does not
  substitute for the deploy task's own verification.
- **The 09:30 and 15:00–15:30 rejection holes are ruled out as stalls via the eval counters** (§1.2), per
  §3.11's honesty limit — an empty bucket is never by itself proof of a dead engine, and here it is proof
  of nothing more than a narrow trade window.
- **No shadow or paper position was opened**, so every PnL number in §5 is carried unchanged from 07-27 and
  is a **stale** cumulative, not a fresh measurement.
- This run was **read-only**: SELECTs, `docker logs`, in-container actuator/health GETs, and source reads.
  No restart, deploy, write or config change. No strategy knob was altered.
