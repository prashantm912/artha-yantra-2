# 2026-08-02 — Scoping the #128 swing exit-parity HOLD batch against current HEAD

Research + scoping only. No production code touched. Checkout: `origin/main @ bc699806`
(worktree `docs/128-exit-parity-scoping`). Every material claim is tagged **[computed]** (derived
here from a file/config/test I read on this checkout), **[sourced]** (quoted or paraphrased from a
cited doc/PR/commit), **[recalled]** (my own memory of the codebase, not re-verified in this pass),
or **[assumed]** (an inference I could not verify). A claim computed from an assumed input is left
labelled **[assumed]**.

---

## Read this first — the bottom line

The batch's own citations (`MinerviniSwingEngine.java`, `ManasAroraSwingEngine.java`,
`ExitEvaluator.java:458-487`) are stale — those two engine classes were deleted by the #655
SwingDoctrine consolidation (2026-07-10) — so this doc re-derives every one of the 12 findings
against current `HEAD`, with fresh `file:line` citations, before proposing anything.

**Re-mapping result: 3 of 12 are already fixed or moot, 8 are confirmed still-live, 1 (M11) is a
disclosure/data-acquisition gap rather than a code defect.** The three closures are *incidental* —
none of them happened because anyone worked task #128; they were side-effects of the F10 cold-start
project (M2), the app-platform audit's paper reconciler (M3), and the batch-liveness outage-gap
project (M13).

**Proposed first slice: M7 + M14 + M27 — three clean, additive-only items (a swing exit-equivalence
characterization fixture, wiring the already-emitted `stale` candle flag into a loud signal, and a
frozen-output golden test for the deep sims) — plus a deploy-free, measurement-only characterization
of M6's and M9's actual impact on the paper book E1 is accruing right now.** This deliberately
diverges from the 2026-08-01 decision sheet's own suggestion ("the fixture + the two items biasing
E1," i.e., ship M6+M9 changes in the first slice): re-deriving M6 and M9 against current code shows
both are genuine **exit-doctrine / backtest-methodology changes** — changing either one moves a
live-paper or backtest number, which is HOLD-tier by the task's own rule, and picking which side
(live or backtest) is "correct" without measurement is exactly the mistake that cost the M39 fix 99%
of a trade population. The first slice instead builds the evidence a real M6/M9 HOLD decision needs,
without pre-empting it.

---

## Part 1 — Why the batch's citations are stale

