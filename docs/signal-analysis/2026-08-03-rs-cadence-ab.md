# 2026-08-03 — the RS screen-cadence A/B (M9 axis 2)

Owner-commissioned follow-up to PR #1238, which measured M6/M9 and found, incidentally, that M9's
real divergence looked like it was **not** the formula the #128 batch named — reporting a
cadence/staleness term of **3.5753%** against the named formula term's **0.0111%**, and stating
explicitly that no P&L was claimed for it and that it "needs its own A/B". The owner approved
running that A/B. This is it.

**This is MEASUREMENT-ONLY.** No production code, strategy config, YAML, migration, fixture, golden
vector or `.env` was changed; nothing is armed. The only artifacts are this document, four
deterministic deep-sim runs driven through the **unmodified** production endpoint
`POST /api/v1/market/screener/deep-swing/run`, and read-only SQL. No engine record was read, written
or extended, so there was nothing for the Golden+Parity ladder to gate.

Claims are tagged **[computed]** (derived here from code/DB on this checkout), **[sourced]** (quoted
from a cited file:line or SQL output), **[recalled]** (memory, not re-verified) or **[assumed]** (an
inference I could not fully verify). A claim computed from an assumed input stays **[assumed]**.

---

## Read this first — the verdict

**Cadence is NOT a lever. Switching the backtest's weekly RS cross-section to a daily one moves the
mean per-trade gross return by `+0.0134 pp` (2.1686% → 2.1819%, +0.62% relative) across 12,998
stale-admitted trade legs — a number whose own 95% bootstrap CI is `[−0.357, +0.381]`, i.e. ~27×
wider than the estimate, with `P(delta > 0) = 0.5242`. [computed]** A re-phased window reproduces it
(`+0.0178 pp`, CI `[−0.381, +0.421]`, `P = 0.5324`). The direction hint is "fresher is marginally
better"; the evidence cannot separate it from a coin flip.

Three things came out of the way there, and two of them matter more than the verdict:

1. ⚠️ **PR #1238's 3.5753% measures a divergence the production code does not have.** Its SQL froze
   the symbol's **own** RS at the stale rank date as well as the cross-section. Production freezes
   **only the cross-section** — `perBarRsRank` computes `weightedRs(close, i)` at the *current* bar
   and percentiles it against `dist.get(rd)` at the stale rank date
   (`ManasAroraBacktestService.java:717-731`; `MinerviniBacktestService.java:764-778`, identical).
   Measured on one fixed 3,376,887-name-date population: **production spec 1.6957%, #1238 spec
   3.4845% — a 2.05× overstatement.** **[computed]**
2. **The corrected cadence divergence over 11 years is 1.7025%** of 3,382,316 name-dates (still
   monotone in staleness, 0.0000% → 2.9186%), and it converts to **1.4073% of trade legs**
   (215 of 15,277) — of which 163 are entries staleness lets in and 52 are entries freshness lets in.
   **[computed]**
3. **The direction is "different", not "better" — the null survives.** The 52 freshness-only entries
   do beat a rank-matched boundary control (+6.01% vs +2.10%, resample `p = 0.0352`), but that result
   is carried by three trades: dropping the top 1 / 2 / 3 contributors moves `p` to
   0.0842 / 0.1584 / 0.2457. The 163 staleness-only entries are indistinguishable from the same
   control (`p = 0.3996`). **[computed]**

**Recommendation: ACCEPT the weekly cadence. Close M9 axis 2.** The one thing worth carrying forward
is not a tuning item at all — it is that #1238's cadence number should be corrected in the record
before anyone else builds on it.

---

## Part 0 — STEP 0: verifying #1238 before building on it

Both of #1238's headline numbers **reproduce exactly**, re-run today against the live DB. **[computed]**

