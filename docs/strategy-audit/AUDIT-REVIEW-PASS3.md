# Third-pass review — Options-Scalper Automation Audit

This is the **third-pass review** of the Wave-5 #1 automation audit ([README.md](./README.md)), following
the v1 audit and the second-pass re-audit ([AUDIT-REVIEW.md](./AUDIT-REVIEW.md)). Where pass 2 re-checked
*coverage* (did the audit miss rules? are statuses right?), this pass focused on three narrower questions:

1. **Convergence** — after two passes, is the rule set *stable*, or does each pass still churn statuses
   and figures?
2. **Citation validation** — was **every** cited `file:line`, yaml key, and doc-line actually opened and
   confirmed to say what the row claims?
3. **Cross-dimension integrity** — do the 18 section files plus the two index/summary files agree with
   each other (no duplicates, contradictions, broken tables, or count mismatches)?

**Headline:** the audit has **converged**. Across 584 rows re-validated, **0 statuses changed** and **0
figures were invented**. The pass fixed **24** stale-but-non-substantive citations in the section files
(line-drift, off-by-one array indices, one wrong-file attribution), added **4** genuinely-missed table
rows (all gaps), and applied **9** count/consistency corrections to the two index files. Every verdict the
prior two passes reached still stands; only pointers and totals moved.

---

## 1. Per-dimension validation

`Rows checked` = main-table rows opened and cite-validated this pass. `Bad citations fixed` = rows whose
`file:line` / yaml key / doc-§ was stale or wrong and was corrected (verdict unchanged). `Still missed` =
genuinely-absent rules this pass surfaced (added as new rows unless noted confirmation-only). `Status fixes`
= rows whose FULL/PARTIAL/NONE/MANUAL_COVERED verdict had to change. `Convergence` = whether the dimension
is settled.

| Dimension | Rows checked | Bad-citations fixed | Still-missed | Status fixes | Convergence |
|-----------|-------------:|--------------------:|-------------:|-------------:|-------------|
| [Two Candle Theory](./two-candle.md) | 39 | 0 | 1 | 0 | stable |
| [Open=High / Open=Low](./open-high-low.md) | 30 | 2 | 0 | 0 | stable |
| [Market Movers](./market-movers.md) | 25 | 2 | 0 | 0 | stable |
| [Gap Theory](./gap-theory.md) | 26 | 2 | 0 | 0 | minor-drift |
| [Trending OI Crossover](./trending-oi.md) | 34 | 4 | 0 | 0 | stable |
| [Golden Crossover](./golden-crossover.md) | 21 | 3 | 0 | 0 | stable |
| [Hero-Zero (Expiry-Day OI)](./hero-zero.md) | 32 | 4 | 0 | 0 | stable |
| [BTST / STBT](./btst-stbt.md) | 28 | 1 | 1 | 0 | stable |
| [Morning Trade](./morning-trade.md) | 28 | 1 | 1 | 0 | stable |
| [Connect the Dots (framework)](./connect-the-dots.md) | 41 | 0 | 0 | 0 | stable |
| [Straddle (Long & Short)](./straddle.md) | 24 | 1 | 0 | 0 | stable |
| [Trend Change](./trend-change.md) | 30 | 1 | 1 | 0 | stable |
| [Global Risk Management](./risk-framework.md) | 45 | 0 | 2 | 0 | stable |
| [Indicators / OI / VIX / IV](./indicators-oi-vix-iv.md) | 39 | 0 | 0 | 0 | stable |
| [Cues / A-D / Strike / Time / S&R / OIP / FII-DII](./gates-strike-sr-fiidii.md) | 26 | 1 | 0 | 0 | stable |
| [Session-21..24 + open-questions + checklist](./session-additions-and-manual-coverage.md) | 57 | 2 | 0 | 0 | stable |
| [Introduction & Terminology](./intro-terminology.md) | 33 | 0 | 0 | 0 | stable |
| [Whole-document completeness sweep](./completeness-sweep.md) | 26 | 0 | 0 | 0 | minor-drift |
| **Total** | **584** | **24** | **6** | **0** | **16 stable · 2 minor-drift** |