**[sourced]** `docs/signal-analysis/2026-08-01-e4-e8-decision-sheet.md:272-283` already established
this: the 2026-07-10 SwingDoctrine consolidation (#655, commit `9bae2161`) deleted
`MinerviniSwingEngine.java` (577 lines) and `ManasAroraSwingEngine.java` (807 lines), replacing them
with one `SwingBatchEngine` + per-family `SwingDoctrine` (`MinerviniDoctrine`/`ManasDoctrine`) —
confirmed **[computed]** via `git show --stat 9bae2161`. `ExitEvaluator.java` (the H4/M6 citation)
was *not* deleted — it still lives at
`libs/strategy-engine/src/main/java/in/arthayantra/strategyengine/eval/ExitEvaluator.java` (780
lines today) — but its line numbers moved (last touched by #628, the H4 canonical-Chandelier fix,
2026-07-06). So the batch's citations are a mix of "class no longer exists" and "class exists, lines
moved" — either way, unusable as-written.

---

## Part 2 — The 12-finding re-mapping

| # | verdict | current location |
|---|---|---|
| M2 | **MOOT — no gap found under either reading** | `SignalEngine.java` (scalper) fixed by #892/#987; `SwingBatchEngine.loadPublishedSwingStrategies:1050-1079` (swing) has no market-data dependency to fail |
| M3 | **LARGELY FIXED, incidentally** | `PaperReconciliationService.java:24-53` (stranded-carry + dead-anchor-orphan detectors, #883/#894) |
| M4 | **STILL LIVE** | `PaperService.java:1070-1081`, `:1314-1323` |
| M6 | **STILL LIVE** | `SwingBatchEngine.java:303-311`, `:736`, `:863-871` (live) vs `ManasAroraSwingBacktest.java:275-293`, `MinerviniSwingBacktest.java:177-194` (backtest) |
| M7 | **STILL LIVE (absent)** | no swing analogue of `contracts/fixtures/exit-equivalence.json` exists |
| M8 | **STILL LIVE** | `ManasGates.java:94-100` (live, 20d) vs `ManasAroraSwingBacktest.java:209,436` (backtest, 50d) |
| M9 | **STILL LIVE** | `ManasScreenService.java:197-218` (live) vs `ManasAroraBacktestService.java:707-733,769-779` (backtest) |
| M10 | **STILL LIVE** | `SwingPortfolio.java:82`, `SwingRotationPortfolio.java:74` |
| M11 | **STILL LIVE — disclosure gap, not a code defect** | no PIT-constituents source exists (`remaining-items.md:534`, D4/P2-6-D6, still deferred) |
| M13 | **LARGELY FIXED, incidentally** | `SwingBatchCatchUp.java`, `SwingMarketHoursGuard.java`, `SwingBatchCanary.java` (#1044/#1036/#1066) |
| M14 | **STILL LIVE** | `MarketDataCandlesClient.java:76-88` vs `CandlesController.java:26` |
| M27 | **STILL LIVE** | only test is `ManasBacktestBatchEqualityIntegrationTest` (determinism, not a frozen golden) |

### M2 — "Reconcile never retries transient load failures; mid-session reload unloads ALL strategies"

**[computed]** This finding's own fix-log entry (`docs/audits/2026-07-05-full-codebase-audit.md:582`)
already separates it from the M3/M4/M6/M7/M8/M9/M10/M11/M27 "coherent HOLD batch" — it has its own
note ("first attempt broke `#579` IT; needs the resolver to signal transient-vs-stable"). "#579" is
`SignalEngine`'s reconcile-reload loop bug (`e41e3d44`, 2026-07-05, same day as the audit) — so M2 is
about the **scalper tick-driven `SignalEngine`**, not the swing engines, which the audit's own author
apparently folded into task #128 later (`docs/superpowers/plans/2026-07-02-remaining-items.md:1039`)
alongside the swing-specific items.

**[computed]** On the scalper side, this is now comprehensively fixed. `SignalEngine.java:914-931`
retains a strategy's last-good loaded state on a retryable (market-data-dependent) failure rather
than dropping it — the log line is literally `KEEP_BEST_RETAINED_LAST_GOOD: {} strategies failed
this retry ... and kept their last-good entry instead of being dropped — a retry must never leave
the engine holding less than it already did`. This was built across four review rounds under
`task_f10a01`/`task_f10a02` (#892, commit `27bd41fb`, merged 2026-07-17) and hardened further by T15
(#987, `8254af01`, 2026-07-25, a durable `engine_reloads` ledger). **[sourced]** #892's own body
documents the exact failure class M2 describes and its fix: "the chain could end WORSE than no retry
at all ... a regression across the retry window could take a live 19-of-39 engine to 0."

**[computed]** On the swing side, no analogous gap exists to fix: `SwingBatchEngine.loadPublishedSwingStrategies`
(`SwingBatchEngine.java:1050-1079`) is a synchronous registry-DB read + `StrategyCompiler.compile` —
it has no market-data HTTP dependency at all, so a market-data restart cannot make it fail to load
a strategy definition. The one place market-data IS called mid-batch (`series()`/`candles.fetch()`
inside `exitPass`, `SwingBatchEngine.java:1037-1047`) already has a **per-symbol** retry-and-loud-skip
(`P0-3`, shipped in the same 2026-07-06 autonomous pass that produced #128's own fix log): on an
empty fetch it retries once (`retryFetch`), and only on a second empty result does it skip that one
symbol's exit for the day with an ERROR log + `exitSkipped` counter (`SwingBatchEngine.java:771-785`)
— it never drops a strategy's *loaded* state, because swing strategies aren't "loaded" persistently
the way scalper ones are; they're read fresh every `runDaily` call.

**Verdict: MOOT.** Whichever engine M2 was actually about, the gap it describes does not exist on
current `HEAD`. No action under #128.

### M3 — "Exit-commit→publish window ... strand OPEN positions with EXPIRED anchors (no reconcile)"

**[computed]** `SwingBatchEngine.emitExit` (`SwingBatchEngine.java:927-999`) still has the exact
commit-then-publish shape M3 describes: the DB transaction that marks the anchor `EXPIRED`
(`signals.transition(lot.id(), "EXPIRED")`, line 974, inside `tx.execute` closing at line 980)
commits *before* `publisher.publish` (line 984) and the `SignalExited` event that drives the paper
close (lines 992-994) — neither of which is wrapped in a catch here. So the underlying race M3 names
is still structurally present.

**[computed]** But the "(no reconcile)" half of the finding is no longer true. A nightly paper
reconciler now detects exactly this state. `PaperReconciliationService.java:24-53`'s own javadoc:
*"**Stranded carry** — every OPEN position anchored to an ENTRY with a persisted opposite-side EXIT
... It alerts only when a position id is newly seen. **Dead-anchor orphans** — every OPEN position
that no exit evaluator will ever anchor, so no EXIT is ever emitted for it."* This is precisely the
"EXPIRED anchor, OPEN position" shape M3 worries about. **[sourced]** Base V5/V16 reconciliation
shipped 2026-07-11 (#701, `49547407`, from the *2026-07-10 app-platform audit* §8 — not #128); the
stranded-carry detector followed 2026-07-17 (#883, `435f3f99`, "v2 after v1 was rejected") and the
complementary dead-anchor-orphan detector the same day (#894, `a716d851`).

**Residual gap:** this is detection + ntfy alert, not automatic repair — a stranded position still
needs a human to close it once alerted. **Verdict: LARGELY FIXED (incidentally, by a different
audit's work).** Recommend noting the residual (alert-only, no auto-repair) rather than treating M3
as fully open.

### M4 — "Swing book MTM blind — non-ticking equities mark at cost"

**[computed]** Confirmed still exactly true, in two call sites that share the same logic:
`PaperService.java:1070-1081` (`positionDetail`) and `PaperService.java:1314-1323` (`toPositionDto`,
used by the `openPositions` list). Both compute `mark = lastTick.lastPrice(exchange,
tradingsymbol).orElse(null)` and only derive `unrealized` when `mark != null`. Swing funnel equities
never tick (CLAUDE.md: "funnel equities do NOT tick — the live feed is index/options only"), so
`lastTick.lastPrice` never resolves for them, and `mark`/`unrealized` are structurally `null` for
every OPEN swing position, for its entire holding period. There is no daily-bar-close fallback
anywhere in either method.

**[computed]** The second half — "daily-loss/sizing never see open drawdown" — follows directly:
since `unrealized` is always null for swing, nothing downstream that reads it (risk sizing, heat-cap,
daily-loss checks) can ever see interim swing drawdown; it only ever sees the realized P&L once a
position closes. I did not separately trace `RiskService`'s daily-loss aggregation in this pass
(**[assumed]** it reads position P&L the same way the FE does, via `PaperService`'s DTOs, not a
private daily-bar re-fetch) — flagged in Open Doubts.

**Verdict: STILL LIVE.** Real, but note the scope trap: a "just show accurate MTM" fix (read the
swing symbol's last daily close as a fallback when no tick exists, display-only) is low-risk/likely
clean; wiring that number into daily-loss/heat-cap decisions is a live-risk-behaviour change and
would be its own HOLD call. Don't let the two get bundled silently.

### M6 — "Live engines evaluate exits on the entry bar; both backtests never do"

**[computed]** Confirmed still true, and now shared code (post-#655) for both families.

**Live:** `SwingBatchEngine.runDaily` (`SwingBatchEngine.java:270-328`) always runs the entry pass
*before* the exit pass in the same call (`entry` at line 303-307, `exit` at 308-311). `exitPass`
(`SwingBatchEngine.java:728-826`) re-queries open lots fresh via `openLotsBySymbol(resolution)`
(line 736) — i.e., *after* the entry pass has already committed any new anchor — and filters them
through `lotsAsOf` (`SwingBatchEngine.java:863-871`):
```java
return lots.stream()
    .filter(lot -> !lot.generatedAt().withOffsetSameInstant(IST).toLocalDate().isAfter(requiredBarDate))
    .toList();
```
"not after `requiredBarDate`" includes a lot whose `generatedAt` date **is** `requiredBarDate` — i.e.
a position opened in this exact batch run. Its exit is then evaluated via
`ExitEvaluator.evaluate(strat.definition(), bank, position, series.size() - 1)`
(`SwingBatchEngine.java:803-808`) at the *last* bar index, which for a same-day entry is the entry
bar itself.

**Backtest:** both deep sims explicitly refuse this. `ManasAroraSwingBacktest.java:275-293`: on entry
fire, `lots.add(...)` then `continue;` — the loop advances to `i+1` before any exit check runs on the
same bar. `MinerviniSwingBacktest.java:177-194`: same shape via `if (!inTrade) { ...; } else {
exitFires(...) }` — an entry that fires this iteration sets `inTrade=true` inside the `if` branch, so
the `else` (exit check) never runs until the *next* iteration.

**[computed], useful context:** this codebase already has a tested, frozen convention that agrees
with the backtest side, not the live side. The scalper's `contracts/fixtures/exit-equivalence.json`
pins "entry bar (index 0) never exits" as a cross-suite invariant (checked by 5 test classes per its
own header comment). So the "textbook" choice here already has precedent elsewhere in this repo —
but that is not the same as an owner decision to change *this* surface, and it does not tell us
which side (live or backtest) should move, or what the P&L impact of moving either one is.

**Verdict: STILL LIVE. HOLD-tier if changed** (in either direction — it's a live-paper exit-timing
change or a backtest-methodology change, both owner-facing numbers). See the proposed slice below
for how to make progress without pre-empting the HOLD call.

### M7 — "No swing exit-equivalence fixture"

**[computed]** Confirmed absent. Every class that references `exit-equivalence.json` is scalper/
options-premium: `PremiumExitEvaluator`, `PremiumBracketRules`, `PaperBracketEvaluator`,
`PaperSignalListener`, `OptionsPremiumReplay`, and their five equivalence tests. None touch
`market-data-service`'s swing screener package or `SwingBatchEngine`.

**[computed]** Worse than "no fixture": the deep-sim's exit logic doesn't even call the shared
`ExitEvaluator` the live engine uses. `ManasAroraSwingBacktest.java` mentions `ExitEvaluator` exactly
once, in a comment (line 300: "matches the live ExitEvaluator, which reads peak/close on the same
bar") — there is no `import ... ExitEvaluator` and no call to it anywhere in the file. The deep sim
hand-rolls its own Chandelier trail / initial-stop / position-exit logic
(`ManasAroraSwingBacktest.java:261-421`) that is independently coded to *resemble* `ExitEvaluator`'s
behaviour, with nothing but a code comment asserting the two agree.

**Verdict: STILL LIVE.** This is the actual gap the scalper's fixture pattern was built to close, and
it doesn't exist for swing at all.

### M8 — "Manas live volume gate 20-day vs backtest 50-day — different trade populations"

**[computed]** Confirmed, and the two gates are not even the same doctrine concept. Live:
`ManasGates.java:94-100`, `liquidVolume(BigDecimal avgVolume20, BigDecimal minAvgVolume)` — "§4.3
absolute low-volume veto: reject any name whose ~20-day average traded volume is ≤ `minAvgVolume`."
Backtest: `ManasAroraSwingBacktest.java:209` builds `volRatio50 = volumeRatio(volume, 50)` and gates
entries on it at line 436, `if (volRatio50[i] <= volMin) { return false; } // §4.7 expanding-volume
breakout` — a *different* doctrine section (§4.7, a volume-expansion-on-breakout check) over a
different window (50 vs 20 sessions), computed as a ratio rather than an absolute floor. The
backtest's `selectionGates` (`ManasAroraSwingBacktest.java:452-467`) calls `ManasGates.gates()` (the
6 core §4.1 gates) but never calls `ManasGates.liquidVolume`/`liquidDepth` at all — the deep sim has
no §4.3 liquidity check whatsoever; it substitutes an unrelated §4.7 volume-expansion filter.

**Verdict: STILL LIVE.** Genuinely different populations, on two different axes (window length AND
which doctrine rule is even being enforced).

### M9 — "RS-rank universe/convention differs live vs backtest"

**[computed]** Confirmed. Live: `ManasScreenService.java:197-218` sorts the screened universe
ascending by weighted RS (with a symbol tie-break) and assigns an **ordinal** rank,
`rsRank = i * 100 / (n-1)` (line 211-216), over `raws` — the CA-adjusted screened universe
(`AdjustedEquityDailySql.SCREENER_BASE_CTE`, referenced at line 170). Backtest:
`ManasAroraBacktestService.java:774-779`, `percentile()` computes a **midpoint** percentile,
`100 * (below + 0.5*equal) / sorted.length`, over the full historical cross-sectional distribution
built at `perBarRsRank` (lines 707-733) from the whole ~11-year NSE-EQ universe — explicitly
labelled **[sourced]** "survivorship-biased" in the method's own doc comment
(`ManasAroraBacktestService.java:388`). Two different formulas (ordinal-position vs. tie-aware
midpoint) over two different populations (today's screened/filtered set vs. the full historical
survivor set).

**Verdict: STILL LIVE.** I verified this concretely for Manas; I did not separately re-derive
Minervini's RS-rank formula in this pass (`MinerviniGates.weightedRs` is documented as sharing "the
same math" as `ManasGates.weightedRs`, per `ManasGates.java:26`, but I did not check whether
Minervini's screen-side ranking assignment uses the same ordinal formula as `ManasScreenService`) —
flagged in Open Doubts.

### M10 — "Deep-sim cost model never compounds order size"

**[computed]** Confirmed, with an exact mechanism. `SwingPortfolio.java:82`:
`double orderValue = costs == null ? 0 : costs.capital() / slots;` — computed **once**, outside the
per-trade loop, from the static configured `capital` (default ₹1,000,000,
`ManasAroraBacktestService.java:211`). This `orderValue` feeds the market-impact term for *every*
trade's cost (`participation = orderValue / adv`, line 90; `impactPerSide`, line 91) across an
~11-year simulation, even though the sleeve itself (`sleeve[k]`, initialized at line 133-134) does
compound multiplicatively as trades close. So a sleeve that has 10x'd its capital by year 8 still has
its cost model computed as if every order were still sized off the original ₹125,000 (at 8 slots) —
understating market-impact cost on later, larger (in reality) trades, which inflates the reported net
CAGR. **[computed]** The identical pattern exists in Manas's rotation variant,
`SwingRotationPortfolio.java:74` (same `orderValue` formula, same static-capital-only computation).

**Verdict: STILL LIVE**, confirmed in both `SwingPortfolio` (used by Minervini per M10's audit text
listing "worst for rs-only") and `SwingRotationPortfolio` (Manas rotation).

### M11 — "Survivorship + universe mismatch (disclosed, but present in every quoted number)"

**[computed]** The underlying cause is still structurally true and independently confirmed elsewhere
in the ledger: no point-in-time index-constituent source exists in-repo. The D4 row's own "P2-6 D6"
note (`docs/superpowers/plans/2026-07-02-remaining-items.md:534`) states this is still
**DEFERRED — OWNER-GATED**, unchanged since 2026-07-14: "no in-repo live membership source exists;
the live fetcher is a deliberate empty placeholder ... the static JSON is non-PIT current-only."

**[computed]** The "disclosed" half is thin, not absent: `docs/strategies/swing-backtest-latest-2026-07-06.md:139`
mentions the survivorship caveat exactly once, in passing prose ("the survivorship + slippage caveat
bites harder"), not as a disclosure attached to each published number.

**Verdict: STILL TRUE, but this is a data-acquisition/documentation gap, not a code defect** — there
is no bug to fix short of sourcing real PIT constituent data (an owner-gated, uncosted project per
D4/P2-6-D6) or strengthening the doc's disclosure. Low urgency; not a good first-slice candidate
either way (a real fix needs a data source decision; a doc-only strengthening is cheap but not
pressing relative to M6/M7/M9/M14/M27).

### M13 — "Holiday/stale-bar runs: no MarketCalendar guard"

**[computed]** Confirmed fixed, incidentally, by the unrelated 2026-07-26–28 batch-liveness
outage-gap project (`docs/audits/...` not applicable here — see MEMORY topic
`batch-liveness-outage-gap`). Three new classes now exist, none of which existed at the 2026-07-05
audit: `SwingBatchCanary.java` (missed-run detection, uses `MarketCalendar.nse()`, #1044 `f64f8f07`,
2026-07-26), `SwingBatchCatchUp.java` (a calendar-aware catch-up sweep — "sweeps the max-attempts+2
most-recent **trading days**," using `MarketCalendar.nse()` to identify them, #1036 `2e4ea6f0`,
2026-07-27), and `SwingMarketHoursGuard.java` (refuses manual runs during NSE market hours, fails
CLOSED with an honest message if the bundled calendar can't resolve the date, #1066 `00f0244a`,
2026-07-28). The catch-up javadoc explicitly documents guarding against stale-close re-entry:
*"ENTRIES run only when the funnel is actually the session's screen ... entering off it would take
the WRONG day's names."*

**Residual:** catch-up is default-OFF (`artha.swing.catchup-enabled`) pending the owner arming it —
the capability exists, arming is a separate owner call, out of this scope.

**Verdict: LARGELY FIXED (incidentally).** No action needed under #128.

### M14 — "No daily-bar freshness assertion; stale:true flag dropped by the client"

**[computed]** Confirmed still true. The `/api/v1/market/candles` response envelope carries a
top-level `stale` field — `CandlesController.java:26`:
`record CandleResponse(List<Candle> items, boolean stale, OffsetDateTime asOf, int limit, int
offset, int total)`. But `MarketDataCandlesClient.fetch()` (`MarketDataCandlesClient.java:53-96`),
the client the swing batch (and the live straddle-SL monitor) use, only ever does
`objectMapper.readTree(body).path("items")` (line 76) and iterates `items` — it never reads
`root.path("stale")` or `root.path("asOf")`. The flag is parsed by nobody; it is silently discarded
at the HTTP boundary.

**Verdict: STILL LIVE.**

### M27 — "Deep sims have no frozen-output golden"

**[computed]** Confirmed. The only regression-style test over the deep sims is
`ManasBacktestBatchEqualityIntegrationTest`
(`services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/manas/ManasBacktestBatchEqualityIntegrationTest.java`),
whose single `@Test` is `batchedReadsReproduceTheSerialBacktestToTheDecimal` — it asserts that a
BATCHED read path produces the same numbers as a SERIAL read path *within the same run*. That is an
internal-consistency/determinism check, not a frozen-expected-value check: it would not catch a
refactor that changed the cost formula, the RS-rank formula, or the exit logic and moved every
published CAGR/Sharpe number, because both the batched and serial paths would move together and
still agree with each other.

**Verdict: STILL LIVE.** This is exactly the gap the finding names — nothing pins the *actual*
published numbers against a reference.

---

## Part 3 — Evaluating the decision sheet's suggested slice, and what I propose instead

**[sourced]** The 2026-08-01 decision sheet suggested: *"build the swing exit-equivalence fixture
(M7) and use it to characterize and align M6 + M9 ... I am not costing this slice."*

Having re-derived M6 and M9 against current code, I think "characterize and align" conflates two
different-risk activities under one recommendation:

- **Characterizing** M6/M9 (measuring how much they actually diverge, and how much of E1's real
  paper-book evidence they touch) is safe, cheap, and can happen now.
- **Aligning** them (changing live or backtest so the two agree) is, by construction, a change to
  either live exit timing/P&L or backtest methodology/reported returns — HOLD-tier under this task's
  own rule, and exactly the shape of decision that burned the M39 fix (a doctrine-literal change,
  applied without measurement, deleted 99% of a profitable population). M36/M37 already sit in the
  ledger explicitly waiting on "needs a backtest A/B first" for the same reason; M6/M9 deserve the
  same discipline, not a fast-tracked exception just because they happen to share a task id with the
  fixture.

So I am **not** proposing to ship an M6 or M9 alignment change in the first slice. I propose instead
to build the measurement infrastructure and the actual measurement, which is the same evidence-first
sequence that eventually resolved M39/H4/T1, and leave the alignment decision explicitly for the
owner once real numbers exist.

## Part 4 — Proposed first slice

### Item 1 — M7: swing exit-equivalence characterization fixture

**What:** a new fixture + test suite, modelled on `contracts/fixtures/exit-equivalence.json`'s
pattern but for swing: a JSON (or equivalent) fixture of synthetic daily-bar sequences + config, run
through (a) the live path (`ExitEvaluator.evaluate` via `SwingBatchEngine.buildBank`) and (b) each
deep sim's hand-rolled exit logic (`ManasAroraSwingBacktest`'s Chandelier/stop logic,
`MinerviniSwingBacktest`'s equivalent), asserting what the fixture finds — agreement where it
genuinely holds, and an explicit, named divergence assertion for the entry-bar case (M6) rather than
silence.

**Files/seams:** new test class(es) under
`services/market-data-service/src/test/java/in/arthayantra/marketdata/screener/{manas,minervini}/`,
new fixture file under `contracts/fixtures/` (e.g. `swing-exit-equivalence.json`), reusing
`SwingBatchEngine.buildBank` (already `public static`, `SwingBatchEngine.java:1010-1027`) to drive
the live side without needing the full Spring context.

**Verify (red→green):** the test should initially fail if written to *assume* equivalence (a naive
"both sides agree on every scenario" assertion is false today, per M6) — the correct green state is
a test that explicitly documents *where* the two sides agree and *where* they don't (entry-bar
exits, at minimum), so a future refactor that accidentally changes either side's behaviour trips it.

**Parity/golden exposure:** none — this is a brand-new, additive fixture and test file. It does not
touch `contracts/fixtures/exit-equivalence.json` (scalper-only, frozen), `GoldenSignalsJson.write()`,
or any existing golden/parity test. Zero risk to those surfaces.

**Merge tier: CLEAN.** Pure test/fixture addition; no production code changes; no behaviour change.

### Item 2 — M14: surface the dropped `stale`/`asOf` candle-envelope fields

**What:** extend `MarketDataCandlesClient.fetch()` to read `root.path("stale")` /
`root.path("asOf")` alongside `items`, and surface a stale response the same way `P0-3` already
surfaces a fetch failure — a loud log line + a counter the swing batch can report (e.g., extending
`SwingRun`'s existing `exitSkipped`-style counter family, `SwingBatchEngine.java:89-97`), **without**
changing what data is used for entries/exits. Scope this strictly to visibility; do not add a refusal
gate in this slice (a refusal would change which sessions can enter/exit — that's a HOLD-tier
behaviour change, and out of scope here).

**Files/seams:** `MarketDataCandlesClient.java` (return type needs to carry `stale`/`asOf`, or add a
new `fetchWithMeta` alongside the existing `fetch` to avoid touching the other caller,
`straddleSl`), `SwingBatchEngine.java`'s `series()`/`retryFetch()` call sites (`:1037-1047`,
`:878-883`) to read and log/count the new field.

**Verify (red→green):** a stubbed candles response with `stale:true` should currently produce no
observable signal from `MarketDataCandlesClient` — a new unit test asserting a log line / counter
increment on a stale response is red before, green after.

**Parity/golden exposure:** none — read-only observability addition; does not change any value fed
into `ExitEvaluator`, `EntryEvaluator`, or the deep sims.

**Merge tier: CLEAN.** Additive, no behaviour change to entries/exits/P&L.

### Item 3 — M27: frozen-output golden test for the deep sims

**What:** a new golden-style regression test that runs each deep sim
(`ManasAroraBacktestService`/`MinerviniBacktestService`) over a **fixed, seeded** symbol set and date
range and asserts the headline published statistics (CAGR, Sharpe, max drawdown, trade count) against
a checked-in reference file — mirroring the discipline `GoldenSignalsJson` provides for live signals,
but as a new, independent artifact (not touching the frozen writer itself).

**Files/seams:** new test class(es) alongside the existing
`ManasAroraSwingBacktestTest`/`MinerviniSwingBacktestTest`/`MinerviniBacktestBatchEqualityIntegrationTest`,
new reference/golden file under a new `src/test/resources/.../swing-golden/` directory (or similar).

**Verify (red→green):** red-proof by temporarily perturbing one formula (e.g., the `SwingPortfolio`
impact-cost coefficient, or the RS-rank percentile method) and confirming the new golden test catches
the resulting number shift; then revert and confirm green.

**Parity/golden exposure:** this is a **new, parallel** golden mechanism — it must not touch or
depend on `GoldenSignalsJson.write()` (frozen, signals/trades only) or the scalper's
`exit-equivalence.json`. No interaction with `BacktestParityTest` or `GoldenDeterminismTest`, which
live in a different module (`libs/strategy-engine`/`services/backtest-service`) and cover a different
surface (the tick-wise golden runner, not the deep sims in `market-data-service`).

**Merge tier: CLEAN.** Pure test + fixture addition; the reference numbers being pinned are whatever
the code currently produces — nothing about production behaviour changes.

### Item 4 — M6 + M9 impact measurement (not a PR; a findings note)

**What:** a deploy-free, session-analysis-style measurement (same method that resolved T1/T7/M39):
(a) for M6, walk `strategy.signals` / paper-position rows opened by the two swing books so far and
count how many closed same-day-as-entry under the live engine's current behaviour, and what P&L those
specific trades represent, so the owner can see the real (not hypothetical) size of the divergence;
(b) for M9, re-run the live ordinal-rank formula and the backtest midpoint-percentile formula over a
handful of recent live screen dates' actual candidate sets and report how much the two conventions
would have changed which names cleared the RS-rank gate.

**Deliverable:** a `docs/signal-analysis/*` findings note (not a ledger `DONE`) — feeds a *future*
owner HOLD decision on whether/how to align M6 and/or M9; does not itself change any number.

**Merge tier: N/A (not code).** If its conclusion recommends an actual alignment change, that
follow-up change is HOLD-tier by definition (changes exit doctrine or backtest methodology) and
needs its own PR + owner sign-off, same as M36/M37.

## Part 5 — Deferred (not in this slice)

- **M2** — MOOT, no action.
- **M3** — LARGELY FIXED; optionally note the residual "alert-only, no auto-repair" gap in a future
  pass, low urgency (a human already gets paged).
- **M4** — real, but the display-only fix and the risk-integration question need to be scoped
  separately before building either; propose for a second slice once someone decides whether "show
  accurate swing MTM" is meant to stay display-only or feed sizing.
- **M8, M10** — real, but each is a live/backtest-population-shifting change like M36/M37: needs its
  own deploy-free geometry check + A/B before touching any number, not a "just fix it" PR.
- **M11** — real but not a code defect; either wait for a PIT-constituents source (uncosted,
  owner-gated) or, if the owner wants something now, a cheap doc-only strengthening of the disclosure
  language is available separately from this slice.
- **M13** — LARGELY FIXED; no action (arming the catch-up default is a distinct, already-known owner
  lever, out of this scope).

## Note on stale #128 references elsewhere in the ledger

**[computed]** Beyond the E4 row this task was scoped to update, `docs/superpowers/plans/2026-07-02-remaining-items.md`
carries the same stale 12-item list in three more places — lines 1038-1039, 1050, and 1178 — all
inside **dated historical narrative sections** (§8's 2026-07-09 cross-doc sweep and 2026-07-10 §8f
arm-effect audit), not live status. Left untouched deliberately: the task scoped me to the E4 row
specifically (two other agents are concurrently editing that same row for its H8/M38 and M36/M37
sub-items), and these three are point-in-time audit-trail entries rather than maintained state.
Flagging them here so a future ledger-hygiene pass knows they exist.

---

## Ledger update

Updated only the "#128" clause inside the E4 row
(`docs/superpowers/plans/2026-07-02-remaining-items.md:543`), leaving the H8/M38, M36/M37/M40,
VcpDetector, and #591/#607 clauses of that same row byte-for-byte untouched, since two other agents
are concurrently editing those parts of the row.

---

## Open doubts

1. **M4's downstream risk-sizing consumer was not traced.** I confirmed `unrealized` is structurally
   null for swing positions in both `PaperService` MTM call sites, and inferred that anything reading
   it for daily-loss/heat-cap purposes therefore sees zero open drawdown — but I did not read
   `RiskService`'s daily-loss aggregation to confirm it actually consumes `PaperService`'s DTOs rather
   than some other path. **[assumed]**, load-bearing for M4's second half.
2. **M9 was verified concretely for Manas only.** I did not re-derive Minervini's live-side RS-rank
   assignment formula to confirm it uses the same ordinal `i*100/(n-1)` shape as
   `ManasScreenService.java:211-216` — `ManasGates.java:26` documents the underlying `weightedRs` math
   as shared with `MinerviniGates`, but the RANKING step (percentile assignment) is a separate piece
   of code I did not check on the Minervini side.
3. **M2's "correct" original scope is unrecoverable.** The 2026-07-05 audit's own fix-log entry keeps
   M2 separate from the swing-specific M3/M4/M6/M7/M8/M9/M10/M11/M27 grouping, but the ledger's later
   bookkeeping (`remaining-items.md:1039`) folded it into task #128 anyway. I could not find the
   original audit's full prose for M2 (only the one-line table entry + the fix-log's one-line
   retrospective) to settle which engine it meant. My MOOT verdict holds under both readings I could
   construct, but a different, unconsidered reading might exist.
4. **No live database or runtime verification was performed.** Every "computed" claim in this doc
   comes from static reads of code/tests/git history on the `origin/main @ bc699806` checkout, not
   from querying the live `artha` database or the running containers — e.g., M3's stranded-carry/
   dead-anchor counts, M13's catch-up arming state, and M14's actual observed `stale` rate on live
   candle reads were not queried live.
5. **The M6/M9 measurement item (Part 4, Item 4) is scoped but not executed.** This doc proposes what
   to measure and how; it does not contain the measurement itself. Building it is the next step, not
   something this scoping pass produced.
6. **I did not verify whether `SwingBatchCatchUp` (M13's fix) is currently armed live**
   (`artha.swing.catchup-enabled`) — the doc states its default is OFF per the class's own javadoc,
   but I did not check the live `.env`/compose config to confirm that default hasn't been overridden.

---

## Receipt

- **Doc:** `docs/signal-analysis/2026-08-02-e4-128-batch-scoping.md` (this file).
- **Re-mapping:** 12/12 findings re-derived against current `HEAD` with fresh `file:line` citations
  (Part 2). 3 already fixed/moot (M2, M3, M13), 8 confirmed still-live (M4, M6, M7, M8, M9, M10, M14,
  M27), 1 disclosure/data-acquisition gap not a code defect (M11).
- **Proposed first slice:** M7 (swing exit-equivalence characterization fixture, CLEAN) + M14 (surface
  the dropped `stale`/`asOf` candle fields, CLEAN) + M27 (frozen-output golden for the deep sims,
  CLEAN) + a deploy-free M6/M9 impact measurement (not code; feeds a future HOLD decision).
- **Deferred:** M2 (moot), M3 (largely fixed, note residual), M4 (needs display-vs-risk scoping
  first), M8/M10 (need their own A/B, same class as M36/M37), M11 (data-acquisition/disclosure, not
  code), M13 (largely fixed).
- **Ledger:** E4 row's "#128" clause updated in place; H8/M38/M36/M37/M40/VcpDetector/#591/#607
  clauses of the same row left untouched.
- **Claim labels:** [computed] / [sourced] / [recalled] / [assumed], used inline throughout.
- **Open doubts:** see section above (6 items).

Cross-vendor review: PENDING (docs-only scoping pass; queued for the next review round per the
delegation standard).
