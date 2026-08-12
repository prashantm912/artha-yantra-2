# Session findings — 2026-07-30 (data date)

Analysis date: 2026-07-30 (scheduled post-market agent, ran 15:55–16:35 IST).
Analyst: Claude (scheduled `session-analysis post`).
Data: `signal_rejections` rows **1,118** (bar times 09:18–15:18), signals **4** (2 ENTRY + 2 EXIT),
paper positions opened **0**, shadow positions opened **25** (champion; all closed).

**2026-07-30 is a Thursday and a BSE MONTHLY index expiry** (last Thursday of July; NSE monthly was
Tuesday the 28th). Per README §3.19 the S24 OI suppression is **PER OI ROOT** — and it did **not** bite
this session, because every context-bearing row read the **`NIFTY 50`** root (814/814), including all 16
`sensex-niftyoi` slugs, which read NIFTY OI by design. The OI bloc is fully live (0 NEUTRAL quadrants,
0 null spurt pcts). **The BSE expiry landed on the EXECUTION root instead, through `strike-pick`** —
that is this session's clearest finding and a new method dimension (§6.2 → README §3.27).

**Signal contract: `NFO:NIFTY26AUGFUT@3m`, confirmed DIRECTLY from the engine log** (1,125 occurrences
of `NFO:NIFTY26AUGFUT` in the rail lines, **zero** of any other future). Unchanged since the 07-27
roll, so volume comparisons against 07-27/07-28/07-29 are like-for-like.

**Session regime (§3.25 / G15): `mixed`, efficiency 0.434.** `NIFTY 50` open **24,249.55** → close
**24,317.15** (+0.28% intraday), high 24,342.95, low 24,187.10, range 0.64%,
`|close−open|/(high−low)` = **0.434** ⇒ `mixed` (cut 0.29–0.61). ⚠️ **NOT the `chop` day ledger row
G11 is BLOCKED-DATA on** — the stamp exists precisely so this is announced rather than assumed, and it
says "keep waiting". G11 nevertheless gains its **first counter-signed datapoint** this session (§5.2).

This file folds in the two earlier read-only runs of the same date —
[`2026-07-30-open-gate.md`](2026-07-30-open-gate.md) (PASS) and
[`2026-07-30-midday-liveness.md`](2026-07-30-midday-liveness.md) (PASS) — and discharges the midday
file's §10 carry-list in §6.6.

---

## 0 Read this first — the session's headline

1. **`strike-pick`'s expiry mechanism is now PROVEN by a matched root-swap control, and the root is the
   discriminator.** 405 fails, **every single one on a `sensex-niftyoi` slug** (16 of 16), reason
   `no strike met the delta/premium band`; **zero** on any NIFTY-rooted slug. Across three consecutive
   sessions: 07-28 (NSE monthly) **534, all NIFTY-rooted**; 07-29 (non-expiry) **ZERO**; 07-30 (BSE
   monthly) **405, all SENSEX-rooted**. The 07-29 file closed this carry as "no-longer-observable,
   expiry-chain cause consistent but untested" — it is now tested and it holds (§2.1). ⚠ One session
   does not fit: 07-24 was a Friday with no expiry and 550 sensex-rooted fails, so the honest claim is
   *an expiry day saturates the expiring root's `strike-pick`*, **not** *only expiry causes it*.
2. **`breadth` (w 1.0, REQUIRED) read 0/814 on a fully live, moving input — and it is a session-level
   step function, not a dot.** The rule is `advances/declines > 32`; today's `advances` ran **23–32**
   (10 distinct values, 0 nulls, 0 zero-pairs), max **exactly 32** against a strictly-greater test —
   the **second** session with max exactly 32 (07-28 was the first). Over 8 sessions the dot is 0% on
   five, ~100% on two and 0.2% on one — **never in between**, because breadth is a market-wide scalar
   shared by every row of a session. It costs **1.0 of the 18.80 denominator = 5.3 pp of composite
   headroom on every row**, on 5 of 8 sessions. New row **T30** (§3.2).
3. **G11 gets its first counter-signed observation, and the sign FLIPS.** On the **6 matched
   `(bar, leg, entry_ltp)` events** where both exit models resolve, hold-to-15:12 (the champion book's
   `SQUARE_OFF` rows) returns **−88.50 pts** while the 30-minute `time_stop` model returns **−72.87
   pts** — the time stop was **better by +15.63 pts**, the opposite sign to 07-29's reading. The −25%
   premium stop also fired for real for the first time in a while (**8 `STOP_LOSS` closes**) and cut the
   two worst legs (§5.2). ⚠ This is a `mixed` day, not the `chop` day G11 needs; it narrows the row, it
   does not unblock it.
4. **The champion shadow book had its WORST session on record: 25 closes, 2W/23L, −839.65 pts,
   −₹58,233.05**, taking the book all-time −19,892.76 → **−78,125.81**. ⚠ **Dedupe before quoting it
   (§3.24):** the 25 rows collapse to **9 distinct `(bar, leg, entry)` events**, and the **10:30
   (−₹26,738.46) and 12:33 (−₹22,005.48) clusters carry 84% of the loss** — the same fan-out that made
   07-29's "best session ever" essentially one bar makes today's worst session essentially two.
5. **Both fires were structurally unfundable — #1075's evidence base moves to 6 of 14.** The two
   12:33 ENTRY fires resolved `NIFTY2680424100CE` at ₹272.85 × lot 65 = **₹17,735 > the ₹15,000
   budget**; `suggested_qty` NULL on both, status `EXPIRED`, **zero paper positions this session**
   (§5.3). Cumulative since the book started firing: **6 of 14 fires unfunded, every one NIFTY-rooted.**
6. **The counterfactual loses again — the fifth consecutive measurement.** 13 resolvable would-have-fired
   legs: **2W/11L, −231.87 pts**. Every rail's set loses except `hero-zero` (+41.18 on 4 legs), and that
   one is a single BSE-expiry-afternoon SENSEX lottery leg (§5.0). The standing prior holds.
7. **G10/T27's mechanism reproduces but at roughly a third of 07-29's severity.** The opening
   relative-volume threshold peaked at **48,993.75** (09:45) = **p92** of the session's own 3m
   distribution, against 07-29's 133,185 = p98.4; pre-11:00 share of `volume-floor` blocks
   **166/574 = 28.9%** against 07-29's 43% (§2.2).
8. **G12's frozen-operand probe (#1111) is LIVE and reads correctly at EOD** — `dot-health` labels
   `fii` (9.62) and `iv_abs_band` (0.112743) `frozen BY DESIGN — EOD daily operand`, on **18 bars**,
   comfortably past `MIN_FROZEN_BARS = 8`. First EOD confirmation of the shipped probe (§4).
9. **Every liveness oracle is clean.** `confluence-blocked` = **1,118** = the rejection row count
   exactly; `fired` = **2** = the ENTRY count exactly; eval grid **375/375**; failures **0**; both
   liveness gauges non-negative and mutually equal; `subscriber_health_events` **empty**; **0 ERROR**
   lines in both services; **0** misaligned candles (6th clean session); OI capture **375/375** minutes;
   clock drift **0.25 s**. ⚠ `RestartCount` is **1** (was 0) — two boots, both pre-open (§1).
10. **T23 at its quietest ever: 2 WARNs** (6 on 07-29, 10 on 07-28, 37 on 07-24), both exact ×65
    multiples on consecutive buckets (§6.1).

## 1 Funnel numbers (§3.1–3.2)

| metric | 2026-07-27 | 2026-07-28 | 2026-07-29 | **2026-07-30** |
|---|---|---|---|---|
| rejections | 1,253 | 1,350 | 1,293 | **1,118** |
| distinct strategies emitting | 38 | 36 | 34 | **38** ✅ |
| published + enabled | 44 | 44 | 44 | **44** (38 scalper + 6 swing) |
| **coverage ratio (§3.10)** | 38/38 | 36/38 | 34/38 | **38/38** — set-difference EMPTY |
| signals | 3 ENTRY + 3 EXIT | 0 | 12 ENTRY + 8 EXIT | **2 ENTRY + 2 EXIT** |
| paper positions opened | 0 | 0 | 4 | **0** (both fires unfundable — §5.3) |
| bar-time coverage | 09:18–15:18 | 09:18–14:57 | 09:18–15:12 | **09:18–15:18** |
| scored rows | 909 | 1,068 | 983 | **814** |
| composite ≥ threshold rows | 253 | 0 | 311 | **118 (14.5%)** |
| max composite | 0.8511 | 0.4521 | 0.9118 | **0.8627** |
| **max ACHIEVABLE composite** | 0.9043 | 0.5479 ⚠ | 0.9574 | **0.9118 / 0.9043** (§3.1) |
| regime (§3.25) | mixed 0.563 | chop 0.163 | mixed 0.501 | **mixed 0.434** |

