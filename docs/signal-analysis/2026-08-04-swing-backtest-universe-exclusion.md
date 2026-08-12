# Swing backtest universe — the 260-bar silent exclusion

**Date:** 2026-08-04 · **Type:** investigation only (no production code, no live mutation)
**Question:** the swing backtests gate at `MIN_BARS = 260` on `candles`@1d, excluding ~374 EQ
symbols. Is that exclusion correct, or is the backtest universe materially different from the
tradeable one?

---

## Verdict

**The exclusion is CORRECT — close it.** The live screen enforces its *own* history floor of **252
sessions** (`TrendTemplateService.java:51`, a hard SQL filter), so **357 of the 374** backtest-excluded
symbols are excluded by the screen too. The two planes agree. **Zero** backtest-excluded symbols pass
either live screen — not today, and not on any persisted screen date in the table's history. The
excluded population is a rolling ~12-month cohort of genuine new listings that ages out completely and
is replenished at ~28/month; it is steady-state, not accumulating.

The one real gap on the briefed question is **17 symbols in a `[252, 260)` band** where the screen's
floor admits and the backtest's refuses. It is 8 sessions wide, every symbol crosses it in ~11 calendar
days, and none of the 17 currently passes a screen. It is a cosmetic threshold mismatch, not a bias.

**But close the briefed question and open a different one:** the same measurement shows **1,042 of
2,927 symbols (36%) contribute essentially nothing to any swing backtest** — 374 excluded outright plus
**668 that pass the gate and then yield 1–39 candidate bars**, because their entire `candles` history is
the ≤276-bar bhavcopy projection. Those 668 are counted as `scanned`. Eight of them passed the live
Minervini screen in the last month. That is a *gradient* problem inside the included set, not a gate
problem at its edge, and it is the more consequential of the two — see §6a.

---

## 1. Premise check (STEP 0)

| Premise as briefed | Verdict | Measured (IST timestamps below) |
|---|---|---|
| `MinerviniSwingBacktest.java:78` gates at `MIN_BARS = 260` | **CONFIRMED** | `private static final int MIN_BARS = 260;` `[sourced]` |
| `ManasAroraSwingBacktest.java:118` gates at `MIN_BARS = 260` | **CONFIRMED** | same literal `[sourced]` |
| 2,927 EQ symbols hold daily bars | **CONFIRMED exactly** | 2,927 `[computed]` |
| 419 sit below the gate | **STALE — now 374** | 419 → 393 (17:06) → 383 (17:10) → **374** (17:12 through 17:20, stable) `[computed]` |
| Live screen reads `nse_eod_bhavcopy`, not `candles` | **CONFIRMED** | `AdjustedEquityDailySql.java:64-81` `[sourced]` |
| "This is about the other ~374" | **COINCIDENTALLY the same number** | the brief's 374 = 419 − 45 gutted; my 374 = the *current* total short count, gutted included (the restore lifted ~40 of the 45 above 260 mid-investigation). Different sets, same size. `[computed]` |
| **PREMISE INCOMPLETE** | **the decisive fact is missing** | the brief frames this as "screen population vs backtest population differ". They differ far less than framed: **the screen has its own 252-session floor**, which the brief does not mention. That floor is what makes the answer "correct, close it". `[sourced]` |

The moving count is the concurrent CA restore, exactly as the brief warned. All numbers below are
from the **17:12–17:20 IST** window unless stated; the short set was stable at 374 across it.

---

## 2. Why is each short — the bucket table

Buckets are cut on the 260th-newest session, **2025-07-15** `[computed]`: a symbol whose first bar
post-dates it *cannot* have 260 bars.

| Bucket | n | avg bars | avg bhavcopy rows | Why short |
|---|---:|---:|---:|---|
| **A — genuine new listing** | **354** | 132 | 131 | listed after 2025-07-15 |
| **B — stopped printing** | 10 | 247 | 229 | 9 Axis ETFs delisted/renamed 2026-07-01, + `LANDMARC` (last bar 2025-03-27) |
| **C — long span, sparse** | 5 | 192 | 192 | intermittent coverage |
| **D — CA-gutted (known, fix in flight)** | 5 | 251 | 251 | bhavcopy full, `candles` destroyed — the residue of the 45 |
| **Total** | **374** | | | |

### Bucket A is genuinely new listings, not a backfill artifact

This is the load-bearing distinction, so I ran the separating test rather than assuming `[computed]`:

- **351 of 354** first appear in `nse_eod_bhavcopy` **mid-window** (after its 2025-06-20 start), i.e.
  an independent source agrees they were not trading earlier. Only 3 are absent from bhavcopy entirely.
- Their `candles` first bar and their bhavcopy first row **agree to within 5 days** (mean difference
  **−1 day**) for all 351. Two independent ingest paths agreeing to the day is not a backfill artifact.

