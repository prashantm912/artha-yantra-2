# E1 — forward-paper reliability analysis (ledger row `forward-paper-reliability-month`)

**Date:** 2026-08-03 · **Scope:** READ-ONLY measurement. No code, no config, no arming, no DB writes.
**Ledger row:** `docs/superpowers/plans/2026-07-02-remaining-items.md:724` (§E, tier OWNER).
**Method authority:** [`../superpowers/plans/2026-06-30-live-signal-analysis-runbook.md`](../superpowers/plans/2026-06-30-live-signal-analysis-runbook.md) (exit bands from executed trades) + [`README.md`](README.md) (entry gates from rejections).
**Data:** live DB `artha` (`ay-timescaledb`), all timestamps rendered `AT TIME ZONE 'Asia/Kolkata'`, all bounds `+05:30` literals.

---

## 0. Verdict

**The book is not yet measurable. E1 cannot be closed today, and the honest deliverable is the
population count, not a list of per-strategy verdicts.**

E1 asks for three things. Two are refusals-on-sample and one is answerable:

| E1 asks for | Verdict | Why |
|---|---|---|
| **Per-scalper keep / cut / tune** | **NOT SUPPORTABLE — 0 strategies get a verdict** | 10 closed trades total, 3 sessions, and every one of them is a *blended pair* of two strategies (§3.1). Per-strategy attribution is arithmetically impossible on this data, not merely thin. |
| **E9 exit-band (35% TP / trail) tune** | **ANSWERED — leave the band alone; it is inert, not mistuned** | The band has never bound once in the entire live-paper history. Max premium excursion at exit was **6.14%** against a **35%** target and a **25%** premium stop (§3.3). This is a structural read, not a statistical one, so the small n does not undermine it. |
| **Swing §0.5 #12 reliability sign-off** | **REFUSED — bar not met, on sample AND on criteria** | Bar is ≥30–50 closed trades with expectancy > 0, payoff ≥ 2, batting ≥ ~45%. Actual: **n=9 per book**, batting **0%** (minervini) / **11%** (manas-arora), expectancy negative in both (§4). |

**Strategy counts (38 published+enabled scalpers):**

| bucket | n | note |
|---|---|---|
| **Keep / cut / tune verdict issued** | **0** | see §3.1 — the blend confound blocks all four |
| **Unmeasurable — never fired a signal** | **33** | zero ENTRY rows in the entire live history |
| **Unmeasurable — PE side, zero signals ever** | *(18 of those 33)* | §3.5 |
| **Measured but not separable (blended pairs)** | **4** | golden-crossover ± connect-the-dots × {nifty, sensex-niftyoi} |
| **Fired but never routed to paper** | **1** | `scalp-straddle-nifty` — 9 NEUTRAL null-qty signals, last 07-20; **now `enabled=f`**, so outside the 38 |

**The one number that matters for reading everything below:** the scalper book's headline
loss of **−₹3,109.70** collapses to **−₹161.12** when the 3 worst trades are dropped, and to
**+₹69.58** when two of the three sessions are dropped (§3.4). The sign is carried by a single
session. **This measurement cannot distinguish the book from zero.**

---

## 1. Why the window is not "~1 month"

E1's premise is ~1 month of accrual. **The scalper half of that premise is false.** *(computed)*

Scalper auto-paper was a **dead money path until 2026-07-28** — position sizing was computed
against the index future instead of the option leg, flooring every size to 0 lots and returning
silently ([`2026-07-28-scalper-paper-routing-dead-path.md`](2026-07-28-scalper-paper-routing-dead-path.md), G7/T25).
The first scalper paper row in the platform's history is `id=41`, opened **2026-07-29 11:07 IST**.

```
book         status   n   first_open   last_open
manas-arora  CLOSED   9   2026-07-05   2026-07-24
manas-arora  OPEN     6   2026-07-10   2026-07-31
minervini    CLOSED   9   2026-07-07   2026-07-24
minervini    OPEN    11   2026-07-07   2026-07-31
scalper      CLOSED  10   2026-07-29   2026-08-03
```