**Reading the "Still-missed" column.** Of the 6 genuinely-absent rules this pass surfaced, **4 were added
as new gap rows** (and folded into the README totals):

- **Two Candle** — *Multi-timeframe RSI cross-check* (CE RSI(5m) <75/80 & RSI(Daily) <75; PE mirror).
  Status **NONE** — no daily-RSI indicator is wired.
- **BTST / STBT** — *Morning re-confirm before continuing the hold* (keep the carry only if yesterday's
  3:20pm view, the morning-trade read, premarket and global cues all align). Status **NONE** — the engine
  just `time_stop max_holding_days:1` exits; none of those inputs reach a carry-path gate.
- **Morning Trade** — *Take EVERY signal but modulate the lot* (normal lot when aligned with the market,
  reduced when opposing/neutral). Status **NONE** — the YAML is a fixed `budget_inr:15000` and the scorer
  returns only a validity verdict, no strength→size multiplier.
- **Trend Change** — *Strong-trend / late entry — wait for pullbacks, do not chase.* Status
  **MANUAL_COVERED** — no detector; carried by `ScalperManualChecks` `not_parabolic` + `clean_setup`.

The remaining **2** (Risk-Framework r48 and r60) are **confirmation-only restatements** of daily-profit-
target / loss-symmetry rails already captured in existing rows (16/19/28 and 13/16/28). They add no new
automatable rail and were deliberately **not** added as rows — recorded here so the count is honest.

**The two `minor-drift` dimensions** ([Gap Theory](./gap-theory.md), [Completeness sweep](./completeness-sweep.md))
each carried a small wording/note imprecision (a doc-comment phrase that spans two lines; two lead-in
phrasings) that the cite-validation tightened. Neither moved a status or a figure; they are flagged
`minor-drift` rather than `stable` only because the pass touched prose, not just pointers.

---

## 2. Overall convergence verdict

**The audit has converged and is trustworthy.** Three independent signals support this:

- **Zero status churn.** Pass 2 made a handful of defensible status flips (one PARTIAL→FULL demotion, two
  promotions, one invented-figure FULL correction). Pass 3 made **none** — every FULL/PARTIAL/NONE/
  MANUAL_COVERED verdict survived a full cite re-open. When the third pass stops moving statuses, the rule
  set is settled.
- **Citations are now provable, not just plausible.** The 24 fixes were *all* pointer hygiene — line-drift
  (e.g. `OpenHighLowGate.java:228` → the real `:64,111`; the StrikePicker call re-cited to
  `ScalperConfluenceGate.java:271-276` + `ScalperConfig.java:117-118`), off-by-one array indices in the
  §6.x JSON (`setup[2]`→`[3]`, `bullish[8]`→`[7]`, `setup_preconditions[4]`→`[5]`), and one wrong-file
  attribution (a Hero-Zero "~10% of profits" comment that lives in `scalp-hero-zero-nifty.yaml:29-30`, not
  any Java gate). None changed what a row claims; each made the claim *checkable*.
- **No genuine completeness hole remains.** The 4 newly-added rows are edge management/sizing rules, not
  core mechanics, and after they were added every dimension's main table matches its README row count.

The prior passes' headline conclusion is unchanged: the engine automates the mechanical core (entries,
structural stops, the soft confluence dots) and leaves the management layer, the cross-source reads
(FII/DII, VIX, per-stock screeners), and the discretionary discipline to the human checklist.

---

## 3. Cross-dimension integrity

All 20 files (18 section files + README.md + AUDIT-REVIEW.md) were read and cross-checked for duplicates,
contradictions, table/link breakage, and count mismatches.

### 3a. No genuine status contradictions