~90 of the 374 (~24%) look like **ETFs** by symbol/name pattern (`ETF|BEES|GOLD|SILVER|NIFTY|SENSEX`)
— not swing-equity candidates at all. `[computed, approximate — pattern heuristic, no ETF flag exists
in `instruments`]`

---

## 3. Would they clear the screen's own floors?

The screen's gates, all from the main checkout `[sourced]`:

| Gate | Value | Where |
|---|---|---|
| **History floor** | **`sessions >= 252`** within the 420-day base window | `TrendTemplateService.java:51,166-167`; `ManasScreenService.java:49,175-176` |
| Price floor | close ≥ **₹30** (CA-adjusted) | `TrendTemplateService.java:52,168` |
| Liquidity floor | `avg_turnover_50 >= ` **₹937,500/day** (= 150000 × 0.25 × 25) | `TrendTemplateService.java:57-59,71,169` |

Raw liquidity of the 354 excluded new listings is **not** microcap — median ₹2.85 cr/day, p75 ₹20.9 cr,
p90 ₹79.2 cr, max ₹2,680 cr `[computed]`. On liquidity alone **216 of 351 clear ₹1 cr/day** and 152
clear ₹5 cr/day. The names are real: `INDOMIM`, `SBIFUNDS`, `GROWW`, `MEESHO`, `LENSKART`, `PINELABS`.

**But that is the wrong floor to test, and this is the crux.** The screen's *history* floor eliminates
them first:

> **Of the 374 backtest-excluded symbols, 357 also fail the screen's own 252-session gate. Only 17
> clear it.** `[computed]`

The 252-session gate is load-bearing precisely because nothing else would stop them: the moving
averages do **not** go NULL on short history — `avg(close) OVER (... ROWS BETWEEN 199 PRECEDING AND
CURRENT ROW)` (`TrendTemplateService.java:135`) averages whatever rows exist, so a 132-bar symbol would
otherwise get a *fake* "sma200" that is really a 132-bar mean and sail through every MA gate. The
`sessions >= 252` filter drops the row before any gate is evaluated `[sourced]`.

---

## 4. Any excluded symbol in live screen output? — the sharpest test

Persisted screen output, `screen_date = 2026-08-03` (Minervini 1,767 rows / 277 passers; Manas 2,262
rows / 124 passers) `[computed]`:

| Screen | Backtest-excluded symbols appearing as rows | …that **PASS** |
|---|---:|---:|
| Minervini | 4 | **0** |
| Manas | 8 | **0** |

**Stronger form — across every persisted screen date in both tables: 0 passing rows, 0 distinct
symbols** `[computed]`. (Bar counts only accumulate, so a *currently*-short symbol was even shorter on
any earlier screen date — the historical claim is sound in that direction.)

**No symbol is named, because none exists.** The 12 rows that do appear are the `[252, 260)` band and
all fail on ordinary gates:

| Symbol | bars | screen | gates passed | passes_all |
|---|---:|---|---:|---|
| `ANTHEM` | 255 | minervini | 7 / 8 | false |
| `SMARTWORKS` | 257 | minervini | 5 / 8 | false |
| `PASHUPATI` | 257 | minervini | 3 / 8 | false |
| `BELLACASA` | 256 | minervini | 1 / 8 | false |
| `ABGSEC`, `PASHUPATI`, `SMARTWORKS`, `ANTHEM`, `IVZINNIFTY`, `QUALITY30`, `BELLACASA`, `GROWWNIFTY` | 254–257 | manas | 1–5 | false |

`ANTHEM` at 7/8 gates and ₹52.7 cr/day is the closest any excluded name has come. It crosses 260 bars
within ~5 sessions of this writing.

### Cross-check

2,274 of 2,862 bhavcopy EQ/BE symbols clear the 252-session gate `[computed]`, which reconciles with
the 2,262 persisted Manas rows (the ~12 difference is the trailing-bar guard dropping stale symbols —
the 9 Axis ETFs among them). The measurement reproduces the screen's own arithmetic.

---

## 5. Steady-state or accumulating?

**Steady-state.** Cohorts age out completely and reliably `[computed]`:

| Listing quarter | symbols | now backtestable | still short |
|---|---:|---:|---:|
| 2023-Q1 … 2025-Q1 | 243 | **242** | **1** |
| 2025-Q2 | 674 † | 655 | 19 |
| 2025-Q3 | 86 | 13 | 73 |
| 2025-Q4 | 105 | 0 | 105 |
| 2026-Q1 | 55 | 0 | 55 |
| 2026-Q2 | 88 | 0 | 88 |
| 2026-Q3 (partial) | 33 | 0 | 33 |

† the 2025-Q2 row is inflated by the bulk NSE backfill onboarding (bhavcopy starts 2025-06-20), not by
674 real listings — a data-onboarding artifact, flagged so it is not read as a listing wave.

