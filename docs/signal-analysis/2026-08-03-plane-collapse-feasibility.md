# Collapsing the equity screen onto ONE price plane — feasibility (2026-08-03)

**Scope:** read-only investigation. No production code, SQL, config, YAML, migration or javadoc
touched. Nothing armed. All DB access read-only against the LIVE `artha` database, re-measured at
write time (not carried over from the sibling investigations #1272 / #1274).

Sibling docs, deliberately NOT re-derived here: `docs/signal-analysis/2026-08-03-minervini-live-plane-split.md`
(the CA-plane question) and `docs/signal-analysis/2026-08-03-m6-m9-impact-measurement.md`.

---

## 0. Verdict first

**DEFER — the coverage claim that motivated the split is genuinely stale, but the plane it points at
is not yet fit to replace it, for a reason the stale claim never mentioned.** Tier: **HOLD** if it is
ever recommended (it changes which names the screen admits). Nothing to build today.

| the brief's question | answer |
|---|---|
| Is the "~100 names" coverage claim stale? | **YES — decisively.** 1,759 of 1,813 screened symbols carry ≥252 daily candle bars. The javadoc is wrong by a factor of ~17 **[computed]** |
| Does that make the planes interchangeable? | **NO.** Coverage ≠ completeness. Only **429 of 1,813** have a *complete* trailing-252-session candle window; 1,384 are short, mean 8.47 sessions **[computed]** |
| Is the only other difference the dividend doctrine? | **NO** — six differences, §2. The decisive one is not dividends **[computed]** / **[sourced]** |
| Verdict | **DEFER.** Re-ask when §4's two repairs have landed and held |

**The one-line reason:** the bhavcopy plane is **internally consistent** — all 1,813 names carry the
same (unadjusted-for-dividends) basis. The candles plane is **internally inconsistent**: 132 of the
1,813 are Kite-sourced and dividend-back-adjusted while the other ~1,638 are bhavcopy projections and
are not. RS-rank is **universe-relative**, so a mixed-basis plane corrupts the ranking of every name,
not only the 132. Collapsing onto candles today would trade a *uniform bias* for a *differential* one
— which is strictly worse for a comparative screen.

---

## 1. Question 1 — is the coverage claim stale? (yes; and coverage is the wrong test)

### 1.1 The claim

`TrendTemplateService.java:15-19` justifies reading `nse_eod_bhavcopy` on the ground that `candles`'
*"dense recent-year history only covers the ~100 subscribed/backfilled names"*. Repeated at `:99-106`,
and echoed by `ManasScreenService.java:15-17` and `DailyBarReader.java:11-12`. **[sourced]**

### 1.2 Breadth — the claim is stale

```sql
WITH universe AS (SELECT DISTINCT symbol FROM marketdata.minervini_screen_results), depth AS (
  SELECT u.symbol, count(c.bucket) FILTER (WHERE c.bucket >= now() - interval '400 days') AS bars_400d
  FROM universe u LEFT JOIN marketdata.candles c
    ON c.tradingsymbol=u.symbol AND c.exchange='NSE' AND c."interval"='1d' GROUP BY u.symbol)
SELECT count(*) screened, count(*) FILTER (WHERE bars_400d>=252) ge252,
       count(*) FILTER (WHERE bars_400d=0) zero, round(avg(bars_400d),1) mean, min(bars_400d) min
FROM depth;
```
```
 screened | ge252 | zero | mean  | min
     1813 |  1759 |    0 | 260.9 |  38
```

**Zero screened symbols have zero candle bars.** The "~100 names" figure is a stale *depth* claim
(~11 y of Kite history) doing duty as a *breadth* claim. **[computed]**

### 1.3 Completeness — and this is the test that fails

Breadth is necessary, not sufficient: a 50-bar SMA and a 252-bar RS window need the *same sessions*,
not merely *enough rows*. Over the trailing 252 NSE sessions (2025-07-24 → 2026-08-03):

```
 window_start | window_end | symbols | candles_complete | candles_short | mean_missing | worst_missing
 2025-07-24   | 2026-08-03 |    1813 |              429 |          1384 |         8.47 |           214
```

Distribution of missing sessions per symbol:

| missing sessions | symbols | what it is |
|---|---|---|
| −1 | 133 | candles has one MORE session than bhavcopy EQ/BE |
| 0 | 296 | complete |
| 3 / 4 / 25 | 3 | idiosyncratic |
| **5** | **1,340** | the June projection hole (§1.4) |
| **213–214** | **41** | CA-rebuild casualties (§1.5) |

**[computed]**

### 1.4 The 5-session projection hole is real, and it is exactly the dates named

Per-session coverage of the screened universe, candles vs bhavcopy:

```
 trade_date | n_bhav | n_cand | delta
 2026-06-11 |   1813 |   1772 |   -41
 2026-06-12 |   1813 |    430 | -1383
 2026-06-15 |   1813 |    429 | -1384
 2026-06-16 |   1813 |    429 | -1384
 2026-06-17 |   1812 |   1771 |   -41     <- NOT holed
 2026-06-18 |   1813 |    429 | -1384
 2026-06-19 |   1812 |    429 | -1383
 2026-06-22 |   1813 |   1772 |   -41
```

Five sessions — **2026-06-12, 06-15, 06-16, 06-18, 06-19** — lost ~1,384 symbols each. 06-17 sits
inside the range and is intact, so this is five discrete failures, not one outage. Bucketed over
2025-08-01 → 2026-08-03 (246 sessions): 5 severe (<50% covered), 202 partial (mean −41.1), 39 fully
covered. **[computed]**

**It has closed going forward.** Every session from 2026-06-25 to 2026-08-03 has delta exactly 0.
The hole is a fixed historical scar, not an ongoing leak — but it sits inside every window the screen
would read. It leaves a 50-bar window around **2026-08-28** and a 252-bar window around **mid-2027**,
unless backfilled. **[computed]**

### 1.5 The 41 gutted symbols are CA-rebuild damage — 41 of 41

The persistent −41 baseline is a fixed set of symbols holding only ~38 of 252 sessions. Testing them
against `marketdata.corporate_action_events`:

```
 gutted_symbols | with_any_ca_event | with_failed_ca_event
             41 |                41 |                   41
```

**Every single one has a FAILED or REFRESH_FAILED corporate-action event.** `CorporateActionJob`
purges a symbol's candle series (`candles.purgeSymbol`) before re-fetching it from Kite; when the
re-fetch fails, the series stays gutted and nothing retries it. Current event states, re-measured:

```
 status         | count | latest (IST)
 DETECTED       |   248 | 2026-07-17 17:18:19
 FAILED         |   162 | 2026-07-31 16:55:43
 RESOLVED       |    25 | 2026-06-23 16:46:04
 REFRESH_FAILED |    13 | 2026-07-31 17:16:04
```

175 of 448 events ended in a failure state and the newest `RESOLVED` is over six weeks old. This is
the same class the `ca-rebuild-wipes-caggs` memory topic records (13 symbols, caggs, fixed #1151 /
#1156) but measured on the **base `candles` table**, where the population is 41. **[computed]**

⚠️ This finding is independent of the plane question and outlives it: **41 screened symbols currently
have a materially incomplete candle series, and any consumer reading candles for them is already
wrong.** Chipped separately — it is not a reason to keep two planes, it is a bug.

---

## 2. Question 2 — every difference between the planes, not just the motivating one

| # | axis | `nse_eod_bhavcopy` | `candles`@1d | material? |
|---|---|---|---|---|
| 1 | **dividend doctrine** | NOT adjusted | Kite-sourced rows ARE dividend-back-adjusted; bhavcopy-projected rows are not | **YES — and asymmetric, §2.1** |
| 2 | **split/bonus mechanism** | SQL lateral `exp(Σ ln ratio)` over `eod_corporate_actions`, applied to EVERY row | Java `EquitySplitBonusAdjuster`, applied ONLY to `source='BHAVCOPY'` rows | agree to ~2 paise (#1272 §3.5); two implementations of one rule |
| 3 | **session completeness** | complete | 1,384 / 1,813 short | **YES — §1.3, the blocker** |
| 4 | **retro-mutability** | late-BACKFILLED (rows for Apr–Jun trade dates still arriving in Aug) | retro-REWRITTEN (whole series replaced by `upsertAuthoritativeAll`) | **YES — §4.2** |
| 5 | **ingest timing** | one landing, post-close | TWO landings per day: bhavcopy projection then a Kite revision ~40 min later | **YES — §2.2** |
| 6 | **provenance / universe key** | `series IN ('EQ','BE')`; carries delivery data | `source` ∈ {BHAVCOPY, KITE, BACKFILL}; no `series` concept | minor — §2.3 |

### 2.1 The dividend divergence is confined to the Kite-sourced subset

Comparing both planes on 2026-02-02 after neutralising split/bonus with the same factor the screener
applies, split by candle `source`:

```
  source  | symbols | diverge_gt_0p5pct | diverge_gt_2pct | worst_pct
 BHAVCOPY |    1638 |                 2 |               2 |     90.00
 KITE     |     132 |                39 |              35 |     12.79
 BACKFILL |       2 |                 0 |               0 |      0.00
```

The divergence is **not spread across the plane** — it is 35 of the 132 Kite-sourced names, up to
12.79%, and essentially absent from the 1,638 bhavcopy-projected ones. This reproduces the "32 of
1,813 by 2–12%" from #1272/#1274 on a different as-of date. **[computed]**

**Why this is the decisive fact, not a detail:** the RS legs rank each name *against the universe*
(memory topic `swing-universe-nse-only-decided` — RS-rank is universe-relative, which is why the
BSE-only proposal was declined). A plane on which 132 names carry dividend-adjusted history and 1,681
do not does not merely mis-price those 132 — it shifts the percentile of **every** name in the
ranking. The bhavcopy plane's dividend bias is uniform and therefore cancels in a comparative
ranking; the candles plane's is differential and does not.

⚠️ The 2 BHAVCOPY-sourced outliers (one at 90%) are NOT explained here — see open doubt 4.

### 2.2 Candles revises itself intra-evening; bhavcopy lands once

Landing times for the 2026-08-03 bar (IST):

```
 plane / source            | rows | earliest             | latest
 nse_eod_bhavcopy          |    - | 19:08:43             | -
 candles source=BHAVCOPY   | 2545 | 19:08:47             | 19:08:50
 candles source=KITE       |  161 | 19:45:00             | 20:05:46
```

The bhavcopy projection lands ~4 s after the bhavcopy row (same job). The Kite 1d rows land ~40 min
later and, because Kite writes via `upsertAuthoritativeAll` (REPLACE) while bhavcopy projection is
DO-NOTHING, **the Kite write overwrites the projected row for those names** — which is the mechanism
that creates §2.1's divergence. A consumer reading candles between 19:08 and 20:05 sees a
mixed-freshness plane. **[computed]**

### 2.3 Universe membership is very nearly identical

Restricted to `instruments.instrument_type='EQ'` since 2026-07-01: 2,595 names in candles, 2,722 in
bhavcopy EQ/BE, and only **5** present in candles but not in bhavcopy EQ/BE. Membership is not an
obstacle — but note `candles` has no `series` column, so an equivalent of `series IN ('EQ','BE')`
would have to be reconstructed from `instruments`, and delivery-percentage data exists only on the
bhavcopy side. **[computed]**

---

## 3. Question 3 — which consumers would move

Map re-derived from the code this session. Four consumers read the shared adjusted-bhavcopy plane:

| consumer | entry point | what moving would change |
|---|---|---|
| **RS legs / trend template** | `TrendTemplateService.java:109` → `AdjustedEquityDailySql.SCREENER_BASE_CTE` | The 8 trend-template gates and the RS rank. Gate 4 is `close > sma50` (strict `>`, `MinerviniGates.java:42`). Highest-impact mover: a 252-bar RS window on 1,384 short series, plus §2.1's mixed basis |
| **`ManasScreenService`** | `ManasScreenService.java:168-172`, same CTE | Same funnel-admission shift, second strategy family |
| **`EquityReturnsService`** | `EquityReturnsService.java:82-105` | Returns at rn ∈ {1,2,6,22,127,253}. The rn=253 leg reaches the full holed window; the rn=1/2 legs would not move |
| **VCP pivot geometry** | `DailyBarReader.java:21` → `GEOMETRY_SYMBOL_SQL`, called by `MinerviniGeometryService.java:22,30` and `ManasGeometryService.java:25,34` | Pivot/cheat levels are highs/lows over the window — a **missing session can delete the pivot bar itself**, which moves the level the live entry gate crosses, not merely a mean |

**A side effect worth naming:** the pivot is currently the one genuinely cross-plane *comparison* in
the live chain (`SwingBatchEngine` compares a candles-plane `px` against a bhavcopy-plane `pivot` —
#1272 §1.3). Collapsing onto candles would **close** that seam. That is a genuine argument in favour,
and it is why the verdict is DEFER rather than SKIP. **[sourced]**

Not movers (raw, deliberately unadjusted single-day reads): `RegimeService`, `BreadthService`,
`EquitySectorService`, `EquityIndexContributionService`, `EquityDeliveryService`,
`PreOpenEquityScanService`, `EquityContextRepository`, `BhavcopyCloseCanary`. `EquityReturnsService.java:71-81`
records why: they compare against the exchange-published, already-ex-adjusted `prev_close`, so
adjusting one side would double-count. **[sourced]**

`EquityBreadthDailyRepository.java:47` reads bhavcopy raw with no lateral — the still-open chip
`task_53ce441b`. It is a bhavcopy-plane bug, unaffected by this question either way.

---

## 4. Question 4 — what would break or shift

### 4.1 Admissions would change, in the permissive direction (~1%)

Measured in #1272 §3.3–§4 and not re-derived here: 38 of 3,814 clean name-dates (0.996%) are admitted
by the bhavcopy screen while already at-or-below their 50-day mean on the candles plane, and all 38
have **byte-identical closes** on the two planes — the disagreement is entirely in the 50-bar mean.
That is the §1.3 completeness gap expressing itself, which is the same defect this document is
recommending be fixed *first*. **[sourced from #1272; not re-measured]**

### 4.2 Moving onto a retro-mutable plane has its own consequences

`candles` is not merely mutable, it is **whole-series** mutable: `CorporateActionJob` purges and
re-fetches, and one symbol's entire July series was rewritten on 2026-07-31. Consequences a move
would inherit:

1. **Screen results stop being reproducible.** A persisted `minervini_screen_results` row already
   cannot be recomputed from current tables (#1272 §5.1: BHEL 2026-07-10 recomputes to 396.0402
   against a persisted 395.0954, and no window length 45–60 reproduces it). Moving to candles does
   not fix that — it makes the rewriting *coarser*, since a rewrite replaces the whole series at once.
2. **A purge can empty a series with no retry** — §1.5, already true of 41 names today. On the
   bhavcopy plane a failed CA fetch cannot delete history, because nothing purges bhavcopy.
3. **Backtest/live comparability** would change: `MinerviniBacktestService`, `ManasAroraBacktestService`
   and `MinerviniHitRateService` already read `candles`@1d **raw, with no adjuster at all**. Moving the
   *live* screen to candles-with-adjuster narrows one gap and widens another — the live screen and the
   backtest would then read the same table under two different CA rules, which is a more confusing
   failure than two frankly different tables.
4. ⚠️ **The measurement trap fires here too:** because both tables are retro-mutable, any A-vs-B
   validation of a move must gate on `fetched_at`, which is an UPSERT timestamp, not first-seen. On
   #1272 four apparent verdict flips dissolved under that gate.

---

## 5. Question 5 — verdict

**DEFER. Tier HOLD if ever proposed.**

The motivating javadoc is stale and should eventually be corrected (already flagged by #1274 — not
touched here, per this investigation's read-only scope). But "the reason was wrong" is not "the
conclusion was wrong": the planes are still not interchangeable, for a reason the javadoc never
stated.

**Two repairs are prerequisites, and both are worth doing on their own merits regardless of whether
the planes ever collapse:**

1. **Repair the 41 CA-gutted series** (§1.5) and stop `CorporateActionJob` leaving a purged symbol
   unrepaired on a failed re-fetch. This is a live defect today.
2. **Backfill the 5-session June hole** (§1.4) into `candles`. The bhavcopy projection is
   `ON CONFLICT DO NOTHING`, so a re-run over those five dates fills the gaps without clobbering any
   Kite-owned bar — cheap and low-risk, though I have NOT verified the projection is re-runnable
   over an arbitrary past window (open doubt 2).

**Even with both done, the §2.1 mixed-basis problem remains and is the real decision**, and it is a
doctrine question for the owner rather than an engineering one: *should the swing screen's history be
dividend-adjusted at all?* Adjusting it uniformly would be defensible; adjusting 132 of 1,813 names
is not. Today the bhavcopy plane answers "no, uniformly", which is coherent. Re-ask this question
only after repairs 1 and 2 have landed and held for a full 252-session window.

---

## 6. Open doubts

1. **The 0.996% admission-shift number is carried from #1272, not re-measured here.** I re-derived
   the coverage, completeness, CA-damage, dividend-divergence and timing numbers at write time, but
   re-running the full as-of-date screen replay was out of proportion for a feasibility call. If that
   number becomes load-bearing for a build decision, re-measure it.
2. **I did not verify that the bhavcopy→candles projection can be re-run over an arbitrary past
   window.** §5's repair 2 assumes `BhavcopyBackfillService`'s projection is invocable for a chosen
   historical date range. If it is only reachable via the daily path, that repair is larger than
   stated.
3. **I did not establish WHY the five June sessions failed to project.** Five discrete failures with
   an intact 06-17 between them does not look like one outage, and I did not read the ingest ledger
   (`marketdata.ingest_runs`) for those dates. The repair may be safe without knowing; the *recurrence
   risk* is unknown without it.
4. **Two BHAVCOPY-sourced symbols diverge >2% in §2.1, one by 90%.** That contradicts the expectation
   that a bhavcopy-projected candle equals its bhavcopy row, and I did not chase it. It is 2 of 1,638
   so it does not move the verdict, but it is unexplained and could be a missing corporate action.
5. **`minervini_screen_results` only reaches back to 2026-07-03**, so "the screened universe" is
   defined by one month of screens. A symbol that entered or left the universe earlier is invisible
   to every count in this document.
6. **The 41-gutted / FAILED-CA link is correlational at 41-of-41, not traced through logs.** The
   purge-then-failed-refetch mechanism is read from code (`CorporateActionJob`), not observed in a log
   for a specific symbol. The correlation is total, which is strong, but I did not watch it happen.
7. **I did not probe the deployed jars.** Java citations are read off `origin/main` @ `d0ae26b9` and
   assume `ay-market-data-service` is current on main.