| #1238 claim | Re-measured here | Match |
|---|---|---|
| M9 axis 1 (formula): 5 flips / 45,069 name-dates, 20 screen dates | 5 / 45,069, 20 dates, `max abs rank delta = 0.0273` | ✅ exact |
| M9 axis 2 (cadence): 1,608 / 44,975 = 3.5753% | 1,608 / 44,975 = 3.5753% | ✅ exact |
| axis-2 monotonicity 0.000 → 5.603 by staleness | 0.0000 / 2.8905 / 4.3691 / 5.0139 / 5.6031 | ✅ exact |
| axis-2 0-stale control = 0 flips, 0.000 mean delta | 0 flips, 0.0000 mean delta | ✅ exact |
| deep sim: 13,303 legs, CAGR 25.20, maxDD 64.40, Sharpe 0.71, win 50.2293, scanned 2,496 | identical on an independent re-run a day later, plus `tradesTaken 1,328 / tradesSkipped 11,975` | ✅ exact |

So the arithmetic is sound and the sim is deterministic across sessions. **The premise that fails is
what those numbers are a measurement OF.**

### The spec error

`perBarRsRank` is the whole of it (`ManasAroraBacktestService.java:707-733`):

```java
for (int i = RS_LOOKBACK; i < n; i++) {
  LocalDate rd = asOfRankDate(rankDates, bars.get(i).date());  // STALE rank date
  double[] d = dist.get(rd);                                   // STALE cross-section
  double my = weightedRs(close, i);                            // FRESH own RS, at bar i
  out[i] = percentile(d, my);
}
```

**[sourced]** The symbol's own weighted RS is recomputed on **every** bar. Only the comparison
distribution is up to 4 sessions old. #1238's axis-2 SQL joined
`manas_arora_screen_results` at `bar_date` against the same table at `as_of`, which takes the
*persisted rank* — a number in which both the numerator and the denominator are frozen. That is a
different, larger quantity, and it is not what the backtest does.

Isolated on one population (all real bars, deep-sim universe, 2015-08-03 →, both specs computed
side by side so nothing but the spec differs): **[computed]**

| Spec | Gate flips | of 3,376,887 name-dates |
|---|---|---|
| Production (fresh own RS, stale cross-section) | 57,263 | **1.6957%** |
| PR #1238 (stale own RS, stale cross-section) | 117,669 | **3.4845%** |

The #1238 spec's 3.4845% on my population and its 3.5753% on the live-screen population agree
closely, which confirms the discrepancy is the **spec**, not the population or my reconstruction.

---

## Part 1 — Method, and the four harness controls

The measurement replicates the production RS machinery exactly in numpy —
`RANK_CADENCE=5`, `RS_LOOKBACK=252`, `MIN_SERIES=260`, `MIN_DIST=20`,
`weightedRs = 0.4·r63 + 0.2·r126 + 0.2·r189 + 0.2·r252` on **own-bar-index** lags, midpoint
`percentile = 100·(below + 0.5·equal)/len`, `asOfIndex` carry-forward, `asOfRankDate` binary search,
`weeklyRankDates` = every 5th distinct NSE 1d session from `warmStart = from − 600 days`. Trade
populations come from the unmodified endpoint. Nothing was patched, stubbed or approximated.
**[computed]**

Because a replica is only as good as its proof, four controls ran before any result was read:

1. ⚠️ **Bit-exact reconstruction.** For every trade the sim returns `rsRankAtEntry` — the stale rank
   it actually used. My reconstruction reproduces it on **13,303 / 13,303** primary-variant trades
   and **15,277 / 15,277** turnover-variant trades with **`max |delta| = 0.000000000000`, 0
   unresolved** — and again on the re-phased 2018 window (**12,574 / 12,574**, same zero). Three
   independent populations, exact to the last bit. This is what licenses trusting the **fresh** rank:
   it is the same pipeline with one substitution. **[computed]**
2. **0-stale control.** At staleness 0 the stale and fresh cross-sections are the same object, so the
   flip count must be exactly 0 and the mean rank delta exactly 0.0000. It is, on 681,119 name-dates.
   An unchanged comparison reproduces to the decimal, so the non-zero rows are measurements, not
   harness noise. **[computed]**