**Eval counters (actuator :8082, read 15:59 IST).** The container booted **2026-07-30 08:05:55 IST**
(`StartedAt 2026-07-30T02:35:55Z`) — before the open, with **`RestartCount 1`** and a second boot line
at 01:03 IST (`2026-07-29T19:33:53Z`). Both boots precede the session, so the cumulative counters ARE
today's session totals:

| outcome | 2026-07-30 |
|---|---|
| `chart-gate-failed` | **2,068** |
| `confluence-blocked` | **1,118** |
| `composite-below-threshold` | **360** |
| `fired` | **2** |
| `discipline-paused` / `unscoreable-indicators-warming` / `confluence-gate-absent` | 0 |
| **Σ** | **3,548** |
| `ay_signal_eval_failures_total` | **0** |

`confluence-blocked` = **1,118** = today's rejection row count **exactly**, and `fired` = **2** = the
ENTRY-signal row count **exactly**. Two independent reconciliations.

`ay_signal_eval_duration_seconds_count` = **375** — the complete 09:15→15:29 minute grid, a third
consecutive full grid. Sum 1,773.80 s ⇒ mean **4.73 s**/eval (07-29: 5.15 s; 07-28: 5.54 s) — the
three-session upward drift is now fully reversed.

**Liveness gauges at 15:59 IST:** `ay_signal_bar_received_age_seconds` = **1734.219**,
`ay_signal_bar_evaluated_age_seconds` = **1734.22** — non-negative, mutually equal, consistent with the
15:30 close. The last bar received was also evaluated.

**Engine load state.** `02:36:13Z main: loaded 0 published strategies (38 dropped on an unresolved
universe)` → `02:37:38Z signal-eval: loaded 38 published strategies (0 dropped, 0 failed)` — the
documented F10 cold-start shape, self-healed in **85 s** at boot, pre-open. **`unresolved == 0`** is the
health signal and it is met.

**First-blocking-rail histogram** (1,118 rows, **17** distinct rails):

| rail | n | avg operand | avg threshold | avg margin |
|---|---|---|---|---|
| volume-floor | **574 (51.3%)** | 9,829.2 | 18,998.9 | −9,169.8 |
| time-window | 240 (21.5%) | — | — | — |
| rsi-band | 88 | 47.27 | — | — |
| time-of-day-preference | 34 | — | — | — |
| option-side-constraint | 30 | — | — | — |
| divergence-vol-gate | 24 | 39,070.4 | — | — |
| volume-pump | 22 | 40,382.7 | — | — |
| pct-price-move | 22 | 0.277 | 1.000 | −0.723 |
| two-candle | 22 | — | — | — |
| confluence-composite | 16 | 0.561 | 0.600 | −0.089 |
| oi-cross-required | 16 | 132.48 | — | — |
| **strike-pick** | **10** | — | — | — |
| hero-zero | 6 | — | — | — |
| directional-change-gate | 6 | 0.713 | — | — |
| morning-eod-precondition | 4 | — | — | — |
| call-put-delta-filter | 2 | 43.62 | 50.00 | −6.38 |
| supertrend-15m | 2 | — | — | — |

`volume-floor`'s share fell to **51.3%**, the lowest reading in this folder (58.5% / 56.3% / 61.3% on
the three prior sessions). `confluence-composite` stays a near-miss first-blocker (avg margin −0.089,
16 rows).

**All-failed-rails expansion (§3.3)** — top 20:

| rail | policy | fails | avg operand | avg threshold |
|---|---|---|---|---|
| confluence-composite | FAIL_CLOSED | **736** | 0.402 | 0.600 |
| volume-floor | FAIL_CLOSED | **574** | 9,829.2 | 18,998.9 |
| **strike-pick** | FAIL_CLOSED | **405** | — | — |
| rsi-band | FAIL_CLOSED | 274 | 45.86 | — |
| time-window | FAIL_CLOSED | 240 | — | — |
| divergence-vol-gate | FAIL_CLOSED | 130 | 18,726.0 | — |
| trend-change | FAIL_CLOSED | 130 | — | — |
| two-candle | FAIL_CLOSED | 110 | — | — |
| volume-pump | FAIL_OPEN | 110 | 19,693.8 | — |
| pct-price-move | FAIL_OPEN | 110 | 0.233 | 1.000 |
| oi-cross-required | FAIL_CLOSED | 86 | 113.13 | — |
| oi-divergence-magnitude | FAIL_CLOSED | 86 | 9.197 | 20.000 |
| directional-vix-gate | FAIL_OPEN | 82 | 12.161 | — |
| open-high-low | FAIL_CLOSED | 62 | — | — |
| directional-change-gate | FAIL_CLOSED | 62 | 0.076 | — |
| rising-volume | FAIL_CLOSED | 62 | 12,180.2 | — |
| constituent-gate | FAIL_OPEN | 48 | 1.140 | — |
| oi-slope-agree | FAIL_CLOSED | 36 | −0.182 | — |
| time-of-day-preference | FAIL_CLOSED | 34 | — | — |
| oi-interval-and-60m-trend | FAIL_OPEN | 34 | −0.294 | — |

⚠ **`confluence-composite` overtakes `volume-floor` as the most-failed rail (736 vs 574)** — a shape not
seen since the 07-28 expiry. Avg failing composite **0.402** against 0.600. Cause is §3.2: the
`breadth` dot alone removes 5.3 pp of headroom from every row, and the directional dots (`vix` 37.6%,
`basis` 62.4%) split on a two-sided tape where 07-29 had them at 98.8%.

✅ **No new rail appeared.** The four rails absent from 07-29's top-20 (`directional-vix-gate`,
`oi-interval-and-60m-trend`, `morning-eod-precondition`, `supertrend-15m`) all have prior sessions in
the 07-27…07-29 window — they were below that file's cut, not new.

### 1.1 Coverage — 38 of 38, set-difference EMPTY

Every enabled scalper emitted at least one row. This ties the best readings in the folder (07-23,
07-27) and reverses 07-29's four silent `-pe` slugs, which is what a two-sided tape (530 CE / 314 PE
scored rows) should produce. Boot line: `loaded 38 published strategies (0 dropped on an unresolved
universe, 0 failed to load)` ⇒ not a T9 load question in either direction.

### 1.2 Interior coverage (§3.11) — 25/25 buckets populated

| bucket | 09:15 | 09:30 | 09:45 | 10:00 | 10:15 | 10:30 | 10:45 | 11:00 | 11:15 | 11:30 | 11:45 | 12:00 | 12:15 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| n | 8 | 8 | 16 | 4 | 68 | 88 | 64 | 56 | 68 | 86 | 50 | 46 | 58 |
| bucket | 12:30 | 12:45 | 13:00 | 13:15 | 13:30 | 13:45 | 14:00 | 14:15 | 14:30 | 14:45 | 15:00 | 15:15 | |
| n | 58 | 70 | 32 | 20 | 28 | 70 | 60 | 50 | 32 | 64 | 10 | 4 | |

Every 15-minute bucket from 09:15 to 15:15 is populated — **no interior hole**. The thin 09:15–10:00
buckets are the pre-09:45 trade window (4 slugs in-window) plus the 10:00 bucket's 4 rows; the thin
15:00/15:15 buckets are window narrowing. All 375 eval cycles completed regardless, and
`subscriber_health_events` is **empty**.

## 2 Rail findings (§3.3 / §3.5 / §3.8)

### 2.1 ✅ NEW — `strike-pick`'s expiry mechanism is PROVEN by a matched root-swap control

`strike-pick` failed **405** times, and the attribution is total:

| root family | slugs failing | fails |
|---|---|---|
| `sensex-niftyoi` (all 16, CE and PE) | **16 of 16** | **405** |
| NIFTY-rooted | 0 of 22 | **0** |

Reason on every row: `no strike met the delta/premium band` (`failPolicy: FAIL_CLOSED`, operand and
threshold both null). Per-slug range 7–41 fails, bar times 09:45–14:27.

**The three-session control:**