So against ~21 trading sessions in the accrual window:

- **scalper**: paper trades on **3** sessions (07-29, 07-31, 08-03). Signals on 10 sessions; the
  07-27 and 07-30 CE signals still carried the null-qty defect and never routed.
- **swing**: batch ran every session since 07-06; 18 closed + 17 open.

**The scalper book has ~3 sessions of accrual, not a month.** Everything in §3 is conditioned on that.

---

## 2. Method + harness verification (do this before believing any P&L)

Per the surprising-result rule, the harness was checked before the strategies. Four checks:

**2.1 `realized_pnl` is NET of costs — confirmed, both directions.** *(computed)*
Reproduced from raw fills: position 41 = BUY 40 @ 482.05 → SELL 40 @ 461.00, gross
(461.00−482.05)×40 = **−842.00**, recorded `realized_pnl` **−971.06**, difference **−129.06** = costs.
Corroborated in code: `PaperService.java:1157-1166` computes
`realized = exit.netValue() + entryBasis.netValue()` where `netValue` is signed cash *including*
costs (`FillSimulator.java:97-98`, `LtpSlippageV1.java:64-66`). Entry slippage is baked into
`avg_entry_price` (`PaperFillService.java:53-59`). **No cost double-count and no missing cost.**

**2.2 Measured cost rates.** *(computed, from row arithmetic — not from the rate table)*

| book | round-trip cost | as % of entry notional |
|---|---|---|
| scalper (options) | ₹129–₹149, mean **₹138.82** | 0.498 – 0.681%, mean **0.588%** |
| swing (equity) | ₹24.60 – ₹51.01 | 25.9 – 28.3 bps, mean **~27 bps** |

**2.3 Fill model is optimistic, in a direction that *strengthens* the negative reads.** *(computed)*
`ltp_slippage/v1`: equity 5 bps proportional; **options a flat ₹0.05 = one tick** — on a ₹482
SENSEX premium that is **1.0 bp**, far tighter than a real option spread. Real fills would be
worse than measured, so the scalper book's measured loss is a **floor, not a ceiling**.

**2.4 The 20:00–20:05 IST swing `opened_at` is BY DESIGN, not a defect.** *(sourced)*
`MinerviniSwingScheduler.java:48` `cron 0 0 20 * * MON-FRI zone Asia/Kolkata`;
`ManasAroraSwingScheduler.java:47` `0 5 20`. Post-close batch, documented as such.

---

## 3. The scalper book

### 3.1 ⚠️ Per-strategy attribution is arithmetically impossible — the blocking finding

**Every scalper paper position is a 50/50 pyramid of TWO strategies, credited entirely to one.** *(computed)*

`scalp-golden-crossover-*` and `scalp-connect-the-dots-*` fire on the **same bar, at the same
price, 0–2 seconds apart**, and both `openPosition` calls land on the same
`(book, exchange, tradingsymbol, side)` — which **averages into the open position** rather than
rejecting (the known pyramiding behaviour; `uq_paper_positions_open` guards the row, never the qty).
The position's `opening_signal_id` records only the **first** signal.

Raw evidence — every entry appears exactly twice, and `qty` is the sum:

```
id  signal  slug                                   sym                 side qty  fill_price   IST
72  128     scalp-golden-crossover-nifty           NIFTY2680424200CE   BUY   65    199.3000   07-31 11:16:21
73  129     scalp-connect-the-dots-nifty           NIFTY2680424200CE   BUY   65    199.3000   07-31 11:16:23
74  (exit)                                         NIFTY2680424200CE   SELL 130    202.8500   07-31 11:46:15
```

`suggested_qty` was **65 each**; the position carries **130**. Same pattern on all 10 positions
(SENSEX: 20 + 20 → 40). **Consequence:** a naive `GROUP BY slug` produces

```
scalp-golden-crossover-nifty            n=5   +69.58
scalp-golden-crossover-sensex-niftyoi   n=5  -3179.28
```