3. **Deep-sim determinism.** The primary run reproduces #1238's independent run (different day,
   different session) on every headline metric and the trade count. The A/B's base `turnover` run was
   then executed **twice** in this pass: `trades[]` is **byte-identical** (SHA-256
   `02d3c79215db7e01f7d3fc91…` on both, n=15,277) and every headline matches
   (totalReturn 796.26 / CAGR 22.09 / maxDD 59.54 / Sharpe 0.71 / win 50.7233 / taken 1,332 /
   skipped 13,945 / scanned 2,496). Only `runAt` differs. Nothing below rests on a number that moved
   between runs — so if a control variant had moved, the sim changed, not the market. **[computed]**
4. ⚠️ **Occupancy / post-filter fidelity — the control that bounds this method's main weakness.** The
   RS gate is a **pure entry filter**: `selectionGates` rejects on `rsRank[i] < rsMin`
   (`ManasAroraSwingBacktest.java:483-488`) and no exit path reads `rsRank`, so a trade's outcome is
   fully determined by its entry bar. That licenses building both arms by post-filtering the
   RS-gate-**off** `turnover` variant instead of re-simulating (which would need a code change).
   What it does *not* capture is occupancy re-sequencing: a blocked entry leaves the symbol flat, so
   it can enter at a later bar the base run never exposed. Measured rather than waved at —
   post-filtering the turnover run by the stale gate against the primary run's *actual* trades:
   **[computed]**

   | | legs |
   |---|---|
   | primary run, actual | 13,303 |
   | turnover run filtered by `stale ≥ 70` | 12,998 |
   | intersection | 12,974 |
   | primary-only (re-sequenced away) | 329 |
   | filtered-only | 24 |
   | **agreement** | **97.3512%** |

   So the method carries a ~2.6% leg-level construction error. Both arms are built the same way, so it
   is common-mode for the *difference*; it is still the largest single caveat here and is restated in
   open doubts.

**Population caveats, in the same sentence as the numbers:** this is the deep sim's *unconstrained*
leg population (every setup on every symbol), **not** a slot-limited portfolio — only 1,328 of the
primary run's 13,303 legs are portfolio-admitted. It is **survivorship-biased** (M11: the universe is
the 2,496 symbols that still have `candles` rows and ≥260 bars; no point-in-time constituent source
exists in-repo — measured here, no symbol's data ends before 2025-03-23, so the bias is close to
total) and **capacity-unbounded**. Derived-OI muting is not relevant to this surface (no OI input).

---

## Part 2 — The corrected cadence divergence: name-dates first

Every real (symbol, session) bar in the deep-sim universe from 2015-08-03, stale vs fresh
cross-section at the `rs ≥ 70` gate. **[computed]**

| Sessions stale | Name-dates | Gate flips | Flip % | mean \|Δrank\| | max \|Δrank\| |
|---|---|---|---|---|---|
| **0 (control)** | 681,119 | **0** | **0.0000** | **0.0000** | **0.0000** |
| 1 | 676,209 | 8,635 | 1.2770 | 1.1497 | 15.9037 |
| 2 | 673,792 | 12,635 | 1.8752 | 1.7275 | 20.7143 |
| 3 | 672,862 | 16,517 | 2.4547 | 2.2530 | 22.6027 |
| 4 | 678,334 | 19,798 | 2.9186 | 2.6577 | 25.3425 |
| **Total** | **3,382,316** | **57,585** | **1.7025** | **1.5552** | **25.3425** |

Monotone in staleness, as it must be. Against M9 axis 1's named formula term (5 of 45,069 =
0.0111%, bounded in closed form at ≤1 name per screen date) the cadence term is still the larger of
the two — **153×**, not #1238's 322×.

---

## Part 3 — Legs

