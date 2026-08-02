# 2026-08-02 — M40: how often would the 6% aggregate open-risk cap have bound? (v3, corrected)

Owner-commissioned measurement, per the ledger's M40 reframe (`docs/superpowers/plans/2026-07-02-remaining-items.md`
row E4, landed via [#1216](https://github.com/prashantm912/artha-yantra-2/pull/1216) @ `878e86f4`): fresh Manas
entries are unbounded by the doctrine's 5–6% aggregate open-risk cap
(`docs/signal-analysis/2026-08-02-m40-fresh-entry-risk-cap-gap.md`, landed via
[#1214](https://github.com/prashantm912/artha-yantra-2/pull/1214)). Enforcing the cap is HOLD-tier (it would refuse
entries that currently fire, a live-P&L change) — this doc is the "measure first" evidence the ledger asked for,
not a decision. **Nothing is armed, enforced, or changed by this doc.**

**This is the SECOND correction.** A first version measured a 6-slot surrogate on a non-LIVE-equivalent admission
model (withdrawn). A second version fixed both of those, but a cross-vendor review found a further, more subtle
bug in the fix: the portfolio replay's per-symbol pointer initialization never advanced past a symbol's
warm-up-period bars, so only symbols whose FIRST loaded candle happened to fall on/after `from` could ever
participate — silently excluding almost every established, long-listed symbol from BOTH arms. **Those numbers
(66 refused entries, +5.95% average, a −4.16pp CAGR cost) are also withdrawn — do not cite them.** This document
reports the corrected v3 measurement, with the population-coverage bug fixed and a new, independent assertion
added specifically to catch a recurrence of this class of defect.

Method reproduces the M36/M37/M39 precedent (`docs/signal-analysis/2026-08-02-m36-m37-backtest-ab.md` §0): a
committed, auditable JUnit harness, read-only (SELECT-only) against the live Postgres, reusing production classes
wherever their visibility allows and duplicating only small, individually-validated pure logic where it doesn't.
Every claim is tagged **[computed]** (derived here from code/config/DB read on this checkout), **[sourced]**
(quoted from a cited doc), **[recalled]** (memory, not re-verified in this pass), or **[assumed]** (an inference
not fully verified).

---

## Read this first — the bottom line

**With the full symbol population correctly included, the picture changes materially: the marginal-refused
trades are no longer clearly profitable, and the whole-portfolio effect of enforcing the cap flips from a cost
to a benefit on this measure.** Over an 11-year proxy backtest of the live Manas book (breakout + VCP sharing
one position PER SYMBOL, matching `Books.MANAS_ARORA`, all 2,491 symbols with sufficient history now verified to
participate in both arms), a REAL 6% aggregate open-risk cap (rupee risk ÷ current mark-to-market equity,
recomputed every session) would have refused **139 entries that actually fired under today's live `MAX_OPEN=7`
rail**, across **112 distinct sessions** (~4.1% of the ~2,750 NSE trading sessions in the window) — out of
**1,065 entries admitted**. `MAX_OPEN=7` remains, by a wide margin, the dominant constraint in both arms (15,302
/ 13,423 refusals vs. 1,949 aggregate-cap refusals in the candidate arm, of which 139 map to an entry baseline's
own trajectory actually admitted).

**The 139 refused trades were, on net, close to breakeven — not the clear winners the two withdrawn versions
both reported**: mean **+1.13%**, but **median −0.68%** and **win rate 46.0%** (64 wins, 74 losses, 1 flat) — a
right-skewed distribution (a handful of large winners, e.g. +48.64% RDBRL, +46.72% HIRECT, pull the mean above
the median) with a TYPICAL trade slightly negative. The whole-portfolio counterfactual — both arms using the
SAME real, equity-proportional position sizing throughout — shows enforcing the cap would have **improved**
this measure: **CAGR 25.36%→29.44% (+4.08pp), maxDD 42.89%→35.90% (−6.99pp, i.e. SHALLOWER), Sharpe 1.08→1.21
(+0.13)**. Excluding a near-breakeven, sub-50%-win-rate set of trades frees book capacity sooner for the next
RS-ranked candidate — in a heavily oversubscribed book (15,000+ refusals against ~1,000 admissions per arm),
that reallocation effect can plausibly dominate the direct cost of the excluded trades themselves.

What this doc does **not** settle, unchanged across every version of this measurement: the aggregate cap exists
to bound *correlated* risk across simultaneously-open positions in a market-wide shock, and this backtest
measures the AVERAGE-return effect of the cap, not its tail-protection value. **No verdict is manufactured on
enforce/don't-enforce** — if anything, this correction argues for MORE caution about drawing a conclusion from
this method, not less: the headline number has now moved twice under correction, in different directions and by
different mechanisms, which is itself evidence that this measurement is sensitive to modeling detail in ways
that warrant a human reading the full doubts list below before acting on any of it.

---

## §0 — Method (now with two rounds of review-driven correction)

### What changed in this round: the population-coverage bug

**[computed]** The v2 harness loaded each symbol's bars from `warmStart = from.minusDays(600)` (≈600 days before
`from`, for indicator warmup) but initialized every symbol's replay pointer to array index **0** and only
advanced it when that index's date **exactly equaled** the current session being processed in the
`allDates`-driven outer loop — and `allDates` itself only contains dates **on/after `from`**. For any symbol with
a full pre-`from` warm-up history (i.e. almost every established, long-listed NSE name — precisely the
population this measurement is supposed to be about), `date[0]` is deep in the `warmStart`-to-`from` window and
never equals any date the outer loop visits, so the pointer never advances and the symbol **silently never
contributes a single bar to either arm**. Only symbols whose first loaded candle happened to already fall
on/after `from` (effectively: very recent listings) could participate — an unintended, severe selection bias
neither arm's own internal consistency checks (both arms are equally affected) could reveal on their own.

**[computed]** Fix: initialize each symbol's pointer to the first index whose date is on/after `from` (mirroring
production's own `date.isBefore(from) → continue` skip, `ManasAroraSwingBacktest.java:276-278`, as a one-time
starting-point jump instead of a per-iteration check), leaving the existing gap-tolerant "advance only on an
exact date match" logic for subsequent sessions unchanged (it was already correct — the bug was purely in the
pointer's INITIAL value).