…and **both rows are wrong**. `connect-the-dots` contributed exactly half of every trade and
shows n=0. The two strategies in each pair traded the identical instrument at the identical price
for the identical holding period, so their per-unit P&L is **mathematically identical** — the
data cannot discriminate them **at any sample size**, let alone this one.

**This is not a small-n problem. Fixing n does not fix it.** Separating these strategies requires
either per-strategy sub-books or per-signal lot tagging; neither exists today.

### 3.2 What the 10 trades actually are

| id | day | instrument | qty | entry | exit | gross | **net** | reason |
|---|---|---|---|---|---|---|---|---|
| 41 | 07-29 | SENSEX26JUL77200CE | 40 | 482.05 | 461.00 | −842.00 | **−971.06** | TIME_STOP |
| 42 | 07-29 | SENSEX26JUL77200CE | 40 | 498.05 | 478.15 | −796.00 | **−926.32** | STRUCTURAL_STOP |
| 43 | 07-29 | SENSEX26JUL77200CE | 40 | 518.05 | 502.95 | −604.00 | **−736.07** | TIME_STOP |
| 44 | 07-29 | SENSEX26JUL77300CE | 40 | 479.20 | 487.40 | +328.00 | **+197.50** | TIME_STOP |
| 47 | 07-31 | NIFTY2680424200CE | 130 | 199.30 | 202.85 | +461.50 | **+318.26** | TIME_STOP |
| 48 | 07-31 | NIFTY2680424200CE | 130 | 216.65 | 229.95 | +1729.00 | **+1579.80** | TIME_STOP |
| 49 | 07-31 | NIFTY2680424250CE | 130 | 197.70 | 192.95 | −617.50 | **−758.82** | STRUCTURAL_STOP |
| 50 | 07-31 | NIFTY2680424250CE | 130 | 196.05 | 197.00 | +123.50 | **−18.46** | STRUCTURAL_STOP |
| 51 | 07-31 | NIFTY2680424250CE | 130 | 198.90 | 191.90 | −910.00 | **−1051.20** | STRUCTURAL_STOP |
| 55 | 08-03 | SENSEX2680678200CE | 40 | 749.80 | 734.95 | −594.00 | **−743.33** | STRUCTURAL_STOP |

**Legs first:** 10 closed round-trips = 20 legs, across 3 sessions, 4 distinct option contracts,
all **BUY CE long-premium**. Three of the four 07-29 trades are *the same contract re-entered
three times in two hours* while it trended down — that is **one event sampled three times**, not
three independent observations.

**Then P&L:** gross **−₹1,721.50**, costs **−₹1,388.20**, **net −₹3,109.70**.
Gross win rate 4/10; **net win rate 3/10** — costs flipped trade 50 from +₹123.50 to −₹18.46.
Mean signed premium move **−0.906%**; mean |move| **2.930%**; cost hurdle **0.588%** of notional,
i.e. **20% of the average absolute move is consumed by costs before direction is even considered.**

### 3.3 E9 band: inert, not mistuned — the one solid answer

Both firing strategies carry the full E9 band in their **published** config *(computed, read from
`strategy_versions.config->'exit_rules'` at `published_version_id`)*:

```
take_profit  premium_pct  35        stop_loss   premium_pct  25
stop_loss    index_points 60        time_stop   max_bars     10   (10 × 3m = 30 min)
trailing_stop indicator supertrend_line          signal_exit close < vwap
```

**Exit attribution across the whole book:**

| close_reason | n | net |
|---|---|---|
| TIME_STOP (10 bars = 30 min) | 5 | **+₹388.43** |
| STRUCTURAL_STOP (index_points 60) | 5 | **−₹3,498.13** |
| TAKE_PROFIT | **0** | — |
| premium stop_loss / TRAILING_STOP / SIGNAL_EXIT | **0** | — |

**`TAKE_PROFIT` has never appeared as a `close_reason` in the entire `paper_positions` table —
all books, all time** *(computed: `SELECT close_reason, count(*) … GROUP BY 1` returns
TRAILING_STOP 11, STRUCTURAL_STOP 5, STOP_LOSS 5, TIME_STOP 5, MANUAL 2, NULL 17 — no TAKE_PROFIT row)*.