Base population: the `turnover` variant (liquidity floor only, RS gate **off**) — 15,277 legs, all
15,277 with a reconstructable rank. Both arms are post-filters on that one set, so they differ by the
gate and nothing else. **[computed]**

| | legs |
|---|---|
| admitted by **STALE** (shipped weekly) | 12,998 |
| admitted by **FRESH** (daily counterfactual) | 12,887 |
| common (both admit) | 12,835 |
| **STALE-only** — staleness lets in, freshness would drop | **163** (1.2540% of stale-admitted) |
| **FRESH-only** — freshness lets in, staleness blocks | **52** (0.4035% of fresh-admitted) |
| neither | 2,227 |
| **legs that change** | **215 of 15,277 = 1.4073%** |

The asymmetry is real and expected: a stale cross-section is on average *lower* than a fresh one in a
rising tape, so it over-admits (163 in) more than it under-admits (52 out).

---

## Part 4 — Then P&L

`pnlPct` as the sim reports it is **gross**; costs are applied at the portfolio layer
(`SwingPortfolio.java:91-92`), `roundTrip = fixedPct + 2·(spreadPct + min(impactCapPct, impactCoeff ·
participation))` with defaults `0.25 / 0.05 / 0.10 / 5.0`
(`ManasAroraBacktestService.java:212-215`) — about 0.35 pp for a very liquid name and ~1.02 pp at the
₹3.75 M/day turnover floor with ₹125 k orders. **Costs are not subtracted below, and that is
deliberate and stated rather than omitted: this is executed-vs-executed** — both arms perform exactly
one round trip per admitted leg, so the same per-trade charge sits on both sides of the subtraction
and the *mean per-trade delta* is cost-invariant to first order. (Second order, the fresh arm takes
111 **fewer** trades, so it pays slightly less total cost — pushing the same, negligible way.)
**[computed]**

### Per-trade distribution of the two difference sets

| Statistic | STALE-only (n=163) | FRESH-only (n=52) |
|---|---|---|
| min | −16.6891% | −13.5356% |
| p10 | −11.3056% | −10.4478% |
| p25 | −8.8612% | −8.4957% |
| **median** | **+0.5135%** | **+2.0144%** |
| p75 | +8.1998% | +17.1029% |
| p90 | +16.9201% | +24.8299% |
| max | +79.1021% | +60.9410% |
| **mean** | **+2.3385%** | **+6.0109%** |
| win rate | 51.53% | 57.69% |
| mean bars held | 15.18 | 15.13 |

### Whole-population

| | n | mean | median | win |
|---|---|---|---|---|
| common (both admit) | 12,835 | +2.1664% | +0.0558% | 50.13% |
| **ALL admitted, STALE semantics (shipped)** | **12,998** | **+2.1686%** | +0.0582% | 50.15% |
| **ALL admitted, FRESH semantics (daily)** | **12,887** | **+2.1819%** | +0.0590% | 50.16% |
| **Delta (FRESH − STALE)** | | **+0.013355 pp** | +0.000813 pp | +0.0129 pp |

`+0.0134 pp` on a `+2.17%` base is **+0.62% relative**.

---

## Part 5 — Sign robustness

### 5.1 The estimate is stable; the *evidence* is not

Dropping extremes from both admitted sets barely moves the delta — **it never changes sign, and never
moves by more than 0.0007 pp**: **[computed]**

| Perturbation | Delta (FRESH − STALE) |
|---|---|
| full | +0.013355 pp |
| drop top 1 / 2 / 3 / 5 from each | +0.013221 / +0.013091 / +0.012980 / +0.012774 pp |
| drop bottom 1 / 2 / 3 from each | +0.013416 / +0.013453 / +0.013484 pp |
| median-based | +0.000813 pp |

**But that stability is not evidence of an effect — it is evidence that 215 changed legs cannot move
a 13,000-leg mean.** The decisive statistic is the noise band: **[computed]**

> **95% bootstrap CI on the delta: `[−0.356966, +0.380801]` pp. `P(delta > 0) = 0.5242`.**