Every cohort older than ~15 months is **100% backtestable** (1 straggler in 243). The short pool is
exactly the trailing ~4 quarters of listings. Inflow ≈ 83.5/quarter ≈ **28/month**; residence = 260
sessions ≈ **12.4 months**; projected steady state ≈ 28 × 12.4 ≈ **347**, against a measured 374. The
pool is at its equilibrium size and drains as fast as it fills.

A related artifact worth not misreading: the 121 new bhavcopy symbols in 2026-04 are **62 real new
listings + 51 series/rename re-appearances** whose `candles` history averages 2,319 bars (~9 years)
`[computed]`. The 51 are fully backtestable and were never excluded.

---

## 6. Recommendation

**Close it — the exclusion is correct.** No code change is warranted on the evidence measured. Every
swing backtest number the owner has seen was computed on a universe that excludes names the live screen
*also* excludes, for the same reason (insufficient history to compute a 200-day MA or a 52-week high).
The backtest universe and the tradeable universe are the same universe.

Two optional, low-value follow-ups — **neither is a correctness fix**:

1. **Align the two floors** (252 vs 260) so the `[252, 260)` band vanishes. Touches a parity surface
   (`MIN_BARS` feeds golden vectors) for a band that is 8 sessions wide, empirically empty of passers,
   and self-draining. **Recommend NOT doing this** — the parity risk exceeds the benefit.
2. **Document the floor** in the swing-backtest doc so "2,927 symbols" is never read as the scanned
   universe. The honest figure is ~2,550 scanned.

---

## 6a. The sharper finding the brief did not ask for — a *gradient*, not a gate

The briefed question is binary and its answer is "correct". But the same measurement exposes something
more consequential, and it is the form the owner's concern actually takes.

Passing `MIN_BARS` is not the same as being *evaluated*. The sim loop runs `for (int i = MIN_BARS; i < n;
i++)` (`MinerviniSwingBacktest.java:177`) and entries fire only on/after `from`, while bars are read from
`warmStart = from.minusDays(600)` (`MinerviniBacktestService.java:423`) `[sourced]`. So a symbol
contributes `n − 260` candidate bars, and `n` is capped by its own history.

**1,030 of 2,927 EQ symbols have NO `candles` history other than the bhavcopy projection** — their
entire series is ≤ 276 bars `[computed]`:

| Tier | n | What a swing backtest actually does with them |
|---|---:|---|
| < 260 bars | **374** | excluded outright — **0** candidate bars |
| 260–299 bars (of which **662** are bhavcopy-only) | **668** | pass the gate, then yield **1–39** candidate bars — and those bars fall around 2026-07 onward, so for any backtest **ending before ~2026-07-15 they contribute zero trades while counting as "scanned"** |
| ≥ 300 bars | 1,885 | evaluated over the real window |

**1,042 of 2,927 (36%) of the "scanned" universe contributes essentially nothing to any swing backtest
result**, and 668 of them do so *invisibly* — they clear the gate, increment `scanned`, and produce no
trades. That is a materially different failure mode from the briefed one: not a silent exclusion, but a
silent **near-zero weight** inside the included set.

And unlike the 374, **this tier can and does reach the live screen**: 8 bhavcopy-only symbols passed
Minervini between 2026-07-03 and 2026-08-03 — `EIFFL` (22 days), `MAFANG` (15), `UNIVASTU` (9),
`LOTUSEYE`, `MASPTOP50`, `ARTEMISMED`, `DBOL`, `NRL` — and `UNIVASTU` + `NRL` also passed Manas
`[computed]`. All sit at exactly 276 bars: **screen-recommendable on 276 sessions, backtest-evaluated on
at most 16 bars.** The brief's question was "a name the screen can recommend that no backtest has ever
evaluated" — the strict answer is none, but these 8 are the honest near-miss, and the distinction
between "evaluated on 16 bars" and "never evaluated" is thinner than the gate suggests.

**Recommendation:** this is worth a separate look — specifically whether `scanned` should report
*evaluable* symbols rather than gate-passers, so a swing backtest's denominator stops counting names it
could not trade. I did **not** measure whether any of these 668 actually produced trades in a real run;
that is the check that would size it. Filed as a chip rather than folded into this verdict.

### Adjacent finding, out of scope — filed separately

`candles`@1d NSE holds **2,150 of 7,134 buckets stamped at intraday IST times** (09:15, 10:00, 11:00 …)
rather than midnight, producing **1,644 duplicate symbol-day rows across 478 symbols** `[computed]`.
The backtest reads `bucket::date` and would see two `DailyBar`s with the same date for those symbols.
This *inflates* bar counts, so it cannot create a false exclusion — it is orthogonal to this
investigation — but it is a real data-quality defect on the backtest's own plane.

