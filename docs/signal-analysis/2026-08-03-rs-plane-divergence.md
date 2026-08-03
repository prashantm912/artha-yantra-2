# The dividend plane split, instrumented — and measured on the RS legs (2026-08-03)

Two pieces of owner-approved work off the finding in
`docs/signal-analysis/2026-08-03-pivot-crossover-plane-split.md` (#1272 §5): **`candles`@1d is
dividend-back-adjusted and `nse_eod_bhavcopy` is not.**

- **Task A** — instrument it. New code, no plane's prices changed.
- **Task B** — measure the RS legs, the one `AdjustedEquityDailySql` consumer #1272 §8.5 flagged as
  reasoned-not-measured. Read-only.

All DB numbers are direct psql output against the LIVE `artha` database on 2026-08-03.

---

## 0. Verdict first

### Task A — instrument it

| question | verdict |
|---|---|
| built | **`PlaneDivergenceProbe`**, riding the existing 20:00-adjacent Minervini screen batch. No new cron, no migration, no plane change |
| does it land on a candidate, or merely exist | **It lands.** Over the 22 persisted screen dates, **197** divergent passer name-dates on **22 of 22** dates, of which **103 are served funnel candidates** on **20 of 22** dates |
| is "a divergent symbol was a candidate" the right page trigger | **No — measured.** That is ~4.7 pages *every evening*. The probe therefore carries **two floors**: a 0.5% REPORT floor (log + endpoint = the visibility fix) and a separate 5% PAGE floor (**7** name-dates on **7** of 22 dates, INDOBORAX + MAHLOG) |
| did instrumentation require a price change | **No.** Both planes are read-only inputs |

### Task B — measure the RS legs

| question | verdict |
|---|---|
| which plane do the RS legs read | **`nse_eod_bhavcopy`** — the SAME plane as the VCP geometry, a **DIFFERENT** plane from the live engine's `px` (`candles`@1d) |
| is the RS gate a cross-plane *comparison* | **No.** `rsRank` is computed wholly inside the bhavcopy plane and compared to a scalar (70). The exposure is the **dividend doctrine**, not a plane split — and unlike the crossover's one session it has a **~252-session tail** |
| how many name-dates would rank differently | **22,928 of 38,868 (58.99%)** swapping only the 32 measured-divergent symbols; **29,316 (75.42%)** swapping every symbol candles covers |
| how many rank changes CROSS the `rs>=70` gate | **34 of 38,868 (0.087%)** — exactly 17 gained / 17 lost, because the percentile is positional |
| how many change the SCREEN OUTCOME (`passes_all`) | **3**, all of them *passive displacement* casualties, all one-way permissive (the live plane admitted them; a dividend-aware plane drops them) |
| did any land on a candidate | **YES — ICEMAKE 2026-07-28**, `passes_all=t`, `is_vcp=t`, pivot 899.00, close 855.80 → **ON_DECK, a served funnel candidate**. The first case in this investigation series where the split reaches a candidate |
| live entries affected | **0** (`strategy.paper_positions` holds no row for any name involved) |

**One line:** RS is where this actually bites. A 2–12% price error on **1.8%** of the universe moves
the rank of **59%** of it — broad but shallow (median displacement 0.11 percentile points, never
≥1.0) — so the harm is confined to names sitting within ~0.4 of the `rs>=70` cut, and there it
landed on a real candidate.

---

## 1. STEP 0 — re-verifying #1272's load-bearing claims

### 1.1 The 32-symbol census: reproduced exactly

Transcribing both adjusters verbatim (plane A = `AdjustedEquityDailySql`'s `factorLateral` over
`nse_eod_bhavcopy` `series IN ('EQ','BE')`; plane B = `candles`@1d NSE with
`EquitySplitBonusAdjuster`'s source-aware rule — factor applied only where `source='BHAVCOPY'`),
joined on the bhavcopy session date, over 2026-04-01…08-03, restricted to the screened universe:

```
 shared_bar_dates | diverging_bars | symbols_shared | symbols_material_divergent
------------------+----------------+----------------+----------------------------
           143119 |           1956 |           1813 |                         32
```

**32 of 1,813 (1.76%), 1,956 of 143,119 bar-dates (1.37%) — identical to #1272 §5.3.** **[computed]**

The named examples all reproduce, with the same ratios: JAGRAN 0.872, EXPLEOSOL 0.879,
INDOBORAX 0.917, ITC 0.973, GLAXO 0.975, ABSLAMC 0.976, LICI 0.977, ABBOTINDIA 0.977, INFY 0.979,
ULTRACEMCO 0.980, RELIANCE 0.992. Every ratio is **≤ 1** — the candles plane always reads LOWER,
which is what a dividend back-adjustment does and nothing else would. **[computed]**

### 1.2 The two paisa-exact reconciliations: both hold

```
 plane     | symbol    | date       | close
-----------+-----------+------------+-----------
 A bhavcopy| INDOBORAX | 2026-07-20 |  481.0500      dividend ex 2026-07-21, subject
 B candles | INDOBORAX | 2026-07-20 |  441.0500      "Rs 10 Per Share/Special Dividend - Rs 30"
 A bhavcopy| ABSLAMC   | 2026-07-21 | 1043.1000      dividend ex 2026-07-22, Rs 25.50
 B candles | ABSLAMC   | 2026-07-21 | 1017.6000
```

481.05 − 40.00 = **441.05** ✓ · 1043.10 − 25.50 = **1017.60** ✓ **[computed]**

### 1.3 Three refinements to file against #1272

1. ⚠️ **The INDOBORAX reconciliation works off the subject TEXT, not `dividends.amount`.** The NSE
   row's `amount` column is **10.0000**, not 40.00 — `DividendSubjectParser.parseAmount` captured
   only the first of the two amounts in the compound subject. A programmatic reconciliation keyed on
   `dividends.amount` reproduces 471.05, **not** the observed 441.05. Anything built on that column
   (including a future Option A) must handle compound subjects, or it will under-adjust by the
   special-dividend leg. **[computed]**
2. **The bhavcopy plane cannot hold a dividend even in principle.** `eod_corporate_actions` carries
   `CHECK (kind = ANY (ARRAY['SPLIT','BONUS']))` — stronger than #1272's "holds only BONUS and
   SPLIT rows", which reads as an observation about current contents. The `continue;` at
   `BhavcopyBackfillService.java:474-486` and the constraint are the same decision stated twice.
   **[sourced]**
3. **21 screen dates → 22.** `minervini_screen_results` now holds 22 distinct `screen_date`s over
   the same 2026-07-03…08-03 range; #1272 measured mid-day. All of §2–§4 below uses 22. **[computed]**

Retro-mutability confirmed in the act, again: ABSLAMC's `candles` bars for 07-21 and 07-22 both
carry `fetched_at = 2026-07-23 16:53:15 IST` — rewritten two days after the sessions they describe.
**[computed]**

---

## 2. Task B — which plane do the RS legs read?

`TrendTemplateService.SQL` (`:107-110`) opens with `"WITH base AS (\n" +
AdjustedEquityDailySql.SCREENER_BASE_CTE`, and the RS legs are computed on it (`:120-123`):

```sql
lag(close, 63)  OVER pw AS c63,
lag(close, 126) OVER pw AS c126,
lag(close, 189) OVER pw AS c189,
lag(close, 252) OVER pw AS c252,
```

then `MinerviniGates.weightedRs(close, c63, c126, c189, c252)` = `0.4·r63 + 0.2·(r126+r189+r252)`,
ranked cross-sectionally into `rsRank`, gated at `g[7] = rsRank >= rsMin` (default 70).

`SCREENER_BASE_CTE` reads `FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')`.

> **The RS legs read the bhavcopy plane.** **[sourced]**

Two consequences, and the second is the one that matters:

- **Not the same plane as the engine.** The live swing engine's `px` is a `candles`@1d close
  (#1272 §1.1). So RS is on the opposite plane from the entry trigger's fast leg — but it *is* on
  the same plane as the pivot it is eventually compared beside.
- **But the RS gate is not a cross-plane COMPARISON.** `close` and every lag leg come from the same
  plane, and the result is compared against the scalar 70. So #1272's crossover analysis does not
  transfer: there is no "one-way permissive, confined to the ex-date session" structure here.
  What is left is the **doctrine** — the bhavcopy plane measures trailing return across an ex-date
  as if the dividend drop were a real loss. Back-adjustment scales the *whole pre-ex history*, so
  the distortion persists for as long as the lag reaches back: **up to 252 sessions**, not one.
  **[sourced]**

(`MinerviniHitRateService` — the backtest harness — reads `candles`@1d with the *same*
`MinerviniGates` math. So the harness and the live screen already sit on opposite planes for RS.
That is pre-existing and out of scope here, but it is the same split viewed from the backtest side.)

---

## 3. Task B — the measurement

### 3.1 Method

For each of the 22 screen dates, the screen was recomputed from raw bars three ways, changing
**only the price plane** — same session sequence, same 420-day window, same universe filters
(`sessions >= 252`, `close >= 30`, `avg_turnover_50 >= 937500`), same `weightedRs`, same positional
percentile with the `thenComparing(symbol)` tie-break:

| plane | definition |
|---|---|
| **A** | live: `nse_eod_bhavcopy`, split/bonus CA-adjusted verbatim |
| **B32** | A, with the **32 measured-divergent symbols'** closes swapped for the `candles` plane |
| **BALL** | A, with **every** symbol's close swapped for `candles` wherever a bar exists |

Where `candles` has no bar on a bhavcopy session date, plane A's price is kept — that fallback is
**3.31%** of the 499,886 bar rows. **[computed]**

**A and B are both recomputes on the same 2026-08-03 snapshot.** They are never compared against a
persisted decision row — that is the retro-mutability trap, and it is exactly what dissolved
#1272's four apparent flips. The persisted rows are used only (a) as the other-7-gates mask and
(b) as a calibration read-out.

**Calibration.** Recomputed universe size per date is 1,754–1,776 against 1,755–1,776 persisted
(within 1–2 rows). Recomputed plane-A `gate8` matches the persisted `gate8` on **38,126 of 38,834
(98.18%)**. Byte-identical `rs_rank` matches on only **6.29%** — expected and not a defect: a
positional percentile shifts for the whole universe when any bar anywhere is rewritten, and both
source tables are retro-mutable. This is precisely why the A/B is recompute-vs-recompute.
**[computed]**

### 3.2 Result

n = **38,868** name-dates (22 screen dates).

| | rank differs | crosses `rs>=70` | of which divergent / passive | changes `passes_all` |
|---|---|---|---|---|
| **B32** | **22,928 (58.99%)** | **34** (17 gain, 17 lose) | 17 / 17 | **3** |
| **BALL** | 29,316 (75.42%) | 54 (27 / 27) | 17 / 37 | 10 |

The gain/lose symmetry is structural, not coincidence: `rsRank = i·100/(n−1)` is positional, so a
divergent name climbing past the 70th percentile displaces exactly one name below it.

**Direction.** All 17 divergent-symbol crossings are `False → True`: the live bhavcopy plane
**under-ranks** dividend payers (the ex-date drop reads as a real loss), and correspondingly
**over-ranks** everyone else. The passive casualties are therefore names the live screen admits and
a dividend-aware plane would not — **one-way permissive**, the same direction #1272 found at the
crossover. **[computed]**

### 3.3 Broad but shallow

| population | n in universe | rank moved | median Δ | max Δ | \|Δ\| ≥ 1.0 pt |
|---|---|---|---|---|---|
| the 32 divergent symbols | 695 | 620 | **+4.03** | **+32.29** | 479 |
| everyone else | 38,173 | 22,308 | 0.11 | **0.40** | **0** |

So the universe-relative propagation the owner predicted is real and enormous in *count* (59% of
name-dates move) but tiny in *magnitude* for the bystanders — never as much as one percentile
point. A bystander can only flip the gate if it already sits within ~0.4 of 70.00, which is exactly
what the three outcome-changing rows look like. **[computed]**

### 3.4 The three that change the screen outcome

```
2026-07-27 SIEMENS    rsA=70.07 rsB=69.96  passes_all True->False   (passive)
2026-07-28 ICEMAKE    rsA=70.08 rsB=69.91  passes_all True->False   (passive)
2026-07-31 DIAMONDYD  rsA=70.05 rsB=69.99  passes_all True->False   (passive)
```

Against the **persisted live record**, not the recompute:

| | persisted `rs_rank` | persisted `passes_all` | `is_vcp` | pivot | funnel bucket |
|---|---|---|---|---|---|
| SIEMENS 07-27 | 70.02 | **t** | f | — | WATCH (no base) |
| **ICEMAKE 07-28** | **70.40** | **t** | **t** | **899.00** | close 855.80 → 0.9520·pivot → **ON_DECK** |
| DIAMONDYD 07-31 | 69.86 | **f** | — | — | — (did not pass live; recompute artifact) |

So of the three, two agree with what the live screen actually did, one does not, and **ICEMAKE
2026-07-28 is a genuine served funnel candidate that a dividend-aware RS plane would have dropped.**
**[computed]**

`strategy.paper_positions` holds no row for SIEMENS, ICEMAKE, DIAMONDYD, MAHLOG, GLOSTERLTD,
ABSLAMC, BFINVEST, REDINGTON or DLINKINDIA — so **0 live entries** were affected. **[computed]**

### 3.5 A stale premise found in passing

`TrendTemplateService`'s class javadoc justifies reading bhavcopy rather than `candles` because the
candle store's "dense recent-year history only covers the ~100 subscribed/backfilled names". Today,
**1,760 of the 1,813 screened symbols carry ≥252 daily candle bars** since 2025-06 (1,772 ≥200; only
41 under 100). Whatever the right answer on plane unification is, *coverage* is no longer the
argument against it. Not acted on here — flagged. **[computed]**

---

## 4. Task A — what was built

`PlaneDivergenceProbe` (market-data-service, `screener/minervini/`).

**Trigger — an existing surface, not a new job.** It runs inside `MinerviniScheduler.runQuietly`,
immediately after the screen + geometry are persisted. That is the only moment at which the screen,
the geometry and therefore the funnel are all fresh for the date; a separate cron would have to
re-derive when that is true. It rides the existing `BhavcopyBackfillCompleted` trigger, the existing
`NtfyClient`, and the existing fail-soft discipline (a probe failure can never fail a screen).

**What it compares.** Both planes, recomputed for every 8-gate passer over the **same 420-day
window `SCREENER_BASE_CTE` binds**, joined on the bhavcopy session date, reporting `max |1 −
planeB/planeA|` per symbol. Plane A goes through `AdjustedEquityDailySql.factorLateral` — the
single shared definition of the CA rule, called rather than pasted, as its javadoc requires. Plane B
applies `EquitySplitBonusAdjuster`'s source-aware rule (`source='BHAVCOPY'` bars only). Cost ≈ 8.5 s
on the live box for ~250 passers.

**Candidate, not merely divergent.** Membership is asked of `MinerviniFunnelService` itself
(buyable ∪ on-deck; `watch` is not a candidate) rather than re-deriving the band arithmetic, so the
two cannot drift.

**Two floors, because one is wrong.** Measured over the 22 screen dates:

| floor | divergent CANDIDATE name-dates | dates hit | symbols |
|---|---|---|---|
| ≥ 0.5% (report) | **103** | 20 / 22 | 55 |
| ≥ 1% | 75 | 20 / 22 | 27 |
| ≥ 2% | 61 | 20 / 22 | 13 |
| ≥ 3% | 19 | 13 / 22 | 4 |
| **≥ 5% (page default)** | **7** | **7 / 22** | **INDOBORAX, MAHLOG** |

A page on the report floor is ~4.7 alerts every evening — alarm fatigue, not a signal. The 5% page
floor fires on ~1 evening in 3, on special-dividend-scale distortions only. Both are tunable
(`artha.minervini.plane-divergence.min-pct` / `.alert-pct` / `.lookback-days` / `.enabled`).

That MAHLOG is one of the two names the page floor selects is a useful independent check: MAHLOG is
also the name §3.2's BALL counterfactual finds *gaining* `passes_all` on six separate name-dates.

**Surfaces.** A structured `log.info` every run; `ntfy` at `high` only on `alertingCandidates > 0`;
and `GET /api/v1/market/screener/minervini/plane-divergence?asOf=` (typed record, rides the existing
`/api/v1/market/**` gateway allowlist) for "which names, how much, worst bar".

**No plane's prices changed.** Teaching either plane about dividends stays the HOLD-tier owner
decision #1272 §6.2 scoped.

### 4.1 This does NOT contradict #1272's "0 were a candidate"

They measure different things and both are right:

- **#1272**: on the *ex-date session* — the single session where the crossover's previous-bar leg is
  cross-plane — was a divergent symbol a candidate? **0.** That is about the entry trigger.
- **Here**: is a served candidate's price history read two ways anywhere in the **420-day window the
  screen's own gates read** (MAs 200, 52-week 252, RS 252)? **103 name-dates.** That is about the
  gates, and it is the exposure §3 just measured as real.

### 4.2 The blind spot, stated because it is measured

The probe reports the **divergent symbol**. §3.4's harm landed on **ICEMAKE**, which is *not*
divergent — it was displaced by someone else's error through the universe-relative percentile. A
per-candidate probe is structurally incapable of seeing that. Catching it needs a two-plane rerank
of the whole universe every evening — roughly the 20-second job §3 ran — which is a bigger,
separate decision. Recorded here, not built.

---

## 5. Testing

| test | what fails if the mechanism breaks |
|---|---|
| `PlaneDivergenceProbeIntegrationTest` | five seeded passers give **opposite** answers on three axes: divergent vs byte-identical planes, served candidate vs WATCH, over vs under the page floor |
| `MinerviniSchedulerTest.pagesOnlyWhenAServedCandidateClearsThePageFloor` | three runs of the same screen: big-but-not-served → silent, served-but-under-floor → silent, served-and-over-floor → pages |
| `MinerviniSchedulerTest.aProbeFailureIsFailSoft` | a throwing probe must not fail or silence the screen |

**Red-proofs** (each through the `test` lifecycle phase, never a bare `surefire:test`):

| break | result |
|---|---|
| plane axis — compare plane A against **itself** (`bp.b_close` → `a.a_close`) | **RED**: `reportsDivergentPassersAndFlagsOnlyTheServedCandidate:114` (`containsExactlyInAnyOrder`) + `alarmCountsOnlyServedCandidates:145` |
| candidate axis — `servedSymbols()` also returns the WATCH bucket | **RED**: `…:120` (`PDVWATCH.candidate()` isFalse) + `…:146` |
| page-floor axis — `isAlerting()` drops the `alertPct` comparison | **RED**: `MinerviniSchedulerTest.pagesOnlyWhenAServedCandidateClearsThePageFloor` |

Contract: spec re-captured with `-Dtest=ContractCaptureTest` **only** (never during a full verify).
Diff is purely additive — 1 path, 2 schemas, **0** schemas changed, **0** `required` stripped.

---

## 6. Open doubts

1. **The window is 22 screen dates, all July.** Same bound as #1272. Indian dividend season peaks
   Jun–Aug, so July is arguably the worst month — which cuts in the finding's favour, but that is
   an argument, not a measurement.
2. **The B32/BALL counterfactual is not Option A.** It substitutes what the *candles plane happens
   to hold*, which is Kite's dividend adjustment for the 32 names it has re-fetched. A real Option A
   would adjust **every** dividend in `marketdata.dividends` (4,743 rows, 1,664 symbols) on every
   symbol, which is a strictly larger perturbation than anything measured here. §3's numbers are a
   **lower** bound on Option A's rank churn, not an estimate of it.
3. **3.31% of bar rows fall back to plane A** because `candles` has no bar on that bhavcopy session.
   Concentrated in 41 thin names, but not decomposed. A fallback on a *lag leg* silently mixes
   planes within one return; I did not measure how many of the 34 crossings involve one.
4. **The `ret()` rounding is not bit-identical to Java.** `MinerviniGates.ret` is
   `divide(past, 6, HALF_UP)`; the replication divides at Python `Decimal` context precision then
   quantizes to 6dp HALF_UP. Double-rounding can differ in the last place. It applies identically to
   both planes, so it cannot bias the A/B, but it is why the calibration is 98.18% and not 100%.
5. **DIAMONDYD 2026-07-31 disagrees with the persisted record** (recompute-A said 70.05 / passes;
   the live row says 69.86 / fails). It is counted in the "3" for consistency of method, but only
   SIEMENS and ICEMAKE are corroborated by the live record. The honest headline number is **2**, of
   which **1** was a served candidate.
6. **§3.5's coverage claim counts bar ROWS, not their quality.** 1,760 symbols have ≥252 daily
   candle bars; I did not check those bars are correct, dense across the specific 420-day windows,
   or free of the very rewrites §1.3 documents. It falsifies the javadoc's stated premise; it does
   not establish that a plane move is safe.
7. **The probe's 8.5 s cost was measured on one screen date** (2026-07-28, ~250 passers) on a box
   simultaneously running two other heavy queries. A day with many more passers, or a cold cache,
   was not measured.
8. **`marketdata.dividends` has both an NSE and a BSE row per event**, with different `amount`
   parsing (the BSE row's `amount` is null on both worked examples). Nothing here depends on it —
   the probe never reads `dividends` — but any future Option A must pick a source deliberately.
9. **I did not verify the deployed jar.** Java citations are read off `origin/main` @ `23f92ba0`;
   the DB numbers are authoritative live state. Whether `ay-market-data-service` currently runs
   current `main` was not probed.