The estimate is ~1/27th of its own CI half-width, and the sign is a coin flip.

### 5.2 Window shift — by calendar year

| Year | n stale | n fresh | mean stale | mean fresh | delta | changed legs |
|---|---|---|---|---|---|---|
| 2015 | 31 | 31 | −3.8388 | −3.8388 | +0.0000 | 0 |
| 2016 | 609 | 608 | +1.4795 | +1.4839 | +0.0045 | 1 |
| 2017 | 1,565 | 1,564 | +4.7012 | +4.7346 | +0.0334 | 7 |
| 2018 | 511 | 512 | −3.9562 | −3.9648 | **−0.0086** | 1 |
| 2019 | 140 | 140 | +0.0676 | +0.0676 | +0.0000 | 0 |
| 2020 | 896 | 886 | +3.5646 | +3.5965 | +0.0319 | 16 |
| 2021 | 2,372 | 2,310 | +5.2729 | +5.3579 | +0.0850 | 100 |
| 2022 | 1,352 | 1,349 | −0.7370 | −0.7647 | **−0.0277** | 17 |
| 2023 | 2,029 | 2,018 | +4.7910 | +4.7623 | **−0.0287** | 19 |
| 2024 | 2,688 | 2,664 | −0.4272 | −0.3655 | +0.0617 | 54 |
| 2025 | 451 | 451 | −2.2013 | −2.2013 | +0.0000 | 0 |
| 2026 | 354 | 354 | −0.6340 | −0.6340 | +0.0000 | 0 |

**Three of twelve years carry a negative delta**, four are exactly zero (no changed legs at all), and
one year (2021) supplies 100 of the 215 changed legs. The sign is not window-robust.

By setup the sign at least agrees: `breakout` +0.0119 pp (n 6,816 → 6,770), `vcp` +0.0148 pp
(n 6,182 → 6,117).

### 5.3 Re-phased grid — an independent control

`from = 2018-08-03` moves `warmStart` to 2016-12-11, which re-phases the **entire** weekly rank-date
grid (501 rank dates on a different origin). If the result were a grid-phase artifact, this is where
it would break. It does not: **[computed]**

| | 2015-08-03 window | 2018-08-03 window (re-phased) |
|---|---|---|
| legs | 15,277 | 12,574 |
| reconstruction control | max \|Δ\| = 0.0 (n=15,277) | max \|Δ\| = 0.0 (n=12,574) |
| changed legs | 1.4073% | 1.4872% |
| STALE-only / FRESH-only | 163 / 52 | 146 / 41 |
| FRESH-only mean | +6.0109% | +4.0800% |
| STALE-only mean | +2.3385% | +1.3806% |
| **delta** | **+0.013355 pp** | **+0.017825 pp** |
| 95% CI | [−0.357, +0.381] | [−0.381, +0.421] |
| P(delta > 0) | 0.5242 | 0.5324 |

Same sign, same order of magnitude, same coin flip. The finding is phase-robust; so is its
insignificance.

---

## Part 6 — Better, worse, or merely different?

⚠️ **A selection test is not an outcome test**, so the null here is explicitly *"the flipped names are
just names sitting at the gate boundary"* — and a boundary-matched control is the way to beat it.
The flipped sets live in a narrow band (STALE-only entry ranks 70.04–77.81, mean 71.51; FRESH-only
70.03–75.47, mean 71.31), so the control is the **non-flipping** trades whose *both* ranks fall in
[70.03, 77.81] — 1,433 legs, mean +2.1040%, median +0.5141%, win 52.13%. **[computed]**

| Set | n | mean | median | win | resample p (≥ observed) |
|---|---|---|---|---|---|
| boundary-band control (no flip) | 1,433 | +2.1040% | +0.5141% | 52.13% | — |
| **STALE-only** (rank *fell* when refreshed) | 163 | +2.3385% | +0.5135% | 51.53% | **0.3996** |
| **FRESH-only** (rank *rose* when refreshed) | 52 | +6.0109% | +2.0144% | 57.69% | **0.0352** |