**[computed]** Why the 2,491/2,491 trade-identity fidelity check from the prior round did not catch this: that
check exercises `standaloneReplay`, an entirely separate code path built for validating the entry/exit SIGNAL
logic in isolation, per symbol, with no pointer-advance mechanism of its own. The population-coverage bug lived
exclusively in `runArm`'s cross-symbol iteration — a second, never-independently-checked code path. **Per the
review's explicit instruction, a new, independent invariant is now asserted every run**: for each arm, the set
of symbols that ever contribute at least one bar within `[from, ...]` must exactly equal the count of symbols in
the fidelity-checked population whose LAST bar is not entirely before `from` (a symbol whose full series ends
before `from` legitimately has zero possible participation — not a bug). **Confirmed this run:
`expectedParticipating=2491` (of 2,491; 0 symbols have zero eligible bars), `baseline.participatingSymbols=2491`,
`candidate.participatingSymbols=2491`** — asserted equal in the harness, not eyeballed.

### The three original findings remain fixed (unchanged from the first correction)

**Critical 1 (real risk ratio):** quantity sizes against equity AT ENTRY (`PositionSizer.size`, mirroring
`PaperEmissionGuard.suggestedQty`); the cap divides accumulated rupee risk (`Σ qty × max(0, avgEntry − CURRENT
stopLoss)`, the CURRENT trailing stop) by CURRENT mark-to-market equity (`PaperAccountService.equity` =
startingCapital + Σrealized + Σunrealized, mirroring `ManasPyramidPolicy.breachesRiskCap`), both recomputed every
session, not inferred from a position count.