**Why it is inert, mechanically:** the widest premium excursion observed at exit was **+6.14%**;
the band sits at **+35% / −25%**. The 30-minute `time_stop` and the 60-index-point structural stop
both bind **~6× tighter** than the premium band. The band is unreachable at this holding period —
it is not mistuned, it is **not in the causal path at all**.

> **Recommendation: leave the E9 band untouched.** Tuning it would be tuning a rule that has
> never executed. The live exit question is **`time_stop` vs `structural_stop`**, not the band —
> already open as **T29** in [`rollup.md`](rollup.md):139,191 (2026-07-29: champion shadow with no
> `time_stop` **+₹15,260.87** vs live paper with `time_stop: 10 bars` **−₹2,435.95** on the same
> tape). *That −₹2,435.95 is exactly this analysis's 07-29 day total* — an independent
> reproduction, from the paper book rather than the shadow book. **E1 does not supersede T29;
> it corroborates it.**
>
> ⚠️ **Do not read T29 as settled, and this section does not strengthen it.** `rollup.md`:185
> records that the sign **already flipped** on the 07-30 `mixed` day — hold-to-15:12 **−88.50 pts**
> vs the 30-minute stop **−72.87 pts**, the stop better by +15.63 on 6 matched events. T29 is a
> two-sided sample awaiting a chop-day observation. My §3.2 table is 4 of the same 07-29 trades
> that produced T29's trend-day side, so it adds **no independent weight** to the "remove the
> time_stop" direction — it is the same event, counted again.
>
> One corroborating detail worth carrying: `rollup.md`:139 notes 3 of those 4 losing exits were on
> `SENSEX26JUL77200CE`, **a leg that ran 441.40 (11:00) → 580.25 (14:30)**. That is the same
> single-contract concentration §3.4 flags — the three "independent" losses are three exits from
> one instrument during one intraday drawdown that later rallied.

### 3.4 ⚠️ Sign robustness: the headline FAILS

This is the section that decides the verdict. *(computed)*

| perturbation | book net | sign |
|---|---|---|
| **as measured (n=10)** | **−₹3,109.70** | − |
| drop 3 worst trades | **−₹161.12** | − *(indistinguishable from zero)* |
| drop 2 worst trades | −₹1,087.44 | − |
| drop 3 best trades | −₹5,205.26 | − |
| **drop session 07-29** | **−₹673.75** | − |
| **drop sessions 07-29 + 08-03** | **+₹69.58** | **+ (flips)** |

Per-session: 07-29 **−₹2,435.95** (n=4) · 07-31 **+₹69.58** (n=5) · 08-03 **−₹743.33** (n=1).

**78% of the loss comes from one session, and 3 of that session's 4 trades are the same contract.**
Removing that single session removes the result. The sign is **not robust to window shift and not
robust to leave-3-out**. Per the standing rule about a headline mean flipping on two rows that were
one real event — **this is that failure mode, and it is flagged, not buried.**

Additionally, the apparent "NIFTY strategy good / SENSEX strategy bad" split in §3.1's naive table
is **fully confounded with day and instrument**: `-nifty` = the 07-31 session only; `-sensex-niftyoi`
= 07-29 + 08-03 only. There is no session in which both traded, so no comparison is possible.

### 3.5 The 33 strategies with no evidence at all

Of 38 published + enabled scalpers, **only 5 have ever emitted an ENTRY signal** *(computed, full
history of `strategy.signals` — which holds firing bars only)*:

```
scalp-golden-crossover-nifty           13   scalp-connect-the-dots-nifty            7
scalp-straddle-nifty                    9   scalp-connect-the-dots-sensex-niftyoi   7
scalp-golden-crossover-sensex-niftyoi   8
```

**The 18 `-pe` scalpers have fired ZERO signals — not "zero since 07-21", zero ever**
*(computed: `SELECT count(*) … WHERE slug LIKE '%-pe'` = **0**; the `scalper_detail->>'side'`
distribution over all scalper signals is CE 35 / NEUTRAL 9 / null 29 — **no PE row exists**)*.