The most important negative result: **the same code/yaml fact is never given opposite verdicts for the
same execution path.** The apparent FULL-vs-NONE splits for the breadth `>32` gate, `oiQuadrant`, the RSI
band, the volume floor, and the StrikePicker — FULL/PARTIAL in morning-trade / connect-the-dots but NONE
in btst-stbt — are **legitimate per-path reachability distinctions, not contradictions**: the BTST
`style:btst` pre-close path emits with `decision=null` and bypasses `ScalperConfluenceGate`, so those
gates are *unreached* (NONE) on the carry path but *reached* (FULL/PARTIAL) on the intraday confluence
path. Each file cites the same code and explains the bypass. Shared facts verified **consistent** across
files: `bias60m` (a live hard AND-term, backtest-absent); `max_daily_loss_pct` as a dead YAML key
(straddle / trend-change / risk-framework all agree); VIX null-fed in `MarketOiClient.macro`; the
oi-cross-filter present only on the trending-oi YAMLs.

### 3b. Contradictions found and resolved

- **AUDIT-REVIEW.md §2 "544 rules" vs its own §1 table (566).** §2 had added the +45 missed rules onto the
  README's *v1-published* base of 499 (499+45=544) instead of the *re-counted* v1 base of 521
  (521+45=566). The 420-gaps figure coincidentally matched; only the rules figure was wrong. **Fixed in
  place** — §2 now derives from the 521 re-count → 566, plus the +4 v3 rows → 570 / 424.
- **README / AUDIT-REVIEW totals were one pass stale.** The v3 citation pass added exactly 4 main-table
  rows to the section files but did not propagate the +4 into the two index files, so README §1 (exec
  summary), README §2 (per-dimension table), and AUDIT-REVIEW §1 ("Missed found" column) still published
  the v2 numbers (566/420). This is a count-vs-reality mismatch, not a status contradiction between two
  rows. **Fixed README §1/§2 in place** (566→570, 420→424); the AUDIT-REVIEW §1 table was **left at its
  v1+v2 historical numbers** (it is a v2 snapshot documenting the v1→v2 re-audit specifically) with a §2
  clarifying note added about the later +4 rows.
- **Soft prose near-miss — left as-is, flagged not forced.** README §1 narrative says "51 gaps map onto
  those 7 checklist items" while the exec-summary breakdown tags MANUAL_COVERED = 53. These measure
  different things (distinct gaps mapped to a checklist item vs total MANUAL_COVERED rows, several of which
  share an item), so the 51 was likely intentional. Forcing alignment would assert an unverified number;
  it is flagged for the synthesizer rather than edited.

### 3c. Count mismatches found and reconciled

| Where | Was | Now | Note |
|-------|----:|----:|------|
| README §2 Rules — Two Candle | 39 | 40 | +1 v3 row |
| README §2 Rules — BTST/STBT | 26 | 27 | +1 v3 row |
| README §2 Rules — Morning Trade | 27 | 28 | +1 v3 row |
| README §2 Rules — Trend Change | 30 | 31 | +1 v3 row |
| README §2 Gaps — Two Candle | 28 | 29 | new row is a gap |
| README §2 Gaps — BTST/STBT | 22 | 23 | new row is a gap |
| README §2 Gaps — Morning Trade | 21 | 22 | new row is a gap |
| README §2 Gaps — Trend Change | 25 | 26 | new row is a gap |
| README headline — rules / gaps | 566 / 420 | 570 / 424 | §1 table + §2 total row |
| README §1 breakdown — NONE | 182 | 185 | 3 of the 4 new rows are NONE |
| README §1 breakdown — MANUAL_COVERED | 52 | 53 | the trend-change new row |
| README §1 narrative + table — gaps total | 420 | 424 | exec-summary prose |
| README §1/§4 — automatable | 309 (§4) / ~318 (§1) | ~318 | §4 was both stale and inconsistent with §1 |
| README §1 — manual-only | ~102 | ~106 | 424 − ~318, keeps the split internally consistent |
| AUDIT-REVIEW §2 — rules | 544 | 566 → 570 | see §3b |