**Critical 2 (LIVE-equivalent admission):** one position book PER SYMBOL shared across both setups
(`SwingBatchEngine`'s `openLotsBySymbol` + `pyramid.hasRoom`, pyramiding off ⇒ any open lot blocks both setups),
entry pass run BEFORE the exit pass each session (`SwingBatchEngine.runDaily:303-311`).

**Major 3 (trade-identity validation, harness committed):** every symbol's standalone lifecycle replayed via
this harness's own duplicated entry-signal logic plus the reused, unmodified `ManasAroraSwingBacktest
.initialStop`/`positionExit`/`stopBreached`, asserted to match production's OWN `simulate()` output exactly,
trade for trade — **2,491/2,491 symbols, zero mismatches, both setups** (unchanged this round; this check was
never the site of the population bug and continues to pass). The harness is committed:
`services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/manas/ManasRiskCapReplayTest.java`.

### The harness is now opt-in via a system property, not a source edit

**[computed]** The prior round used class-level `@Disabled`, which is safe for CI but — as the review noted —
"an artifact needing source edits to run is only half auditable." Replaced with
`@EnabledIfSystemProperty(named = "manas.replay.enabled", matches = "true")`: by default the property is unset
and the test reports SKIPPED (never FAILED) in CI, exactly as before, but a human re-running it needs no source
edit — `./mvnw.cmd -pl services/market-data-service -am test -Dtest=ManasRiskCapReplayTest
-Dmanas.replay.enabled=true`. The Postgres host, password-file path, and output-file path are now also system
properties with defaults (`-Dmanas.replay.pgHost`, `-Dmanas.replay.pgPasswordFile`, `-Dmanas.replay.outputFile`)
rather than hard-coded literals — see Open Doubts #5 for the one path-resolution wrinkle this surfaced (worktrees
have no `deploy/secrets/`, a pre-existing, documented repo trap, not new here).

### Determinism

**[computed]** Run **twice independently** after the fix (fresh JVM each time, ~9–10 min wall time: the fidelity
check + the two-arm replay over the now-complete population). Every printed figure — the fidelity result, the
new participation-coverage assertion, both arms' admission/refusal counts and portfolio stats, and the complete
139-row marginal-refused list — reproduced **byte-identical** across both runs (`diff`, empty).

### Read-only confirmed

The harness issues only `SELECT`s plus calls into pure, side-effect-free production methods; no
`INSERT`/`UPDATE`/`persist()` path is reachable from anything this harness calls.

---

## §1 — Reading the two admission arms

**Baseline** (today's live rail — `MAX_OPEN=7` only): **1,065** entries admitted (1,059 closed, 6 open-at-end),
maxConcurrentOpen never exceeded 7 (asserted). Portfolio: starting ₹150,000 → final ₹1,798,678, **CAGR 25.36%,
maxDD 42.89%, Sharpe 1.08**.

**Candidate** (baseline + the real 6% aggregate open-risk cap on every fresh entry): **1,021** entries admitted
(1,015 closed, 6 open-at-end), maxConcurrentOpen also never exceeded 7. Portfolio: final **₹2,559,550**, **CAGR
29.44%, maxDD 35.90%, Sharpe 1.21**.

Both arms use the IDENTICAL real sizing formula for every admitted trade — there is no sizing artifact between
them; this is a clean, unconfounded whole-portfolio counterfactual, and here it favors the candidate arm.

---

## §2 — Which rail binds first

**`MAX_OPEN=7` dominates refusal volume even more decisively than in the withdrawn versions.** Baseline refused
15,302 candidates for `MAX_OPEN` alone; candidate refused 13,423 for `MAX_OPEN` and 1,949 for the aggregate cap
(12.7% of candidate's total refusals). With the full symbol population correctly included, the book is
oversubscribed by an order of magnitude relative to its 7-slot capacity — total entry attempts across both arms
run to roughly 16,000+ against only ~1,000–1,065 admissions.

**Of the 1,949 aggregate-cap refusals in the candidate arm, 139 map to an entry baseline's own trajectory
actually admitted** — the trustworthy, directly-comparable count (the other refusals arose only inside the
candidate arm's own progressively-diverging trajectory and have no clean baseline counterfactual, for the same
reason discussed in the withdrawn v2: refusing an early entry frees a slot sooner, so the two arms' admission
histories diverge beyond the directly-attributable set).

**The real aggregate-risk-% distribution, sampled immediately before every one of baseline's 1,065 admitted
entries:**

| percentile | aggregate risk % (existing book, before this entry) |
|---|---:|
| p0 | 0.000 |
| p10 | 1.857 |
| p25 | 2.853 |
| p50 (median) | 3.824 |
| p75 | 4.752 |
| p90 | 5.072 |
| p95 | 5.921 |
| p99 | 6.113 |
| p100 (max) | 6.306 |

**Share of admitted entries where existing risk was already ≥5%: 15.40%; ≥6%: 3.76%.** Both figures are higher
than the withdrawn v2's (11.53% / 2.63%) — consistent with the book now correctly reflecting a much more fully
subscribed, higher-utilization state once all 2,491 symbols compete for the 7 slots, rather than the artificially
thin candidate pool the population bug produced.

---

## §3 — The 139 marginal-refused entries, with realised P&L

**[computed]** Entries admitted under `MAX_OPEN=7` (today's live rail) that the real 6% aggregate cap would have
refused, matched against baseline's own admission log (full list, mechanically generated from the harness's own
run output, not hand-transcribed):

| Symbol | Setup | Entry | Exit | PnL% | Exit reason |
|---|---|---|---:|---:|---|
| GMBREW | breakout | 2016-06-01 | 2016-06-06 | -11.49 | STOP_LOSS |
| INDIANHUME | breakout | 2016-10-03 | 2016-11-02 | +12.75 | TRAILING_STOP |
| EDELWEISS | vcp | 2016-10-03 | 2016-11-09 | -14.54 | STOP_LOSS |
| INDIANB | breakout | 2016-11-10 | 2016-11-24 | -10.88 | STOP_LOSS |
| HINDALCO | breakout | 2016-11-10 | 2016-11-21 | -10.47 | STOP_LOSS |
| PNBGILTS | vcp | 2016-11-21 | 2016-11-28 | +14.58 | TRAILING_STOP |
| SESHAPAPER | vcp | 2016-11-23 | 2016-12-20 | +6.22 | TRAILING_STOP |
| ALLDIGI | breakout | 2016-11-29 | 2016-12-23 | +6.42 | TRAILING_STOP |
| TAMBOLIIN | breakout | 2016-12-05 | 2016-12-22 | -13.80 | STOP_LOSS |
| MOTILALOFS | vcp | 2016-12-21 | 2016-12-26 | -10.57 | STOP_LOSS |
| ENGINERSIN | breakout | 2016-12-23 | 2016-12-26 | -6.66 | STOP_LOSS |
| DATAMATICS | vcp | 2016-12-29 | 2017-01-27 | +20.49 | TRAILING_STOP |
| DHAMPURSUG | breakout | 2017-01-31 | 2017-02-07 | +11.97 | TRAILING_STOP |
| SUDARSCHEM | breakout | 2017-02-01 | 2017-02-10 | -2.05 | TRAILING_STOP |
| HERITGFOOD | breakout | 2017-02-13 | 2017-03-08 | -7.00 | STOP_LOSS |
| VEDL | breakout | 2017-02-16 | 2017-04-13 | -8.86 | STOP_LOSS |
| RDBRL | vcp | 2017-05-24 | 2017-07-14 | +48.64 | TRAILING_STOP |
| KEC | vcp | 2017-05-25 | 2017-06-08 | +3.57 | TRAILING_STOP |
| BEPL | breakout | 2017-06-28 | 2017-07-25 | +27.68 | TRAILING_STOP |
| WELENT | vcp | 2017-09-26 | 2017-11-02 | +8.20 | TRAILING_STOP |
| DEEPAKNTR | breakout | 2017-09-26 | 2017-10-11 | +13.23 | TRAILING_STOP |
| IFBIND | breakout | 2017-11-15 | 2017-12-08 | +16.72 | TRAILING_STOP |
| GNA | breakout | 2017-11-16 | 2017-12-01 | -8.76 | STOP_LOSS |
| VIDHIING | breakout | 2017-12-06 | 2017-12-27 | -0.57 | TRAILING_STOP |
| UNOMINDA | vcp | 2018-01-18 | 2018-02-05 | -11.12 | STOP_LOSS |
| ACE | breakout | 2018-01-25 | 2018-02-02 | -14.34 | STOP_LOSS |
| ROHLTD | breakout | 2018-01-29 | 2018-02-02 | -13.68 | STOP_LOSS |
| CHAMBLFERT | vcp | 2018-02-07 | 2018-03-06 | -1.52 | TRAILING_STOP |
| WINDMACHIN | vcp | 2018-02-22 | 2018-03-07 | -10.34 | STOP_LOSS |
| DBL | breakout | 2018-04-02 | 2018-04-19 | +1.70 | TRAILING_STOP |
| MOREPENLAB | breakout | 2018-04-20 | 2018-05-03 | -10.81 | STOP_LOSS |
| LTIM | breakout | 2018-04-25 | 2018-04-27 | -7.64 | STOP_LOSS |
| GRAPHITE | vcp | 2018-05-14 | 2018-06-04 | +7.86 | TRAILING_STOP |
| DMART | vcp | 2018-05-25 | 2018-08-10 | +6.13 | TRAILING_STOP |
| PITTIENG | vcp | 2018-05-25 | 2018-06-05 | -12.24 | STOP_LOSS |
| KSE | breakout | 2018-05-31 | 2018-06-19 | -11.64 | STOP_LOSS |
| LTIM | breakout | 2018-07-11 | 2018-08-03 | -5.97 | STOP_LOSS |
| INTELLECT | vcp | 2018-09-12 | 2018-09-24 | -13.52 | STOP_LOSS |
| UNIVCABLES | breakout | 2018-09-14 | 2018-09-21 | -5.04 | TRAILING_STOP |
| KILITCH | vcp | 2018-11-16 | 2018-12-05 | -13.25 | STOP_LOSS |
| VINDHYATEL | breakout | 2018-11-26 | 2019-01-21 | -10.57 | STOP_LOSS |
| BATAINDIA | breakout | 2019-03-15 | 2019-05-14 | -4.65 | STOP_LOSS |
| TRIVENI | vcp | 2019-04-25 | 2019-06-12 | +9.11 | TRAILING_STOP |
| MANAPPURAM | breakout | 2019-05-24 | 2019-07-10 | -8.14 | STOP_LOSS |
| DHAMPURSUG | vcp | 2019-07-04 | 2019-07-11 | -8.99 | STOP_LOSS |
| MANAPPURAM | vcp | 2019-10-17 | 2019-11-14 | +13.04 | TRAILING_STOP |
| KEI | breakout | 2019-10-30 | 2019-11-05 | -8.05 | STOP_LOSS |
| AVANTIFEED | breakout | 2019-12-19 | 2020-01-20 | +19.26 | TRAILING_STOP |
| DIXON | vcp | 2020-02-03 | 2020-02-20 | -8.31 | STOP_LOSS |
| DEEPAKNTR | breakout | 2020-05-07 | 2020-05-22 | -10.65 | STOP_LOSS |
| GRANULES | breakout | 2020-09-04 | 2020-09-11 | -0.68 | TRAILING_STOP |
| CAMLINFINE | vcp | 2020-09-24 | 2020-10-12 | +9.35 | TRAILING_STOP |
| BEPL | breakout | 2020-09-25 | 2020-10-12 | +21.63 | TRAILING_STOP |
| LAURUSLABS | vcp | 2020-10-29 | 2020-11-03 | -11.70 | STOP_LOSS |
| ADANIENT | breakout | 2020-10-30 | 2020-12-21 | +26.36 | TRAILING_STOP |
| BUTTERFLY | breakout | 2020-11-05 | 2020-11-23 | +14.27 | TRAILING_STOP |
| GOLDIAM | vcp | 2020-12-22 | 2020-12-30 | +0.00 | TRAILING_STOP |
| TATAELXSI | vcp | 2020-12-22 | 2021-01-15 | +42.79 | TRAILING_STOP |
| LTIM | breakout | 2020-12-22 | 2021-01-07 | +8.67 | TRAILING_STOP |
| KPRMILL | vcp | 2020-12-22 | 2021-01-18 | +1.16 | TRAILING_STOP |
| HAVELLS | breakout | 2021-01-28 | 2021-01-29 | -11.59 | STOP_LOSS |
| GREENPANEL | vcp | 2021-02-01 | 2021-02-08 | +2.43 | TRAILING_STOP |
| KANORICHEM | vcp | 2021-05-17 | 2021-06-24 | +6.92 | TRAILING_STOP |
| NAHARSPING | breakout | 2021-06-01 | 2021-06-08 | -0.97 | TRAILING_STOP |
| ORIENTBELL | breakout | 2021-06-10 | 2021-07-27 | +8.30 | TRAILING_STOP |
| LAURUSLABS | breakout | 2021-06-25 | 2021-07-29 | -6.96 | STOP_LOSS |
| VENUSREM | breakout | 2021-06-25 | 2021-07-06 | +16.39 | TRAILING_STOP |
| SAKSOFT | vcp | 2021-08-25 | 2021-09-17 | +8.48 | TRAILING_STOP |
| VEDL | vcp | 2021-08-25 | 2021-09-20 | -1.41 | TRAILING_STOP |
| GOODLUCK | vcp | 2021-10-22 | 2021-10-28 | -9.15 | STOP_LOSS |
| NAHARCAP | breakout | 2021-11-01 | 2021-11-12 | +6.27 | TRAILING_STOP |
| RSWM | breakout | 2021-11-01 | 2021-11-17 | -0.82 | TRAILING_STOP |
| APARINDS | vcp | 2021-12-21 | 2022-01-31 | -14.97 | STOP_LOSS |
| 63MOONS | vcp | 2022-01-04 | 2022-01-13 | +28.01 | TRAILING_STOP |
| INDSILHYD | vcp | 2022-01-06 | 2022-01-11 | +6.14 | TRAILING_STOP |
| PREMEXPLN | breakout | 2022-01-27 | 2022-01-31 | -9.75 | STOP_LOSS |
| GANGESSECU | vcp | 2022-02-23 | 2022-02-24 | -14.94 | STOP_LOSS |
| GANGESSECU | vcp | 2022-02-25 | 2022-02-28 | -4.06 | TRAILING_STOP |
| GLOBUSSPR | breakout | 2022-02-28 | 2022-04-12 | +2.65 | TRAILING_STOP |
| ADANIENSOL | vcp | 2022-02-28 | 2022-03-07 | +3.42 | TRAILING_STOP |
| TAALENT | breakout | 2022-04-26 | 2022-05-09 | -12.30 | STOP_LOSS |
| NATCAPSUQ | vcp | 2022-06-03 | 2022-06-10 | -11.15 | STOP_LOSS |
| SHARDACROP | vcp | 2022-06-08 | 2022-06-14 | -10.94 | STOP_LOSS |
| MIRZAINT | vcp | 2022-06-15 | 2022-06-20 | -21.94 | STOP_LOSS |
| BLS | breakout | 2022-06-15 | 2022-08-03 | +12.78 | TRAILING_STOP |
| FLUOROCHEM | vcp | 2022-06-24 | 2022-08-22 | +25.91 | TRAILING_STOP |
| VOLTAMP | breakout | 2022-07-01 | 2022-07-26 | +7.54 | TRAILING_STOP |
| KPIGREEN | breakout | 2022-08-18 | 2022-09-20 | +1.59 | TRAILING_STOP |
| ADANIENSOL | vcp | 2022-08-23 | 2022-09-21 | +7.33 | TRAILING_STOP |
| DEEPAKFERT | breakout | 2022-08-23 | 2022-09-01 | -10.88 | STOP_LOSS |
| SBCL | vcp | 2022-08-23 | 2022-09-19 | +11.49 | TRAILING_STOP |
| JPOLYINVST | breakout | 2022-09-23 | 2022-09-28 | -13.42 | STOP_LOSS |
| BIGBLOC | breakout | 2022-09-23 | 2022-09-28 | -9.76 | STOP_LOSS |
| AURIONPRO | breakout | 2022-09-29 | 2022-10-31 | -14.82 | STOP_LOSS |
| PRICOLLTD | breakout | 2022-09-29 | 2022-11-16 | -9.48 | STOP_LOSS |
| SKIPPER | breakout | 2022-12-29 | 2023-01-03 | -10.63 | STOP_LOSS |
| ANDHRAPAP | breakout | 2023-01-31 | 2023-02-27 | -7.00 | STOP_LOSS |
| BDL | breakout | 2023-02-23 | 2023-03-13 | -8.57 | STOP_LOSS |
| TRITURBINE | breakout | 2023-02-28 | 2023-03-02 | +1.31 | TRAILING_STOP |
| IMAGICAA | breakout | 2023-03-03 | 2023-03-13 | -8.83 | TRAILING_STOP |
| SAFARI | vcp | 2023-03-03 | 2023-03-15 | -8.88 | STOP_LOSS |
| STERTOOLS | vcp | 2023-03-14 | 2023-04-19 | +7.83 | TRAILING_STOP |
| ZENTEC | breakout | 2023-03-14 | 2023-04-10 | +6.12 | TRAILING_STOP |
| SHANTIGEAR | vcp | 2023-03-14 | 2023-06-08 | +17.88 | TRAILING_STOP |
| STARHFL | vcp | 2023-03-16 | 2023-04-27 | +0.84 | TRAILING_STOP |
| ACE | vcp | 2023-03-29 | 2023-06-20 | +19.92 | TRAILING_STOP |
| APARINDS | breakout | 2023-03-31 | 2023-05-09 | +3.88 | TRAILING_STOP |
| KECL | breakout | 2023-04-20 | 2023-05-19 | +18.55 | TRAILING_STOP |
| PARACABLES | vcp | 2023-05-15 | 2023-06-09 | -9.29 | STOP_LOSS |
| BOMDYEING | vcp | 2023-09-14 | 2023-09-15 | -0.26 | TRAILING_STOP |
| DBREALTY | breakout | 2023-09-18 | 2023-10-23 | -16.46 | STOP_LOSS |
| HIRECT | breakout | 2023-10-25 | 2023-11-06 | +46.72 | FAST_MOVE |
| WELSPUNLIV | breakout | 2023-10-25 | 2023-11-29 | +9.88 | TRAILING_STOP |
| ANGELONE | breakout | 2023-10-25 | 2023-11-06 | +13.98 | TRAILING_STOP |
| JAIBALAJI | vcp | 2023-12-13 | 2023-12-20 | +18.24 | TRAILING_STOP |
| OMINFRAL | breakout | 2023-12-21 | 2024-01-01 | +8.94 | TRAILING_STOP |
| STARTECK | breakout | 2023-12-21 | 2023-12-26 | +43.18 | FAST_MOVE |
| RAMKY | vcp | 2024-01-24 | 2024-02-02 | -10.58 | TRAILING_STOP |
| VASCONEQ | vcp | 2024-01-24 | 2024-02-12 | -13.11 | STOP_LOSS |
| SCANSTL | breakout | 2024-02-13 | 2024-02-27 | +6.99 | TRAILING_STOP |
| TITANBIO | breakout | 2024-02-13 | 2024-02-21 | -0.36 | TRAILING_STOP |
| BOROLTD | breakout | 2024-02-13 | 2024-04-23 | -8.33 | STOP_LOSS |
| DBREALTY | vcp | 2024-03-06 | 2024-03-13 | -17.58 | STOP_LOSS |
| POWERINDIA | vcp | 2024-05-13 | 2024-05-17 | +17.40 | TRAILING_STOP |
| HEROMOTOCO | breakout | 2024-06-04 | 2024-06-20 | +3.65 | TRAILING_STOP |
| ORIENTCEM | breakout | 2024-07-22 | 2024-08-09 | -0.81 | TRAILING_STOP |
| HSCL | breakout | 2024-08-06 | 2024-10-04 | +33.54 | TRAILING_STOP |
| SUNDARMHLD | vcp | 2024-08-30 | 2024-09-26 | -10.22 | STOP_LOSS |
| ADSL | vcp | 2024-09-05 | 2024-09-25 | -10.68 | STOP_LOSS |
| SUMMITSEC | breakout | 2024-10-21 | 2024-10-24 | -11.84 | STOP_LOSS |
| MANORAMA | breakout | 2024-10-21 | 2024-11-11 | +6.90 | TRAILING_STOP |
| FSL | breakout | 2024-10-23 | 2024-11-13 | -0.41 | TRAILING_STOP |
| ARVSMART | vcp | 2024-10-28 | 2024-11-13 | +2.63 | TRAILING_STOP |
| DYCL | breakout | 2024-10-28 | 2024-11-08 | +16.36 | TRAILING_STOP |
| BAJAJST | vcp | 2024-11-12 | 2024-11-18 | -14.13 | STOP_LOSS |
| AEGISLOG | vcp | 2024-11-14 | 2024-12-02 | +3.94 | TRAILING_STOP |
| AARNAV | breakout | 2025-01-24 | 2025-01-28 | -11.49 | STOP_LOSS |
| DEEPINDS | vcp | 2025-01-30 | 2025-02-14 | -13.58 | STOP_LOSS |
| BLUEJET | vcp | 2025-02-19 | 2025-03-25 | +11.64 | TRAILING_STOP |

**[computed]** Summary (139 closed rows, no open-at-end entries this run; independently re-derived via a
separate `awk` pass directly against the harness's raw output, matching the harness's own printed figures
exactly): **mean +1.129% / median −0.68% / win rate 46.04% (64 wins, 74 losses, 1 flat — GOLDIAM +0.00%) / best
+48.64% (RDBRL) / worst
−21.94% (MIRZAINT)**. Entries span 2016 through early 2025.

**Reading:** unlike both withdrawn versions, the trades a real 6% cap would refuse are NOT clearly net-winners —
the median trade is a small loser, and win rate is below 50%. This is the more economically coherent picture:
a book capacity-constrained by an order of magnitude (15,000+ refusals against ~1,000 admissions) is already
selecting fairly aggressively via RS-priority; an additional risk-based filter on top of that selection lands
closer to "marginal quality names get filtered," not "the best names get filtered," which is consistent with
the whole-portfolio result in §1 turning out favorable rather than costly.

---

## Caveats (ride with every claim above)

- **This is the repo's established historical-equity-universe PROXY for "real Manas history"** (same convention
  as M36/M37/M39), not actual paper fills — live Manas paper trading is only weeks old.
- **Survivorship-biased** — delisted/renamed symbols since 2015 are likely undercounted in the current
  `instruments`⋈`candles` join. Inherited from every prior Manas/Minervini deep-sim, not introduced here.
- **Gross, no cost-of-trading model** — neither arm nets transaction costs (unlike M36/M37's net-of-cost
  headline). Both arms miss the same cost model, so the DELTA should be less sensitive to this than the levels
  — though with the whole-portfolio delta this large (+4.08pp CAGR), a cost model could plausibly narrow but is
  unlikely to reverse the direction; not verified here.
- **Paper-only today, ₹150,000 real starting capital used throughout the 11-year replay** (matching the live
  `manas-arora` book's actual seeded size).
- **`MAX_DEPLOYMENT_PCT` (80%, live-enabled) and `DAILY_LOSS_LIMIT` (10%/day, live-enabled)** are NOT modeled —
  an unchanged, explicit scope boundary from every prior version of this measurement.
- **Same-day, multi-SYMBOL admission order is a modeling choice (RS-priority)**, not a byte-exact replay of the
  live funnel's own candidate order — this repo's established "realistic-live" convention. Not cross-checked
  against a FIFO alternative in this round (time-boxed, unchanged from the prior round's disclosed scope limit).
- **Same-day, same-SYMBOL double-fire (both setups) resolves to breakout** — a documented convention, not
  derived from `registry.listAll()`'s live, operationally-unstable `ORDER BY updated_at DESC`.

---

## Recommendation

**Unchanged in structure, now resting on a twice-corrected number that has moved in BOTH direction and
magnitude across corrections.** The gap is real (139 entries / 112 sessions bind a real, non-hypothetical rail),
but its net effect on this measure is now the OPPOSITE of what both prior versions reported — a benefit, not a
cost. Given that reversal, **the single most important thing this document can say is: treat the SIGN and
MAGNITUDE of the whole-portfolio effect as provisional, not settled** — two rounds of review have each found a
real defect that materially changed both the population being measured and the resulting conclusion. A third
independent read of this exact harness (or a differently-constructed one) before this number is used for
anything beyond "the gap is reachable and non-trivial" would be prudent. **No verdict is manufactured** on
enforce/don't-enforce either way; the backtest still cannot evaluate the cap's tail-risk purpose, which is
unaffected by which direction the average-return effect points. Given paper-only stakes today, there remains no
urgency to force this decision.

---

## Open doubts

1. **This is the second defect a cross-vendor review has found in two rounds — both changed the reported
   population or admission logic, not just a numeric detail.** The base rate of "further undiscovered defects
   in this specific harness" should be treated as non-trivial given that history, not zero. A third
   independent implementation (not merely a third review of this one) would be the strongest next check, if
   this number is to be relied on for a real decision.
2. **The reversal (cap now helps rather than costs) is economically plausible but was not independently
   re-derived via a second, differently-shaped calculation** — e.g. by directly computing what the RS-priority
   substitution effect actually put into the freed slots and checking THOSE trades' own quality. This document
   accepts the whole-portfolio equity-curve number as the answer without decomposing WHY it moved, beyond the
   qualitative "near-breakeven trades excluded, freed capacity reallocated" argument in §3.
3. **The breakout-wins-tie convention for a same-symbol, same-day double signal remains a documented
   assumption, not a derived fact** (unchanged from the prior round) — not measured how often this scenario
   arises in the now-much-larger candidate population.
4. **Same-day multi-SYMBOL admission order (RS-priority) still not cross-checked against FIFO** in this round —
   carried over from the prior round's disclosed scope limit, now more consequential given the much higher
   candidate density (16,000+ attempts) this correction revealed.
5. **The default `-Dmanas.replay.pgPasswordFile` path (`../../deploy/secrets/postgres_password`, relative to
   the module directory) does not resolve when this harness is run from a git WORKTREE** — worktrees have no
   `deploy/secrets/*` by design (gitignored, a pre-existing documented repo trap, `CLAUDE.md`'s "NEVER `docker
   compose up` from a git worktree" section). Running from a worktree needs an explicit
   `-Dmanas.replay.pgPasswordFile=<absolute path to the real checkout's secret>` override, as this run did.
   Running from the primary checkout needs no override.
6. **`MAX_DEPLOYMENT_PCT`/`DAILY_LOSS_LIMIT` remain unmodeled** — unchanged scope boundary.
7. **The correlated/tail-risk rationale for the cap is not evaluated here** — unchanged across every version of
   this measurement; still the single most consequential open item regardless of which way the average-return
   number points.
8. **Determinism was proven for two runs on 2026-08-02, outside trading hours** — not a permanent guarantee for
   a re-run on a different calendar date (`from` is date-relative).

---

## Receipt

- **Doc:** `docs/signal-analysis/2026-08-02-m40-risk-cap-backtest.md` (this file) — supersedes and withdraws
  the numbers in BOTH prior versions of this measurement.
- **Harness (committed):**
  `services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/manas/ManasRiskCapReplayTest.java`
  (opt-in via `-Dmanas.replay.enabled=true`, no source edit required).
- **Headline:** a REAL 6% aggregate open-risk cap would have refused **139 entries across 112 distinct
  sessions** (~4.1% of ~2,750 NSE sessions) that fired under today's live `MAX_OPEN=7` rail (1,065 admitted).
  `MAX_OPEN=7` dominates refusal volume overwhelmingly (15,302–13,423 vs. 1,949). Refused trades averaged
  **+1.13%** (median **−0.68%**, win rate 46.0% — near-breakeven, not clear winners). Whole-portfolio,
  unconfounded effect of enforcing: **CAGR +4.08pp, maxDD −6.99pp (shallower), Sharpe +0.13 — a BENEFIT on this
  measure, reversing both prior (withdrawn) findings.**
- **Recommendation:** treat this number as provisional given two consecutive correction rounds; the gap is real
  and non-trivial in frequency, but its sign and magnitude should not be relied on without further
  independent verification. No verdict manufactured on enforce/don't-enforce; the tail-risk question remains
  unresolved regardless.
- **Fidelity validation:** 2,491/2,491 symbols matched production's own `simulate()` output exactly (unchanged
  from the prior round). **New this round:** portfolio-replay symbol-coverage independently asserted equal to
  the expected population (2,491/2,491 both arms) — the check that would have caught this round's bug had it
  existed the first time.
- **Determinism:** 2 independent full runs, byte-identical on every printed figure.
- **Claims labeled** [computed] / [sourced] / [recalled] / [assumed] inline throughout.
- **Open doubts:** 8 items above, topped by an explicit warning about this document's own correction history.