| session | expiry | strike-pick fails, NIFTY-rooted | strike-pick fails, sensex-rooted |
|---|---|---|---|
| 2026-07-27 (Mon) | none | 175 | 89 |
| 2026-07-28 (Tue) | **NSE MONTHLY** | **534** | **0** |
| 2026-07-29 (Wed) | none | **0** | **0** |
| **2026-07-30 (Thu)** | **BSE MONTHLY** | **0** | **405** |

The expiring root's option chain is what fails the delta/premium band, and the non-expiring root's is
untouched. 07-29's zero is the negative control between them. **This discharges the four-session
"un-bucketed by slug/time" carry that 07-29 closed as no-longer-observable** — it is now bucketed, and
the bucket is the root.

⚠ **One session does not fit and is recorded rather than smoothed over:** 2026-07-24 was a Friday with
no expiry on either exchange and **550** sensex-rooted `strike-pick` fails. So the mechanism is
*an expiry day saturates the expiring root's `strike-pick`*, not *only an expiry can*. A thin or
freshly-rolled chain is an unexcluded alternative for the 07-24 case; no code read was done this run.

**Tuning consequence:** none. `strike-pick` failing on an expiring chain is the gate declining to buy a
contract with no usable delta/premium — correct behaviour. What it *does* mean is that **a BSE monthly
expiry mutes the sensex-rooted half of the fleet through the execution path**, which belongs in the
same "this session is REGIME for that family" bucket as §3.19's OI suppression (§6.2).

### 2.2 G10 / T27 — the opening-surge mechanism reproduces, at ~⅓ of 07-29's severity

**Registry state, standing §3.14 query:**

| carries `relative-volume-floor` | published on | count |
|---|---|---|
| yes | 2026-07-28 | **38** |
| no | — | **0** |

**38 of 38 armed**; `count(*) WHERE blocking_threshold = 125000` = **0**; observed threshold range
**9,945 – 48,993.75**. T16 stays closed.

**Ground truth on `NIFTY26AUGFUT`, 3m rollup, minute-aligned bars only (§3.15):**

| bars | min | p25 | p50 | p90 | p99 | max | bars ≥125,000 |
|---|---|---|---|---|---|---|---|
| 125 | 2,665 | 7,800 | **13,715** | 40,105 | 121,290 | **122,720** | **0** |

**Threshold time series** (per bar, identical across every armed slug):

| IST | 09:45 | 10:15 | 10:21 | 10:27 | 10:39 | 10:51 | 11:03 | 11:15 | 11:30 | 11:48 |
|---|---|---|---|---|---|---|---|---|---|---|
| threshold | **48,993.75** | 38,220 | 28,957.5 | 25,935 | 23,351.25 | 22,863.75 | 20,670 | 18,476.25 | 11,992.5 | 9,945 |
| avg operand | 14,625 | 18,720 | 9,165 | 11,180 | 14,105 | 14,820 | 3,250 | 3,445 | 8,970 | 9,685 |

Place the peak on the session's own distribution: **48,993.75 is exceeded by 10 of 125 bars (p92.0)**,
against 07-29's 133,185 at **p98.4**. The first ten 3m buckets were **121,290 / 56,160 / 86,320 /
87,555 / 32,370 / 37,115 / 18,070 / 36,270 / 30,680 / 14,625** — four ≥50,000 but **none ≥100,000**,
where 07-29 had four ≥100,000. A milder surge ⇒ a milder over-tightening, exactly as the mechanism
predicts.

**Consequence, measured:** **166 of the 574 `volume-floor` blocks (28.9%) occurred before 11:00**,
against 07-29's 43%. G10's mechanism is confirmed on a second session with a **quantitatively
different** severity — which is itself the argument that it is surge-driven rather than a constant
mis-calibration. **G10's arming recommendation is unchanged (NO)**; today adds severity variance, not
a P&L case.

### 2.3 The `volume` dot post-#1082, second live session

`volume` supported **240 of 814 (29.5%)**, up from 23.1% on 07-29, on a session where **ZERO of 125 3m
bars cleared the pre-fix static 125,000 floor** (session max 122,720). Under the pre-#1082 code path the
dot could not have supported on a single row. That is a second, independent, data-side confirmation of
G6/T24's fix — stronger than 07-29's, because the static floor is unreachable today rather than merely
rare. Jar-side check per §3.23: the deployed `dot-health` payload carries the `frozen` field shipped by
#1111 (§4), so the running artifact is the current one.

### 2.4 Rails with no evidence of miscalibration