They are **alive and evaluating**, not dead: the PE slugs generate thousands of
`signal_rejections` rows daily (`volume-floor` 4,148 across 25 slugs; `option-side-constraint`
84 rows across 6 slugs from 07-21 onward — the bias-veto rail). Per the brief this is the 60m
SuperTrend bias veto working as designed on a persistently bullish tape.

> **Verdict for all 18 PE strategies: UNMEASURABLE-SO-FAR. No keep/cut/tune.** Absence of
> signals on a one-directional tape is not evidence about the strategy.
>
> *Caveat on the mechanism:* `blocking_rail` records the **first** failing rail and rails
> short-circuit, so the dominant `volume-floor` count does not contradict a downstream bias veto —
> both can hold. I did not re-derive the veto's 100% claim; it is carried as **sourced** from the brief.

### 3.6 Per-scalper keep / cut / tune

| strategy | n (closed) | verdict | evidence |
|---|---|---|---|
| `scalp-golden-crossover-nifty` | **5, blended** | **NO VERDICT** | 1 session; 50/50 blend with connect-the-dots (§3.1); sign not robust (§3.4) |
| `scalp-connect-the-dots-nifty` | **0 attributed / 5 real** | **NO VERDICT** | contributed half of every 07-31 trade, credited none |
| `scalp-golden-crossover-sensex-niftyoi` | **5, blended** | **NO VERDICT** | 2 sessions, one dominated by a thrice-repeated contract |
| `scalp-connect-the-dots-sensex-niftyoi` | **0 attributed / 5 real** | **NO VERDICT** | as above |
| `scalp-straddle-nifty` | 0 | **NO VERDICT — currently disabled** | `enabled=f` (published, but the engine loads `enabled AND published`); 9 NEUTRAL signals, all null-qty, none routed; last 07-20 |
| **33 others** (incl. all 18 `-pe`) | 0 | **UNMEASURABLE-SO-FAR** | never fired |

**Zero cut recommendations. Zero tune recommendations.** Per the standing prior — *every measured
loosening of the scalper entry gate has lost money (T1/T7/G13/G10)* — a "tune" here would have to
clear legs → P&L → sign robustness → costs. **Nothing in this data clears even the first gate**,
and the one loosening-shaped idea the data might suggest (relax the structural stop, since
STRUCTURAL_STOP carries the whole loss) is **exactly the shape the prior warns about** and rests
on n=5 across 2 sessions. Do not act on it.

---

## 4. Swing book — §0.5 #12 reliability sign-off

**The bar** *(sourced,* `archive/2026-07-04-minervini-build-log.md:496` *and* `:440`*)*:
**≥30–50 single-stop forward paper trades**, **positive expectancy**, **payoff ≥ 2**,
**batting ≥ ~45%** (`SwingReportCard` A/B/C/D grade).

### 4.1 Measured

| book | n closed | batting | expectancy | avg win | avg loss | payoff | **grade vs bar** |
|---|---|---|---|---|---|---|---|
| `minervini` | **9** | **0.0%** (0/9) | **−7.84%**/trade | — | −7.84% | **0.00** | **FAIL** (n, expectancy, payoff, batting) |
| `manas-arora` | **9** | **11.1%** (1/9) | **−5.59%**/trade | +2.38% | −6.58% | **0.36** | **FAIL** (n, expectancy, payoff, batting) |
| combined | 18 | 5.6% | −6.72%/trade | | | | |

Excluding the 2 `MANUAL` (owner-intervention) closes, `manas-arora` is n=7, expectancy **−5.07%** —
still negative. Sign robustness: combined expectancy stays negative at **−5.50%** dropping the 3
worst and **−4.67%** dropping the 5 worst. **Unlike the scalper book, the swing negative IS robust**
— it is a broad pattern (17 of 18 losses), not an outlier artifact.

> ### ⛔ Sign-off: **REFUSED.** n = 9 per book against a ≥30 bar (30% of the minimum), and all
> three quality criteria fail independently of sample size. **Do not promote; keep accruing.**

