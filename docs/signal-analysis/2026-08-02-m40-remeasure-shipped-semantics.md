# 2026-08-02 — M40 re-measured against the semantics PR #1221 actually ships

Owner-directed 2026-08-02: re-measure [#1218](https://github.com/prashantm912/artha-yantra-2/pull/1218)'s
M40 backtest against the policy **[#1221](https://github.com/prashantm912/artha-yantra-2/pull/1221)
(`feat/manas-fresh-entry-risk-cap` @ `a5b1b63b`) actually implements**, before that PR is allowed to
merge. Measurement only — **no production behaviour is changed by this document or by the commit that
carries it.** Claims are tagged **[computed]** (derived here from code/config/DB read on this checkout),
**[sourced]** (quoted from a cited doc), **[recalled]** (memory, not re-verified) or **[assumed]**.

---

## Verdict, and its confidence

**Do not merge #1221 on the strength of a CAGR number — there is no reliable CAGR number here. The
CAGR sign does not survive; the DRAWDOWN and SHARPE improvements do.** Confidence: **HIGH** that the
CAGR effect is indeterminate on this data (four independent robustness tests each flip or dominate it);
**MODERATE** that the drawdown/Sharpe improvement is real (unanimous across all twelve cap arms measured,
but from one 11-year replay of one strategy family on a survivorship-biased universe); **the correlated-
shock question that the cap actually exists for remains entirely unmeasured and out of reach.**

Under the semantics #1221 ships, the cap refuses **304** entries (274 sessions) that fire under today's
live rail — **2.2× #1218's 139** — and the whole-portfolio effect is **CAGR 25.36% → 24.48% (−0.88pp, a
COST), maxDD 42.89% → 31.27% (−11.62pp, far SHALLOWER), Sharpe 1.08 → 1.15 (+0.07)**. #1218 reported
**+4.08pp / −6.99pp / +0.13** — a benefit. **This is the fourth M40 headline and the second sign
reversal** (152 refusals/costly → 66/costly → 139/beneficial → 304/CAGR-costly-but-drawdown-beneficial;
the sign itself flipped at v2→v3 and again here). The reason it moved this time is **not** a harness
defect: #1218's harness is intact and reproduces byte-identically here. It modelled a stop basis
production almost never reaches.

The one recommendation this measurement does support: if #1221 merges, it should merge as a
**drawdown-control** change with the CAGR cost disclosed and accepted, not as a free improvement.

---

## §1 — Divergence table: modelled (#1218) vs shipped (#1221)

| # | Axis | #1218 modelled | #1221 ships | Real? | Direction | Material? |
|---|---|---|---|---|---|---|
| 1 | **Stop basis** | Current Chandelier trail every session | `ManasGoverningStopCache` when populated, else persisted `stop_loss` | **YES — dominant** | Shipped charges MORE risk ⇒ cap binds MORE | **Decisive** (139 → 304 refusals) |
| 1a | ↳ trail *value* | Running max of `peak − 2×ATR` | Same (`rollingAtrTrailLevel` ratchets internally) | **NO** | — | — |
| 1b | ↳ one-session *lag* | Trail written in exit pass, read next session's entry pass | Same | **NO** | — | — |
| 1c | ↳ *unarmed* position | Charges `initialStop` | Charges `initialStop` | **NO** | — | — |
| 1d | ↳ cache *coldness* | Not modelled (assumed always warm) | Cold after every restart **and** for every unarmed position | **YES** | — | This IS divergence 1 |
| 2 | **Price basis** | Candle close | Real fill: `round₂(close + round₂(close×5/10000))` | **YES** | Stricter | Negligible (~0.6% of one lot's risk) |
| 3 | **Fail-open on missing ATR** | Falls back to the 10% cap ⇒ models a REFUSAL | `stopLoss()==null` ⇒ `wouldBreachRiskCap` returns **false** ⇒ ADMITS | **YES** | **Opposite** | ~0 frequency (MIN_BARS=260 ⇒ ATR20 always defined) |
| 4 | **Number of gates** | One check | Two: emission-time (close) **and** authoritative write-time (fill, under the book lock) | **YES** | Structural only | Nil — write-time is strictly stricter for a BUY, so modelling it alone is equivalent |
| 5 | **"Fresh"-ness source** | One symbol-keyed book; a held symbol is never a candidate | Write-time check reads `paper_positions` directly, so an anchor-less paper row participates and an averaging add is projected against the RETAINED stop | **YES** | Unmodellable | Zero frequency in the replay (that branch is unreachable) |
| 6 | **ATR implementation** | Own Wilder loop seeded at `Σ TR[1..20]/20` over full history | ta4j `ATRIndicator`, Wilder MMA seeded from `TR[0]`, over a 520-day window | **YES** | Either | Pre-existing proxy limit; affects both arms; sets a floor on boundary-event precision |

### §1.1 — Why divergence 1 is the whole story

**[computed]** #1218's model and production's **warm-cache** behaviour are the same thing. The harness
charges `trailArmed ? max(initialStop, trailStop) : initialStop`, with `trailStop` a running max updated
in the exit pass (`ManasRiskCapReplayTest.java:1057-1063`) which runs *after* the entry pass — so the
entry decision reads the previous session's trail. Production's cached value is
`ExitEvaluator.trailStop` → `rollingAtrTrailLevel`, which **accumulates `level.max(c)` across the whole
loop from `entryIndex` to `index`** (`ExitEvaluator.java:554`) — also a running max — written by
`SwingBatchEngine`'s exit pass, which also runs *after* the entry pass
(`SwingBatchEngine.java:303-311`). Same value, same lag, same unarmed fallback. **Rows 1a/1b/1c of the
table are premises from the brief that turned out not to exist**, and saying so is part of the finding.

**[computed]** What is left is coldness, and coldness is the normal state for **two independent
reasons**, only one of which involves restarts:

1. **The cache is only ever written for a position whose trail has ARMED.**
   `SwingBatchEngine.cacheGoverningStop` skips on `governingStop == null`
   (`SwingBatchEngine.java:882-887`), and `ManasDoctrine.governingStop` → `trailLevel` →
   `ExitEvaluator.trailStop` returns `Optional.empty()` until the trail arms at **+9%**
   (`arm_pct: 9`, both Manas YAMLs; `ExitEvaluator.java:530-556`). A held position below +9% is
   **never cached, by design** — no restart required.
   **Live right now: ZERO of the six open Manas positions qualify** (peak gain since entry: PRECOT
   8.19%, KANORICHEM 8.08%, AVALON 5.60%, SANSERA 3.62%, SCPL 2.39%, GRWRHITECH 0.50%). So the
   cache would be empty tonight even on a service that had never restarted. (Computed as
   `max(high) since opened_at` over `candles`@1d vs `avg_entry_price` — a close proxy for the engine's
   own `favorableExtreme`/`arm_pct` test, not that test itself. PRECOT at 8.19% is within ~1pp of
   arming, so "zero armed" is today's state, not a standing property.)
2. **It is in-memory (`ConcurrentHashMap`, `ManasGoverningStopCache.java:38`) and dies on every
   restart**, while the batch that repopulates it runs **once per weekday at 20:05 IST**
   (`ARTHA_MANAS_ARORA_SWING_CRON: 0 5 20 * * MON-FRI`). Restart cadence vs that: **21 of the 28 days**
   since Manas go-live (2026-07-05) carried ≥1 merge touching `strategy-signal-service` (159 merges
   total, up to 16 in one day). Concretely today: the container was recreated **2026-08-02 03:58 IST**
   (`docker inspect`, `RestartCount=0` ⇒ a deploy, not a crash-loop), i.e. *after* the last batch
   (Friday 2026-07-31 20:05 IST — 2026-08-01/02 are Sat/Sun). **The next batch, Monday 2026-08-03
   20:05 IST, runs its entry pass with an empty cache.** [computed]

Merge count is an upper bound on deploys, not a deploy count — but reason (1) does not depend on it at
all, and reason (1) alone is currently sufficient.

**[computed]** Consequence, and this is the mechanism behind every number below: on the persisted-stop
basis a position charges ~1% of entry-equity **forever**, so the 6% cap is arithmetically close to a
**6-position rail**. The measured aggregate-risk distribution confirms it — median **5.53%** on the
shipped basis vs **3.82%** on #1218's trailed basis, and the share of entries facing an
already-≥5% book goes **15.40% → 67.70%**. That is why `AGG_CAP` refusals go **1,949 → 15,472** while
`MAX_OPEN` refusals **collapse 15,302 → 123**: under shipped semantics the aggregate cap *replaces*
`MAX_OPEN=7` as the binding rail. #1218's headline claim that "`MAX_OPEN=7` dominates refusal volume
overwhelmingly" is **true of its own model and false of the shipped one.**

### §1.2 — Exits are genuinely untouched (so the replay's exit model needs no change)

**[computed]** Enumerated every consumer of `ManasGoverningStopCache` in `src/main/java`: only
`PaperEmissionGuard.effectiveStop`/`openRiskInr`, `RiskService.manasAggregateRiskWouldCross`, the
`SwingBatchEngine` writer, and `PaperService.doSettle`'s evict. **`PaperBracketEvaluator` does not
appear** — round 3 reverted the `paper_positions.stop_loss` write, so #1221 changes the risk
*calculation* only. Exit behaviour is unchanged, which is what makes it legitimate to re-measure by
varying only the admission rule.

---

## §2 — Harness validation (do this before trusting anything above)

**[computed]** The committed harness was reused, not rebuilt; the extension is additive and opt-in the
same way (`-Dmanas.replay.enabled=true`). Four gates, all passed on the same run that produced the
numbers:

1. **Production trade-identity fidelity** — every symbol's standalone lifecycle vs
   `ManasAroraSwingBacktest.simulate()`: **breakout 2,491/2,491, vcp 2,491/2,491, zero mismatches.**
2. **Portfolio symbol coverage** — `expectedParticipating=2491`; **all four primary arms and all six
   hybrid arms = 2491**, asserted, not eyeballed. Per the brief's instruction that a green fidelity arm
   does not imply a green portfolio arm, this run additionally asserts
   `baseline.participatingSymbols` **equals** `shippedCold.participatingSymbols` as a *set*, not merely
   as a count (`assertEquals` on the `Set`, not `.size()`).
3. **v3 reproduction (the decisive gate)** — the unchanged arms print **byte-identically** to #1218:
   baseline `1065 admitted / CAGR 25.36 / maxDD 42.89 / Sharpe 1.08`; v3 candidate
   `1021 / 29.44 / 35.90 / 1.21`; and the marginal-refused set recomputed in-harness gives
   **139 / mean +1.129 / median −0.678 / winRate 46.04% (64 W, 74 L, 1 flat) / best +48.64 /
   worst −21.94** — matching #1218's published figures, including the median it had derived by a
   separate `awk` pass. **If the harness had drifted, this gate would have caught it.**
4. **New-code boundary check** — the hybrid sweep at `N=1` (cold every session) must be bit-for-bit the
   same arm as the independently-configured `StopBasis.INITIAL` arm. It is: both print
   `877 admitted / AGG_CAP 15472 / MAX_OPEN 123 / finalEquity 1665699 / CAGR 24.48 / maxDD 31.27 /
   Sharpe 1.15`. Two different code paths through the new `stopFor` switch agreeing exactly is a free
   correctness check on the one piece of logic this re-measurement added.
5. **Determinism** — **two independent full runs, fresh JVM each, byte-identical on every figure**
   (`diff` of the two output files, empty). Separately, the window is `now() − 11 years` and #1218 ran
   on the same calendar date, so gate 3 is *also* a cross-run, cross-JVM, cross-session determinism
   check against a run this session did not perform.

Read-only confirmed: `SELECT`s plus pure production methods; no `INSERT`/`UPDATE`/DDL path is reachable.
Live-DB queries in this doc were bounded and `statement_timeout`-capped.

---

## §3 — The re-run under shipped semantics

Both arms use the identical real equity-proportional sizing throughout, so this is an unconfounded
counterfactual. `n = 2,491` symbols, ~2,750 NSE sessions, 2015-08-02 → 2026-08-02, starting ₹150,000.

| Arm | Admitted | AGG_CAP refusals | MAX_OPEN refusals | CAGR % | maxDD % | Sharpe |
|---|---:|---:|---:|---:|---:|---:|
| **Baseline** (today's live rail) | 1,065 | — | 15,302 | **25.36** | **42.89** | **1.08** |
| #1218 v3 candidate (trailed, close) | 1,021 | 1,949 | 13,423 | 29.44 | 35.90 | 1.21 |
| **SHIPPED, cache cold** (initial stop, fill) | **877** | **15,472** | **123** | **24.48** | **31.27** | **1.15** |
| SHIPPED, cache warm (trailed, fill) | 1,024 | 1,452 | 13,907 | 29.62 | 37.85 | 1.24 |

**Headline deltas vs baseline — shipped, cache cold: CAGR −0.88pp · maxDD −11.62pp (shallower) ·
Sharpe +0.07.** Cache warm: **+4.26pp · −5.04pp · +0.16**. The two brackets **disagree in sign on
CAGR** and **agree on drawdown and Sharpe**.

**Marginal-refused set** (admitted in baseline, refused only by the cap), shipped-cold:
**304 entries across 274 distinct sessions** — mean **+2.093%**, median **−0.797%**, win rate
**47.04%** (143 W / 161 L / 0 flat), best **+97.10%**, worst **−21.94%**. Right-skewed with a
below-water typical trade, same shape as #1218 found, on a 2.2× larger set. Shipped-warm: 128 refusals,
mean +1.012%, median −0.625%, 46.09%.

---

## §4 — Sign robustness: the CAGR sign does not survive

Four independent tests. **Every one of them flips or dominates the CAGR result.**

**(a) The modelling assumption itself flips it.** Cold −0.88pp vs warm +4.26pp — a 5.14pp swing across
the two ends of one unmeasured parameter.

**(b) The hybrid sweep is NON-MONOTONIC in that parameter** — which is stronger evidence than a wide
range, because it means there is no stable functional relationship to interpolate along:

| cold share | 100% | 50% | 33% | 20% | 10% | 5% | 0% (warm) |
|---|---:|---:|---:|---:|---:|---:|---:|
| CAGR % | 24.48 | **30.83** | 28.37 | 28.92 | 29.53 | 31.58 | 29.62 |
| maxDD % | **31.27** | 38.46 | 34.11 | 35.94 | 37.70 | 38.55 | 37.85 |
| Sharpe | 1.15 | 1.24 | 1.22 | 1.16 | 1.23 | 1.25 | 1.24 |

CAGR wanders 24.48–31.58 with no monotone ordering (50% cold beats 33% and 20% cold; 5% cold beats 0%).
**maxDD is below baseline's 42.89% in all seven, and Sharpe above 1.08 in all seven.**

**(c) A handful of legs carries it — decisively.** Waiving the cap for the top-k marginal refusals by
|PnL| in the shipped-cold arm:

| k exempted (of 304) | CAGR % | vs shipped-cold | vs baseline |
|---|---:|---:|---:|
| 0 | 24.48 | — | −0.88 |
| 1 (`BHARATAGRI@2022-09-19`) | 25.76 | **+1.28** | **+0.40 — sign FLIPS** |
| 3 | 25.56 | +1.08 | +0.20 |
| 5 | 27.66 | **+3.18** | +2.30 |
| 10 | 27.79 | +3.31 | +2.43 |

**Exempting ONE trade out of 304 (0.3% of the set) flips the headline from a cost to a benefit, and
exempting five moves CAGR by 3.18pp — 3.6× the size of the −0.88pp effect being measured.** #1218's own
trail-only variant had 63% of its benefit in three legs; this is the same pathology, and it is worse
here. Named, per the brief: `BHARATAGRI@2022-09-19` (+97.10%, the single largest), then
`LYKALABS@2021-11-15`, `RAIN@2017-09-19`, `BHAGYANGR@2026-04-09`, `TITAGARH@2023-06-02`.

**(d) Per-calendar-year: 5 of 12 years favour the cap**, and two years supply most of the magnitude:

| year | 2015 | 2016 | 2017 | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Δ return (pp) | −0.30 | +2.76 | **−24.61** | +7.37 | +4.50 | −3.88 | **−17.93** | +7.45 | −9.29 | −0.63 | +9.06 | −8.55 |

2015 and 2026 are partial years. A 5/12 split with a ±25pp range is a coin flip with fat tails, not an
estimate.

**What survives all four:** maxDD is shallower than baseline in **8 of 8** distinct cap arms
(31.27–38.55 vs 42.89) and Sharpe higher in **8 of 8** (1.15–1.25 vs 1.08); counting the four drop-k
arms too, **12 of 12** on both. The cold arm — the realistic
one — is the **best** on drawdown of any arm and by a wide margin. That is the coherent economic story:
capping aggregate open risk trades raw return for materially less drawdown, which is what a risk cap is
supposed to do.

---

## §5 — Costs subtracted, and a pessimistic fill

**[computed]** Both arms are **gross** (unchanged from #1218 — the harness has no cost model). Equity
delivery round-trip cost from this repo's own pinned schedule (`FeeConstants`, `LtpSlippageV1`,
`PaperFillService`): STT 0.10%×2 = 0.200 · brokerage 0.03%/side×2 = 0.060 · slippage 5 bps×2 = 0.100 ·
exchange txn 0.00297%×2 = 0.0059 · stamp 0.0015% (buy only) · SEBI 0.0002 · GST 18% on
(brokerage+txn+SEBI) = 0.0119 → **≈ 0.38pp per round trip.**

| shipped-cold refused set (n=304) | gross | net @ 0.38pp | net @ pessimistic 100 bps/side (2.28pp) |
|---|---:|---:|---:|
| mean | +2.093% | **+1.71%** | **−0.19%** |
| median | −0.797% | **−1.18%** | **−3.08%** |

So at a realistic fill the refused set is a small positive mean over a clearly negative median; at a
pessimistic fill it is negative on both. **Excluding it is not obviously costly on either.**

Portfolio-level, costs are **second-order and signed in the cap's favour**: the cap arm does 871 round
trips vs baseline's 1,059, so **188 fewer** × ~0.38pp of turnover each. Netting costs would therefore
*narrow* the −0.88pp CAGR cost, not widen it. It is not quantified into a CAGR figure here because both
arms would have to be re-run with a full cost model to do that honestly, and — given §4 — a more precise
point estimate of a number that flips on one trade would be false precision, not progress.

---

## §6 — Honest power

**On CAGR: the sample cannot distinguish the hybrid from either extreme, and cannot establish a sign.**
Not "the effect is small" — the estimator is unstable. The evidence is (b)'s non-monotonicity and (c)'s
one-trade flip: with 304 boundary events over 11 years, whose realised P&L is right-skewed to a +97%
outlier, the portfolio delta is dominated by *which* names happened to land in the freed slots, and
that is path noise, not signal. **Reporting a shipped-semantics CAGR delta as a number would be the
same mistake M40 has now made three times.**

**What would settle it** (in increasing order of cost):
1. **Measure the cache hit-rate instead of parameterising it.** A counter on `effectiveStop` —
   cached vs fallback, per batch run — would replace the entire hybrid sweep with an observed number
   within ~2 weeks of live batches. This is cheap, is a pure observability addition, and should
   arguably precede the merge decision. **This is the single highest-value next step.**
2. **A block bootstrap over the equity curve** (resampling 6-month blocks) to put an interval on the
   CAGR delta rather than a point. Would very likely confirm the interval spans zero.
3. **A differently-constructed harness by an independent implementation** — #1218's Open Doubt 1
   asked for this and it is still not done. This doc reused #1218's harness deliberately (the brief
   required it), so it inherits every modelling choice #1218 made: RS-priority same-day admission order,
   breakout-wins-tie, survivorship bias, `MAX_DEPLOYMENT_PCT`/`DAILY_LOSS_LIMIT` unmodelled.
4. **Forward paper.** At 6 open positions today the rail is live-reachable immediately (§7), so a
   handful of real refusals would accumulate quickly — but at ~1 binding event per few weeks, a
   P&L-significant sample is many months away.

---

## §7 — What #1221 would do if merged and deployed today

**[computed]**, live DB, read-only, 2026-08-02 14:49 IST:

- 6 open Manas BUY positions; aggregate open risk on the persisted-stop basis **₹8,186**.
- Book equity **₹142,125** (₹150,000 starting − ₹7,279 realised − ₹596 unrealised).
- **Current aggregate open risk = 5.76% of equity.**
- A 7th entry at `risk_pct_equity=1.0` adds ≤ ₹1,421 → **≈6.76%, over the 6% cap ⇒ REFUSED.** To fit
  under 6% the new lot would have to risk ≤ ₹341, about a quarter of its sized amount. **This is not a
  marginal boundary case.**
- Because **no position has an armed trail**, the cold and warm bases coincide today — both give 5.76%.
  The next batch (Mon 2026-08-03 20:05 IST) runs cold regardless.

So the gap is live and immediate: **#1221's first observable effect would be refusing the next fresh
Manas entry.**

---

## §8 — ⚠️ The correlated-shock question is still out of reach

Unchanged from every prior version, and it must not be read past. **An average-return backtest cannot
evaluate what a risk cap exists for.** The 6% aggregate cap bounds the loss if *many* open positions gap
against you together in a market-wide shock. This measurement reports the mean-path effect over 11
years; it says nothing about the tail the cap is purchased to cover.

**A favourable drawdown number here must not be read as validating the cap, and the unfavourable CAGR
number must not be read as invalidating it.** maxDD over a historical replay is a *realised-path*
statistic, not a tail bound — it happens to be the closest thing in this data to the cap's actual
purpose, which is precisely why it is the only result reported with any confidence, and still not
enough on its own to justify the rail. The correct instrument is a stress/scenario analysis over
simultaneous correlated gaps, which does not exist and is not attempted here.

---

## Caveats (ride with every number above)

- Inherited unchanged from #1218: historical-equity-universe **proxy**, not real Manas fills;
  **survivorship-biased**; **gross of costs** in both arms; ₹150,000 starting capital;
  `MAX_DEPLOYMENT_PCT` (80%) and `DAILY_LOSS_LIMIT` (10%/day) unmodelled; RS-priority same-day
  multi-symbol admission order and breakout-wins-tie are documented conventions, not derived facts.
- New here: the cold/warm split is a **modelled parameter, not an observed rate** (§6 item 1).
- The slippage model applies 5 bps to the cap projection only, and as `entry × (1+5/10000)` rather than
  production's double-paise-rounded form — a sub-paise difference, immaterial at these magnitudes.

---

## Recommendation on #1221

**Merge or hold is an owner call; this measurement removes one specific justification for merging and
supplies a different, weaker one.**

- **#1218's "+4.08pp CAGR, free improvement" reading is withdrawn** for #1221 as shipped. It described
  the cache-warm ceiling; production runs cold.
- **If the goal is more CAGR: the evidence does not support merging.** The point estimate is a −0.88pp
  cost and the sign is not stable enough to be worth arguing about in either direction.
- **If the goal is drawdown control: the evidence is supportive but not conclusive** — 9/9 arms
  shallower, cold arm best at −11.62pp, and it is the one result no robustness test overturned.
- **Cheapest de-risking move, and my actual recommendation: land the `effectiveStop` cached-vs-fallback
  counter first** (observability only, no behaviour change), let it run ~2 weeks, and replace the
  hybrid sweep's guessed parameter with a measured one. That converts the single largest unknown in
  this document into a fact for the cost of a metric.
- Whatever is decided, **#1221's own doc/comments should stop describing the cold path as a merely
  "conservative" fallback**. It is the normal path, it is where the cap's real behaviour lives, and
  `ManasGoverningStopCache`'s javadoc claim that a miss is "the SAME conservative behaviour the
  aggregate cap already had before this whole M40 effort" is accurate but badly undersells that this
  *is* the operating regime, not an edge case.

Paper-only at ₹1.5L/book, so there is no urgency either way.

---

## Open doubts

1. **The cold share is guessed, not measured.** Everything separating a −0.88pp cost from a +4.26pp
   benefit is one unobserved parameter. §6 item 1 is the fix and it is cheap. Highest-priority doubt.
2. **A single trade flips the CAGR sign** (`BHARATAGRI@2022-09-19`, +97.10%). Any CAGR statement in
   this doc, including my own −0.88pp, should be read as "indistinguishable from zero", not as a
   measurement.
3. **This reuses #1218's harness by instruction, so it cannot detect #1218's shared-mode errors.**
   Gate 3 proves the harness did not *drift*; it cannot prove it was right. #1218's Open Doubt 1
   (a third, independent implementation) remains open and this doc does not discharge it. Two prior
   review rounds each found a real defect in this harness — the base rate for a third is not zero.
4. **maxDD's robustness may be partly mechanical.** Fewer concurrent positions ⇒ lower gross exposure
   ⇒ smaller drawdowns, somewhat by construction. I did not decompose how much of the −11.62pp is a
   genuine risk-selection effect versus simply holding ~6 names instead of ~7. That decomposition
   would materially change how much weight the one surviving result deserves.
5. **The hybrid model restarts the whole cache at once** (index-modulo, all positions cold together).
   Reality is per-position: a restart empties everything, but re-warming is staggered as each position
   arms. Given (1), refining this is not worth doing before the hit-rate is observed.
6. **Divergences 3, 5 and 6 are argued to zero/near-zero frequency, not measured to it.** In particular
   divergence 6 (different ATR seeds and warm-up windows between live and backtest) means the live and
   modelled stops are not the same number even in principle, so any exact boundary-event count carries
   an unquantified error bar. This affects both arms and so is unlikely to move a delta, but "unlikely"
   is not "verified".
7. **Costs are applied post-hoc to the refused set only**, not re-run through both portfolio arms.
   The direction (favouring the cap) is argued from trade counts, not computed.
8. **Determinism was confirmed for two runs on 2026-08-02, not for a re-run on another date.** Two
   independent full runs (fresh JVM each) produced **byte-identical output** (`diff`, empty) across all
   ten arms. But `from` is `now() − 11 years`, so a re-run on a different calendar date measures a
   different window — the same standing caveat as #1218's Open Doubt 8. The byte-identity with #1218's
   own published figures holds precisely because both ran on 2026-08-02.
9. **Live evidence is a single snapshot** (2026-08-02, 6 positions, none armed). "No armed trails" is
   true today and is a strong argument, but it is one observation of a 4-week-old book, not a rate.

---

## Receipt

- **Doc:** `docs/signal-analysis/2026-08-02-m40-remeasure-shipped-semantics.md` (this file). Supersedes
  `2026-08-02-m40-risk-cap-backtest.md` **for the #1221 merge decision**; that doc's figures remain
  valid as the cache-warm bracket and it now carries a banner saying so.
- **Harness:** the committed `ManasRiskCapReplayTest.java`, extended additively (one file, +299/−8).
  Same opt-in switch. The v3 arms are unchanged and reproduce byte-identically (gate 3).
- **Re-run command:** `./mvnw.cmd -pl services/market-data-service -am test -Dtest=ManasRiskCapReplayTest
  -Dmanas.replay.enabled=true -Dmanas.replay.pgPasswordFile=<abs path to deploy/secrets/postgres_password>`
  (from a worktree the absolute override is required — #1218 Open Doubt 5, still true).
- **Headline:** under shipped semantics the cap refuses **304 entries / 274 sessions** (vs #1218's
  139/112); **CAGR −0.88pp, maxDD −11.62pp shallower, Sharpe +0.07**. The aggregate cap **replaces**
  `MAX_OPEN=7` as the binding rail (AGG_CAP 1,949→15,472; MAX_OPEN 15,302→123).
- **Sign robustness: FAILS on CAGR** (flips on the warm/cold assumption, non-monotonic in it, flips on
  1 of 304 trades, 5/12 years). **HOLDS on maxDD and Sharpe** (8/8 distinct cap arms; 12/12 with drop-k).
- **Validation:** fidelity 2,491/2,491 both setups; portfolio coverage 2,491 asserted in all 10 arms
  **and** asserted set-equal between baseline and shipped-cold; v3 figures reproduced byte-identically
  including the 139-row marginal set's mean/median/win-rate; hybrid `N=1` bit-identical to the
  independently-configured `INITIAL` arm.
- **Determinism:** 2 independent full runs on 2026-08-02, **byte-identical** (`diff`, empty).
  Checkstyle 0 violations, `BUILD SUCCESS`, `Tests run: 1, Failures: 0, Errors: 0`.
- **Production change: NONE.** Test-harness + docs only.
- **Claims labeled** [computed] / [sourced] / [recalled] / [assumed] inline.
- **Open doubts:** 9 above, topped by the un-measured cold share and the one-trade sign flip.