Read literally, the freshness-only entries beat the boundary control at a nominal 3.5%. **That result
does not survive its own robustness check:**

| | n | mean | p vs band |
|---|---|---|---|
| FRESH-only, full | 52 | +6.0109% | 0.0352 |
| drop top 1 | 51 | +4.9338% | 0.0842 |
| drop top 2 | 50 | +4.0597% | 0.1584 |
| drop top 3 | 49 | +3.3877% | 0.2457 |

**Three trades carry it.** A one-sided permutation test of FRESH-only against STALE-only directly
gives `p = 0.0681` on an observed gap of +3.6724 pp, with `SE = 2.29` on the 52-trade mean — and it
is one of several tests run here, so it does not survive a multiplicity correction either. The
staleness-only side is flatly indistinguishable from the control.

**Answer: merely different.** The null is not beaten.

---

## Part 7 — The channel this A/B cannot see

The RS rank has a **second** consumer besides the `≥ 70` gate: `SwingPortfolio` sorts each maximal run
of same-day ENTRY events by `rsRankAtEntry` **descending** so the strongest names claim the scarce 8
slots first (`SwingPortfolio.java:107-125,342-344`). **[sourced]** A rank change therefore reshuffles
the slot queue even when it does not flip the gate — and a post-filter cannot model that. Bounded
rather than ignored: **[computed]**

| | |
|---|---|
| entry dates with ≥2 stale-admitted entries | 1,727 |
| ... on which the fresh rank **reorders** the queue at all | 106 (6.14%) |
| entry dates with >8 entries (a genuine slot contest) | 545 |
| ... on which the **top-8 set** changes | **9 (1.65%)** |
| same-day entry count | mean 7.32, median 6, p90 14, max 44 |

So the unmeasured channel touches at most 9 contested days in 11 years. It cannot rescue a lever the
admission channel could not find.

⚠️ **A warning against over-reading my own leg-level number, from the same runs.** On this
unconstrained leg population the *rejected* legs (both ranks < 70, n=2,227) have a **higher** gross
mean (+3.1507%) than the admitted ones (+2.1686%) — yet the portfolio the sim actually reports is
better *with* the RS gate (`rs-turnover-nopyramid` CAGR 25.20 / Sharpe 0.71 vs `turnover` CAGR 22.09
/ Sharpe 0.71). Leg means and portfolio economics diverge sharply here, because slots are scarce and
allocated by RS priority. That applies to my `+0.0134 pp` exactly as much as to anything else: it is
a **leg-level** quantity, and the portfolio-level equivalent is not measured.

---

## Part 8 — What the owner should do