`rsi-band` fails 274 with avg operand **45.86** — that is the *floor* side biting on a two-sided tape
(07-29's 64.74 was the exhaustion cap on an up-day), the design behaving in the other direction.
`pct-price-move` (0.233 vs 1.000), `volume-pump` and `constituent-gate` (both FAIL_OPEN),
`psar-durability` (0.031 vs 0.050), `call-put-delta-filter` (30.74 vs 50.00) and
`oi-divergence-magnitude` (9.197 vs 20.000, a live operand at ~46% of threshold) all read plausibly.
`max-oi-sr-gate` is absent from the failing table entirely this session, after 81 fails on 07-29.

## 3 Composite + dots (§3.4 / §3.6)

**Composite histogram** (814 scored rows):

| bucket | 0.1 | 0.2 | 0.3 | 0.4 | 0.5 | 0.6 | 0.7 | 0.8 |
|---|---|---|---|---|---|---|---|---|
| n | 15 | 93 | 361 | 79 | 148 | 60 | 45 | 13 |
| CE | 15 | 63 | 209 | 57 | 86 | 36 | 29 | 13 |
| PE | 0 | 30 | 152 | 22 | 62 | 24 | 16 | 0 |

Max **0.8627**; **118 rows (14.5%) at or above the 0.600 threshold** (07-29: 31.6%). The distribution's
mass has moved DOWN a full bucket — 361 rows in the 0.3 bucket against 07-29's 190 — and the mechanism
is §3.2 plus the two-sided tape. **The population is genuinely two-sided** (530 CE / 314 PE scored,
against 07-29's 971/12), which is the first balanced session since 07-24.

**Dot support rates:**

| dot | w | supports | % | read |
|---|---|---|---|---|
| `breadth` | 1.0 | **0/814** | **0.0** | ⚠⚠ **NEW T30 — live, moving operand, never crosses `>32`. §3.2** |
| `iv_pair` | 0.8 | **0/814** | **0.0** | ⚠ structurally impossible (G13); in Σw, so it costs headroom |
| `iv_rank` | 0.8 | 0/814 | 0.0 | dead-data, **withheld from Σw** (#676) — 12th session |
| `oi_spurt` | 1.0 | 40/814 | 4.9 | alive (8.5% on 07-29) — #991 floors holding |
| `vwap` | 2.5 | 118/814 | 14.5 | alive (16.3% on 07-29) — the #990 ≥15 bps condition behaving |
| `rsi` | 1.0 | 166/814 | 20.4 | alive; 55.3% on 07-29 — the two-sided tape |
| `trending_cross` | 1.0 | 186/814 | 22.9 | ✅ alive, best reading yet (5.8% on 07-29) |
| `iv_slope` | 0.8 | 35/119 | 29.4 | alive |
| `volume` | 1.0 | **240/814** | **29.5** | ✅ highest ever — §2.3, G6's fix on an unreachable static floor |
| `vix` | 1.0 | 306/814 | 37.6 | regime (98.8% on 07-29) — a direction dot on a two-sided tape |
| `futures_oi` | 1.5 | 425/814 | 52.2 | ✅ live |
| `underlying_oi` | 1.0 | 427/814 | 52.5 | ✅ live |
| `drastic_oi` | 1.0 | 476/814 | 58.5 | alive (86.6% on 07-29) |
| `sentiment` | 1.0 | 498/814 | 61.2 | |
| `basis` | 1.0 | 508/814 | 62.4 | regime (98.8% on 07-29) |
| `sentiment_slope` | 1.0 | 578/814 | 71.0 | |
| `premium_skew` | 1.0 | 16/22 | 72.7 | 2nd sighting; 22 rows — watchlist, not a calibration base |
| `psar` | 1.0 | 682/814 | 83.8 | |
| `vwma` | 1.0 | 750/814 | 92.1 | |
| `iv_abs_band` | 0.8 | **119/119** | **100.0** | ⚠ FREE dot on a FROZEN input (G12) — now correctly LABELLED (§4) |
| `supertrend` | 1.0 | 814/814 | **100.0** | 5th 100% session in the folder |

### 3.1 Dead-weight cap

Reconciled against the session's top row (`id 14624`, `scalp-connect-the-dots-sensex-niftyoi`):
20 dots, Σw **21.20**, supporting weight **17.60**, stored composite **0.8627** ⇒ denominator
**20.40** = 21.20 − 0.80, i.e. `iv_rank` withheld exactly as #676 specifies.

- **20-dot IV roster** (119 rows): dead-in-denominator = `iv_pair` 0.8 + `breadth` 1.0 = 1.8 ⇒
  cap **(20.40 − 1.80)/20.40 = 0.9118**.
- **18-dot majority roster** (814 rows): Σw 19.60, denominator 18.80, same 1.8 dead ⇒
  cap **17.00/18.80 = 0.9043**.

Observed max **0.8627 = 94.6% of the applicable cap**. **No structural starvation** — unlike 07-28, the
gate had headroom and the market decided the outcome. ⚠ But `breadth` alone costs **1.0/18.80 = 5.3 pp**
of that headroom on every row, which is the difference between today's 14.5% pass rate and 07-29's
31.6% being a market reading versus a partly mechanical one (§3.2).

### 3.2 ⚠⚠ NEW (T30) — `breadth` is a session-level step function on a live, moving operand

`breadth` (weight **1.0**, and the canary's only `required: true` non-OI probe) supported **0 of 814**
rows. The input is emphatically healthy: `advances` **0 nulls**, **0 zero-pairs**, **10 distinct
values**, range **23–32** on a 50-constituent index.

The dot's own reason string is the rule: **`advances/declines > 32`**. Today's session maximum is
**exactly 32**, against a strictly-greater test — a one-constituent near-miss, and **the second such
session** (07-28's max was also exactly 32; 07-21's was 31).

**The cross-session shape is the finding, not today's zero:**

| session | advances min–max | rows with `advances > 32` | `breadth` support |
|---|---|---|---|
| 2026-07-21 | 19–36 | 2 / 1,070 | 0.2% |
| 2026-07-22 | 5–14 | 0 / 828 | 0.0% |
| 2026-07-23 | 14–26 | 0 / 1,120 | 0.0% |
| 2026-07-24 | 3–21 | 0 / 1,100 | 0.0% |
| 2026-07-27 | 35–46 | **909 / 909** | ~100% |
| 2026-07-28 | 23–**32** | 0 / 1,068 | 0.0% |
| 2026-07-29 | 35–42 | **983 / 983** | ~100% |
| **2026-07-30** | 23–**32** | **0 / 814** | **0.0%** |

**Eight sessions, and the dot is 0% on five, ~100% on two and 0.2% on one — never in between.** The
mechanism is structural: `advances` is a market-wide scalar shared by every row of a session and moves
slowly, so a fixed threshold makes the dot an all-or-nothing per-session term. Weight 1.0 of an 18.80
denominator is therefore not a discriminator between bars — it is a **per-session bias of ±5.3 pp on
every composite**.

**Classification.** This is the **same family as G12's frozen `atmIv`** — a per-session step function —
but with a *moving* operand, so it is a **fourth dot state: live, moving, and never crossing**. Neither
`DotHealthCanary`'s alive/dead test (input is live) nor #1111's frozen test (10 distinct values) can
see it; the canary reports `alive=true, frozen=false, required=true`, which is correct on both axes and
still misses the finding.

**Not established this run:** whether `>32` is deliberate (a "majority-plus-buffer" breadth read) or
inherited, and where the constant lives. No code read of the breadth scorer was done — T30 names the
observation and the arithmetic, not the cause. ⚠ Note T18 was CLOSED on 07-22 as "regime" after a
96.6% session; that closure was correct *for the question asked then* (is the input dead?). T30 is a
different question (is the threshold a per-session coin flip?) and should not be filed as a T18 reopen.

### 3.3 `iv_abs_band` / `iv_pair` — G12 and G13 unchanged, both now correctly instrumented

`atmIv` carries **exactly one distinct value** again (**0.112743**, a fifth consecutive session), and
`iv_abs_band` is 119/119 = 100% because that stamp sits inside the 0.10–0.12 band. ✅ **The difference
from 07-29 is that the system now SAYS so** — #1111's probe labels it `frozen BY DESIGN — one value
(0.112743) across 18 bars (EOD daily operand — correct, not an outage)`. G12's diagnosis half is
discharged in the read surface; the redefinition half stays open.

Neighbouring IV operands all moved normally (`ceIvAvg6` 38 distinct, `peIvAvg6` 41, `vixLevel` 18,
`premiumSkewPct` 78, `futuresBasis` 75), so the freeze remains ONE field. `iv_pair` is 0/814, as G13's
put-call-parity finding predicts; no new evidence either way.

## 4 Data health (§3.7)

| field | 2026-07-28 | 2026-07-29 | **2026-07-30** | class |
|---|---|---|---|---|
| `futuresQuadrant` / `underlyingQuadrant` | 1,068/1,068 NEUTRAL (S24) | 0/983 | **0 / 814 NEUTRAL** | ✅ fully live — the BSE expiry did NOT suppress (§6.2) |
| `spurtOiPct` / `spurtPricePct` | 1,068/1,068 NULL (S24) | 0 null | **0 / 814 NULL** | ✅ live |
| `futuresBasis` | LIVE | 983/983 | **814/814 LIVE** (75 distinct) | ✅ |
| `advances` / `declines` | 0 zero-pairs, max 32 | 0 zero-pairs, 35–42 | **0 zero-pairs, 0 nulls, 23–32** | ⚠ **input HEALTHY, dot 0% — T30, §3.2** |
| `fiiLongPct` | 8.78 on 1,068 | 0 nulls | **0 nulls / 814** (9.62, frozen by design) | ✅ #1050 holds a 3rd session |
| `atmIv` | 1 distinct | 1 distinct | **1 distinct (0.112743)** | ⚠ frozen — G12, now LABELLED |
| `ivRank` | NULL 100% | NULL 100% | **NULL 814/814** | dead-data (carried since 07-02) |
| `dowUp` | NULL 100% | NULL 100% | **NULL 814/814** | by design (un-armed) |

**The 304 context-less rows reconcile exactly**: 240 `time-window` + 34 `time-of-day-preference` + 30
`option-side-constraint` = **304** = 1,118 − 814. Every context-bearing row carries live macro data.

**Capture (minute-aligned bars only):**

| series | bars | misaligned | day range | last bar |
|---|---|---|---|---|
| **`NIFTY26AUGFUT` 1m (today's signal series)** | **375** | **0** | 24,250.00–24,397.00 | 15:29 |
| `NIFTY26SEPFUT` 1m | 375 | 0 | 24,376.40–24,510.00 | 15:29 |
| `NIFTY 50` 1m | 375 | 0 | 24,190.00–24,342.95 | 15:29 |
| `SENSEX26JULFUT` 1m (BFO) | 375 | 0 | 77,423.00–77,971.70 | 15:29 |
| `SENSEX` 1m (BSE) | 375 | 0 | 77,458.76–77,991.35 | 15:29 |
| `futures_oi_snapshots` | 25,875 rows / **375** distinct minutes | — | — | 15:30 |

✅ **T19 quiet for a SIXTH consecutive session** — the §3.15 misaligned-bucket probe returns the empty
set session-wide, for any source.
✅ **T12 holds at its best reading — 375 of 375 OI minutes (100%)**, matching 07-29.
✅ **`SENSEX26JULFUT` at 375/375**, its best in the folder (372 on 07-29) — on its own expiry day.

**`dot-health` canary at 15:59 IST** (200 scanned / 40 context-bearing, 18 operand-bearing bars):

```
breadth        alive=true  required=true  frozen=false
iv_rank        alive=false required=false frozen=false   input dead across 40 context-bearing rejections
dow            alive=false required=false frozen=false   input dead across 40 context-bearing rejections
fii            alive=true  required=false frozen=TRUE    frozen BY DESIGN - one value (9.62) across 18 bars (EOD daily operand)
oi_spurt_price alive=true  required=false frozen=false
vix            alive=true  required=false frozen=false
iv_abs_band    alive=true  required=false frozen=TRUE    frozen BY DESIGN - one value (0.112743) across 18 bars (EOD daily operand)
futures_oi     alive=true  required=true  frozen=false
underlying_oi  alive=true  required=true  frozen=false
```

**Dead set = the standing pair `ivRank` + `dowUp`. Nothing newly dead.** ✅ **This is the first EOD read
of #1111's frozen probe and it is correct on both counts** — `fii` and `iv_abs_band` are the two EOD
daily operands, both labelled `BY DESIGN`, both on **18 bars** (past `MIN_FROZEN_BARS = 8`, so the
verdict is evidenced rather than defaulted, per README §4.1). ⚠ The probe registry still has **no
`breadth`-threshold dimension** — see §3.2 for why no alive/dead/frozen test can reach T30.

**Feed + ingest.** `GET /api/v1/market/health/data` → `{"status":"GREEN","tickedTokens":69,
"problems":[]}`. `GET /api/v1/market/health/ingest` (07-16 → 07-29, 10 trading days): `NSE_FII_DII`
**GREEN** (0 missing), `NSE_PARTICIPANT_OI` **GREEN** (0 missing).

**Error channels.** `ay-strategy-signal-service`: **0 ERROR** lines all session.
`ay-market-data-service`: **0 ERROR** lines. `strategy.subscriber_health_events`: **0 rows**.

**Host-clock guard (B8).** Host UTC `2026-07-30T10:30:58.380` vs container `now()` `10:30:58.626` —
**~0.25 s** apart (0.1 s at the open gate, 0.4 s at midday). No drift. B8 remains a free-running-CMOS
watch item.

## 5 Shadow-book outcomes + the counterfactual

### 5.0 The README-§4.2 counterfactual — 13 resolvable legs, 2W/11L, −231.87 pts

⚠ **Anchor note:** earlier files in this folder cite "§4.2" for their own counterfactual table while
numbering §4 as Data health, which leaves a dangling anchor (the same class #1123 fixed). This file
numbers the table **§5.0** and reserves "§4.2" for the README method it applies.

The §3.5 query (composite ≥ threshold AND no failed check other than the blocking rail) returns **34
rows across 7 rails**, collapsing to **15 distinct `(bar_time, leg)` pairs** — of which **13 resolve**
(the two 15:12-bar legs have no forward window before the 15:12 square-off). A further **10 rows are
`strike-pick` blocks with NO `wouldBeLeg`** and are structurally unresolvable: that rail fires before
the leg is picked.

Model per README §3.16/§4.2: **+35% TP / −25% SL / 30-min (10-bar) time stop / 15:12 square-off**,
priced off `marketdata.options_chain_snapshots`.

| bar | leg | blocking rail(s) | entry | exit | **pts** |
|---|---|---|---|---|---|
| 10:30 | `NIFTY2680424400PE` | confluence-composite | 230.40 | time stop 11:00 | **−17.50** |
| 10:33 | `NIFTY2680424400PE` | confluence-composite | 235.00 | time stop 11:02 | **−23.00** |
| 12:33 | `NIFTY2680424100CE` | pct-price-move, two-candle, volume-pump | 272.85 | time stop 13:02 | **−41.05** |
| 12:36 | `NIFTY2680424100CE` | pct-price-move, two-candle, volume-pump | 263.65 | time stop 13:06 | **−34.55** |
| 12:39 | `NIFTY2680424100CE` | two-candle, volume-pump | 251.15 | time stop 13:08 | **−21.35** |
| 12:42 | `NIFTY2680424100CE` | two-candle, volume-pump | 257.15 | time stop 13:12 | **−40.55** |
| 12:45 | `NIFTY2680424100CE` | pct-price-move, two-candle, volume-pump | 249.75 | time stop 13:14 | **−38.60** |
| 13:42 | `NIFTY2680424100CE` | **volume-floor** | 239.60 | time stop 14:12 | **−28.10** |
| 14:27 | `NIFTY2680424400PE` | confluence-composite | 208.10 | time stop 14:56 | **−28.35** |
| 15:06 | `NIFTY2680424550CE` | hero-zero | 20.05 | square-off 15:12 | **+1.35** |
| 15:06 | `SENSEX26JUL77700CE` | hero-zero | 116.50 | **TAKE_PROFIT** | **+40.78** |
| 15:09 | `NIFTY2680424550CE` | hero-zero | 21.75 | square-off 15:12 | **−0.35** |
| 15:09 | `SENSEX26JUL77800CE` | hero-zero | 63.75 | square-off 15:12 | **−0.60** |
| **union (distinct legs)** | | | | **2W / 11L** | **−231.87** |

**Per rail** (⚠ rails share legs, so these are NOT additive — the union row above is the honest total):

| blocking rail | wf rows | distinct legs | resolved | W | L | **pts** |
|---|---|---|---|---|---|---|
| volume-pump | 5 | 5 | 5 | 0 | 5 | **−176.10** |
| two-candle | 5 | 5 | 5 | 0 | 5 | **−176.10** |
| pct-price-move | 3 | 3 | 3 | 0 | 3 | **−114.20** |
| confluence-composite | 3 | 3 | 3 | 0 | 3 | **−68.85** |
| **volume-floor** | 2 | 1 | 1 | **0** | **1** | **−28.10** |
| hero-zero | 6 | 6 | 4 | **2** | 2 | **+41.18** |
| strike-pick | 10 | — | 0 | — | — | **unresolvable (no leg)** |

**Every rail's would-have-fired set loses except `hero-zero`**, and that exception does not survive
inspection: all four of its resolved legs are **15:06–15:09 entries on a BSE monthly expiry
afternoon**, and **+40.78 of the +41.18 is one leg** (`SENSEX26JUL77700CE`, a ₹116.50 cheapie that ran
to 172.70 in six minutes on the expiring series). That is a lottery ticket on a 0DTE chain with a
6-minute holding window, not a case for loosening `hero-zero`.

**`volume-floor` — the T1 knob — is 1 leg, 0W/1L, −28.10 pts. Fifth consecutive session in which the
measured loosening loses.**

### 5.1 The champion book's WORST session on record

All four books traded; **all positions closed by EOD, zero left OPEN**:

| variant | closed | wins | pts | **net ₹** | null `pnl_net` |
|---|---|---|---|---|---|
| **champion** | **25** | **2** | **−839.65** | **−58,233.05** | 0 |
| composite-055 | 4 | 1 | −80.15 | **−5,504.71** | 0 |
| vol-off | 4 | 0 | −112.40 | **−7,595.42** | 0 |
| vol-12k5 | 2 | 0 | −104.75 | **−6,946.52** | 0 |

**Champion by close reason:**

| close reason | n | pts | **net ₹** | bar-time span |
|---|---|---|---|---|
| SQUARE_OFF (held to 15:12) | 12 | −176.50 | **−14,194.19** | 10:30–15:12 |
| **STOP_LOSS** (−25% premium) | **8** | **−541.80** | **−35,782.94** | 10:30–12:33 |
| STRUCTURAL_STOP | 5 | −121.35 | −8,255.92 | 10:30–14:27 |

**§3.24 dedupe — the 25 closes are 9 distinct `(bar, leg, entry_ltp)` events:**

| bar | leg | entry | rows | exits seen | net ₹ |
|---|---|---|---|---|---|
| **10:30** | `NIFTY2680424400PE` | 230.40 | **7** | SQUARE_OFF / STOP_LOSS / STRUCTURAL_STOP | **−26,738.46** |
| 10:33 | `NIFTY2680424400PE` | 235.00 | 1 | STRUCTURAL_STOP | −705.21 |
| **12:33** | `NIFTY2680424100CE` | 272.85 | **5** | SQUARE_OFF / STOP_LOSS / STRUCTURAL_STOP | **−22,005.48** |
| 13:42 | `NIFTY2680424100CE` | 239.60 | 4 | SQUARE_OFF / STRUCTURAL_STOP | −1,067.38 |
| 14:27 | `NIFTY2680424400PE` | 208.10 | 4 | SQUARE_OFF / STRUCTURAL_STOP | −8,477.54 |
| 15:06 | `NIFTY2680424550CE` | 20.05 | 1 | SQUARE_OFF | +50.99 |
| 15:06 | `SENSEX26JUL77700CE` | 116.50 | 1 | SQUARE_OFF | +913.10 |
| 15:12 | `NIFTY2680424550CE` | 20.45 | 1 | SQUARE_OFF | −49.67 |
| 15:12 | `SENSEX26JUL77800CE` | 63.40 | 1 | SQUARE_OFF | −153.40 |

**The 10:30 and 12:33 clusters carry −₹48,743.94 of the −₹58,233.05 session net (83.7%)** on an
effective independent sample of **9**, not 25. Symmetric with 07-29: the fan-out that made the best
session on record essentially one bar makes the worst session essentially two.

**All-time league (refreshed 16:20 IST):**

| variant | closed | wins | pts | **net ₹** | per-close ₹ | movement today |
|---|---|---|---|---|---|---|
| champion | 268 | 108 | −397.30 | **−78,125.81** | −291.5 | **−₹58,233.05** (was −19,892.76) |
| composite-055 | 18 | 5 | −166.75 | **−9,999.07** | −555.5 | −₹5,504.71 |
| vol-12k5 | 43 | 13 | −280.55 | **−16,090.55** | −374.2 | −₹6,946.52 |
| vol-off | 57 | 15 | −473.90 | **−25,169.47** | −441.6 | −₹7,595.42 |

⚠ **All three challengers lost again, and champion remains the best book per close (−₹291.5 vs
−₹374.2 / −₹441.6 / −₹555.5).** `composite-055` is now the worst per close for a second session, and
the `vol-12k5 > vol-off` ordering survives a **seventh** session where both traded. Cumulative points
flipped back negative (+442.4 → −397.3).

**Exit-fidelity caveat (standing, §3.16):** the shadow book replicates brackets + structural stop +
15:12 square-off. It does **not** replicate indicator signal-exits **or the YAML `time_stop`** — which
is what makes §5.2's comparison possible.

### 5.2 ⚠⚠ The exit-model comparison FLIPS SIGN — G11's first counter-signed datapoint

Restricting to the **6 `(bar, leg, entry_ltp)` events where BOTH models resolve** (a champion
`SQUARE_OFF` row exists and the §5.0 premium path resolves):

| event | entry | hold-to-15:12 (shadow `SQUARE_OFF`) | 30-min `time_stop` (§5.0) | better model |
|---|---|---|---|---|
| 10:30 `NIFTY2680424400PE` | 230.40 | **−61.65** | **−17.50** | time stop |
| 12:33 `NIFTY2680424100CE` | 272.85 | −35.30 | −41.05 | hold |
| 13:42 `NIFTY2680424100CE` | 239.60 | **−2.05** | −28.10 | hold |
| 14:27 `NIFTY2680424400PE` | 208.10 | −39.35 | **−28.35** | time stop |
| 15:06 `NIFTY2680424550CE` | 20.05 | +1.55 | +1.35 | ≈ |
| 15:06 `SENSEX26JUL77700CE` | 116.50 | +48.30 | +40.78 (TP) | hold |
| **total** | | **−88.50 pts** | **−72.87 pts** | **time stop, by +15.63** |

**The sign is opposite to 07-29's**, where the same comparison favoured holding by an order of
magnitude (+₹15,260.87 vs −₹2,435.95). Entry is held constant to the paisa on every row here, so this
is a controlled comparison in the §3.24(b) sense — the only variable is the exit model.

**Corroborating, from the live signal path:** both 12:33 ENTRY fires produced **`TIME_STOP` EXIT
signals** (13:03 and 13:09), and on that leg the time stop was the *worse* of the two models
(−41.05 vs −35.30) — so today's aggregate edge to the stop comes from the 10:30 PE, not from the fires.

**Corroborating, from the brackets:** the −25% premium stop fired for real, **8 `STOP_LOSS` closes for
−₹35,782.94**, all on the 10:30 and 12:33 clusters. On 07-29 not one leg touched either bracket. So on
a `mixed` tape the bracket layer is live and doing work; on a trend tape it was inert.

⚠ **Three limits, all load-bearing:**

1. **This is `mixed` (efficiency 0.434), NOT `chop`.** G11 stays **BLOCKED-DATA**. What today supplies
   is a *counter-sign*, which shrinks the risk that G11 was going to be decided on a one-sided sample.
2. **6 events is a small sample and the aggregate is +15.63 pts, not a landslide** — 2 events favour
   the stop, 3 favour holding, 1 is a wash. The aggregate is carried by a single event (10:30, +44.15
   pts of the +15.63 net).
3. **Neither model includes `signal_exit` (`close < vwap`) or the trailing SuperTrend**, so both are
   bounds on specific models, not predictions of realised P&L.

**Standing G11 evidence ledger after today:** one trend-ish day (07-29) where holding won large, one
`mixed` day (07-30) where the stop won small. **The chop-day observation is still missing** and remains
the gate.

### 5.3 #1075 — both fires unfundable; the evidence base moves to 6 of 14

| id | slug | time | fire | tradeable leg | `suggested_qty` | paper |
|---|---|---|---|---|---|---|
| 119 | `scalp-golden-crossover-nifty` | 12:33 | ENTRY | `NIFTY2680424100CE` | **NULL** | none |
| 120 | `scalp-connect-the-dots-nifty` | 12:33 | ENTRY | `NIFTY2680424100CE` | **NULL** | none |
| 121 | `scalp-connect-the-dots-nifty` | 13:03 | EXIT (`TIME_STOP`) | — | — | — |
| 122 | `scalp-golden-crossover-nifty` | 13:09 | EXIT (`TIME_STOP`) | — | — | — |

`NIFTY2680424100CE` at ~₹272.85 × lot **65** = **₹17,735 > the ₹15,000 `budget_inr`** ⇒ both fires
`EXPIRED` unfunded, **0 paper positions this session**. No SENSEX-rooted fire occurred to fund.

**Cumulative evidence for the deferred [#1075](https://github.com/prashantm912/artha-yantra-2/pull/1075)
(₹15,000 → ₹20,000, owner date 2026-08-12):**

| session | fires | funded | unfunded | root of the unfunded |
|---|---|---|---|---|
| 2026-07-29 | 12 | 8 | 4 | all NIFTY |
| **2026-07-30** | **2** | **0** | **2** | all NIFTY |
| **total** | **14** | **8** | **6 (43%)** | **all NIFTY** |

**Reported, not acted on.** ⚠ Two counter-considerations unchanged: the concurrency cliff (above
₹15,000 each ₹30,000 sub-account holds 1 leg not 2, concurrency 8 → 5), and that 07-29's *funded* legs
LOST money (−₹2,435.95) — so "more of them" is not self-evidently better. Per the 07-30 pickup sheet,
**decide with G14**.

### 5.4 T10 — stale OPEN paper positions improved 19 → 17

`minervini` **11** (was 12), `manas-arora` **6** (was 7). Zero scalper positions left OPEN (none were
opened). Chronic OWNER row; first improvement since 07-24.

### 5.5 Latency (T8 / G8)

| metric | 2026-07-29 | **2026-07-30** |
|---|---|---|
| shadow entry latency p50 / p95 (bar close → entry) | 1:19.6 / 1:21.6 | **1:19.8 / 1:22.3** |
| `ay_signal_bar_to_emit_seconds` mean | 17.0 s (n=20) | **18.18 s (n=4)** |

Both structurally unchanged. G8's re-characterisation (a **uniform** ~17–18 s emit cost, not
entry-specific) holds on a smaller sample: all 4 emissions today are ENTRY/EXIT-mixed at 18.0–18.3 s.

## 6 New data points / anomalies

### 6.1 T23 — the quietest session in the series: 2 WARNs

`PartialBucketCanary` WARNed **2** times (6 on 07-29, 10 on 07-28, 3 on 07-27, 37 on 07-24), both on
`NFO:NIFTY26AUGFUT@3m`:

| IST bucket | engine 3m | Σ(3×1m) | shortfall | lots (÷65) |
|---|---|---|---|---|
| 12:30 | 81,965 | 73,905 | **−8,060** | **124** |
| 12:33 | 122,720 | 130,390 | **+7,670** | **118** |

Consecutive buckets, opposite signs, both exact ×65 multiples — the documented benign boundary
straddle (README §3.17). Not an exact ± pair (124 vs 118 lots), the same "near-pair" shape 07-29
recorded twice. As a fraction of their buckets the errors are 9.8% and 6.3% — **larger in percentage
terms than 07-29's 3.5%**, on much thinner bars, which is again the argument that the **650 absolute**
tolerance is the wrong shape. **T23 / ledger G9 stays PROPOSED**; the count is at its floor but the
mechanism is not fixed.

### 6.2 ⚠ NEW METHOD DIMENSION — on a BSE monthly expiry the suppression lands on the EXECUTION root, not the OI root

README §3.19 tells you to read the OI root from `diagnostic->'context'->>'underlying'` and never from
the slug name, because the `sensex-niftyoi` variants read **NIFTY** OI by design. That is correct — and
today it means the §3.19 fingerprint query **cannot see this session's expiry effect at all**:

| probe | today |
|---|---|
| `context.underlying` (OI root) | `NIFTY 50` on **814/814** rows |
| `futuresQuadrant` / `underlyingQuadrant` NEUTRAL | **0/814** |
| `spurtPricePct` NULL | **0/814** |
| `futuresBasis` LIVE | **814/814** |

Every S24 discriminator reads "no suppression", correctly: **NSE is not expiring, so no NIFTY-rooted OI
read is suppressed, and every row is NIFTY-rooted.** The BSE monthly expiry instead hit the
**execution** side — `strike-pick`, 405 fails, all 16 sensex-rooted slugs (§2.1).

**The dimension:** a scalper has up to three independent instrument roles (ADR-0003 — signal
underlying / strike reference / execution underlying). An expiry suppresses whichever role touches the
expiring chain, so **the OI-root query answers only the OI question**. On a BSE monthly expiry with
`sensex-niftyoi` slugs, look at `strike-pick` and the `wouldBeLeg` symbols, not at the quadrants.
Promoted to README **§3.27**.

### 6.3 `tickedTokens` = 69 — the 07-29 → 07-30 drop is the contract set, confirmed independently

The open-gate and midday runs both carried `tickedTokens` 69 (down from 105 on 07-29) as an
**assumed** expiry-roll artefact. A DB-side count settles it: distinct instruments with 1m bars today
is **34 NFO + 28 BFO + 5 NSE + 2 BSE = 69**, matching `tickedTokens` **exactly**.

| session | NFO | BFO | NSE | BSE | total |
|---|---|---|---|---|---|
| 2026-07-28 | 226 | 30 | 5 | 2 | 263 |
| 2026-07-29 | 42 | 40 | 5 | 2 | 89 |
| **2026-07-30** | **34** | **28** | **5** | **2** | **69** |

The subscribed option-contract set is what moves (226 NFO symbols on the NSE monthly expiry, 34 today),
and both the gauge and the bar table move together. **Not a feed loss.** ⚠ The 07-29 gauge reading
(105) is higher than that day's DB count (89), so the two measures are not identical — the gauge counts
tokens that ticked in a recent window, the table counts symbols that produced a bar all session. The
claim here is that they **agree on the direction and magnitude of the drop**, and today they happen to
agree exactly.

### 6.4 `RestartCount` 1 — two pre-open boots, no session impact

`docker inspect` reports `StartedAt 2026-07-30T02:35:55Z` (08:05:55 IST) and `RestartCount 1`, with an
earlier boot line at `2026-07-29T19:33:53Z` (01:03:53 IST). Both precede the 09:15 open, the F10
cold-start self-healed in 85 s at the later one, and the eval grid completed 375/375, so the counters
in §1 are a clean session tally. Recorded because 07-29's file explicitly noted `RestartCount 0` and a
reader comparing the two would otherwise wonder.

### 6.5 `rsi-band` failed on the FLOOR side, which is a first in this folder

`rsi-band` was the third-largest first-blocker (88) and fourth-largest failing rail (274) with **avg
failing operand 45.86**. Every prior session in this folder records it failing on the *exhaustion cap*
(07-29: 64.74). A two-sided tape produces PE candidates whose RSI is too *low* for the band's floor.
Not flagged as miscalibration — it is the same rail biting in the other direction, and it is the shape
a balanced session should produce. Recorded so the 45.86 is not later read as a broken operand.

### 6.6 The midday carry-list, discharged

| # | carry item | outcome |
|---|---|---|
| 1 | Fold the midday PASS + the +30/+30 counter deltas in | ✅ Done (§1). The 12:36 bucket's persisted 30 and the counter delta agree; full-session Σ 3,548 |
| 2 | `volume-floor` 376/636 (59%) — classify STRUCTURAL vs REGIME | ✅ **REGIME on level, STRUCTURAL on shape.** Full session settled at **574/1,118 = 51.3%**, the folder's lowest. The level is vindicated again (§5.0's 1-leg set loses); the surge-driven SHAPE is G10 and reproduced milder (§2.2). **No loosening proposed** |
| 3 | Two fires at 12:33 — did they reach a paper entry, how did they exit, same strategy or two? | ✅ **Two different slugs** (`golden-crossover-nifty`, `connect-the-dots-nifty`), same leg `NIFTY2680424100CE`; **neither reached a paper entry** (₹17,735 > ₹15,000); both exited `TIME_STOP` at 13:03/13:09 (§5.3) |
| 4 | `tickedTokens` = 69 — confirm the expiry-roll assumption at EOD | ✅ **Confirmed independently** — DB instrument count = 69 exactly (§6.3) |
| 5 | Re-check `iv_rank` / `dow` at EOD | ✅ **Still the standing dead pair**, both `required: false`, nothing newly dead (§4) |
| 6 | Re-check the host-clock guard at EOD | ✅ **~0.25 s** (§4) |
| 7 | Confirm the 201/201 front-future bar cadence held through the close | ✅ **375/375** on `NIFTY26AUGFUT`, 0 misaligned (§4) |

### 6.7 Method addenda → README §3.27, §4.1

- **§3.27 (new):** *on an expiry day the suppression lands on whichever instrument ROLE touches the
  expiring chain* — the §3.19 OI-root query answers only the OI question, and a BSE monthly expiry with
  `sensex-niftyoi` slugs shows up in `strike-pick` and `wouldBeLeg`, not in the quadrants (§6.2).
- **§4.1 (amend):** a dot at 0% with a **live, moving** operand is a third explanation alongside dead
  and frozen — run the operand's own min/max against the dot's threshold before classifying (§3.2).

## 7 Tuning candidates

Carried forward from 07-29 plus this session's movement. **Nothing here is applied** — every open row
is a PROPOSAL. Ledger rows in
[`../superpowers/plans/2026-07-02-remaining-items.md`](../superpowers/plans/2026-07-02-remaining-items.md)
**§0 group G** are the authoritative status; this table is the evidence.

| # | knob | current | proposed | evidence | class | status |
|---|---|---|---|---|---|---|
| **T30** | `breadth` dot threshold (`advances/declines > 32`) | fixed `> 32` on a 50-constituent index | make it relative (a percentile / a ratio band), or accept it as a per-session bias and reweight | §3.2: **0/814** on a fully live, moving operand (10 distinct values, 23–32, max **exactly 32** against a strictly-greater test — 2nd such session). Across 8 sessions the dot is 0% on **five**, ~100% on **two**, 0.2% on **one** — never in between. Weight 1.0 of an 18.80 denominator ⇒ a **±5.3 pp per-session bias on every composite**, not a per-bar discriminator. No canary axis can see it (alive=true, frozen=false) | **STRUCTURAL (threshold shape)** | **PROPOSED — NEW. Needs a §0 group-G row. Cause NOT established (no breadth-scorer code read this run); do NOT file as a T18 reopen — T18 asked whether the input was dead, this asks whether the threshold is a per-session coin flip** |
| **T29** | scalper `time_stop` (`max_bars: 10` = 30 min on 3m) | armed fleet-wide (T21/#990) | owner review: lengthen, make it trend-conditional, or drop it for the trailing SuperTrend | ⚠ **THE SIGN FLIPPED.** §5.2, 6 matched `(bar, leg, entry)` events: hold-to-15:12 **−88.50 pts** vs 30-min stop **−72.87 pts** ⇒ **the stop was BETTER by +15.63** on a `mixed` day, where 07-29's trend day favoured holding by an order of magnitude. Also: the −25% bracket fired for real (**8 `STOP_LOSS`, −₹35,782.94**) where 07-29 had zero bracket touches | **EXIT-BAND (owner) — ledger G11** | **OPEN, still BLOCKED-DATA.** Today is `mixed` (0.434), **not the `chop` day the row waits on**. First counter-signed datapoint; sample is 6 events and the aggregate is carried by one. **Do not act** |
| **T27** | relative-volume-floor window (`priorVolumes`) | `1.5 × median(prior N)`, window starts at the session open | exclude the opening ~30 min, seed from the prior session's median, or normalise by time-of-day | §2.2, **second session**: opening threshold **48,993.75 = p92** (07-29: 133,185 = p98.4); pre-11:00 block share **28.9%** (07-29: 43%). Mechanism reproduces with **~⅓ the severity** — which supports "surge-driven", not "constant mis-calibration" | **STRUCTURAL (code) — ledger G10** | **OPEN. Arming recommendation unchanged (NO)** — build is in and default-OFF; today adds severity variance, no P&L case (G10 measured −₹590.95 after 1% cost) |
| **T28** | `macro.atmIv` freshness + a frozen-operand probe | 1 distinct value/session, **5 sessions running** (0.112743 today) | redefine `iv_abs_band` on an intraday operand (`(ceIvAvg6+peIvAvg6)/2` already exists) | §3.3: ✅ **the probe half SHIPPED (#1111) and read correctly at EOD for the first time** — `frozen BY DESIGN — one value (0.112743) across 18 bars (EOD daily operand)`, past `MIN_FROZEN_BARS = 8`. The dot is still 119/119 = a free 0.8 | **STRUCTURAL — ledger G12** | **OPEN, diagnosis half DISCHARGED.** Remaining work is the redefinition |
| **T3** | `iv_pair` min-gap | 0.02 | drop the dot from Σw, or redefine its operand | 0/814 again; no new evidence. G13's arithmetic (gap max 0.00070 vs a 0.02 threshold — put-call parity) stands. Payoff ≈ 6 legs, against the loosening prior | **STRUCTURAL — ledger G13** | **OPEN (owner).** Unchanged |
| **T23** | `partial-bucket-canary.volume-tolerance` (absolute 650) | 2 WARNs — the series floor | make the tolerance scale with bar size | §6.1: both events exact ×65 multiples on consecutive buckets, but **9.8% and 6.3% of their buckets** — proportionally the largest yet, on thin bars. Benign by shape, alarmed anyway | **STRUCTURAL (defect) — ledger G9** | **OPEN. The count is at its floor; the mechanism is not fixed** |
| **T1** | `relativeVolumeMultiplier` (`k`) | 1.5 | 1.2 (or 1.0) | §5.0: the `volume-floor` would-have-fired set is **1 leg, 0W/1L, −28.10 pts**. **Fifth consecutive session in which loosening loses** | **REJECTED (forward-evidenced)** | **REJECTED — do NOT apply.** Reconfirmed, not reopened |
| **T7** | composite threshold | 0.600 | no change | `composite-055` lost **−₹5,504.71** on 4 closes and is the worst book per close (−₹555.5) for a **second** session | **REJECTED** | **REJECTED — reconfirmed** |
| **T24** | the `volume` dot's floor resolution | ✅ fixed (#1082) | — | §2.3: **240/814 = 29.5%** on a session where **ZERO of 125 bars** cleared the pre-fix static 125,000 — impossible under the old path. A second, stronger data-side confirmation than 07-29's | — | ✅ **CLOSED / ledger G6 VERIFIED — reconfirmed on data** |
| T26 | ENTRY-path emit latency | uniform ~17–18 s | measure across more fires | §5.5: 18.18 s mean on 4 emissions, ENTRY and EXIT alike. Uniform, as 07-29 re-characterised | **STRUCTURAL (measurement) — ledger G8** | **OPEN (data)** |
| T2 | `iv_rank` dot | w 0.8, NULL 100% | source `ivRank` or drop from Σw | NULL 100% a 12th session; withheld from Σw (#676) so it costs no headroom | **STRUCTURAL** | **PROPOSED (carried)** |
| T10 | stale OPEN paper positions | **17** OPEN (11 `minervini`, 6 `manas-arora`) | square off / age out | improved 19 → 17 (§5.4); 0 scalper positions left OPEN | ops | **OWNER — chronic, first improvement since 07-24** |
| T14 | rejection-row margin invariant | global `blocking_margin < 0` | sign-aware per rail | **0 self-contradicting rows** on a session where 118 rows passed composite — a non-vacuous clean reading, a 2nd consecutive one | **STRUCTURAL (diagnostic)** | **PROPOSED — carried** |
| T8 | shadow entry latency | p50 ~80 s | stamp entry at bar close | §5.5: p50 **1:19.8** / p95 **1:22.3** — structurally unchanged for a 2nd session | **STRUCTURAL (data-model)** | **PROPOSED** → README §7 |
| T15 | engine boot-line durability | V046 `strategy.engine_reloads` (#987) | — | ⚠ **exercisable for the first time** — `RestartCount 1` with two pre-open boots (§6.4). Not checked against the table this run | **STRUCTURAL (data-model)** | **SHIPPED #987 — still unverified; the verification opportunity now exists** |
| T16 / T12 / T19 / T22 / T18 / T21 / T6 / T17+T13 / T20 / T25 | — | — | — | §2.2 (T16 38/38 armed, 0 flat floors); §4 (T12 **375/375**; T19 **0 misaligned, 6th clean**); §3 (T22 `oi_spurt` 4.9%; T18's input live — but see **T30**, a different question); §5.1 (T21's brackets fired 8×) | — | ✅ **remain CLOSED** |

**Ledger note (README §5 rule).** **T30 is a new BUILD-or-owner row and needs a §0 group-G row.** It is
not created in this docs-only PR (that ledger is the forward-work authority and this run is read-only
analysis) — it is flagged here as the next action.

**Group G status movement this session:** **G11** gains its first counter-signed datapoint but stays
BLOCKED-DATA (regime is `mixed`, not `chop`); **G12**'s diagnosis half is discharged by #1111 reading
correctly at EOD; **G10** reproduces milder, arming recommendation unchanged (NO); **G6** reconfirmed
on stronger data; **G9**, **G8**, **G13** unchanged.

## 8 Honesty caveats

- **§5.0's counterfactual model (README §4.2) is the uniform +35% TP / −25% SL / 30-min time stop / 15:12 square-off**
  (§3.16, all 63 YAMLs carry `premium_pct` since T21/#990). It omits `signal_exit` (`close < vwap`) and
  the trailing SuperTrend, both of which the shadow book also omits. **−231.87 pts is a bound on a
  specific model, not a prediction of realised P&L.**
- **3-minute LTP granularity** means a 1-minute bracket touch can be missed. One leg did touch its
  take-profit today (`SENSEX26JUL77700CE`, hi 172.70 vs a 157.28 trigger), so the granularity is not
  merely academic this session; the other 12 resolved far inside their bands.
- **Two of the 15 would-have-fired legs are UNRESOLVED, not resolved-as-flat.** The two 15:12-bar legs
  have no forward window before the 15:12 square-off under the strict model. The champion book, which
  closes them at the next tick, records 0.00 and −5.20 pts — including them would move the total to
  ≈ −237 pts. Stated rather than silently folded in.
- **10 of the 34 would-have-fired ROWS are `strike-pick` blocks with NO `wouldBeLeg`** and are
  structurally unresolvable — the rail fires before the leg is picked. The 13-leg counterfactual is
  therefore a lower bound on coverage, not on P&L.
- **§5.2's 6-event comparison is small and one-event-dominated.** 2 events favour the stop, 3 favour
  holding, 1 is a wash; the +15.63 aggregate is carried by the 10:30 PE (+44.15 alone). It flips the
  sign of 07-29's reading — it does not establish that the stop is right.
- **Today is `mixed` (efficiency 0.434), not `chop`.** G11's blocking condition is unchanged, and this
  file must not be cited as having satisfied it.
- **T30 names an observation and its arithmetic, not a cause.** No code read of the breadth scorer was
  done, so whether `>32` is a deliberate majority-plus-buffer read or an inherited constant is unknown.
- **§2.1's expiry mechanism has one non-conforming session** — 07-24, a Friday with no expiry and 550
  sensex-rooted `strike-pick` fails. The claim is "an expiry saturates the expiring root", not "only an
  expiry can".
- **§6.3's `tickedTokens` reconciliation is exact today and approximate on 07-29** (gauge 105 vs DB 89).
  The two measures differ in window; the agreement claimed is on direction and magnitude.
- **The champion book's −₹58,233.05 is 9 independent events, not 25** (§5.1), and 83.7% of it is two
  bars. Its all-time −₹78,125.81 inherits the same fan-out, as does every row of the league table.
- **`premium_skew`'s 72.7% rests on 22 rows** — a second sighting, watchlist only.
- This run was **read-only**: SELECTs, `docker logs`, `docker inspect`, in-container actuator/health
  GETs. No restart, deploy, write or config change. **No strategy knob was altered.**