---

## 7. Claim labels

| Claim | Label |
|---|---|
| `MIN_BARS = 260` in both backtests; screen floors 252 / ₹30 / ₹937,500 | **sourced** (file:line above) |
| 2,927 total; 374 short; 357 also screen-excluded; 17 in band | **computed** (SQL, 17:12–17:20 IST 2026-08-04) |
| 351 of 354 are genuine new listings (two-plane agreement, −1 day mean) | **computed** |
| 0 backtest-excluded symbols pass either screen, ever | **computed** |
| Steady state ≈ 347 vs measured 374 | **computed** (projection from measured inflow + residence) |
| ~90 of 374 are ETFs | **computed, approximate** — pattern heuristic, no ETF flag exists |
| 1,030 bhavcopy-only symbols; 668 in the 260–299 tier; 1,042 total near-zero-weight | **computed** |
| 8 bhavcopy-only symbols passed Minervini 2026-07-03…08-03, all at 276 bars | **computed** |
| Those 668 yield ≤39 candidate bars | **computed** from `n − MIN_BARS` with `n` capped by history — **not** confirmed against an actual run's trade output (see open doubt 7) |
| The 45 CA-gutted symbols have a fix in flight | **sourced** (brief + `2026-08-04-ca-gutted-restore-feasibility.md`) |
| Bar counts only accumulate (underpins the historical claim) | **assumed** — true except for CA-gutting, which *destroys* bars; see open doubts |

---

## 8. Open doubts

1. **The historical "never passed" claim rests on a monotonicity assumption that CA-gutting violates.**
   A symbol gutted by a corporate action *loses* bars, so a currently-short symbol could have been
   ≥260 bars and passing at an earlier screen date. The 45 gutted symbols are exactly this population.
   I did not reconstruct per-date bar counts to rule it out. The claim is solid for the 354 new
   listings (monotone by construction) and **not fully proven for the gutted residue**.
2. **`nse_eod_bhavcopy` starts 2025-06-20**, so the two-plane listing-date agreement can only be tested
   over 13.5 months. A symbol that listed in early 2025 shows `bh_first` = the bhavcopy start for
   reasons of ingest, not listing. I handled this by requiring `bh_first > 2025-06-25`, which
   *undercounts* real listings — conservative in the right direction, but it means the inflow rate of
   28/month is a floor, not a point estimate.
3. **Both source tables are retro-mutable.** Per the standing trap, `candles` has had whole series
   rewritten and `nse_eod_bhavcopy` gains rows for months-old trade dates. I did **not** gate any
   comparison on `fetched_at`. For the counting questions here (does a symbol have ≥260 bars *now*)
   that is the correct read, but it means these numbers are a snapshot, not a reproducible constant.
4. **The concurrent restore moved the count 419 → 374 during the investigation.** It was stable at 374
   across four measurements 17:12–17:20 IST, but it may move again. The *bucket proportions* are what
   the verdict rests on, and those are insensitive to the remaining ~5 gutted symbols.
5. **I did not verify the deployed screen matches the source I read.** Per the house rule, an
   `@Value` default is not a deployed value. The agent confirmed no override exists in
   `application.yml`, `docker-compose.yml`, or `.env` for `artha.minervini.min-sessions`, but I did
   not probe the running service. If the live floor were configured *below* 252, more symbols would
   enter the screen than I measured — though the persisted-output test (0 passers) is a direct
   observation of live behaviour and is not subject to this doubt.
6. **The backtest's real gate is bars within `from − 600 days`, not total bars.** I used total bars as
   the proxy. For the new-listing cohort the two are identical (their whole history is recent). For
   *delisted* names the proxy is wrong in the lenient direction — a name with 3,000 bars ending in
   2024 passes my proxy but would be excluded from a 2026 backtest window. That affects survivorship
   analysis, which this investigation did not attempt.
7. **§6a's "≤39 candidate bars" is arithmetic on the loop bounds, not an observed trade count.** I read
   the loop and the window bound from source and computed `n − MIN_BARS`; I did **not** run a backtest
   and count how many trades those 668 symbols actually produced. The direction is not in doubt (the
   loop cannot evaluate bars that do not exist), but the *materiality* — whether these names measurably
   move a headline number, or are simply inert — is unmeasured. That is the check that should size the
   chip, and it is the one thing in this document I would want verified before anyone acts on §6a.
8. **§6a's 1,030-symbol cohort is defined by `source <> 'BHAVCOPY'` being absent**, which is a broader
   set than the 45 CA-gutted symbols — it includes names that were simply never Kite-backfilled. I did
   not separate "never had deep history" from "had it and lost it"; for the evaluable-bars argument the
   distinction does not matter, but it would matter for deciding whether backfilling them is worthwhile.