| Item | Legs | Outcome | Recommendation |
|---|---|---|---|
| **M9 axis 2 — cadence** (the item under test) | 215 / 15,277 = 1.41% | +0.0134 pp, CI [−0.357, +0.381], P(>0)=0.52 | **ACCEPT the weekly cadence. Close the item.** |
| **M9 axis 1 — formula** (the named #128 item) | 5 / 45,069 = 0.011%, ≤1 per date by algebra | bounded to noise by leg count | **ACCEPT** (unchanged from #1238) |
| **#1238's 3.5753% cadence figure** | — | measures a divergence production does not have; 2.05× over | **CORRECT THE RECORD** before anyone builds on it |

Notes on the recommendation:

1. **Accepting is not the same as the divergence being absent.** The backtest's RS gate genuinely
   disagrees with a daily-cadence gate on 1.7% of name-dates and 1.4% of legs. It is real, it is
   monotone in staleness, and it is economically inert at the scale this strategy trades.
2. **If the cadence is ever changed anyway, change it for fidelity, not for return** — and expect the
   compute cost, not the P&L, to be the deciding factor: a daily cross-section is 660 rank dates → 3,297,
   a 5× increase in pass 1 of a run that already takes minutes.
3. **The most actionable line in this document is item 3 in the table** — a corrected number in the
   record, at zero risk.

---

## Open doubts

1. ⚠️ **The post-filter construction carries a measured ~2.6% leg error and I could not eliminate it.**
   Reproducing the true fresh-cadence trade set needs `RANK_CADENCE` to vary, which is a production
   constant (`ManasAroraBacktestService.java:64`) and therefore out of bounds for a measurement-only
   brief. Control 4 bounds it (97.3512% agreement) and it is common-mode across the two arms, but
   both arms are built on the `turnover` run's occupancy path, which is neither arm's true path.
   A code-changing A/B would be the decisive version of this measurement.
2. **The portfolio-level effect is not measured at all** — only its two input channels are bounded
   (admission 1.41% of legs; priority 9 of 545 contested days). Part 7 shows leg means and portfolio
   outcomes diverging by a lot on this very population, so a small leg-level delta does **not**
   formally imply a small CAGR delta. I believe it does here **[assumed]**, on the grounds that 215
   changed legs out of 15,277 cannot plausibly reorganise a 1,328-trade portfolio, but I did not
   prove it.
3. **`P(delta > 0) = 0.52` is the honest headline and the `+0.0134 pp` is nearly meaningless on its
   own.** If anyone later quotes "+0.0134 pp, fresher is better" without the CI, they will have
   quoted a coin flip as a finding. The leg count (1.41%) is the robust number here; the P&L is not.
4. **The FRESH-only p = 0.0352 is reported because suppressing it would be dishonest, but I do not
   believe it.** n = 52, three trades carry it, it dies under trimming, and it is one of ~3 tests run
   on the same data. If a future measurement with more legs revives it, that would be new information;
   this one does not establish it.
5. **CA-adjustment plane.** Every number here is on the deep sim's own plane — `candles` @1d, NSE,
   **unadjusted** (`readClosesBatched`/`readSeriesBatched` apply no adjuster), which is deliberate so
   the counterfactual is internally consistent. Live reads the same table `adjust=back`, and a
   separate agent is investigating a possible entry/exit CA-plane split in the live Minervini path.
   ⚠️ **I am flagging rather than resolving this, per the brief.** A split/bonus inside a symbol's
   252-session RS lookback shifts its weighted RS on the adjusted plane but not here — which would
   change *which* names sit near the gate, hence the composition of the 215 changed legs. It should
   not change the *rate* (both arms read one plane), and I did not enumerate the exposure.
6. **Manas only.** Minervini's `perBarRsRank`/`weeklyRankDates`/`asOfRankDate` are line-for-line
   identical (`MinerviniBacktestService.java:728-795`), so the mechanism transfers **[sourced]**, but
   I did not run the Minervini A/B and its universe/gates differ. The leg counts and P&L here are
   Manas figures and should not be quoted for Minervini.
7. **Survivorship (M11) is close to total on this universe** — no symbol's `candles` history ends
   before 2025-03-23, so the 2,496-name universe is essentially "names still listed today". The
   *rate* should transfer (both arms share the population); the *composition* of the flipped set may
   not, and a genuinely point-in-time universe would have more names near the gate boundary in
   drawdowns.
8. **The 1.7025% (Part 2) and 1.6957% (Part 0) populations differ slightly by construction** — the
   spec-isolation run additionally requires the symbol to have `idx ≥ 252` at the *rank date* so that
   #1238's frozen own-RS is defined at all (3,376,887 vs 3,382,316 name-dates). Both are stated
   rather than reconciled into one figure.
9. **The 0-stale rows are a control, not data.** 681,119 name-dates at staleness 0 contribute zero
   flips by construction (the two cross-sections are the same object). They are included in the
   3,382,316 denominator, which is the correct treatment for "how often does cadence matter across
   all bars", but a reader wanting "how often does it matter when the screen IS stale" should use
   57,585 / 2,701,197 = **2.1318%**.

---

## Reproduction

Read-only against the live stack; no writes, no deploys, no code changes. Each deep-sim run holds a
single `SwingBacktestGate` permit — **run them sequentially** or the second returns 409.

```bash
# 1. The four deep-sim runs (unmodified production endpoint, container loopback), ~2 min each.
#    MSYS_NO_PATHCONV=1 is required on this box or Git Bash rewrites /tmp/... to a Windows path.
MSYS_NO_PATHCONV=1 docker exec ay-market-data-service sh -c 'wget -q -O /tmp/deep-manas-turnover.json \
  --header="Content-Type: application/json" \
  --post-data="{\"family\":\"manas\",\"from\":\"2015-08-03\",\"variant\":\"turnover\"}" \
  --timeout=3600 --tries=1 http://127.0.0.1:8081/api/v1/market/screener/deep-swing/run'
#   ... and with variant "rs-turnover-nopyramid" (the primary), the same turnover run a SECOND time
#   (determinism), and "turnover" with from=2018-08-03 (the re-phased window control).

# 2. The two inputs the numpy replica needs. warmStart = from - 600 days = 2013-12-11 for the
#    2015-08-03 run. ⚠️ Using 2013-12-12 here silently shifts EVERY 5th-session rank date and
#    invalidates the whole reconstruction -- there IS a 2013-12-11 session. The bit-exact control
#    against rsRankAtEntry is what catches this; nothing else does.
docker exec ay-timescaledb psql -U artha -d artha -t -A -c "SET search_path TO marketdata,public;
COPY (SELECT DISTINCT bucket::date AS d FROM candles
      WHERE exchange='NSE' AND interval='1d' AND bucket >= '2013-12-11' ORDER BY d)
TO STDOUT WITH CSV" > sessions.csv

docker exec ay-timescaledb psql -U artha -d artha -t -A -F',' -c "SET search_path TO marketdata,public;
COPY (WITH syms AS (SELECT DISTINCT c.tradingsymbol AS ts FROM candles c JOIN instruments i
        ON i.exchange=c.exchange AND i.tradingsymbol=c.tradingsymbol AND i.instrument_type='EQ'
      WHERE c.interval='1d' AND c.exchange='NSE')
      SELECT c.tradingsymbol, c.bucket::date, c.close FROM candles c JOIN syms ON syms.ts=c.tradingsymbol
      WHERE c.exchange='NSE' AND c.interval='1d' AND c.bucket >= '2013-12-11'
      ORDER BY c.tradingsymbol, c.bucket)
TO STDOUT WITH CSV" > eq_closes.csv     # 4,075,977 rows / 2,926 symbols; 2,496 clear MIN_SERIES

# 3. #1238's two axis-1 / axis-2 queries reproduce verbatim from that PR's Reproduction block
#    (both re-run here; both exact). The axis-2 one measures the SUPERSEDED spec -- see Part 0.
```

The numpy replica (≈120 lines: `weightedRs` on own-index lags → per-session as-of matrix via
`searchsorted` → sorted per-session distributions → midpoint `percentile`) is a scratch artifact, not
committed. It is fully specified by the constants and formulas in Part 1 plus
`ManasAroraBacktestService.java:680-767`, and **its correctness claim is not "I read the code right"
but the bit-exact control** — `max |reconstructed − rsRankAtEntry| = 0.0` over 41,154 trades across
three populations (13,303 + 15,277 + 12,574) and two rank-date grids. Anyone re-deriving it should reproduce that control first
and trust nothing until it reads zero.

Traps that cost time here and will again: **`bucket::date` is UTC**, so 1d buckets sit at IST
midnight and a Monday session reads as the preceding Sunday (harmless as long as *every* query uses
it, which the service does and this does — but it makes the session list contain "weekend" dates);
the **`warmStart` off-by-one above**; and `AT TIME ZONE '+05:30'` **inverts** — render with
`'Asia/Kolkata'`, bound with `+05:30` literals.