Post-fix the figures reconcile exactly: README §2 `Rules` column sums to **570** and `Gaps` to **424**;
the §1 status breakdown **178 PARTIAL + 185 NONE + 53 MANUAL_COVERED + 8 UNCERTAIN = 424**; and the
automatable split **~318 + ~106 = 424**. PARTIAL (178) and UNCERTAIN (8) were unchanged.

### 3d. Tables and links

- **No broken markdown tables.** A column-count scan flagged ~12 rows as having 7 or 11 "columns"
  (e.g. two-candle, trending-oi, hero-zero, risk-framework, indicators, straddle, golden-crossover, gates),
  but **all are false positives**: the extra pipes are escaped literal pipes (`\|`) or pipes inside
  backtick code spans (e.g. `!floorMet(first) \|\| !floorMet(second)`, `|peΔ−ceΔ|/max(...)`,
  `|close−vwap|`). Every main table renders with exactly 5 columns; no fix needed.
- **All 18 relative section-file links** in README.md and AUDIT-REVIEW.md resolve to existing files. No
  malformed v2/v3 notes blocks. The README §2 edited rows and the §1 exec table re-verified well-formed
  after the count fixes (every data row = 5 / 2 cells respectively).

### 3e. AUDIT-REVIEW §4 "all 6 resolved" — verified

AUDIT-REVIEW §4's closing claim *"(All 6 resolved in this pass.)"* was independently checked against the
section files and is **accurate**: Market Movers' v2-notes heading now reads "6 new rows"; the BTST SC/SB
classification cites `OiQuadrant.java:14,16`; Connect-the-Dots line 406 is correctly attributed to §3.1
Filters (not §1.1); and Completeness Row 39 correctly says the gate drops the *current* VWAP before 10:30
(no phantom previous-day anchor). All six cosmetic v2 issues are genuinely fixed.

---

## 4. New issues introduced by this pass

This pass introduced **3** new issues, all cosmetic and all in the `minor-drift` dimensions — none alter a
status, a figure, or a table:

- **[Gap Theory](./gap-theory.md):** 1 — a doc-comment phrase re-cited to the line pair it actually spans
  (`GapState.java:12-13`); harmless precision note.
- **[Completeness sweep](./completeness-sweep.md):** 2 — two lead-in phrasings tightened during cite
  validation.

No new issue is substantive: none overturns a status, invents a figure, loses prior content, or breaks a
table. The pass therefore meets the "near-zero new issues" bar that the second pass set.

The **9 cross-dimension fixes** applied to the index files (the four §2 Rules cells, the four §2 gap cells
via three edits, the two total-row figures, the §1 exec-summary NONE + MANUAL_COVERED + rules + gaps, the
§4 automatable `309`→`~318`, and the AUDIT-REVIEW §2 `544`→`566/570` reconciliation) are corrections, not
new defects — they bring the two index files into line with the section files, which are the source of
truth. The root structural problem was simply that the v3 citation pass edited the section files but did
not propagate the +4 rows to the summaries; that is now reconciled.

---

## 5. Bottom line

After three passes the audit is **stable and trustworthy**. The first pass built it (~96% accurate at the
row level), the second corrected coverage (+45 rules, 16 fixes, 6 cosmetic new issues), and this third
pass validated every citation, found **no status errors and no invented figures**, added the last **4**
genuinely-missed rows, and reconciled the index counts so the headline (**570 rules / 424 gaps**) ties out
exactly against the section files, the per-dimension table, the status breakdown, and the automatable
split. The operator takeaway is unchanged and now fully cite-checked: the engine automates the mechanical
entry/stop core and the soft confluence dots; the management layer, the cross-source reads, and the
discretionary discipline remain a human-checklist responsibility.
