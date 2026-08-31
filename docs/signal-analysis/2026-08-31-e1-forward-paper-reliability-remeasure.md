# E1 re-run — forward-paper reliability (ledger row `forward-paper-reliability-month`)

**Date:** 2026-08-31 · **Scope:** READ-ONLY measurement. No code, no config, no arming, no DB writes.
**Ledger row:** `docs/superpowers/plans/2026-07-02-remaining-items.md:1766` (§E, tier OWNER).
**Supersedes-in-part:** [`2026-08-03-e1-forward-paper-reliability.md`](2026-08-03-e1-forward-paper-reliability.md)
(PR #1245), whose verdict was *"the book is not yet measurable"*. **That refusal was correct on its data
and is now partly overtaken.** This doc says exactly which of its findings survived and which did not.
**Data:** live DB `artha` (`ay-timescaledb`); every bound a `+05:30` literal, every render
`AT TIME ZONE 'Asia/Kolkata'`.

Claims labelled `computed` (re-queried today) / `sourced` (read from code or a committed doc) /
`recalled` / `assumed`.

---

## 0. Verdict

> **The scalper book IS now measurable, and the answer is negative and robust. The swing book is
> still three closed trades short of its own bar, but the censoring-corrected view now CLEARS every
> quality criterion — so the honest swing verdict is "the bar is mis-specified", not "the book failed".**

E1 asks for three things. On 2026-08-03 two were refused. Today **all three are answerable**, and
one of the two former refusals reverses.

| E1 asks for | 2026-08-03 | 2026-08-31 verdict |
|---|---|---|
| **Per-scalper keep / cut / tune** | NOT SUPPORTABLE (attribution arithmetically impossible) | **PARTIALLY ANSWERABLE — attribution now works.** 1 CUT-candidate strategy, 3 HOLD-AND-KEEP-ACCRUING, 5 no-verdict, 29 unmeasurable. §3 |
| **E9 exit-band (35% TP / 25% premium SL) tune** | ANSWERED — inert, leave alone | **AMENDED — half-inert.** The **−25% stop now BINDS** (2 intrabar touches, 1 realized close). The **+35% TP is still never touched** — but peak intrabar excursion rose **6.14% → 29.95%**, so it is now *near*-reachable, not structurally unreachable. **Still: do not tune.** §4 |
| **Swing §0.5 #12 reliability sign-off** | REFUSED (sample AND criteria) | **REFUSED ON THE BAR AS WRITTEN (n=27 closed vs ≥30), but every quality criterion now PASSES once censoring is corrected.** Recommend re-specifying the bar rather than waiting. §5 |

**The single most important number, and it is the one that changed:** on 2026-08-03 the scalper
book's headline loss **flipped sign** on leave-two-sessions-out. Today it does not.

| perturbation | 2026-08-03 (n=10, 3 sessions) | **2026-08-31 (n=45, 16 sessions)** |
|---|---|---|
| as measured | **−₹3,109.70** | **−₹21,488.78** |
| drop worst 3 trades | −₹161.12 *(≈ zero)* | **−₹8,977.66** |
| drop worst 5 trades | — | **−₹4,609.78** |
| drop worst 2 sessions | **+₹69.58 — SIGN FLIPS** | **−₹12,045.41** |
| drop worst 3 sessions | — | **−₹8,972.41** |
| excluding the 2 owner MANUAL closes | n/a | **−₹12,595.99** (n=43) |
| first half vs second half by date | n/a | **−₹7,697.07 / −₹13,791.71** — both negative |
| negative sessions | 2 of 3 | **12 of 16** |

*(all `computed`)* **The scalper book's negative sign now survives every perturbation applied to it.**
This is a real result, not a small-n artifact — and it is the first time that sentence can be written.

---

## 1. The window — re-measured, not assumed

*(computed)* The 08-03 refusal's first ground was that the window was not a month. **It is now.**

| book | status | n | first open | last open | **sessions traded** |
|---|---|---|---|---|---|
| `scalper` | CLOSED | **45** | 2026-07-29 | 2026-08-28 | **16** |
| `minervini` | CLOSED | 13 | 2026-07-07 | 2026-08-14 | 7 |
| `minervini` | OPEN | 12 | 2026-07-08 | 2026-08-21 | 8 |
| `manas-arora` | CLOSED | 14 | 2026-07-05 | 2026-08-14 | 11 |
| `manas-arora` | OPEN | 6 | 2026-07-20 | 2026-08-25 | 6 |

Against **23 completed NSE sessions** in 2026-07-29 … 2026-08-28 *(computed from
`marketdata.candles` @1d `NIFTY 50`)*, the scalper book traded on **16**. Accrual went
**3 sessions / 10 closed trades → 16 sessions / 45 closed trades**, a 4.5× increase in trades and
5.3× in sessions. **The "not yet a month" ground is discharged.**

## 2. Attribution — #1259 merged, deployed, and it works

*(computed + sourced)* PR **#1259** (`feat(strategy-signal): per-signal lot tagging so a pyramided
position decomposes per strategy`) is **MERGED** (`e929d15d`, 2026-08-03T17:38Z) and **DEPLOYED** —
`strategy.paper_position_lots` exists in `artha` and carries **53 lots across 44 positions**, first
row **2026-08-05 11:04:20 IST**, last **2026-08-28 11:58:12 IST**. Every lot has a non-null
`signal_id`, so `lot → signals → strategy_versions → strategies.slug` resolves 100%.

**Coverage is partial and that matters:** lots cover **35 of the 45** closed scalper positions (the
other 9 lot-covered positions are swing). The 10 pre-deploy scalper positions (2026-07-29 …
2026-08-03) have **no lots** and are attributable only by the old, wrong `opening_signal_id`.
**All per-strategy figures in §3 are on the 35-position / 44-lot subset; all book-level figures are
on the full 45.** Stated wherever used.

### 2.1 ⚠️ The blocking finding of 2026-08-03 has DISSOLVED — the twins now fire solo

The 08-03 doc and the sub-books spike (`docs/superpowers/plans/2026-08-03-scalper-per-strategy-sub-books.md`
§2.1) both recorded that `scalp-connect-the-dots-*` had **zero solo entry bars, n=14** — every one of
its fires was also a `scalp-golden-crossover-*` fire, so the pair was inseparable *at any sample size*.

**That is no longer true.** *(computed)* Of the 35 lot-covered scalper positions, **26 carry exactly
one lot** (single-strategy) and only **9 carry two**:

| slug | solo positions | twinned positions |
|---|---|---|
| `scalp-golden-crossover-sensex-niftyoi-pe` | **11** | 4 |
| `scalp-connect-the-dots-sensex-niftyoi-pe` | **4** | 3 |
| `scalp-golden-crossover-nifty-pe` | **4** | 2 |
| `scalp-connect-the-dots-nifty-pe` | **4** | 2 |
| `scalp-connect-the-dots-nifty` | 1 | 2 |
| `scalp-connect-the-dots-sensex-niftyoi` | 1 | 1 |
| `scalp-golden-crossover-nifty` | 1 | 2 |
| `scalp-golden-crossover-sensex-niftyoi` | 0 | 1 |
| `scalp-gap-theory-sensex-niftyoi-pe` | 0 | 1 |

`connect-the-dots` now has **10 solo positions**, `golden-crossover` **16**. The 08-03 sub-books
spike's finding #1 ("connect-the-dots has contributed zero independent entry decisions") is
**superseded by data, not by argument** — and its conclusion (*skip sub-books; E1 is partly
answerable without code*) is **vindicated**: no code was needed, the solo sample arrived on its own.

**Two twins even entered at DIFFERENT prices and times** — position 73 (`744.50` @ 10:46 vs `706.80`
@ 10:52, 6 min apart) and position 98 (`212.40` vs `191.65`, 6 min apart). The 08-03 "identical price,
zero entry edge by construction" caveat therefore no longer holds universally, though it still holds
for **7 of the 9** twins.

**Method for §3:** each position's `realized_pnl` is split across its lots as
`qty_lot × (exit_vwap − lot_fill_price) × side_sign`, with the position's residual cost
(`realized_pnl − Σ gross_lot`) allocated pro-rata by `qty`. Exit VWAP from
`paper_orders WHERE leg_kind='EXIT' AND status='FILLED'`. Costs re-verified below.

### 2.2 Harness re-verification (re-measured, not recalled)

*(computed — the 08-03 finding is REPRODUCED, not carried)* `realized_pnl` is **NET of costs**.
Reconstructing gross from the entry/exit fills and differencing:

| book | n | mean round-trip cost | as % of entry notional |
|---|---|---|---|
| `scalper` (options) | 35 | **₹95.92** | **0.571%** |
| `manas-arora` (equity) | 5 | ₹44.91 | 0.283% |
| `minervini` (equity) | 4 | ₹26.00 | 0.274% |

Matches 08-03's 0.588% / ~27 bps to within rounding. **No cost double-count, no missing cost.**
The options fill model is still `ltp_slippage/v1` with a flat ₹0.05 option tick *(sourced, 08-03 §2.3)* —
optimistic, so **the measured losses remain a floor, not a ceiling**.

---

## 3. Per-scalper keep / cut / tune

### 3.1 Book level (all 45 closed positions)

*(computed)* **n=45 · 16 sessions · net −₹21,488.78 · 13 winners (28.9%)**. Robustness table in §0.
By option side:

| side | n | sessions | winners | net |
|---|---|---|---|---|
| **PE** | 29 | 10 | 8 | **−₹17,593.49** |
| **CE** | 16 | 6 | 5 | **−₹3,895.29** |

⚠️ **The 08-03 "PE is unmeasurable" finding is REVERSED.** That doc recorded **zero PE signals ever**
and read the 60m SuperTrend bias veto as blocking 100% of PE evaluations on a bullish tape. *(computed)*
**Five PE slugs have now fired**, first on **2026-08-11**, and PE is the **dominant loss source**.

⚠️ **And the "one-directional bullish tape" excuse does not apply to the loss.** *(computed)* Over the
PE-active window `NIFTY 50` went **24,471.70 (08-11) → 24,175.65 (08-28), −1.21%**, and from the
08-03 peak 24,774.30 it is **−2.42%**. **The PE side lost ₹17,593 on a tape that drifted down.**
That is a substantive negative result about the PE scalpers, not a sampling artifact.

### 3.2 Per-strategy, lot-attributed (35 positions / 44 lots)

*(computed)* Blended view — every lot counted, twins split:

| slug | legs | positions | sessions | net | sign flips on leave-3-out or drop-worst-session? |
|---|---|---|---|---|---|
| `scalp-golden-crossover-sensex-niftyoi-pe` | 15 | 15 | 9 | **−₹10,423.74** | **no** (−1,566.58 / −6,548.92) |
| `scalp-connect-the-dots-sensex-niftyoi-pe` | 7 | 7 | 6 | −₹4,719.58 | **YES** (+2,860.20 / +298.39) |
| `scalp-connect-the-dots-sensex-niftyoi` | 2 | 2 | 2 | −₹3,475.81 | **YES** (0.00) |
| `scalp-golden-crossover-nifty-pe` | 6 | 6 | 5 | −₹2,437.77 | **YES** (+534.81) |
| `scalp-golden-crossover-sensex-niftyoi` | 1 | 1 | 1 | −₹892.08 | n/a (n=1) |
| `scalp-gap-theory-sensex-niftyoi-pe` | 1 | 1 | 1 | −₹288.22 | n/a (n=1) |
| `scalp-connect-the-dots-nifty-pe` | 6 | 6 | 5 | +₹275.82 | **YES** (−2,200.84) |
| `scalp-connect-the-dots-nifty` | 3 | 3 | **1** | +₹1,666.17 | **YES** — single session |
| `scalp-golden-crossover-nifty` | 3 | 3 | **1** | +₹1,916.12 | **YES** — single session |

### 3.3 The uncontaminated view — solo positions only

*(computed)* Restricting to the **26 single-lot positions** (no twin blending at all), and then
excluding the 2 owner `MANUAL` closes (n=24, net −₹19,481.26):

| slug | n | sessions | winners | net | drop worst 1 | drop worst 3 |
|---|---|---|---|---|---|---|
| `scalp-golden-crossover-sensex-niftyoi-pe` | **10** | **6** | 2 | **−₹8,307.55** | −₹4,689.22 | **−₹2,324.51** |
| `scalp-golden-crossover-nifty-pe` | 4 | 3 | 0 | −₹3,509.39 | −₹2,180.79 | −₹536.81 |
| `scalp-connect-the-dots-sensex-niftyoi` | 1 | 1 | 0 | −₹2,583.73 | — | — |
| `scalp-connect-the-dots-sensex-niftyoi-pe` | 3 | 3 | 1 | −₹2,502.47 | −₹805.51 | 0.00 |
| `scalp-connect-the-dots-nifty-pe` | 4 | 4 | 2 | −₹2,144.55 | −₹681.99 | **+₹625.03 — flips** |
| `scalp-connect-the-dots-nifty` | 1 | 1 | 0 | −₹341.76 | — | — |
| `scalp-golden-crossover-nifty` | 1 | 1 | 0 | −₹91.81 | — | — |

⚠️ **Every strategy is negative on its solo sample — including the two that look positive in §3.2.**
`scalp-golden-crossover-nifty` (+₹1,916 blended) and `scalp-connect-the-dots-nifty` (+₹1,666 blended)
are **both negative solo** (−₹92, −₹342). Their blended profit comes entirely from the **twinned**
positions, which as a group returned **+₹12,468.13 on TIME_STOP** while solo TIME_STOP positions
returned **−₹3,664.23**. *(computed)* **The apparent NIFTY-CE winners are an artifact of which
positions happened to be double-entered, and it is a single session (2026-08-20) in both cases.**

### 3.4 Verdicts

| strategy | n (solo / blended legs) | sessions (solo / blended) | **verdict** | evidence |
|---|---|---|---|---|
| `scalp-golden-crossover-sensex-niftyoi-pe` | **10 / 15** | **6 / 9** | ⚠️ **CUT CANDIDATE — recommend owner review, not an automatic cut** | Largest single loss source (−₹10,424 blended, −₹8,308 solo, MANUAL-excluded). **Only strategy whose negative sign survives leave-3-out on its own solo sample** (−₹2,324.51). 8 of its 10 solo exits are `STRUCTURAL_STOP`. |
| `scalp-golden-crossover-nifty-pe` | 4 / 6 | 3 / 5 | **HOLD — keep accruing** | Negative in every view (−₹3,509 solo, 0 winners in 4), **but n=4 and the sign flips on leave-3-out.** Watch; do not cut on this. |
| `scalp-connect-the-dots-sensex-niftyoi-pe` | 3 / 7 | 3 / 6 | **HOLD — keep accruing** | Sign flips both ways. |
| `scalp-connect-the-dots-nifty-pe` | 4 / 6 | 4 / 5 | **HOLD — keep accruing** | Only strategy positive blended *and* it flips to +₹625 on leave-3-out solo. Genuinely undetermined. |
| `scalp-golden-crossover-nifty` | 1 / 3 | 1 / 1 | **NO VERDICT** | 1 solo position, 1 session. Blended profit is one day. |
| `scalp-connect-the-dots-nifty` | 1 / 3 | 1 / 1 | **NO VERDICT** | as above |
| `scalp-golden-crossover-sensex-niftyoi` | 0 / 1 | 0 / 1 | **NO VERDICT** | never fired solo; last signal 2026-08-06 |
| `scalp-connect-the-dots-sensex-niftyoi` | 1 / 2 | 1 / 2 | **NO VERDICT** | n=1 solo |
| `scalp-gap-theory-sensex-niftyoi-pe` | 0 / 1 | 0 / 1 | **NO VERDICT** | 1 signal ever (2026-08-24) |
| **29 others** | 0 | 0 | **UNMEASURABLE-SO-FAR** | never fired a signal, ever |

**Zero TUNE recommendations.** *(sourced — standing prior: every measured loosening of the scalper
entry gate has lost money, T1/T7/G13/G10)* Nothing here clears legs → P&L → sign robustness → costs
for a gate change. The one loosening-shaped idea the data suggests (relax `STRUCTURAL_STOP`, which
carries −₹12,675 of the loss) is **exactly the shape the prior warns about** and is
selection-confounded — see §4.3.

*(computed)* **29 of 38 published+enabled scalpers have never emitted a signal.** The 9 that have:
`golden-crossover-nifty` 30 · `golden-crossover-sensex-niftyoi-pe` 28 · `connect-the-dots-nifty` 21 ·
`connect-the-dots-nifty-pe` 17 · `connect-the-dots-sensex-niftyoi-pe` 17 ·
`connect-the-dots-sensex-niftyoi` 15 · `golden-crossover-nifty-pe` 15 ·
`golden-crossover-sensex-niftyoi` 15 · `gap-theory-sensex-niftyoi-pe` 1. **13 of 18 `-pe` slugs are
still at zero.** Absence of fires is not evidence about a strategy — no cut verdicts for these.

---

## 4. E9 exit band — amended, still do not tune

### 4.1 Published config *(computed, read from `strategy_versions.config->'exit_rules'` at `published_version_id`)*

```
take_profit    premium_pct 35        stop_loss     premium_pct 25
signal_exit    vwma20 vs vwap        trailing_stop indicator supertrend_line
time_stop      max_bars 12           risk.max_daily_loss_pct 2
```

⚠️ **`time_stop.max_bars` is 12, not the 10 the 08-03 doc recorded** *(computed)* — the config moved
between the two measurements. At 3m primary that is **36 minutes, not 30**. Any comparison against
08-03's `time_stop` numbers must carry this.

⚠️ **`stop_loss index_points 60` is NOT in `exit_rules` any more**, yet `STRUCTURAL_STOP` still fires
20 times. *(sourced)* It is not an exit rule: `SignalEngine.java:1706-1713` evaluates
`structuralStopHit(...)` against the **signal's own `stop_loss` level on the underlying**, live-only,
at protective priority ahead of the confluence path. Do not look for it in `exit_rules`.

### 4.2 Does the band bind? — half of it now does

*(computed)* `TAKE_PROFIT` **still does not appear as a `close_reason` anywhere in
`strategy.paper_positions`, all books, all time.** Distribution: `TRAILING_STOP` 22 ·
`STRUCTURAL_STOP` 20 · `TIME_STOP` 18 · `STOP_LOSS` 8 · `MANUAL` 4 · NULL 18.

Excursion, measured against **1m bars during each hold** (44 of 45 positions have bars) — this is a
true intrabar max, not the at-exit proxy the 08-03 doc used:

| | 2026-08-03 (at exit, n=10) | **2026-08-31 (intrabar, n=44)** |
|---|---|---|
| max favourable excursion | +6.14% | **+29.95%** |
| positions touching the **+35% TP** | 0 | **0 of 44** |
| positions touching the **−25% premium stop** | 0 | **2 of 44** |
| `STOP_LOSS` closes realized | 0 | **1** (id 64, 2026-08-13, entry 324.95 → stop 243.68 = −25.0%) |

**Amended verdict: the band is no longer inert — the −25% half binds. The +35% half has still never
been touched, but it is now near-reachable rather than structurally unreachable.**

**Recommendation: leave the band untouched.** *(computed)* The three largest favourable excursions
were **+29.95% / +25.51% / +19.14%**, and **all three closed by `TIME_STOP`** as the book's three
largest winners (+₹4,454.56 / +₹4,006.00 / +₹3,604.56). A TP at ~20% would have captured them nearer
their peaks — but that is **n=3, fitted to the three best trades in the sample**, and it is the
textbook shape of a curve-fit. **Do not act on it.** Re-read at the next E1 cadence.

### 4.3 ⚠️ The exit-model split is real but selection-confounded — do NOT read it as "remove the structural stop"

*(computed, lot-attributed, n=44 legs / 13 sessions)*

| exit model | legs | net | per leg |
|---|---|---|---|
| `TIME_STOP` (12 bars) | 19 | **+₹8,803.91** | +₹463.36 |
| `STRUCTURAL_STOP` | 18 | **−₹12,675.32** | −₹704.18 |
| `TRAILING_STOP` | 4 | −₹3,917.93 | −₹979.48 |
| `STOP_LOSS` (premium 25%) | 1 | −₹1,696.96 | −₹1,696.96 |
| `MANUAL` (owner) | 2 | −₹8,892.79 | −₹4,446.40 |

This reproduces **T29**'s direction on a 4.4× larger sample from an independent angle. ⚠️ **It is not
evidence for removing the structural stop.** The exit reason is *selected by the outcome*: a trade
that goes against you exits `STRUCTURAL_STOP` **because** it went against you, and can never reach
`TIME_STOP`. The comparison is confounded by construction, and only a **counterfactual replay**
(the shadow-book method) can settle it. **T29 stays open and this section adds corroboration, not
resolution.**

### 4.4 Owner MANUAL closes — flagged, not analysed

*(computed, `strategy.paper_admin_audit`)* Two `MANUAL_CLOSE` rows, both **2026-08-28 19:56 IST**:
position 99 (`SENSEX2690377900PE`, −₹3,874.82 @ 589.40) and position 100 (`SENSEX2690377800PE`,
−₹5,017.97 @ 526.20). **₹8,892.79 — 41% of the book's total loss — is owner intervention, not
strategy exit doctrine.** Both belong to `scalp-golden-crossover-sensex-niftyoi-pe`. Excluding them
the book is **−₹12,595.99 (n=43)** and that strategy is **−₹8,307.55 solo (n=10)** — the CUT-candidate
verdict in §3.4 is computed **on the MANUAL-excluded figure**, so it does not rest on the owner's action.

---

## 5. Swing book — §0.5 #12 reliability sign-off

**The bar** *(sourced,* `archive/2026-07-04-minervini-build-log.md:496`, `:440`*)*: **≥30–50 closed
single-stop forward paper trades**, **expectancy > 0**, **payoff ≥ 2**, **batting ≥ ~45%**.

### 5.1 Closed-only — still fails, but every metric improved

*(computed)*

| book | n | batting | avg win | avg loss | expectancy | payoff | net |
|---|---|---|---|---|---|---|---|
| `minervini` | 13 | 7.7% | +1.86% | −7.55% | **−6.83%** | 0.25 | −₹8,414.70 |
| `manas-arora` | 14 | 28.6% | +7.57% | −6.92% | **−2.78%** | 1.09 | −₹5,932.47 |
| **combined** | **27** | **18.5%** | +6.43% | −7.26% | **−4.73%** | **0.89** | **−₹14,347.17** |

vs 2026-08-03: n 18 → **27**, batting 5.6% → **18.5%**, expectancy −6.72% → **−4.73%**,
payoff 0.00/0.36 → **0.89**. Every metric moved toward the bar; none reached it, and **n is still 3
short of the minimum**.

### 5.2 ⚠️ Censoring correction — and this time it changes the SIGN

The 08-03 doc's warning stands and has strengthened: an 8%-stop / 50-day-MA-trail book cuts losers
fast and lets winners run, so **closed-only sampling preferentially samples losers**.

*(computed, 18 open positions marked to their last stored NSE 1d close; newest bar 2026-08-28;
0 unpriced)*

| | closed | open |
|---|---|---|
| **win rate** | 5 of 27 = **18.5%** | **17 of 18 = 94.4%** |
| **P&L** | **−₹14,347.17** | **+₹35,355.50** |

| book | realized | unrealized | combined |
|---|---|---|---|
| `minervini` | −₹8,414.70 | **+₹22,441.20** | **+₹14,026.50** |
| `manas-arora` | −₹5,932.47 | **+₹12,914.30** | **+₹6,981.83** |
| **total** | **−₹14,347.17** | **+₹35,355.50** | **+₹21,008.33** |

⚠️ **And unlike 08-03, the unrealized gain is NOT concentrated.** *(computed)* On 08-03 one name
(`DIACABS`) was **106%** of the unrealized total. Today the largest single contributor is still
`DIACABS` (+₹4,979.10, +51.2%) but that is **14.1%** of +₹35,355.50; the gain is spread over
**17 of 18** positions ranging +5.1% to +51.2%, with one loser (`INDOTECH`, −1.0%).

Treating each open position as an observation at its mark:

| scope | n | batting | avg win | avg loss | **expectancy** | **payoff** | rupees |
|---|---|---|---|---|---|---|---|
| **all (closed + MTM open)** | **45** | **48.9%** | +15.77% | −6.99% | **+4.13%** | **2.25** | **+₹21,008.33** |
| `minervini` | 25 | 48.0% | +20.14% | −7.05% | +6.00% | 2.86 | +₹14,026.50 |
| `manas-arora` | 20 | 50.0% | +10.52% | −6.92% | +1.80% | 1.52 | +₹6,981.83 |

Sign robustness on that view *(computed)*: as measured **+4.13% / +₹21,008**; drop best 3
**+1.66% / +₹9,824**; drop best 5 **+0.43% / +₹2,809**; drop `DIACABS` **+3.06% / +₹16,029**.
**The sign survives leave-3-out and leave-out-the-top-name; it is thin but not gone at leave-5-out.**

### 5.3 Sign-off verdict

> ### ⛔ **Sign-off REFUSED on the bar as written** — n=27 closed against a ≥30 minimum, and on
> closed trades all three quality criteria fail (batting 18.5% < 45%, expectancy −4.73% < 0,
> payoff 0.89 < 2).
>
> ### ⚠️ **But the refusal is now a MEASUREMENT-DEFINITION problem, not a book-quality problem.**
> On the censoring-corrected basis the same book shows **n=45, batting 48.9%, expectancy +4.13%,
> payoff 2.25** — **all four criteria met**, with the sign robust to leave-3-out.
>
> **Recommendation to the owner (a decision, not a measurement):** re-specify §0.5 #12 to be
> censoring-aware — e.g. *"≥30 closed trades **or** ≥40 position-observations marked to market, with
> realized and unrealized stated together"* — rather than waiting for 3 more closes. As written, the
> bar systematically defers sign-off on precisely the book behaviour the strategy is designed to
> produce. **I am not granting sign-off on the marked-to-market view;** an open winner is not a
> result, and 3 more closed trades will settle the letter of the bar within weeks either way.

### 5.4 The 8% stop still realizes ~11% — reproduced

*(computed, n=7 `STOP_LOSS` closes)* `minervini` **−11.14%** (n=4), `manas-arora` **−10.79%** (n=3),
against a configured **−8%**. The 08-03 finding (~11.7%, n=5) **reproduces on a larger sample**.
Cause unchanged *(sourced, 08-03 §4.3)*: swing exits are close-basis only
(`ExitEvaluator.java:323`, `SwingBatchEngine.java:1072`), no intrabar touch, and equities are not on
the live tick subscription so the intraday bracket never fires (T10). **Risk per trade runs ~35–40%
wider than configured; expectancy must be judged against the realized stop, not the configured one.**
Owner already accepted EOD-only exits (#992), so this is a sizing/expectation note, not a defect.

---

## 6. What changed vs 2026-08-03 — the survival table

| 08-03 finding | status today |
|---|---|
| Window is 3 sessions / 10 trades, not a month | **DISCHARGED** — 16 sessions / 45 trades |
| Per-strategy attribution arithmetically impossible | **DISSOLVED** — #1259 deployed 08-05; 26 of 35 positions are single-strategy |
| Twins never fire solo (0/14) | **FALSIFIED BY DATA** — 26 solo positions; 2 twins even entered at different prices |
| Headline sign flips on leave-two-sessions-out | **NO LONGER** — sign survives every perturbation applied |
| E9 band inert, never binds | **AMENDED** — the −25% half binds (2 touches, 1 close); the +35% half still never touched |
| Max premium excursion 6.14% | **+29.95% intrabar** (and the 08-03 figure was at-exit, a weaker proxy) |
| 18 PE scalpers have zero signals ever | **REVERSED** — 5 PE slugs fired from 08-11; PE is the dominant loss source, on a tape that fell 1.2% |
| `time_stop` is 10 bars | **12 bars** today — config moved between measurements |
| Swing unrealized carried by one name (106%) | **NO LONGER** — top name is 14.1% of +₹35,355; 17 of 18 open positions are winners |
| Swing negative robust to leave-3-out | **REVERSED on the corrected basis** — +₹9,824 at leave-3-out |
| `realized_pnl` is net of costs; options cost ≈0.59% notional | **REPRODUCED** — 0.571% (n=35) |
| Swing 8% stop realizes ~11.7% | **REPRODUCED** — 10.79% / 11.14% (n=7) |
| §5 stale-entry finding (already self-falsified 08-03) | **remains falsified** — not re-examined |

---

## 7. Open doubts

1. **Selection confound in §4.3 is not resolved and cannot be from this data.** The
   `TIME_STOP +₹8,804` / `STRUCTURAL_STOP −₹12,675` split is the single most action-tempting number
   in this doc and it is the one least entitled to action. Only a counterfactual replay settles it.
2. **The per-strategy sample is still small in the way that matters.** The strongest verdict
   (`golden-crossover-sensex-niftyoi-pe`) rests on **10 solo positions over 6 sessions**. Its sign
   survives leave-3-out at −₹2,324 — real, but 72% of its loss is in 3 trades. I call it a CUT
   *candidate for owner review*, not a cut.
3. **10 of 45 closed positions predate lot tagging and are excluded from every per-strategy figure.**
   They are the 07-29…08-03 set, which is exactly the set the 08-03 doc showed had the least robust
   sign. Their exclusion is not neutral, and I did not model it.
4. **`connect-the-dots-nifty` / `golden-crossover-nifty` are one session each (2026-08-20).** Their
   solo n=1 verdicts are formalities. 2026-08-20 was the book's best session (+₹3,582); a single good
   day is doing all the work in the blended table.
5. **The swing mark-to-market uses last stored NSE 1d close (newest bar 2026-08-28)**, not today's.
   Three sessions of drift are unmodelled. It also assumes the open positions are exitable at the
   close — no liquidity or slippage haircut applied, which flatters the corrected view.
6. **Open swing positions are aged** — the oldest opened 2026-07-08 and is up 51%. A trend book's open
   book is survivorship by construction; the corrected view in §5.2 is a *floor on how wrong the
   closed-only view is*, not a claim that these gains will be realized.
7. **I did not re-derive the 60m SuperTrend bias-veto mechanism.** I measured that PE now fires and
   loses; I did not establish *why* it started firing on 2026-08-11 rather than earlier. Something
   changed — a config republish, the tape rolling over, or a code change — and I did not identify it.
   That is a real gap: if the PE fires are a side effect of an unintended change, the PE loss is
   evidence about *that*, not about the strategies.
8. **`max_bars` moved 10 → 12 and I did not find when or in which PR.** Any cross-doc comparison of
   `TIME_STOP` behaviour between 08-03 and today is contaminated by it.
9. **Costs are measured on the 35 positions that have reconstructible exit orders**, not all 45.
10. **Nothing here is a counterfactual.** Every figure is what the book did, not what an alternative
    would have done. No knob recommendation follows from it, and none is made.
