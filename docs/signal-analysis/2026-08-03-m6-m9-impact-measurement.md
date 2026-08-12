# 2026-08-03 — M6 / M9 impact measurement (E4 `audit-doctrine-holds` residue)

Owner-commissioned measurement of the two remaining #128 swing exit-parity items the E4 ledger row
left explicitly OUT of both shipped slices ("M6/M9 alignment and any M8/M10 alignment remain
explicitly OUT — HOLD … for the owner"), and which its own scoping doc scoped but did not execute
(`docs/signal-analysis/2026-08-02-e4-128-batch-scoping.md` Part 4 Item 4; Open Doubt 5: *"the M6/M9
measurement item is scoped but not executed"*).

**This is MEASUREMENT-ONLY.** No production code, strategy config, YAML, migration or `.env` was
changed; no golden vector or fixture was touched; nothing is armed. The only artifacts are this
document and the two deterministic deep-sim runs it cites, both driven through **existing production
endpoints** with no code modification of any kind — so there was no engine-record change and hence
nothing for the Golden+Parity ladder to gate (stated explicitly because the brief flagged it: the
`SignalEvent`/`Trade` records were not read, written or extended in this pass).

Every claim is tagged **[computed]** (derived here from code/DB I read on this checkout),
**[sourced]** (quoted from a cited file:line / SQL output / test name), **[recalled]** (memory, not
re-verified), or **[assumed]** (an inference I could not fully verify). A claim computed from an
assumed input stays **[assumed]**.

---

## Read this first — the bottom line

**Verdict: ACCEPT both. Do not align either one on a P&L argument — there is no P&L there to
argue about. [computed]**

- **M6 is worth `+0.00123 pp` on mean per-trade gross return** for the Manas family (2.17816% →
  2.17939%, i.e. **+0.056% relative**), measured over **13,303 deep-sim trade legs / 12,386 distinct
  entry events** across 2,496 symbols and 11 years. It touches **119 legs = 0.8945%** of that
  population. For Minervini it is **exactly 0 of 16,739 entry bars**, and that zero is *structural*,
  not lucky — the deep sim's own entry gate makes the only entry-bar-reachable exit rule
  unsatisfiable. **[computed]**
- **M9's named divergence — the ordinal-vs-midpoint rank formula — flips the `rs ≥ 70` gate for 5
  name-dates out of 45,069** (Manas; 0.0111%) and **5 of 37,114** (Minervini; 0.0135%), bounded in
  closed form at **≤ 1 name per screen date, ever**. **[computed]**
- **But the same code carries an unnamed sibling divergence ~150× larger**: the backtest ranks only
  at *weekly* rank dates and reuses that rank for up to 4 more sessions, while live re-ranks every
  session. Holding the population fixed, that flips the same gate for **1.7025% of 3,382,316
  name-dates**, rising monotonically with staleness. **If the owner ever wants the
  backtest's RS gate to match live, the formula is not where the divergence lives.** **[computed]**

> ⚠️ **CORRECTED 2026-08-03, same day, by the follow-up A/B ([#1244](https://github.com/prashantm912/artha-yantra-2/pull/1244)).**
> This section originally read **1,608 of 44,975 = 3.5753%** and **322×**. Those numbers are an
> **overstatement by 2.05×** — the arithmetic was sound but the *spec* was not. The SQL here froze the
> symbol's **own** RS at the stale rank date **as well as** the cross-section. Production does not do
> that: `perBarRsRank` (`ManasAroraBacktestService.java:717-731`, Minervini identical) recomputes
> `weightedRs(close, i)` at the **current** bar and percentiles it against the stale `dist.get(rd)` —
> **only the denominator is stale**. On one fixed 3,376,887-name-date population, production spec
> measures **1.6957%** against this doc's **3.4845%**. The corrected 11-year figure is **1.7025%**,
> i.e. **153×** the formula term, not 322×. Both of this doc's headline numbers were independently
> reproduced exactly before the spec error was found — reproducibility did not catch it, because the
> flaw was in what was being measured, not in the measuring.

The honest one-line framing: **M6 and M9-as-written are both real, both correctly identified, and
both economically inert.** The measurement's value is not the verdict (which is "accept") but the
two things it turned up on the way — the M9 cadence term above, and the M6 cross-plane caveat in
§3.4 — neither of which was in the #128 batch.

Direction, since "how much" is meaningless without it: on the affected population **live is the
better side of M6**, not the worse one. Live books a 0.00% gross round trip; the backtest holds at
least one more bar and books a mean of −0.47% per event. Aligning *live to the backtest* (suppressing
entry-bar exits) would therefore cost about what aligning the other way would gain — ~0.001 pp per
trade either way.

---

## Part 0 — Premise check (STEP 0)

The brief framed M6/M9 as "a backtest-vs-live swing exit divergence". **Half right, and the half that
is wrong matters for how the result is read. [computed]**

- **M6 IS exactly that**: "Live engines evaluate exits on the entry bar; both backtests never do"
  (`2026-08-02-e4-128-batch-scoping.md:152`).
- **M9 is NOT an exit divergence.** It is "RS-rank universe/convention differs live vs backtest"
  (`:227`) — an *entry-selection* divergence: which names clear the `rs ≥ 70` gate and become
  candidates at all. It changes the trade population, never an exit. I measured it as an
  entry-selection divergence; §4 reports gate flips, which is the correct leg unit for it, and
  deliberately does **not** convert those flips into a P&L number (see §4.4 for why that would be
  manufactured rather than measured).

The measurement has **not** in fact already been done — verified by reading the E4 row in full
(`docs/superpowers/plans/2026-07-02-remaining-items.md:727`, which states the residue is "the #128
batch (slice 1 MERGED #1215; slice 2 = M4/M8/M10 characterization … the M6/M9 impact measurement +
any M8/M10 alignment still open)") and the scoping doc's Open Doubt 5. **[sourced]** So the brief's
premise stands and the work proceeded.

---

## Part 1 — What the divergence IS, taken from the fixture and tests, not from prose

Per the brief, the definition of the divergence is the shipped #1215 characterization artifact, not
the audit's English. **[sourced]**

`contracts/fixtures/swing-exit-equivalence.json` → `entryBarDivergence`, consumed by
`ManasSwingExitEquivalenceTest.entryBarDivergenceLiveCanExitOnTheEntryBarButTheDeepSimStructurallyCannot`:

```json
"entryBarDivergence": {
  "config":  { "exit_rules": [ { "type": "square_off", "params": { "fast_pct": 35, "fast_bars": 3 } } ] },
  "closes":  ["100.00", "100.00", "100.00", "140.00"],
  "entryIndex": 3,
  "expect":  { "liveFiresOnEntryBar": true, "reason": "square_off" }
}
```

Reduced to its operative algebra against the **live** exit rules the two families actually publish,
M6 is not one condition but a small closed set. On the entry bar the live engine sets
`entryPrice = bar.close()` (`SwingBatchEngine.java:1046` uses `bar.close()` symmetrically on the exit
side) and evaluates `ExitEvaluator.evaluate(..., series.size()-1)` (`SwingBatchEngine.java:803-808`)
in the fixed precedence `stop_loss → trailing_stop → take_profit → scaled_exit → square_off →
time_stop → signal_exit` (`ExitEvaluator.java:19`, `:326-329`). Therefore: **[computed]**

**Manas** (`manas-arora-breakout.yaml:47-52`, `manas-arora-vcp.yaml:45-50` — identical exit blocks):

| # | Rule | Fires on the entry bar iff | Why |
|---|---|---|---|
| — | `stop_loss` atr_multiple 2, cap_pct 10 | **never** | entry-relative; `close == entryPrice`, stop is strictly below |
| **A** | `trailing_stop` atr_multiple 2, `arm_pct: 9`, `atr_basis: rolling`, `breakeven_floor: true` | `high[i] ≥ close[i] × 1.09` | `rollingAtrTrailLevel` (`ExitEvaluator.java:519-557`) runs `j = entryIndex..index` — one iteration on the entry bar. Arms iff the bar's own high is ≥9% over entry; the breakeven floor then makes `level ≥ entry = close[i]`, so `close ≤ level` is **necessarily** true. Arming *is* firing. |
| **B** | `square_off` `fast_pct: 35, fast_bars: 3` | `close[i] ≥ close[i-3] × 1.35` | entry-independent bar function (`ExitEvaluator.java:404-416`) |
| **C** | `square_off` `parabolic_ma: 10, parabolic_dist_pct: 40` | `close[i] ≥ SMA10[i] × 1.40` | entry-independent bar function (`ExitEvaluator.java:417-435`) |

**Minervini** (`minervini-vcp.yaml:41-42` and the three siblings — identical):

| # | Rule | Fires on the entry bar iff | Why |
|---|---|---|---|
| — | `stop_loss` percent 8 | **never** | `close == entryPrice` |
| **D** | `trailing_stop` `basis: indicator, alias: sma50` | `close[i] ≤ sma50[i]` | `ExitEvaluator.java:659-670` |

Clause A was **not** named anywhere in the #128 batch, the scoping doc, or the fixture — the fixture
pins only the `square_off` shape. It turns out to be the **dominant** M6 mechanism (67% of affected
legs, §2.2). Flagged as a finding, not a defect: the fixture is a characterization of *a* divergence,
and correctly labels itself as such.

Both deep sims structurally cannot reach any of A–D: `ManasAroraSwingBacktest.simulateSetup` adds the
lot then `continue`s (`ManasAroraSwingBacktest.java:290-300`), and `MinerviniSwingBacktest`'s entry
`if` / exit `else` are mutually exclusive within one iteration (`MinerviniSwingBacktest.java:177-194`).

---

## Part 2 — M6 measured

### 2.1 Method and population

Deploy-free. The entry population is produced by **unmodified production code** through the existing
`POST /api/v1/market/screener/deep-swing/run` endpoint (`DeepSwingBacktestController.java:70-100`),
called on the live stack's market-data-service over the container loopback. It returns per-trade rows
(`DeepSwingTrade`: symbol, setup, entryDate, entryPrice, exitDate, exitPrice, pnlPct, barsHeld,
exitReason, rsRankAtEntry). Conditions A–D are then evaluated on **the same bars the sim itself read**
— `marketdata.candles` @ `interval='1d'`, `exchange='NSE'`, unadjusted, which is the deep sim's own
plane (`ManasAroraBacktestService.java:904-919` reads it by direct JDBC with no adjuster). **[computed]**

| | Manas | Minervini |
|---|---|---|
| Variant | `rs-turnover-nopyramid` (the primary; pyramiding off = live) | `rs-turnover` (the primary) |
| Window | `from=2015-08-03` (11 y) | `from=2015-08-03` |
| Symbols scanned | 2,496 | 2,496 |
| **Trade legs (the population)** | **13,303** | **23,113** |
| Distinct entry events | 12,386 | 16,739 |
| Run headline | CAGR 25.20 / maxDD 64.40 / Sharpe 0.71 / win 50.2293% | CAGR 20.86 / maxDD 47.76 / Sharpe 0.67 |

⚠️ **Population caveats, in the same sentence as the numbers**: this is the deep sim's *unconstrained*
trade set (every setup on every symbol), not a slot-limited portfolio — the portfolio-admitted subset
of the Manas run is only 1,328 of 13,303. It is **survivorship-biased** (M11: the universe is the set
of symbols that still have `candles` rows today; no point-in-time constituent source exists in-repo)
and **capacity-unbounded**. Those caveats apply to the *rate* (0.89%) as much as to any P&L. They do
not, however, threaten the direction of the M6 result, because both arms of the M6 comparison are
drawn from the identical population — M6 is a within-trade timing question, so survivorship cancels.

**Two harness controls, both required before trusting anything below: [computed]**

1. **Join-key validation.** The sim's `entryDate` is `bucket::date`, which is **UTC** — 1d buckets sit
   at IST midnight, so a Monday session appears as the preceding Sunday. On 300 randomly sampled
   Manas trades (seed 42), joining `candles` on `bucket::date = entryDate`: **300 / 300 had a bar, 0
   missing, and 300 / 300 had `round(close,2) = round(entryPrice,2)`.** That simultaneously proves
   the key alignment is exact *and* that the primary variant fills `at_close` — which is what makes a
   live entry-bar exit a **0.00% gross** round trip.
2. **Determinism control** (the surprising-result rule: an unchanged deterministic replay must re-run
   to the decimal). The Manas sim was run **twice, independently**. `trades[]` is **byte-identical**
   (SHA-256 `7f86bcca25c9ef1e9ce08d12…` on both), every headline metric matches
   (symbolsScanned/tradesTaken/totalReturnPct/cagrPct/maxDrawdownPct/sharpe/winRatePct/profitFactor),
   and the derived affected set reproduces exactly (n=119, mean −0.137176 both times). Only `runAt`
   differs. Nothing in this document rests on a number that moved between runs.

### 2.2 Legs first — how many exits differ

**Manas: 119 of 13,303 trade legs = 0.8945%; 114 of 12,386 distinct entry events = 0.9204%. [computed]**

| Mechanism (precedence order) | Legs | Share of affected |
|---|---|---|
| **A** — `trailing_stop` arms on the entry bar's own high | **80** | 67.2% |
| **B** — `square_off` too-fast (+35% in 3 bars) | 36 | 30.3% |
| **C** — `square_off` parabolic (+40% over SMA10) | 3 | 2.5% |

**Minervini: 0 of 16,739 distinct entry bars. [computed]** Measured, not assumed — clause D was
evaluated on every one of them (and the 8% stop as a control, also 0). This is structural: entry
requires `MinerviniGates.passed(g) == 8`, and `g[4] = gt(close, sma50)` is a **strict** `>`
(`MinerviniGates.java:42`), read off the same `sma50` array that `exitFires` compares against
(`MinerviniSwingBacktest.java:250`). `close > sma50` and `close ≤ sma50` cannot both hold on one bar.
The empirical zero and the algebra agree, which is the point of running it.

**Live population — the only direct evidence, and it is far too small to be a verdict. [computed]**
Across every swing paper position ever opened (`strategy.paper_positions`, books `manas-arora` +
`minervini`): **35 positions — 15 Manas, 20 Minervini; 18 closed, 17 still open — and 0 entry-bar
exits.** The shortest hold in the entire closed set is **3 days** (`SBCL` #11, `TIRUPATIFL` #37); no
position has `opened_at` and `closed_at` in the same batch run. **Say this loudly: 18 closed trades
is not a measurement of a 0.9% event.** At the measured rate the expectation over 15 Manas entries is
0.13 events and P(observe 0) = 0.874; over all 35 entries, 0.31 events and P(0) = 0.730. The live
book is *consistent* with the replay and *cannot* discriminate anything. The 13,303-leg replay is the
measurement; the live book is a sanity check that passed.

### 2.3 Then P&L

On an affected bar, live enters at `close[i]` and exits at `close[i]` → **0.00% gross, exactly**. The
backtest holds ≥1 further bar and books whatever it books. **Costs cancel and are not subtracted:**
this is an executed-vs-executed comparison, not executed-vs-hypothetical — both sides perform exactly
one round trip on the same instrument at nearly the same value, so the ≈0.38 pp round-trip charge
appears on both sides of the subtraction. (This is the one place the brief's "subtract costs" rule
does *not* bite, and it is worth stating rather than silently omitting.)

**Per-trade distribution of what the backtest earns on the 119 affected legs — i.e. what live forgoes:
[computed]**

| Statistic | Leg level (n=119) | Event level (n=114) |
|---|---|---|
| Mean | **−0.1372%** | **−0.4716%** |
| Median | −0.5661% | −0.5490% |
| Win rate | 35.29% | 35.1% |
| p10 / p25 / p50 / p75 / p90 | −10.13 / −4.06 / −0.57 / +1.24 / +9.05 | — |
| Min / Max | −15.36% / +40.43% | — |
| Mean bars held | 3.03 (median **1**) | — |

**58.8% (70 of 119) of affected legs exited on the very next bar in the backtest.** M6 is
overwhelmingly a **one-bar timing shift**, not a "live abandons a trade the backtest rides".

**Whole-population effect: [computed]**

| | Mean per-trade gross return (n = 13,303) |
|---|---|
| Backtest semantics (as shipped) | **+2.17816%** |
| Live semantics (the 119 become exactly 0.00%) | **+2.17939%** |
| **Delta** | **+0.00123 pp  (+0.056% relative)** |

In rupee terms on the live ₹1.5 L Manas book: at ~7 slots the average position is on the order of
₹21 k **[assumed** — position size is `atr_risk` at 1% equity over a `min(2×ATR, 10%)` stop, so it
varies; ₹21 k is `capital/slots`, not a measured average**]**, so an affected entry is worth ≈ ₹0.03
of divergence, and at 0.9% of entries the book would need ~**110 entries to accumulate one rupee** of
M6 effect. Any conclusion drawn from that rupee figure inherits the **[assumed]** label; the
`+0.00123 pp` figure above does not.

### 2.4 Sign robustness — and where it fails

The brief asks whether the conclusion survives a shifted window or is carried by outliers. Both were
tested; the answers differ by which unit you use, and that difference is itself the finding. **[computed]**

**The leg-level mean is NOT sign-robust:**

| | Mean pnl% |
|---|---|
| Full (n=119) | −0.1372 |
| drop top 1 | −0.4809 |
| drop bottom 1 | −0.0081 |
| **drop bottom 2** | **+0.0990 ← sign flips** |
| drop top 5 | −1.4667 |
| 10% trimmed both tails | −1.0210 |

**Root cause, and it is not noise: `KHAICHEM` 2022-01-05 appears TWICE** — the `breakout` and `vcp`
setups fire on the same symbol/date and produce identical +40.43% trades. One real-world event
supplies 2 of 119 rows and +80.86 of the sum. That is a double-counting artifact of using legs as the
unit, not a fragile market fact.

**At event level (deduplicated across setups) the sign HOLDS on every perturbation tested:**

| | Mean pnl% (n=114) |
|---|---|
| Full | −0.4716 |
| drop top 1 | −0.8336 |
| drop top 2 | −1.1475 |
| drop bottom 1 | −0.3399 |
| 10% trimmed both tails | −1.1425 |

**Window shift — by calendar year. The RATE is strikingly stable; the P&L sign is not.**

| Year | All trades | Affected | Rate % | Mean pnl % | Median pnl % | Win % |
|---|---|---|---|---|---|---|
| 2016 | 610 | 6 | 0.98 | −0.169 | −1.551 | 16.7 |
| 2017 | 1,579 | 12 | 0.76 | −0.935 | −0.216 | 50.0 |
| 2018 | 511 | 5 | 0.98 | −0.070 | +0.541 | 60.0 |
| 2020 | 903 | 6 | 0.66 | **+3.954** | +0.351 | 50.0 |
| 2021 | 2,559 | 32 | 1.25 | −0.059 | −0.645 | 46.9 |
| 2022 | 1,374 | 11 | 0.80 | **+7.025** | −2.010 | 45.5 |
| 2023 | 2,035 | 19 | 0.93 | −2.921 | −0.566 | 21.1 |
| 2024 | 2,756 | 22 | 0.80 | −1.401 | −0.495 | 18.2 |
| 2025 | 451 | 4 | 0.89 | **+0.409** | −1.247 | 25.0 |
| 2026 | 354 | 2 | 0.56 | −9.069 | −9.069 | 0.0 |

(2015 and 2019 had 0 affected of 31 and 140 trades.)

**And by setup, the mean splits outright:** `breakout` mean **−1.241** / median −1.396 (66 of 7,002 =
0.94%); `vcp` mean **+1.237** / median −0.426 (53 of 6,301 = 0.84%).

**Reading it honestly:** the **leg count is robust** — 0.56%–1.25% across ten years, 0.94% vs 0.84%
across two setups, tight around 0.9%. The **mean P&L is not** — it splits 7/3 by year, flips sign by
setup, and at leg level flips on two rows. The **median and win rate are** robust (negative and ~35%
in every cut but two). So: *live's entry-bar exit lands on a consistently identifiable ~0.9% of
entries that are, more often than not, small losers* — but the mean is dominated by a handful of
large winners and should not be quoted as a point estimate. **None of this changes the verdict**,
because the whole effect is `+0.001 pp` and every perturbation above moves it by less than
`±0.01 pp` on the population mean.

---

## Part 3 — M6: three things the measurement turned up

### 3.1 The dominant mechanism was unnamed

Clause **A** (67% of affected legs) is the armed Chandelier trail arming *and firing simultaneously*
on the entry bar, because `breakeven_floor: true` puts the level at or above entry while
`close == entry`. The #128 batch, the scoping doc and the #1215 fixture all describe M6 through
`square_off` only. Not a defect — but anyone who "fixes M6" by reasoning about `square_off` will fix
a third of it. **[computed]**

### 3.2 The affected trades are mostly one-bar trades either way

70 of 119 exit on the next bar in the backtest. So the counterfactual is literally "hold one more
bar", which is why the magnitude is what it is. **[computed]**

### 3.3 Both deep sims already agree with the repo's own frozen convention

The scalper's `contracts/fixtures/exit-equivalence.json` pins "entry bar (index 0) never exits" as a
cross-suite invariant. The swing **backtests** match that convention; live does not. If the owner ever
aligns M6, the precedent points at moving **live** to the backtest, not the reverse — and that
direction costs the ~0.001 pp rather than gaining it. **[sourced]** / **[computed]**

### 3.4 ⚠️ The Minervini zero is a *deep-sim* zero, not a *live* zero

Measured while tracing the exit path, and it is a genuine caveat on the headline. **[computed]**

The deep sim evaluates entry and exit off one array, so `close > sma50` at entry makes clause D
unreachable — that zero is airtight **for the deep sim**. **Live is different**: the live YAML entry
gate is `crossover(px,pivot) ∧ vol > 1.2 ∧ px > sma20` (`minervini-vcp.yaml:32-39`) and contains **no
`close > sma50` term at all**. The `close > sma50` requirement lives only in the *funnel candidate
screen* — and the two run on **different price planes**:

| | Source | Adjustment |
|---|---|---|
| Live entry **candidates** (funnel screen) | `nse_eod_bhavcopy` via `AdjustedEquityDailySql.SCREENER_BASE_CTE:64-81` | CA-adjusted in SQL, every row |
| Live entry **evaluation** and **exit** | `candles` @1d via `GET /api/v1/market/candles` (`adjust` defaults to `"back"`, `CandlesController.java:61`) | CA-adjusted in Java **only for `source='BHAVCOPY'` rows** (`EquitySplitBonusAdjuster.java:41-43`); Kite-sourced rows pass through untouched |
| Deep sim | `candles` @1d by direct JDBC | **no adjuster at all** |

So live Minervini's entry-bar exposure is **not provably zero** — it is bounded by how far the two
planes diverge. Bounding it: over 21 live screen dates, of **5,051** funnel-passing name-dates
(all 8 trend-template gates passed on the bhavcopy plane), 5,037 have a `candles` bar and **47
(0.933%) have candles-plane `close ≤ sma50`** — i.e. on those the live trail *could* fire on an entry
bar despite the screen having passed `close > sma50`. That 0.933% is an **upper bound only**: an
actual entry additionally needs the pivot crossover, `vol > 1.2` and `px > sma20` on the candles
plane, which a bar closing below its own 50-day MA will essentially never satisfy at the same time.
The live population agrees (0 of 20 Minervini positions). It is not zero, though, and the honest
headline is: **Minervini M6 exposure is exactly 0 on the deep sim's single-plane population and
≤0.93% live, for a reason that is a data-plane inconsistency rather than an exit-doctrine one.**

This is the CA-adjustment hazard the brief warned about, and it does **not** flip the M6 conclusion
(0.93% × the same ~0.001 pp arithmetic is still noise). It is logged here because it is a real,
separately-actionable observation that no #128 item covers.

---

## Part 4 — M9 measured

### 4.1 The two conventions, and the gate they feed

**Live** assigns an **ordinal** rank over the screened universe sorted ascending by weighted RS with a
symbol tie-break: `rsRank = i × 100 / (n − 1)`, `i = 0 … n−1` (`ManasScreenService.java:211-216`;
`TrendTemplateService.java:204-218` uses the identical formula — which **closes the scoping doc's own
Open Doubt 2**, *"I did not separately re-derive Minervini's live-side RS-rank formula"*: it is the
same ordinal formula, verified this pass **[computed]**). The ranked set is the ~2,250-name NSE-EQ
universe with sufficient history — `raws` is **pre-gate**, gates are applied afterwards in
`toCandidate` — so live is *not* ranking within an elite subset, which was a plausible reading of the
M9 text.

**Backtest** computes a tie-aware **midpoint percentile**
`100 × (below + 0.5 × equal) / n` (`ManasAroraBacktestService.percentile`), over the historical
cross-section built at **weekly rank dates** (`RANK_CADENCE = 5` sessions), and a bar reads the most
recent rank date at or before it (`asOfRankDate`).

Both feed the same gate: `rs_rank ≥ 70` — live via `artha.manas-arora.funnel-rs-min:70`
(`ManasFunnelService.java:108,130`) and `artha.minervini.rs-min:70`
(`TrendTemplateService.java:51`); backtest via the `rs-min:70` variant parameter. **[computed]**

### 4.2 Axis 1 — the formula (what M9 actually names)

Measured over the **real persisted live screen history**, `marketdata.manas_arora_screen_results` /
`minervini_screen_results`. Because live's ordinal rank is strictly increasing in `i` with step
`100/(n−1) ≈ 0.044` — comfortably wider than the column's 2-dp rounding — `row_number() OVER
(PARTITION BY screen_date ORDER BY rs_rank)` recovers each name's `i` **exactly**, so the backtest's
midpoint percentile for the *same population* is `100 × (i + 0.5)/n` in closed form. Verified: every
date has `count(DISTINCT rs_rank) = count(*)`. **[computed]**

| Family | Screen dates | Name-dates | Live ordinal ≥70 | Backtest midpoint ≥70 | **Gate flips** | Max abs rank delta |
|---|---|---|---|---|---|---|
| Manas | 20 | **45,069** | 13,527 | 13,522 | **5 (0.0111%)** | 0.0273 |
| Minervini | 21 | **37,114** | 11,139 | 11,134 | **5 (0.0135%)** | 0.0330 |

Per-date distribution (Manas): **0 flips on 15 of 20 dates, exactly 1 flip on 5 dates** (2026-07-14,
-15, -16, -29, -30). Never 2.

That ceiling is not luck — it is arithmetic. The ordinal gate threshold sits at `i ≥ 0.7(n−1)`, the
midpoint gate at `i ≥ 0.7n − 0.5`; the difference is `+0.2` index positions **independent of n**.
**At most one name per screen date can ever flip, for any universe size.** **[computed]**

(2026-07-03 is excluded from Manas: `rs_rank` is NULL for all 2,224 rows that date — the column was
backfilled later. Stated rather than silently dropped.)

### 4.3 Axis 2 — the rank-date cadence (not named by M9, ~320× larger)

Same persisted population, population held **fixed**, so this isolates cadence alone: simulate the
backtest's weekly rank dates over the 20 live screen dates (every 5th), and compare each session's
live daily rank against the rank the backtest would have reused from the most recent rank date.

| Sessions stale | Name-dates | Gate flips | Flip % | Mean abs rank delta |
|---|---|---|---|---|
| **0 (control)** | 8,995 | **0** | **0.000** | **0.000** |
| 1 | 8,995 | 260 | 2.890 | 2.459 |
| 2 | 8,995 | 393 | 4.369 | 3.494 |
| 3 | 8,995 | 451 | 5.014 | 4.181 |
| 4 | 8,995 | 504 | 5.603 | 4.702 |
| **Total** | **44,975** | **1,608** | **3.5753** | 2.967 (max 85.87) |

The 0-stale row returning **exactly 0 flips and exactly 0.000 mean delta** is the harness control: an
unchanged comparison reproduces to the decimal, so the 3.58% is a measurement and not an artifact.
Monotone in staleness, as it must be.

~~**3.5753% vs 0.0111% — the cadence term is 322× the formula term**~~ ⚠️ **SUPERSEDED — see the
correction box in §1.** The measured-here figure encodes a spec production does not have (it stales
the symbol's own RS as well as the cross-section). Correct statement: **1.7025% vs 0.0111% — the
cadence term is 153× the formula term**, in the same code, feeding the same gate. **[computed, #1244]**

### 4.4 Why there is no M9 P&L number here, and what it would take

Converting gate flips into rupees requires re-running the deep sim with a *daily* RS cross-section
(≈2,250 symbols × ~2,750 sessions instead of 550 rank dates) and A/B-ing the resulting trade
populations. That is a genuine backtest A/B — the same class of work as M36/M37 and M40 — not a
measurement note, and it changes the trade population rather than a within-trade timing, so
survivorship does **not** cancel the way it does for M6. **I am not manufacturing a number for it.**

What can be said without one: for **axis 1**, the leg count is bounded at ≤1 name per screen date and
measured at 5 in 45,069, and a flipped name must *additionally* clear a pivot crossover to become a
trade — so the P&L is bounded to noise by leg count alone. For **axis 2**, the leg count is 3.58% of
name-dates, which is **not** self-evidently negligible and is the only part of M9 that could justify
follow-up work.

---

## Part 5 — What this means for the owner's "align or accept" call

| Item | Legs | P&L | Recommendation |
|---|---|---|---|
| **M6, Manas** | 119 / 13,303 legs (0.89%); 114 / 12,386 events (0.92%) | **+0.00123 pp** on mean per-trade gross return | **ACCEPT.** No P&L case in either direction. |
| **M6, Minervini** | **0** / 16,739 entry bars (structural); ≤0.93% live via the plane gap (§3.4) | 0 | **ACCEPT.** |
| **M9 axis 1 — formula** (the named item) | 5 / 45,069 (0.011%), ceiling ≤1 per date | bounded to noise by leg count | **ACCEPT.** Aligning the formula would move essentially nothing. |
| **M9 axis 2 — cadence** (not a #128 item) | 1,608 / 44,975 (3.58%) | **not measured — needs its own A/B** | **Owner's call whether to open it.** It is the only live thread here. |

Three notes on the recommendation:

1. **Accepting M6 is not the same as leaving it undocumented — and it is already documented.** #1215
   shipped exactly the right artifact: a fixture + tests that name the divergence as an asserted fact.
   This measurement says that artifact is a *sufficient* response, not an interim one.
2. **If M6 is ever aligned, align live to the backtest**, per §3.3 — that matches the repo's own
   frozen scalper convention and costs ~0.001 pp.
3. **§3.4's plane inconsistency is the most actionable thing in this document** and is not an M6 item
   at all. It is cheap to look at and independent of the HOLD decision.

---

## Open doubts

1. **The live population cannot discriminate anything, and I want that impossible to miss.** 35
   positions, 18 closed, 0 entry-bar exits, P(0) = 0.73 under the measured rate. Every M6 number of
   consequence comes from an 11-year *replay*, with all the replay caveats in §2.1. If someone later
   quotes "0 of 35 live" as the finding, they will have quoted the weakest evidence here.
2. **The whole M6 result is conditional on the deep sim's entry population being the right
   counterfactual.** It is not the live entry population: the sim is unconstrained (13,303 legs vs
   1,328 portfolio-admitted), survivorship-biased, and capacity-unbounded. The *rate* (0.89%) is what
   transfers to live; the composition of the affected set may not.
3. **The affected-set mean is fragile and I have reported it as such, but it would be easy to quote
   out of context.** −0.1372% (legs) / −0.4716% (events) — the leg figure flips sign on dropping two
   rows that are one real event. The median and win rate are the robust statistics. The verdict does
   not depend on any of them.
4. **§3.4's 0.933% is an upper bound whose true value I did not measure.** Getting the real live
   Minervini entry-bar rate needs the crossover/volume/sma20 triggers evaluated on the candles plane
   for the funnel set — a bounded piece of work I did not do. I believe the true figure is far below
   0.933% **[assumed]**, and the live book's 0 of 20 is consistent with that, but I did not prove it.
5. **M9 axis 2's isolation holds population fixed, which is deliberate but partial.** It measures
   cadence *given* the live universe. The backtest's actual population also differs by survivorship
   (M11) and by which names had ≥252 sessions at each historical rank date. I did not separate those;
   axis 2's 3.58% is therefore a lower bound on total live-vs-backtest RS-gate divergence, not the
   whole of it.
6. **20–21 screen dates is a one-month window for both M9 axes** (2026-07-06 → 2026-07-31; the
   `manas_arora_screen_results` history simply does not go back further with a populated `rs_rank`).
   Axis 1's ceiling is proven by algebra and is window-independent; **axis 2's 3.58% is not**, and
   could plausibly differ in a higher- or lower-dispersion month. It should be re-measured over a
   longer window before anyone acts on it.
7. **I did not verify that the live exit pass and the deep sim resolve the *same bar* as "the entry
   bar" in every edge case.** `SwingBatchEngine` uses `indexAtOrBefore(generatedAt)` and skips when
   the entry bar falls outside the fetched window (`:1116-1127` fetches only `warmupDays`); the sim
   indexes directly. For a same-day entry these coincide, which is the M6 case, but a catch-up run
   with a pinned `requiredBarDate` has more moving parts than I traced. **[assumed]** they coincide
   for the same-day case that M6 is about.
8. **Clause A's "arming is firing" derivation is read off `rollingAtrTrailLevel` and is not covered by
   any test I can point at.** The fixture pins the `square_off` shape only. I am confident in the
   reading (`level = max(ext − 2×ATR, entry) ≥ entry = close`, `ExitEvaluator.java:549-554`), and 80
   of 119 affected legs are attributed to it, so if the derivation is wrong the mechanism split in
   §2.2 is wrong — though the total affected count would only *fall*, making the verdict stronger,
   not weaker.
9. **The deep-sim runs read `candles` unadjusted while live reads it `adjust=back`.** I evaluated
   conditions A–D on the sim's own plane deliberately, to keep the counterfactual internally
   consistent. A CA event inside an affected trade's window would therefore be handled differently
   live than measured here. Given 119 affected legs with a median hold of 1 bar, the exposure is
   small **[assumed]**, but it is not zero and I did not enumerate it.

---

## Reproduction

Everything below is read-only against the live stack; no writes, no deploys, no code changes.

```bash
# 1. The two deep-sim runs (production endpoint, container loopback, no auth needed internally).
#    ~1.5 min each; they share a single SwingBacktestGate permit, so run them SEQUENTIALLY (else 409).
docker exec ay-market-data-service sh -c 'wget -q -O /tmp/deep-manas.json \
  --header="Content-Type: application/json" \
  --post-data="{\"family\":\"manas\",\"from\":\"2015-08-03\"}" \
  --timeout=3600 --tries=1 \
  http://127.0.0.1:8081/api/v1/market/screener/deep-swing/run'
# ...then the same with "family":"minervini" -> /tmp/deep-minervini.json
# NOTE (Git Bash on this box): prefix with MSYS_NO_PATHCONV=1 or `/tmp/...` is rewritten to a Windows path.

# 2. Manas entry-bar exit flags A/B/C over every NSE 1d bar (85,756 flagged bars).
docker exec ay-timescaledb psql -U artha -d artha -t -A -F',' -c "
SET search_path TO marketdata, public;
WITH s AS (
  SELECT tradingsymbol, bucket::date AS d, close, high,
         lag(close,3) OVER (PARTITION BY tradingsymbol ORDER BY bucket) AS c3,
         avg(close)   OVER (PARTITION BY tradingsymbol ORDER BY bucket ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS sma10,
         count(*)     OVER (PARTITION BY tradingsymbol ORDER BY bucket ROWS BETWEEN 9 PRECEDING AND CURRENT ROW) AS w10
  FROM candles WHERE exchange='NSE' AND interval='1d' AND bucket >= '2015-01-01'
)
SELECT tradingsymbol, d, (high >= close*1.09)::int,
       (c3 IS NOT NULL AND close >= c3*1.35)::int,
       (w10=10 AND sma10>0 AND close >= sma10*1.40)::int
FROM s WHERE high >= close*1.09
   OR (c3 IS NOT NULL AND close >= c3*1.35)
   OR (w10=10 AND sma10>0 AND close >= sma10*1.40);"
# Then join trades[] on (symbol, entryDate) = (tradingsymbol, d). Precedence: A else B else C.

# 3. M9 axis 1 — formula, same population (Manas; swap the table for Minervini).
docker exec ay-timescaledb psql -U artha -d artha -c "
WITH ranked AS (
  SELECT screen_date, symbol, rs_rank,
         row_number() OVER (PARTITION BY screen_date ORDER BY rs_rank) - 1 AS i,
         count(*)     OVER (PARTITION BY screen_date)                     AS n
  FROM marketdata.manas_arora_screen_results WHERE rs_rank IS NOT NULL
)
SELECT count(DISTINCT screen_date) AS dates, count(*) AS name_dates,
       sum(CASE WHEN (rs_rank >= 70) <> (100.0*(i+0.5)/n >= 70) THEN 1 ELSE 0 END) AS gate_flips,
       max(abs(rs_rank - 100.0*(i+0.5)/n))::numeric(8,4) AS max_abs_rank_delta
FROM ranked;"

# 4. M9 axis 2 — cadence, population held fixed.
#    ⚠️ `SELECT DISTINCT screen_date, row_number() OVER (...)` does NOT work: row_number is computed
#    BEFORE the DISTINCT, over all 45,069 rows, and the join explodes to ~101 M rows. Build the
#    distinct date list in its own CTE first, then number it.
docker exec ay-timescaledb psql -U artha -d artha -c "
WITH dd AS (SELECT DISTINCT screen_date FROM marketdata.manas_arora_screen_results WHERE rs_rank IS NOT NULL),
d  AS (SELECT screen_date, (row_number() OVER (ORDER BY screen_date) - 1) AS di FROM dd),
m2 AS (SELECT m.screen_date AS bar_date, d2.screen_date AS as_of, (m.di - (m.di/5)*5) AS stale
       FROM d m JOIN d d2 ON d2.di = (m.di/5)*5),
j  AS (SELECT m2.stale, s.rs_rank AS live_daily, b.rs_rank AS bt_stale FROM m2
       JOIN marketdata.manas_arora_screen_results s ON s.screen_date=m2.bar_date AND s.rs_rank IS NOT NULL
       JOIN marketdata.manas_arora_screen_results b ON b.screen_date=m2.as_of AND b.symbol=s.symbol AND b.rs_rank IS NOT NULL)
SELECT stale, count(*), sum(CASE WHEN (live_daily>=70)<>(bt_stale>=70) THEN 1 ELSE 0 END) AS flips
FROM j GROUP BY stale ORDER BY stale;"

# 5. Live swing population (M6 sanity check).
docker exec ay-timescaledb psql -U artha -d artha -c "
SELECT book, status, count(*), min(closed_at - opened_at) AS min_hold
FROM strategy.paper_positions WHERE book IN ('manas-arora','minervini')
GROUP BY book, status ORDER BY book, status;"
```

Two traps that cost time and will cost it again: **`bucket::date` is UTC** — a 1d bucket sits at IST
midnight, so a Monday session reads as the preceding Sunday (validate any trade↔candle join by
checking `close == entryPrice`, as §2.1 does); and **screen_date ↔ `bucket::date` is off by one**
(`bucket::date = screen_date − 1`, verified against RELIANCE/TCS/INFY/SBIN on 2026-07-31, closes
identical to 4 dp).
