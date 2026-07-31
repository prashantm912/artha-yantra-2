# Session findings — 2026-07-31 (data date)

Analysis date: 2026-07-31 (scheduled post-market agent, ran 15:50–16:40 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,055** (bar times 09:19–**13:34** — truncation explained in §0.2),
signals **11 ENTRY + 6 EXIT**, paper positions opened **5** (all closed, net **+₹69.58** — the scalper
book's first profitable session), shadow positions opened **18** (champion; all closed).

**2026-07-31 is a Friday with NO expiry on either exchange** (NSE weekly Tue, BSE weekly Thu; both
monthlies were 07-28/07-30). The OI bloc is fully live (0 NEUTRAL quadrants, 0 null spurt pcts on all
835 context-bearing rows, root `NIFTY 50` on 835/835).

**Signal contract: `NFO:NIFTY26AUGFUT@3m`, confirmed DIRECTLY from the engine log** (2,424 occurrences
of `NFO:NIFTY26AUGFUT`, zero of any other future). Unchanged since the 07-27 roll — volume comparisons
against 07-28…07-30 are like-for-like.

**Session regime (§3.25 / G15): `chop`, efficiency 0.171.** `NIFTY 50` open **24,361.45** → close
**24,383.60** (+0.09% intraday), high 24,429.40, low 24,299.70, range 0.53%,
`|close−open|/(high−low)` = **0.171** ⇒ **`chop` (cut <0.29)**.

⚠️⚠️ **THIS IS THE CHOP DAY LEDGER ROW G11 IS BLOCKED-DATA ON — the first post-07-27, expiry-free
`chop` session in the folder.** The regime stamp exists precisely to announce this. G11's observation
has arrived; the full read is §5.2, and the G11 ledger row gets a dated status addendum in this PR.

---

## 0 Read this first — the session's headline

1. **G11's blocked chop-day observation ARRIVED, and on it the 30-minute `time_stop` beat
   hold-to-15:12 decisively on the would-have-fired set: +196.10 pts vs +35.05 pts across 22 legs
   (15W/7L), sign robust to removing the top-2 legs.** But the matched champion-shadow events (5,
   morning-weighted) favour holding (+95.40 vs +32.00) — the split is entry-TIME composition: morning
   entries drifted up into 15:12, midday entries peaked ≈ +30 min and faded. Full table + honesty
   limits in §5.2. **Owner decision ready; do not act unilaterally** (exit-doctrine row).
2. **The paper scalper book had its first PROFITABLE session (+₹69.58 net), and its two winners are
   both `TIME_STOP` closes.** 5 positions, 2W/3L: +318.26 and +1,579.80 (TIME_STOP) vs −758.82,
   −18.46, −1,051.20 (STRUCTURAL_STOP). All-time book: 9 closes, −₹2,366.37.
3. **All 5 sub-accounts froze by 13:34 and the engine paused scalper entries for the rest of the
   session — the first live observation of the full §12.7 discipline path.** Subs 1–2 froze by the
   **1% profit-lock** (+318.26, +1,579.80 ≥ ₹300 on ₹30,000), subs 3–5 by **first-loss** freeze.
   `discipline-paused` = **224** evals (first non-zero ever), starting exactly at the 13:33 bucket.
   §3.30's flag condition (≥3 of 5 stopped before 14:30) **FIRES** (§5.4).
4. **The freeze TRUNCATES the rejection stream: zero rejection rows after 13:34 with the engine
   provably alive** (both liveness gauges fresh and equal, eval grid 375/375, `chart-gate-failed`
   advancing to 14:57). `DISCIPLINE_PAUSED` returns BEFORE the confluence gate, so paused strategies
   write no rows — **every §3.3/§3.6 table in this file covers 09:19–13:34 only** (a §3.21-class
   partial-session caveat that will recur on every freeze day) → new README **§3.31**.
5. **`chain-unavailable` appeared as a first-blocking rail for the first time (40 rows, 14 slugs,
   BOTH roots)** — two short Kite REST circuit-open windows (12:07–12:10, 12:52–12:53 IST, the
   market-data log's only 6 ERROR lines). FAIL_CLOSED behaved correctly; self-recovered; a midday
   `engine_reloads` row at 12:14 (38 loaded / 0 unresolved) sits right after the first window (§6.2).
6. **`strike-pick` failed 374 times, ALL sensex-rooted (9 slugs), on a NON-expiry Friday — the
   second Friday case, and it upgrades §3.27's non-conforming 07-24 observation to a pattern.**
   07-24 (Fri) 550 sensex fails; 07-31 (Fri) 374. Both Fridays follow a Thursday BSE expiry — the
   freshly-rolled SENSEX weekly chain is now the leading hypothesis (§2.2).
7. **Composite pass rate hit its highest ever — 323 of 835 scored rows (38.7%) ≥ 0.600 — and the
   session max (0.8511) IS the 18-dot cap exactly** (16.0/18.80 with `iv_pair` 0.8 + `oi_spurt` 1.0 +
   `breadth` 1.0 dead-in-denominator, `iv_rank` withheld). Rows sat on the ceiling, as on 07-23.
8. **The champion shadow book banked +₹10,697.52 (18 closes, 15W/3L → 6 distinct events)** — 16
   SQUARE_OFF +₹12,013.95 vs 2 STRUCTURAL_STOP −₹1,316.43. All-time: −₹67,428.29 (§5.1).
9. **Every liveness oracle is clean**: `confluence-blocked` counter **1,055** = rejection rows
   exactly; `fired` = **11** = ENTRY rows exactly; eval grid **375/375**, failures 0; gauges equal
   and non-negative; `subscriber_health_events` empty; 0 strategy-signal ERRORs; 0 misaligned 1m
   candles (7th clean session); clock drift <1 s.
10. **#1075 gains its first SENSEX-rooted unfunded fire**: the 11:21 `SENSEX2680677500CE` fire
    (₹786.90 × lot 20 = **₹15,738 > ₹15,000**) expired unfunded — ₹738 over budget. Cumulative:
    **25 fires, 7 unfunded** (6 NIFTY + 1 SENSEX); the "every one NIFTY-rooted" claim is now stale.

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-28 | 2026-07-29 | 2026-07-30 | **2026-07-31** |
|---|---|---|---|---|
| rejections | 1,350 | 1,293 | 1,118 | **1,055** |
| distinct strategies emitting | 36 | 34 | 38 | **20** (10 NIFTY + 10 SENSEX — §1.1) |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| signals | 0 | 12 ENTRY + 8 EXIT | 2 ENTRY + 2 EXIT | **11 ENTRY + 6 EXIT** |
| paper positions opened | 0 | 4 | 0 | **5 (net +₹69.58 — first green session)** |
| bar-time coverage | 09:18–14:57 | 09:18–15:12 | 09:18–15:18 | **09:19–13:34 (freeze-truncated, §0.4)** |
| scored rows | 1,068 | 983 | 814 | **835 (ALL CE, 0 PE)** |
| composite ≥ threshold rows | 0 | 311 (31.6%) | 118 (14.5%) | **323 (38.7%) — highest ever** |
| max composite | 0.4521 | 0.9118 | 0.8627 | **0.8511 = the cap exactly** |
| regime (§3.25) | chop 0.163 (expiry) | mixed 0.501 | mixed 0.434 | **chop 0.171 ✅ G11's day** |

**Eval counters (actuator :8082, read ~15:55 IST).** Container booted **08:57:16 IST**
(`StartedAt 2026-07-31T03:27:16Z`, `RestartCount 1`) — pre-open, so cumulative counters are session
totals:

| outcome | 2026-07-31 |
|---|---|
| `chart-gate-failed` | **2,004** (09:18–14:57) |
| `confluence-blocked` | **1,055** (09:18–13:33) |
| `composite-below-threshold` | **210** (09:42–15:18) |
| **`discipline-paused`** | **224 (13:33–14:57) — first non-zero in the folder** |
| `fired` | **11** |
| `discipline-paused` et al. zero-outcomes | 0 |
| **Σ** | **3,504** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = 1,055 = the rejection row count **exactly**; `fired` = 11 = the ENTRY-signal
row count **exactly**. `ay_signal_eval_duration_seconds_count` = **375** — the complete grid.
`ay_signal_bar_to_emit_seconds`: 17 emissions, sum 283.7 s ⇒ mean **16.7 s** (G8's uniform ~17 s
holds on 4× the prior sample).

**Liveness gauges at ~15:55 IST:** received = evaluated = **1,515.97 s** — non-negative, mutually
equal, consistent with the 15:30 close. The last bar received was evaluated.

**Engine load state.** `03:27:29Z main: loaded 0 published strategies (38 dropped on an unresolved
universe)` → `03:29:16Z signal-eval: loaded 38 (0 dropped, 0 failed)` — the documented F10
cold-start shape, self-healed in **107 s** pre-open. `unresolved == 0` ✅. **T15's V046
`engine_reloads` table is VERIFIED for the first time** — it carries both boot rows (0/38 →
38/0) **and a midday reload at 12:14:08 (38 loaded / 0 unresolved / 0 errors)** right after the
first kite circuit-open window (§6.2).

**First-blocking-rail histogram** (1,055 rows, **16** distinct rails):

| rail | n | share | avg margin |
|---|---|---|---|
| volume-floor | **573** | 54.3% | −9,975.6 |
| time-window | 146 | 13.8% | — |
| two-candle | 42 | | — |
| pct-price-move | 42 | | −0.832 |
| **chain-unavailable** | **40** | | **NEW rail — §6.2** |
| volume-pump | 40 | | — |
| oi-cross-required | 38 | | — |
| divergence-vol-gate | 38 | | — |
| option-side-constraint | 20 | | — |
| max-oi-sr-gate | 18 | | — |
| time-of-day-preference | 14 | | — |
| directional-change-gate | 14 | | — |
| strike-pick | 13 | | — |
| confluence-composite | 11 | | −0.094 |
| psar-durability | 4 | | −0.015 |
| morning-opening-formation | 2 | | — |

⚠ `rsi-band` is **absent entirely** (88 first-blocks / 274 fails on 07-30) — on an all-CE tape with
RSI mid-band, neither the floor nor the cap bit. Recorded so its absence is not later read as a
change.

**All-failed-rails expansion (§3.3)** — top rows (09:19–13:34 only, per §0.4):

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| volume-floor | FAIL_CLOSED | **573** | 8,608.7 | 18,584.3 |
| confluence-composite | FAIL_CLOSED | **512** | 0.475 | 0.600 |
| **strike-pick** | FAIL_CLOSED | **374** | — | — |
| time-window | FAIL_CLOSED | 146 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 130 | 12,952.0 | — |
| trend-change | FAIL_CLOSED | 130 | — | — |
| pct-price-move | FAIL_OPEN | 122 | 0.123 | 1.000 |
| two-candle | FAIL_CLOSED | 122 | — | — |
| volume-pump | FAIL_OPEN | 120 | 13,387.8 | — |
| oi-cross-required | FAIL_CLOSED | 110 | 109.8 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 110 | 13.78 | 20.00 |
| open-high-low | FAIL_CLOSED | 72 | — | — |
| directional-change-gate | FAIL_CLOSED | 72 | 0.613 | — |
| rising-volume | FAIL_CLOSED | 64 | 9,632.2 | — |
| oi-slope-agree | FAIL_CLOSED | 50 | −0.376 | — |
| max-oi-sr-gate | FAIL_OPEN | 45 | 78,000 | — |
| **chain-unavailable** | FAIL_CLOSED | **40** | — | — |

### 1.1 Coverage — 20 of 38, and the set-difference is explained in two halves

Only **20** slugs emitted rows (10 NIFTY-rooted + 10 SENSEX-rooted). Boot line
`loaded 38 (0 dropped, 0 failed)` ⇒ **not a load/T9 question**. The 18 silent slugs decompose as:
(a) the `-pe` variants — 835 of 835 scored rows are CE on an up-drifting chop tape (same shape as
07-29's four silent `-pe` slugs, now fleet-wide because the tape never turned); (b) post-13:34,
**everything** went quiet by discipline pause (§0.4), shortening every slug's window to emit.
Peak concurrent slugs per 15-min bucket: 18.

### 1.2 Interior coverage (§3.11) — populated to 13:30, then freeze

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 8 | 68 | 44 | 64 | 70 | 64 | 58 | 77 | 57 | 78 | 80 | 70 | 70 | 73 | 71 | 70 | 25 |

**No interior hole 09:15–13:30**; buckets 13:45+ are empty **by the discipline freeze, not a
stall** — `chart-gate-failed` and `discipline-paused` both advance to 14:57 in the eval-outcome
buckets, and the gauges are fresh. `subscriber_health_events`: **0 rows**.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 G10 / T27 — the opening-surge mechanism reproduces a THIRD session

**Registry (§3.14):** 38/38 armed `relative-volume-floor`, published 2026-07-28, **0 flat floors**
(`blocking_threshold = 125000` count: 0). Observed threshold range **8,385 – 47,482.50**.

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned only (§3.15):**

| bars | min | p50 | p90 | p99 | max | bars ≥125,000 |
|---|---|---|---|---|---|---|
| 125 | 1,495 | **12,675** | 42,705 | 97,760 | **111,670** | **0** |

Opening threshold peak **47,482.50 ≈ p91** of the session's own distribution (07-30: 48,993.75 =
p92; 07-29: 133,185 = p98.4). **Pre-11:00 share of `volume-floor` blocks: 230/573 = 40.1%** (07-29:
43%, 07-30: 28.9%). Third consecutive reproduction, severity between the two priors. **Arming
recommendation unchanged (NO)** — G10's own counterfactual measured −₹590.95 after cost.

### 2.2 `strike-pick` 374 fails, ALL sensex-rooted, on a NON-expiry Friday — §3.27's outlier is now a pattern

| session | day | expiry | strike-pick fails, NIFTY-rooted | sensex-rooted |
|---|---|---|---|---|
| 2026-07-24 | Fri | none | 0 | **550** |
| 2026-07-28 | Tue | NSE monthly | **534** | 0 |
| 2026-07-29 | Wed | none | 0 | 0 |
| 2026-07-30 | Thu | BSE monthly | 0 | **405** |
| **2026-07-31** | **Fri** | **none** | **0** | **374 (9 slugs)** |

Both non-conforming sessions are **Fridays following a Thursday BSE expiry** — the session on which
the SENSEX weekly chain has just rolled. **The freshly-rolled-chain hypothesis (named but unexcluded
in §3.27) now has two conforming instances and zero counter-instances.** Not promoted to a rule yet —
one more Friday observation would settle it; flagged as a §7 watch item, no tune (the gate declining
a chain with no usable delta/premium band remains correct behaviour).

### 2.3 The `volume` dot post-#1082, third live session

`volume` supported **262/835 (31.4%)** — best yet (29.5% on 07-30, 23.1% on 07-29) — on a session
where **ZERO of 125 bars cleared the pre-fix static 125,000** (max 111,670). Third impossible-under-
the-old-path confirmation; G6 stays VERIFIED.

### 2.4 Rails with no evidence of miscalibration

`pct-price-move` (0.123 vs 1.000), `volume-pump`, `oi-divergence-magnitude` (13.78 vs 20.00 — a live
operand at 69% of threshold, its closest read yet), `psar-durability` (0.030 vs 0.050),
`directional-change-gate` and `max-oi-sr-gate` (FAIL_OPEN, operand pinned at 78,000 = the SENSEX
max-OI strike) all read plausibly. `confluence-composite` first-blocks are near-misses (avg margin
−0.094, 11 rows).

## 3 Composite + dots (§3.4 / §3.6)

⚠️ **All rates below cover 09:19–13:34 only** (freeze truncation, §0.4) — treat as a long partial
session per §3.21; cross-session comparisons remain valid for the covered window.

**Composite histogram** (835 scored rows, ALL CE):

| bucket | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 |
|---|---|---|---|---|---|---|
| n | 26 | 120 | 302 | 175 | 150 | 62 |

Max **0.8511**; **323 rows (38.7%) ≥ 0.600 — the highest pass rate in the folder** (07-29: 31.6%).

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `iv_pair` | 0.8 | 0/835 | 0.0 | structurally impossible (G13); in Σw |
| **`oi_spurt`** | 1.0 | **0/835** | **0.0** | ⚠ **input LIVE and MOVING** (spurtOiPct 64 distinct, −36.1…+200; magnitude ≥15 on 178 rows) — the binding term is the QUADRANT-agreement conjunct on a chop tape that flip-flops quadrants. §3.28 check done: not dead, not frozen, not never-crossing-by-threshold — **conjunct-starved, regime**. Watch: was 4.9% (07-30), 8.5% (07-29) |
| `iv_rank` | 0.8 | 0/835 | 0.0 | dead-data, withheld from Σw — 13th session |
| `breadth` | 1.0 | 2/835 | 0.2 | **T30/G16 pattern continues (9th session, still never in between)**: advances 21–35, crossed `>32` only at 09:25 (35) then fell. Third 0–0.2% session in a row |
| `iv_slope` | 0.8 | 11/95 | 11.6 | alive |
| `vwap` | 2.5 | 201/835 | 24.1 | alive — #990 ≥15 bps behaving |
| `volume` | 1.0 | 262/835 | **31.4** | ✅ best ever — §2.3 |
| `rsi` | 1.0 | 331/835 | 39.6 | alive |
| `trending_cross` | 1.0 | 420/835 | **50.3** | best ever (22.9% on 07-30) |
| `futures_oi` | 1.5 | 496/835 | 59.4 | ✅ live |
| `underlying_oi` | 1.0 | 508/835 | 60.8 | ✅ live |
| `sentiment_slope` | 1.0 | 517/835 | 61.9 | |
| `sentiment` | 1.0 | 695/835 | 83.2 | |
| `drastic_oi` | 1.0 | 701/835 | 84.0 | |
| `psar` | 1.0 | 794/835 | 95.1 | |
| `vwma` | 1.0 | 807/835 | 96.6 | |
| `vix` | 1.0 | 835/835 | **100.0** | direction dot saturated on a one-sided-CE population |
| `basis` | 1.0 | 835/835 | **100.0** | same |
| `iv_abs_band` | 0.8 | 95/95 | 100.0 | frozen input (G12) — 6th session, today's stamp **0.114471**, inside 0.10–0.12 |
| `supertrend` | 1.0 | 835/835 | 100.0 | 6th 100% session |

### 3.1 Dead-weight cap — the observed max IS the cap

18-dot majority roster: Σw 19.60, denominator 18.80 (`iv_rank` withheld), dead-in-denominator =
`iv_pair` 0.8 + `oi_spurt` 1.0 + `breadth` 1.0 = 2.8 ⇒ cap **16.00/18.80 = 0.8511**. Observed max
**0.8511 = 100.0% of the cap** — rows sat on the ceiling (as on 07-23). With three saturated
direction dots (`vix`/`basis`/`supertrend`) the gate had headroom and used all of it; the market
side, not the dot roster, decided which rows passed.

## 4 Data health (§3.7)

| field | 2026-07-29 | 2026-07-30 | **2026-07-31** | class |
|---|---|---|---|---|
| `futuresQuadrant`/`underlyingQuadrant` NEUTRAL | 0/983 | 0/814 | **0/835** | ✅ live |
| `spurtOiPct`/`spurtPricePct` NULL | 0 | 0 | **0/835** (64/49 distinct) | ✅ live |
| `futuresBasis` | LIVE | LIVE | **835/835 LIVE** | ✅ |
| `advances`/`declines` | 35–42 | 23–32 | **21–35, 0 nulls, 0 zero-pairs** | ⚠ dot 0.2% — T30/G16 |
| `fiiLongPct` | 0 nulls | 0 nulls | **0 nulls (10.38, frozen by design)** | ✅ new daily stamp |
| `atmIv` | 1 distinct | 1 distinct | **1 distinct (0.114471)** | frozen — G12, labelled |
| `ivRank` | NULL 100% | NULL 100% | **NULL 835/835** | dead-data (since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 835/835** | by design (un-armed) |

**The 220 context-less rows reconcile exactly**: 146 `time-window` + 20 `option-side-constraint` +
14 `time-of-day-preference` + 40 `chain-unavailable` = **220** = 1,055 − 835.

**Capture (minute-aligned only):** `NIFTY26AUGFUT` 1m **375/375**, last bar 15:29; **0 misaligned 1m
rows session-wide (T19 quiet a 7th session)**; `futures_oi_snapshots` 25,461 rows / **369 of 375
minutes** (small dip from two consecutive 375/375 sessions — the two kite circuit-open windows
account for it; not a T12 regression). Instrument set **69** = 07-30's set exactly (contract-set
plateau between expiries); `tickedTokens` 93.

**`dot-health` canary at 16:00 IST** (200 scanned / 40 context-bearing / 14 operand-bearing bars):
dead set = the standing pair `iv_rank` + `dow`; `fii` (10.38) and `iv_abs_band` (0.114471) both
`frozen BY DESIGN — EOD daily operand` on 14 bars (past `MIN_FROZEN_BARS` 8). **Nothing newly dead.**
Both EOD stamps MOVED day-over-day (9.62→10.38, 0.112743→0.114471) — per-day step, as designed.

**Feed + errors.** `GET /api/v1/market/health/data` → GREEN, 0 problems. `ay-strategy-signal-service`
**0 ERROR** lines; `ay-market-data-service` **6 ERROR** lines — all the two kite circuit-open windows
(§6.2). `subscriber_health_events` **0 rows**. Host↔DB clock **<1 s**.

**T23 / G9 (PartialBucketCanary):** **2 WARNs**, both `NFO:NIFTY26AUGFUT@3m`, shortfalls **−2,405
(37 lots)** and **+780 (12 lots)** — exact ×65 multiples, the documented benign boundary straddle.
Count at floor for a second session; mechanism unchanged, G9 stays open.

## 5 Shadow-book outcomes + the counterfactual

### 5.0 The README-§4.2 counterfactual — 22 resolvable legs, and the exit-model gap IS the finding

The §3.5 query returns **74 rows collapsing to 22 distinct `(bar, leg)` events** (§3.24 dedupe), all
CE, 11:15–13:30; a further 13 `strike-pick` first-block rows carry no `wouldBeLeg` and are
structurally unresolvable. Model: uniform **+35% TP / −25% SL / 30-min time stop / 15:12 square-off**
(§3.16), priced off `options_chain_snapshots`; the **hold-to-15:12** column prices the identical leg
with no stop.

| bar | leg | entry | stop-model exit | **stop pts** | **hold-to-15:12 pts** |
|---|---|---|---|---|---|
| 11:15 | NIFTY2680424200CE | 199.25 | time stop 11:46 | **+3.45** | +19.45 |
| 11:21 | NIFTY2680424200CE | 203.50 | time stop 11:52 | −1.20 | +15.20 |
| 11:21 | SENSEX2680677500CE | 786.90 | time stop 11:52 | −1.05 | +57.20 |
| 11:27 | NIFTY2680424200CE | 200.90 | time stop 11:58 | +2.65 | +17.80 |
| 11:27 | SENSEX2680677500CE | 784.85 | time stop 11:58 | +8.35 | +59.25 |
| 11:45 | SENSEX2680677500CE | 785.00 | time stop 12:16 | +33.25 | +59.10 |
| 12:15 | NIFTY2680424200CE | 216.60 | time stop 12:46 | +14.60 | +2.10 |
| 12:24 | NIFTY2680424200CE | 213.10 | time stop 12:54 | +19.75 | +5.60 |
| 12:27 | SENSEX2680677600CE | 783.95 | time stop 12:58 | **+82.60** | **−15.95** |
| 12:30 | NIFTY2680424200CE | 211.40 | time stop 13:00 | +25.05 | +7.30 |
| 12:36 | NIFTY2680424200CE | 220.80 | time stop 13:06 | +18.40 | −2.10 |
| 12:42 | NIFTY2680424200CE | 225.80 | time stop 13:12 | +12.20 | −7.10 |
| 12:45 | NIFTY2680424250CE | 189.80 | time stop 13:16 | +6.10 | −11.35 |
| 12:48 | NIFTY2680424250CE | 197.65 | time stop 13:18 | +0.75 | −19.20 |
| 12:54 | NIFTY2680424250CE | 193.40 | time stop 13:24 | +6.80 | −14.95 |
| 12:57 | NIFTY2680424250CE | 202.05 | time stop 13:28 | −4.35 | −23.60 |
| 13:00 | NIFTY2680424250CE | 196.00 | time stop 13:30 | +3.35 | −17.55 |
| 13:03 | NIFTY2680424250CE | 196.75 | time stop 13:34 | −4.85 | −18.30 |
| 13:06 | NIFTY2680424250CE | 198.85 | time stop 13:36 | −9.50 | −20.40 |
| 13:09 | NIFTY2680424250CE | 199.10 | time stop 13:40 | −11.90 | −20.65 |
| 13:12 | NIFTY2680424250CE | 197.60 | time stop 13:42 | −10.30 | −19.15 |
| 13:30 | NIFTY2680424250CE | 196.10 | time stop 14:00 | +1.95 | −17.65 |
| **total (22 legs)** | | | **15W/7L** | **+196.10** | **+35.05** |

**Zero TP and zero SL touches — every leg resolved at the time stop**, as on 07-29 (the mirror of
07-30, where the −25% bracket did real work). The would-have-fired set would have MADE money under
either model — a first — but the 5.6× gap between the models is the finding, and it feeds §5.2.

**Per-rail (union legs, stop model; rails share legs — not additive):** `volume-floor` alone-vetoed
legs = 12:54/12:57/13:03 → **1W/2L, −2.40 pts** — the T1 knob's sixth consecutive measurement that
fails to pay. `two-candle`/`volume-pump`/`pct-price-move` share the profitable midday cluster;
`hero-zero` absent (no wf rows).

### 5.1 Shadow book — champion +₹10,697.52 (6 events)

| variant | closed | W | pts | net ₹ |
|---|---|---|---|---|
| **champion** | **18** | **15** | **+439.65** | **+10,697.52** |
| vol-off | 3 | 1 | +16.60 | −1,356.46 |
| vol-12k5 | 2 | 0 | −41.35 | −2,827.16 |
| composite-055 | 0 | — | — | — |

Champion split: 16 SQUARE_OFF **+₹12,013.95**, 2 STRUCTURAL_STOP −₹1,316.43. **§3.24 dedupe: 18 rows
= 6 distinct `(bar, leg, entry)` events** — 09:24 (+₹684.43), 11:15 ×6 (+₹5,628.30), 11:21 ×7
(+₹4,833.05), 11:45 ×2 (+₹1,816.40), 12:54 (−₹1,336.26), 12:57 (−₹928.40). The 11:15/11:21 clusters
carry 98% of the net.

**All-time league:** champion **286 closed / 123 net-wins / +42.35 pts / −₹67,428.29** (−78,125.81 +
10,697.52); vol-off −₹26,525.93; vol-12k5 −₹18,917.71; composite-055 −₹9,999.07 (untraded today —
0 eligible rows it alone accepts). Both trading challengers lost again; the `vol-12k5 > vol-off`
per-close ordering survives an 8th session.

Shadow entry latency p50 **1:20.7** / p95 **1:21.6** (n=23) — structurally unchanged (G8).

### 5.2 ⚠️⚠️ G11 — THE CHOP-DAY OBSERVATION, in full

**The setup G11 waits on:** first post-07-27, expiry-free `chop` session (efficiency 0.171).
Three reads, stated separately because they do not agree:

**(a) The 22-leg would-have-fired set (§5.0): the stop WINS decisively — +196.10 vs +35.05 pts.**
16 of 22 legs favour the stop. Robustness: excluding the single largest-gap leg (12:27 SENSEX,
+98.55 of gap) the stop still wins by **+62.50**; excluding the top two, by **~+40**. The mechanism
is visible in the table: **midday entries (12:15–13:30, 16 legs) peaked within ~30 min and faded
into the close** — the exact behaviour a chop-day time stop exists to monetise.

**(b) The 5 matched champion-shadow events (entry constant to the paisa): HOLD wins — +95.40 vs
+32.00 pts** (09:24: hold +11.65 / stop −10.45; 11:15: +15.55 / +3.45; 11:21: +38.40 / −1.05;
11:45: +49.30 / +33.25; 12:54: −19.50 / +6.80). These are **morning-weighted** — the champion book
froze its intake before the midday cluster (dedup: one OPEN per strategy+side) — and the morning
legs drifted up into 15:12 on this tape.

**(c) The live paper book: both winners are TIME_STOP closes** (+318.26, +1,579.80); the three
losers all hit the structural stop first, so the time stop never arbitrated them. Position 47
(entry 11:16 @199.30) would have been better held (+19.4 pts at 15:12 vs +2.45 realized); position
48 (12:16 @216.65) was better stopped (+12.15 realized vs +2.05 held). Mixed, sample 2.

**The honest synthesis: on this chop day the stop's edge is REAL but time-of-day-shaped** — it
monetised the midday mean-reversion (16 legs, decisive) and cost money on the morning drift (6
legs, consistent across (a) and (b)). Combined three-session G11 ledger: trend-ish 07-29 hold won
large; mixed 07-30 stop won small; **chop 07-31 stop won large on the dominant set**. The
chop-day gate is now SPENT — **the row moves from BLOCKED-DATA to owner-decidable**, and the
evidence favours **keeping the 30-minute `time_stop`** (and, per T1…G10's standing prior, all four
entry-gate rejections that were conditional on it stay decided). Dated addendum added to the G11
ledger row in this PR; **no knob is touched**.

**Limits, load-bearing:** 3-min LTP granularity; no costs in (a)/(b) (a 1% round-trip on ~200-premium
legs ≈ 2 pts/leg ≈ 44 pts on 22 legs — the stop's +161 margin survives it, the hold total does not);
neither model replicates `signal_exit` or the trailing SuperTrend; (b)'s five events are
morning-selected by the book's own dedup, not a random sample; one chop day is one observation.

### 5.3 Paper book — first green session, and the governors' first full cycle

| id | leg | entry | opened | closed | reason | net ₹ | sub |
|---|---|---|---|---|---|---|---|
| 47 | NIFTY2680424200CE | 199.30 | 11:16 | 11:46 | TIME_STOP | **+318.26** | 1 |
| 48 | NIFTY2680424200CE | 216.65 | 12:16 | 12:46 | TIME_STOP | **+1,579.80** | 2 |
| 49 | NIFTY2680424250CE | 197.70 | 12:49 | 12:55 | STRUCTURAL_STOP | −758.82 | 3 |
| 50 | NIFTY2680424250CE | 196.05 | 13:01 | 13:04 | STRUCTURAL_STOP | −18.46 | 4 |
| 51 | NIFTY2680424250CE | 198.90 | 13:07 | 13:34 | STRUCTURAL_STOP | −1,051.20 | 5 |

Net **+₹69.58**; all-time scalper book **9 closes, −₹2,366.37**. Each position is qty 130 = two
1-lot fires (two slugs, same leg) averaging in — `paper_events` records 2 OPENED per sub-account,
which is why §3.30's entry counting goes through events, not rows. The 11:21 SENSEX fire
(`SENSEX2680677500CE`, ₹786.90 × 20 = ₹15,738) expired unfunded — **#1075's first SENSEX-rooted
unfunded fire** (cumulative 25 fires / 7 unfunded).

### 5.4 §3.30 — sub-account freeze telemetry (the flag FIRES)

| sub | entries (OPENED events) | last entry | day PnL | frozen by | at |
|---|---|---|---|---|---|
| 1 | 2 | 11:16 | +318.26 | **1% profit-lock** | 11:46 |
| 2 | 2 | 12:16 | +1,579.80 | **1% profit-lock** | 12:46 |
| 3 | 2 | 12:49 | −758.82 | first-loss | 12:55 |
| 4 | 2 | 13:01 | −18.46 | first-loss | 13:04 |
| 5 | 2 | 13:07 | −1,051.20 | first-loss | 13:34 |

**All 5 frozen by 13:34 — ≥3 of 5 stopped opening before 14:30 ⇒ the §3.30 flag condition is MET.**
Trend: 07-29 (governors' first live day) 3/5 by 13:40; **07-31: 5/5 by 13:34**. Two of the five are
profit-locks, i.e. the design banking a green day, not starvation — and the freeze preserved the
book's first profitable session (+₹69.58) against a §5.0 midday set that was still generating
signals. ⚠️ Counter-read: under the stop model the post-freeze 13:30 wf leg made +1.95 — the blocked
tail was roughly flat, so today the freeze cost ≈ nothing and protected the green. Not yet the
"median freeze before 13:00 for a week" escalation condition; keep the daily row.

## 6 New data points / anomalies

### 6.1 §3.29 — unexercised-path audit (first scheduled run)

Fired vocabulary since 07-01 (paper): TRAILING_STOP 11, TIME_STOP 5 (+2 today), STRUCTURAL_STOP 4
(+3 today), STOP_LOSS 3, MANUAL 2. Armed paths (enabled published configs) vs fires:

| armed path | strategies | fired? | classification |
|---|---|---|---|
| trailing_stop (indicator) | 42 | ✅ 11 | exercised |
| time_stop | 38 | ✅ 5 | exercised (both of today's wins) |
| stop_loss premium_pct | 30 | ✅ (3 STOP_LOSS) | exercised (premium basis; the 4-strategy `percent` basis is indistinguishable in `close_reason` — noted) |
| stop_loss index_points | 8 | ✅ (4 STRUCTURAL_STOP) | exercised |
| **take_profit premium_pct** | **36** | **✗ 0 since 07-01** | **unreachable-this-regime** — nearest miss: today's best real leg peaked ≈ +12% vs the +35% trigger; the T21 bracket has never once paid in a month (carried baseline) |
| **signal_exit** | **38** | **✗ 0** | **shadowed** — the 30-min `time_stop` and the structural stop both fire earlier on every observed path |
| **square_off** | **2** | **✗ 0** | unreachable — the two carrying strategies (btst family) have never fired |
| **tag `oi-confluence-exit`** | **8** | **✗ 0 CONFLUENCE_FLIP** | **unexercised** — T24's exit radius armed for weeks, zero platform-wide fires; still not proven safe (carried watch item) |

Day's delta: TIME_STOP +2, STRUCTURAL_STOP +3; **the never-fired set is unchanged**.

### 6.2 NEW — `chain-unavailable` first-blocks + a midday engine reload: two kite circuit-open windows

40 `chain-unavailable` rows (14 slugs, BOTH roots, 12:07–12:53) line up exactly with the market-data
log's only ERROR lines: **`kite-rest circuit open; serving cached data` at 12:07–12:10 and
12:52–12:53 IST** (plus one response-extraction error at 12:52). FAIL_CLOSED declined entries during
the windows — correct. `engine_reloads` recorded a **12:14:08 reload, 38 loaded / 0 unresolved / 0
errors** — recovery, not an incident. Cost: ~6 of 375 OI-snapshot minutes (369/375). No tune; this
is the first live sighting of the rail, recorded for the vocabulary.

### 6.3 Ledger-consistency pre-check — 10 REVIEW lines, all dispositioned

`tools/ledger-consistency-check.py` returned 10 REVIEWs: **5×[A]** (task_1b85c64f, task_2560273c,
task_9e244d18, task_a86f2d17, task_a2ae20ed reading OPEN and CLOSED in different places) — all five
"open" hits are inside the **historical 2026-07-12 wave-narrative block** (ledger ~line 131) whose
tasks have since closed; the DONE rows are authoritative. **Annotated the narrative line in this PR**
so the checker stops matching it (4 of 5); task_a2ae20ed's second hit is the drift-rule's own
self-referential example text — stands. **4×[B]** keyword matches (pickup bullets mentioning
`map-return`/`cross-context`/`G15`/`G12` that also appear in DONE rows) — false positives by
construction (the bullets reference, not claim). **1×[C]** "T30 promotion claimed but ledger never
mentions it" — false positive: the ledger row **G16** exists (added after the checker's cached scan
shape); T18 is mentioned only as a deliberate non-reopen.

`tools/published-config-drift.py`: **69 published — 69 matched, 69 clean, 0 drifted, 0 DB-only,
0 YAML-only.** No republish proposals.

### 6.4 Method addenda → README §3.31

**§3.31 (new):** *a sub-account discipline freeze truncates the rejection stream* — `DISCIPLINE_PAUSED`
returns before the confluence gate (`SignalEngine.scalperEntry`), so once all 5 sub-accounts freeze,
zero rejection rows are written for the rest of the session while the engine stays fully alive.
Discriminate a freeze from a stall via the eval-outcome buckets (`discipline-paused` > 0 while
`chart-gate-failed` keeps advancing) — and treat every post-freeze §3.3/§3.6 table as partial-session
(§3.21 class).

## 7 Tuning candidates

Carried forward from 07-30 plus this session's movement. **Nothing here is applied.** Ledger rows in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
§0 group G are the authoritative status; this table is the evidence.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T29** | scalper `time_stop` (`max_bars: 10`) | armed fleet-wide | **owner decision now READY: the evidence favours KEEPING the stop** | ⚠⚠ **THE CHOP-DAY OBSERVATION ARRIVED (§5.2).** 22-leg wf set: stop **+196.10** vs hold **+35.05** (16/22 legs, robust to top-2 removal); matched champion events (5, morning-weighted): hold +95.40 vs +32.00; live paper: both winners TIME_STOP. Three-session ledger: trend-ish hold-wins-large / mixed stop-wins-small / **chop stop-wins-large**. Stop's edge is midday-shaped; morning drift favours holding | **EXIT-BAND (owner) — ledger G11** | **UNBLOCKED — chop gate SPENT; owner-decidable. Dated addendum on G11 row this PR. Do not act unilaterally** |
| **T30** | `breadth` dot threshold (`>32`) | fixed | relative/percentile, or reweight | 2/835 (0.2%) — advances 21–35, crossed only at 09:25. **9 sessions: 0% on six, ~100% on two, 0.2% on two — never in between** | STRUCTURAL — **ledger G16** | **OPEN (G16 row exists)** |
| **T27** | relative-floor window | 1.5×median, session-start window | time-of-day profile (built, default-OFF) | §2.1: third reproduction — peak 47,482.50 ≈ **p91**, pre-11:00 share **40.1%** (43% / 28.9% priors) | STRUCTURAL — ledger G10 | **OPEN; arming rec unchanged (NO)** |
| **T28** | `atmIv` freshness / `iv_abs_band` redefinition | frozen daily stamp | intraday operand `(ceIvAvg6+peIvAvg6)/2` | 6th one-distinct-value session (0.114471); dot 95/95 free 0.8; probe labels it correctly | STRUCTURAL — ledger G12 | **OPEN, redefinition half** |
| **T3** | `iv_pair` | 0.02 gap | drop or redefine | 0/835, 10th zero session; G13 arithmetic stands | STRUCTURAL — ledger G13 | **OPEN (owner)** |
| **T23** | partial-bucket tolerance 650 absolute | 2 WARNs (37/12 lots) | scale with bar size | §4: benign shape, still alarms | STRUCTURAL — ledger G9 | **OPEN** |
| **T1** | `relativeVolumeMultiplier` 1.5 | — | — | §5.0: volume-floor sole-blocker legs 1W/2L, −2.40 pts (stop model) — **sixth consecutive no-pay** | REJECTED | **REJECTED — reconfirmed** |
| **T7** | composite threshold 0.600 | — | — | `composite-055` took 0 rows (0 eligible) — no new evidence; prior rejection stands | REJECTED | **REJECTED — carried** |
| **NEW watch** | `strike-pick` on post-BSE-expiry Fridays | — | none (correct behaviour) | §2.2: 07-24 + 07-31 both Fridays after Thu BSE expiry, 550/374 sensex-only fails; 0 counter-instances | REGIME (fresh-chain hypothesis) | **WATCH — one more Friday settles it; no tune** |
| **NEW watch** | `oi_spurt` quadrant conjunct | floors (15, 3) | none yet | §3: 0/835 with magnitude ≥15 on 178 rows — conjunct-starved on chop; was 4.9%/8.5% prior sessions | REGIME (1 session) | **WATCH** |
| T2 | `iv_rank` dot | NULL 100% | source or drop | 13th session | STRUCTURAL | PROPOSED (carried) |
| T10 | stale OPEN paper positions | **17** OPEN | square off / age out | unchanged (11 minervini, 6 manas-arora); 0 scalper | ops | **OWNER — chronic** |
| T14 | margin invariant | — | sign-aware | 0 self-contradicting rows (composite-pass ∩ composite-blocked = 0) — 3rd clean | STRUCTURAL (diagnostic) | PROPOSED (carried) |
| T8/T26 | latency | p50 1:20.7 / emit mean 16.7 s (n=17) | stamp at bar close / measure | structurally unchanged, 4× sample on emit | STRUCTURAL — ledger G8 | OPEN (data) |
| **T15** | engine boot-line durability (V046) | — | — | ✅ **VERIFIED this session** — `engine_reloads` carries both boot rows (0/38 → 38/0, the F10 shape) AND the midday 12:14 recovery reload (38/0/0) | — | ✅ **VERIFIED — closes the "shipped-unverified" carry** |
| T16/T12/T19/T22/T24/T21/T6/T17+T13/T20/T25 | — | — | — | §2.1 (38/38 armed, 0 flat); §4 (369/375 OI — circuit-window dip, not regression; 0 misaligned ×7); §2.3 (volume dot 31.4%); §5.3 (paper book live) | — | ✅ remain CLOSED |

**Group G movement this session: G11 UNBLOCKED (chop observation arrived — owner-decidable, evidence
favours keeping the stop); G16 continues; G10 third reproduction (rec NO unchanged); G12/G13/G9/G8
unchanged; G6 reconfirmed. T15 verified.**

## 8 Honesty caveats

- **The rejection stream ends at 13:34 by DISCIPLINE FREEZE, not by tape or fault** — every §3 table
  is a partial session (09:19–13:34). The engine is proven alive to the close (gauges, eval grid,
  `chart-gate-failed` to 14:57).
- **§5.0/§5.2's models omit costs, `signal_exit` and the trailing SuperTrend**; 3-min LTP granularity
  can miss 1-minute bracket touches. A ~1% round-trip cost (~2 pts/leg) leaves the stop model
  comfortably positive (+~152) and takes the hold model negative — stated, not silently folded in.
- **§5.2(b)'s matched events are morning-selected** by the champion book's one-OPEN-per-strategy
  dedup — not a random sample of the day.
- **One chop day is one observation.** It spends G11's gate; it does not make the answer permanent.
  If the owner changes the exit, T1/T7/G13/G10 must be re-run per the standing rule.
- **§2.2's fresh-chain hypothesis has two conforming instances and no counter-instance — it is a
  hypothesis**, promoted to watch, not to rule.
- The 09:24 matched-event stop-model value (−10.45) was priced by hand from the 09:54 snapshot; it is
  not in the §5.0 table (that table is the §3.5 wf set, which starts 11:15).
- Champion's +₹10,697.52 is **6 independent events, not 18** (§5.1); the all-time league inherits
  fan-out inflation as always.
- This run was **read-only against the live stack**: SELECTs, `docker logs`, `docker inspect`,
  in-container actuator/health GETs. No restart, deploy, write, or config change. **No strategy knob
  was altered.** Docs edits in this PR: findings + rollup + README §3.31 + ledger annotations
  (G11 status addendum, G16 cross-check, one historical-narrative line annotated).