### 4.2 …but 17/18 losses is a surprising result, and it has a testable cause

Per the surprising-result rule, this was treated as a harness suspicion first. **It is largely a
censoring artifact, and the artifact is real.** *(computed)*

The exit doctrine (8% stop → 50-day-MA trail) is designed to **cut losers fast and let winners
run**. Measuring only *closed* trades therefore samples the losers preferentially — **the winners
are still open**:

| | closed | open |
|---|---|---|
| **win rate** | **1/18 = 5.6%** | **7/17 = 41%** |

Marking the 17 open positions to their last stored daily close:

| book | realized (closed) | unrealized (open) | combined |
|---|---|---|---|
| `minervini` | −₹6,689.84 | **+₹4,248.46** | −₹2,441.38 |
| `manas-arora` | −₹7,278.75 | −₹596.35 | −₹7,875.10 |
| **total** | **−₹13,968.59** | **+₹3,652.11** | **−₹10,316.48** |

⚠️ **The unrealized number is itself not robust:** `DIACABS` alone (+₹3,885.84, +39.97%) is **106%
of the entire unrealized gain**. Excluding it, combined = **−₹14,202.32**. So the censoring
correction changes the *interpretation* (the book is not a 5.6%-batting catastrophe) **without
changing the sign** — and rests on one position.

**Conclusion:** the closed-trade view *understates* the book by construction, but the corrected
view is still negative and still far below the bar. The refusal in §4.1 stands. **Future swing
report-card readings must state realized + unrealized together, or they will systematically
libel a trend-following book.**

### 4.3 Measured mechanical finding: the 8% stop realizes at ~11.7%

*(computed, n=5 STOP_LOSS closes — but only **4 independent names**: `SATIN` appears in both books
at an identical price, so those two rows are one event.)*

| name | configured stop | exit fill | overshoot past stop | realized loss |
|---|---|---|---|---|
| GNA | 544.64 (−8.05%) | 507.75 | **−6.77%** | −14.27% |
| SBCL | 722.76 (−9.02%) | 695.45 | −3.78% | −12.46% |
| SATIN ×2 | 246.09 / 247.36 (−8.5/−8.0%) | 239.69 | −3.10% / −2.60% | −10.90% |
| KRN | 1182.66 (−8.05%) | 1156.02 | −2.25% | −10.12% |
| **mean** | **−8.34%** | | **−3.70%** | **−11.73%** |

**Cause, confirmed in code:** swing exits are **close-basis only** — `ExitEvaluator.java:323`
evaluates on `series.candle(primaryIndex).close()`, and `SwingBatchEngine.java:1072` settles at
that same `bar.close()`. There is no intrabar H/L touch anywhere on this path
(`IntrabarExitResolver` is dead code — `docs/audits/2026-07-10-research-fidelity-audit.md:132`, B3).
Compounded by **T10** (`2026-07-25-weekly-bug-queue.md:35`): equities are not on the live tick
subscription, so `PaperBracketEvaluator` polls them all session and never gets a tick — **the
intraday `stop_loss` is decorative and only the 20:00 batch can exit.**

**Implication for the reliability bar: risk per trade is running ~40% wider than configured.**
An 8%-stop book is in practice an ~11.7%-stop book. Owner already accepted EOD-only exits
(option (b), #992), so this is a **sizing/expectation** finding, not a new defect — but the
§0.5 #12 expectancy must be judged against the stop the book *actually realizes*.

---

## 5. Incidental finding (not part of E1, flagged because it is on the live money path)

> ⚠️❌ **§5 IS FALSIFIED — CORRECTED SAME DAY, 2026-08-03. Do not act on it. Rupee error: ₹0.00.**
> A dedicated follow-up probe ([#1250](https://github.com/prashantm912/artha-yantra-2/pull/1250),
> `2026-08-03-swing-stop-realization-and-stale-entry-probe.md`) swept **all 32** batch-priced entries:
> **32 same-day, 0 stale.** `KAPSTON` filled at 475.14 = **07-31's own close** 474.90 × 1.0005 (07-30
> closed 464.00, which would have filled 464.23); `SCPL` at 615.31 = 615.00 × 1.0005 (07-30: 551.15).
> The persisted stops confirm it independently: 436.9080 = 474.90 × 0.92 exactly.
>
> **Root cause of this false positive: `bucket::date` is UTC.** The "no rows for 2026-07-31" and "last
> full day 07-30 with 2,701 symbols" readings below are the *same rows* shifted one day — that "last
> full day 07-30 with 2,701" **is** 07-31 with 2,701, and the "stray row on 08-02, a Sunday" is 08-03,
> a Monday. Both reproduce to the row once the bound is `+05:30` instead of `::date`. This is the trap
> CLAUDE.md documents, and it produced a confident, wrong, money-path finding here.
>
> **What survived the correction, and is real:** a **5-session candle-projection hole** (2026-06-12/15/16/18/19
> + partial 06-17) where raw bhavcopy ingested 3,245–3,287 rows/day and the `candles` projection wrote
> **zero**; the ingest canary being structurally unable to see it (`BHAVCOPY` is `REQUIRE_SUCCESS`,
> satisfied regardless of `rows_written`, and counts RAW rows not projected candles); and the fact that
> **the guard exists and is armed on the wrong path** — `truncateToSession` with a pinned
> `requiredBarDate` works, the 08:35 catch-up pins it, the 20:00/20:05 scheduled path passes `null`.
> ⚠️ Also corrected there: **M14 is not that guard** — its `stale` flag is an upstream-exception marker,
> not an age check, so the sentence below about M14 "materialising" is wrong on the mechanism too.

⚠️ ~~**The 2026-07-31 swing batch priced its entries off Thursday 07-30's close.**~~ *(FALSIFIED — see the box above)*

`marketdata.candles` @ `1d` / NSE has **no rows for 2026-07-31 or 2026-08-03** (last full day
2026-07-30 with 2,701 symbols; a single stray row on 08-02, a Sunday). The swing batch takes
`series.get(series.size()-1).close()` as the entry price, so with 07-31's bar absent it used
07-30's. Confirmed arithmetically on both positions opened that evening:

| position | entry fill | 07-30 close × 1.0005 (5 bps slippage) |
|---|---|---|
| `KAPSTON` (minervini) | **475.14** | 474.90 × 1.0005 = **475.14** ✓ |
| `SCPL` (manas-arora) | **615.31** | 615.00 × 1.0005 = **615.31** ✓ |

This is the M14 scenario materialising: `MarketDataCandlesClient` **logs** a stale response and
increments `ay_candle_fetch_stale_total`, but there is **no refusal gate** — so a stale series
silently becomes a fill price. Two live positions carry a one-session-stale basis, and the
mark-to-market in §4.2 is also as-of 07-30.

*I did not establish the root cause of the missing 07-31 EOD equity ingest* — it could be a
missed batch, a failed bhavcopy, or a retention/compression effect. Recommend checking
`GET /api/v1/market/health/ingest` and `marketdata.ingest_runs` for 07-31. **Worth its own chip;
out of scope here.**

---

## 6. What would make E1 decidable

Ordered by how much each unblocks, not by effort:

1. **Break the strategy blend (§3.1) — the hard blocker.** Until golden-crossover and
   connect-the-dots stop merging into one position, *no amount of accrual* produces per-strategy
   verdicts. Needs per-strategy sub-books or per-signal lot tagging. **This is the prerequisite
   for the "per-scalper keep/cut/tune" half of E1 and should be decided before more accrual is
   banked on it.**
2. **Accrue ~10× the scalper sample.** 10 trades / 3 sessions → target ≥30 closed trades across
   ≥15 sessions *per separable strategy* before any keep/cut. At the current ~3 trades/session
   on ~1 firing session in 4, that is **months**, not weeks — unless entry frequency rises.
3. **Swing: 21+ more closed trades** to reach the ≥30 floor, then re-run §4 with realized +
   unrealized stated together (§4.2).
4. **PE evidence requires a bearish or two-sided tape.** Nothing to build; it is a market-regime
   wait. Do not "fix" it by loosening the bias veto — that is the T1/T7/G13/G10 shape.
5. **T29 (`time_stop` vs `structural_stop`) is the live exit question**, not the E9 band. This
   analysis independently reproduces its 07-29 figure (§3.3).

---

## 7. Open doubts

1. **The measured swing cost (~27 bps of entry notional) is ~3× lighter than a code-read of
   `FeeConstants` predicts (~38 bps of turnover, i.e. ~76 bps of one-side notional).** I did not
   reconcile them. Part of the gap is definitional — my figure derives cost as
   `gross(using avg_entry_price) − net`, which **excludes entry slippage** already baked into
   `avg_entry_price`. Whether that fully accounts for it, I do not know. **Immaterial to the swing
   verdict** (trades lost 5–14%; costs are 0.27%) but it would matter to any future
   cost-sensitivity work. The scalper option cost (0.588% of notional) is measured the same way
   and carries the same caveat, where it matters more (costs = 45% of the net loss).
2. **`FeeConstants.java:62-63` documents `STAMP_EQUITY` as 0.015% but encodes `0.00001500` =
   0.0015% (10× low)**, and `FeeConstantsDriftTest.java:35` pins the discrepancy rather than
   catching it *(sourced, from the code trace — I did not verify this myself)*. Under-charges, so
   it does not explain any negative result. Not filed anywhere I found; may deserve a chip.
3. **The PE bias-veto mechanism is `recalled`/`sourced` from the brief, not re-derived here.** I
   confirmed the *outcome* (0 PE signals ever) and that the `option-side-constraint` rail is
   active from 07-21, but `blocking_rail` short-circuits, so I cannot confirm the "blocks 100% of
   PE evaluations" claim from rejection data alone.
4. **§4.2's censoring correction rests on one position** (`DIACABS`, 106% of the unrealized gain)
   **and on marks that are 2 sessions stale** (§5). The direction of the correction is solid; its
   magnitude is not.
5. ~~`scalp-straddle-nifty` is not in the published+enabled set and I did not determine why.~~
   **RESOLVED during review** *(computed)*: `enabled = f`, `published_version_id` set — it is
   **disabled, not unpublished**, so the engine does not load it (`enabled AND
   published_version_id IS NOT NULL`). Its 9 NEUTRAL null-qty signals all predate the routing
   fix, so it has no P&L either way. *I did not determine when or why it was disabled.*
6. **I did not run the §4.B counterfactual band grid** from the runbook. With the band never
   binding (§3.3) and n=10 confounded to ~3 independent events (§3.4), a grid over these trades
   would fit noise. This is a deliberate omission, not an oversight — the runbook's own §5
   overfitting guard ("only recommend changes that are LARGE + CONSISTENT across families/weeks")
   cannot be satisfied by 3 sessions.
7. **Regime tag:** the whole window is a single persistently-bullish tape (the same fact that
   silenced every PE strategy). Even a well-powered result from this window would be
   one-regime. Not a defect — a bound on what any amount of *this* data can conclude.

---

## 8. Claim labels

`computed` — all DB measurements: population counts, per-trade P&L, gross/net/cost decomposition,
premium-move distribution, sign-robustness perturbations, exit attribution, the blend confound
(§3.1), the zero-PE-signals fact, swing expectancy/batting/payoff, stop overshoot, the 07-31
stale-basis finding (§5).
`sourced` — the §0.5 #12 bar (build-log `:440`,`:496`); the scalper routing dead-path history;
T29 (`rollup.md`); T10 + B3; the code trace of `realized_pnl`, fill pricing, schedulers and
`ExitEvaluator` (file:line via a read-only code investigation, spot-checked against row arithmetic
in §2.1 — the cost-model finding and the close-basis exit finding were each independently
corroborated by measurement before being relied on).
`recalled` — none load-bearing.
`assumed` — that ~21 trading sessions fall in 2026-07-06..2026-08-03 (weekday count, not
holiday-adjusted); used only for coverage context, no verdict depends on it.
