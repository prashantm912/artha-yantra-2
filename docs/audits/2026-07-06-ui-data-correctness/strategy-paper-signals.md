# UI Data-Correctness Audit — Strategy / Paper / Signals / Scalper cluster

**Date:** 2026-07-06 ~11:00 IST (market live). **Scope:** the strategy-signal-service-backed pages —
`/signals` (+`/:book`), `/paper` (+`/:book`), scalper cockpit, `/journal`, `/orders`, `/strategies`,
`/strategies/graduation`, `/signal-rejections`. **Method:** page `.tsx` + `api/*.ts` → endpoint, then
API response (in-container `wget http://localhost:8082/api/v1/...`, bypassing gateway auth) reconciled
against DB truth (`artha` DB, `strategy` schema) and the documented formulas. **Read-only** except this file.

## Live data snapshot (the basis for every reconciliation below)

| Table | State |
|---|---|
| `paper_account` | 5 books: scalper/minervini/manas-arora/manual/other, each `starting_capital=150000`, `cash=150000` |
| `paper_positions` | 2 rows, both OPEN, both `manas-arora` book: `SENORES` (13@1382.59) + `SBCL` (21@794.45). 0 CLOSED. |
| `paper_orders` | 2 FILLED, linked to signals 28/29 (SENORES/SBCL) |
| `signals` | 10 rows, ALL `generated_at = 2026-07-03 00:00 IST`, ALL `TAKEN`/`ENTRY`, swing (manas/minervini). `scalper_detail` null on all. |
| `signal_rejections` | 1177 rows, fresh (latest 10:43 IST **today**); top rail volume-floor (886), time-window (252) |
| `journal_entries` | 1 (a QA test entry from 06-14) |
| broker orders | not wired → `funds.status=NOT_CONFIGURED`, empty books |

**Reconciliation of the manas book (the only book with positions):**
- Notional: SENORES 1382.59×13 = 17,973.67; SBCL 794.45×21 = 16,683.45 → Σ = **34,657.12** = `capitalUsed` ✓
- `cash = startingCapital − capitalUsed = 150000 − 34657.12 = 115,342.88` = API `cash` ✓
- IST conversion: DB `opened_at 2026-07-05 11:18:16 UTC` → API `2026-07-05T16:48:16.505+05:30` ✓

## Per-page verdicts

### /strategies/graduation — CORRECT
`GraduationService.metrics()` math matches CLAUDE.md exactly: `winRate = wins/trades` (0-1 fraction),
`profitFactor = grossProfit/grossLoss` (null when no losses), `expectancy = net/trades`,
`maxDrawdownPct` = peak-to-trough % of running peak on a `base-capital=100000` curve, `stage=TAKE_ELIGIBLE`
iff all 4 criteria pass. `GraduationPage.tsx` renders `winRate × 100` under a **Win%** header (audit M21 fix
present, line 124), PF/expectancy/DD to 2dp, em-dash for null. All 39 strategies read 0 trades → all `PAPER`,
`profitFactor n/a`, `maxDrawdownPct` passes (0 ≤ 25). Board is correct. (Known, pre-logged, not re-flagged:
the attribution join has no position→strategy FK — memory H5 — but moot at 0 trades.)

### /signal-rejections — CORRECT + freshest page
API row 1177 byte-matches DB (rail `volume-floor`, operand 2860, threshold 125000, reason "volume < 125000",
composite 0.6038). `rail-counts` API == DB `GROUP BY blocking_rail` exactly. `dot-health` live (asOf 10:47 IST,
session=true, 40 rows inspected, `breadth` alive; `iv_rank`/`dow`/`fii` dead — the pre-known dead-dot set, not new).
Composite 0.6038 > threshold 0.6000 yet blocked at volume-floor = correct gate short-circuit (never reaches
composite stage). `RejectionsPage` uses `+05:30` IST day bounds, defaults to today, null/NaN-safe formatting.

### /journal — CORRECT
1 DB entry, API returns it verbatim. Auto-journal (AFTER_COMMIT on `PaperPositionClosed`) correctly has NOT
fired for the 2 OPEN manas positions (no close yet). Clean.

### /orders — CORRECT (degraded, as designed)
Broker not wired → `DisabledOrderGateway`: `funds.status=NOT_CONFIGURED`, empty orderbook/positions/tradebook.
Page shows clean empty states, no fabricated numbers.

### /signals (+/:book) — CORRECT numbers; two UX notes
Composite math verified end-to-end: signal 20 SENORES — single `VOLUME_RATIO` w=1, score 0.40693173,
`weightDenominator=1` → composite = Σ(w·s)/Σw = 0.4069 ✓; signal 29 SBCL — score 1, composite 1 ✓, gate
`all` with 3 passing children ✓. `book=manas-arora` filter returns exactly signals 28/29 (per-book isolation OK).
IST day-bounds correct. Score rendered to 4dp, entry to 2dp, em-dash for null. **Notes (not wrong numbers):**
1. **Live view is empty today.** In Live mode the query uses *today's* (07-06) IST bounds → 0 rows (all signals
   are 07-03). Correct given no signal fired today, but the operator must switch to Historical→pick 07-03 to see them.
2. **Strategy column shows a UUID fragment.** `s.strategyId ?? s.strategyVersionId?.slice(0,8)` — the API emits
   `strategyVersionId` only (no `strategyId`, no `strategyName` on the row), so the column reads e.g. `b73429bf`
   instead of a readable strategy name. Cosmetic, same for all rows.

### /paper (+/:book) — CORRECT reconciliation; ONE real correctness gap (swing MTM)

All arithmetic reconciles (see manas reconciliation above). Per-book risk isolation is CORRECT: `?book=scalper`
returns only scalper's 7 keys (incl. `daily_profit_target`), `?book=manas-arora` only manas's 6 — no bleed.
`GraduationPage`/PaperPage `pct`/`money`/`toneClass` formatting correct. The positions table honestly shows
`—` for Mark/Unrealized when the mark is null (line 316-318).

**FINDING P-1 (LOW→MED, real): swing paper books show fabricated breakeven equity / Day P&L = 0.**
`PaperAccountService.unrealizedTotal()` (line 88-97) marks each open position at
`lastTick.lastPrice(...).orElse(pos.avgEntryPrice())` — i.e. **entry price when no live tick exists** — so a
swing equity the live feed never ticks (SENORES/SBCL confirmed: market-data `/ticks/latest` returns `{}`)
contributes **0** to unrealized. Result: the account header shows `equity = 150000.00` (= starting capital),
`unrealized = 0.00`, `dayPnl = 0.00` — i.e. "you are exactly flat" — while the **positions table** (fed by
`PaperService.toPositionDto`, which uses `.orElse(null)`, no fallback) correctly shows Mark/Unrealized = `—`.
The two disagree: the header fabricates breakeven, the table admits it doesn't know. `LastTickReader` reads
ONLY the Redis `ticks:last` hash — it never falls back to the daily candle — so a swing book with no intraday
ticks will read equity == starting capital and dayPnl == 0 **for the whole holding period**, only becoming
truthful when the EOD batch closes the position (which settles correctly at the passed daily-close price).
- **Severity today: LOW** — the latest daily candle for both symbols is the 07-03 entry-day close (no
  07-04/05/06 bar exists yet), so unrealized genuinely ≈ 0 right now; the number is not *currently* wrong.
- **Severity forward: MED** — once a fresh daily close exists (post-Monday batch) but before any live tick,
  the header will still show breakeven while true daily-close unrealized is non-zero. It corrupts the
  forward-paper equity/dayPnl the owner watches for the reliability sign-off.
- **Fix direction:** for a non-ticking swing book, mark from the latest daily candle close (or surface an
  explicit "unpriced" state in the header, matching the positions table), rather than coalescing to entry.

**Note P-2 (informational): the all-books aggregate (no `?book`) sums 5 books incl. `manual`+`other`**
→ `startingCapital=750000` (5×150k). Not user-reachable via `/paper` (the page always scopes: `book = bookParam
?? 'scalper'`), so no visible mismatch — but any future "all books" header would over-count vs the 3-book selector.

### Scalper cockpit — CORRECT (empty feed, render-safe)
Both cockpits (`CockpitPage.tsx`, `ScalperCockpitPage.tsx`) query `useSignals('ACTIVE')` → API returns
`{items:[]}` (DB has 0 ACTIVE signals; all 10 are TAKEN swing with `scalper_detail=null`). Empty state renders
"No live signals — publish a strategy" with no NaN path: `scalperDetail` is `?? null`-guarded, the
`confluence_aggregate.toFixed(2)` / dots math only runs after a real hydrated `useSignalDetail(id)` signal, mtm/money
helpers short-circuit null marks to `—`. Enrichment binds to the correct field (`detail.data?.scalperDetail` via
GET `/signals/{id}`, per the C-2.6 shape — matches the "STOMP frame may omit it" contract). `scalper_subaccount`
(5 rows × 0.20) is a backend capital-split artifact, not surfaced by either cockpit — no rendering bug.
**Note:** `PaperBookPanel` renders the ALL-BOOKS aggregate (no `?book`) → equity 750000 (5 books incl. manual/other),
same over-count as P-2. The 2 manas positions show there.

### /strategies (list) — **BUG S-1 (data loss): silent 50-row cap hides a live strategy**
`useStrategies()` (`frontend-react/src/api/strategies.ts:71-75`) sends NO `limit`, so the endpoint applies its
default **50**. DB has **73** strategies; API `/strategies` returns 50, `/strategies?limit=200` returns 73 —
**23 rows silently dropped**, no "load more", no count, no indicator. Default order is `updatedAt DESC`, so the
oldest-touched strategies vanish. **Impact:** of the 45 published+enabled strategies (the live set), only **44**
are visible — `scalp-btst-stbt-nifty` (published+enabled) is hidden; the other 22 dropped are drafts. Status
badges themselves are CORRECT (`statusTone` maps lowercase `published`/`archived`/draft; DB `strategy_versions`:
45 published / 121 draft / 12 archived). The defect is missing rows, not wrong badges. **Fix:** set `limit=200`
in `strategies.ts:73` (matching how `signals.ts`/`paper.ts` pass explicit limits). Verified independently:
default→50, limit=200→73, visible published+enabled→44 vs DB 45.

### /strategies/graduation cross-check
The graduation board returns exactly **45** rows = the DB live set (`enabled AND published_version_id IS NOT NULL`)
= the lowercase-`published` version count. This is the CORRECT live set — note it shows all 45 live strategies
even though the /strategies LIST (bug S-1) only shows 44 of them; the two pages disagree because of S-1, not
because graduation is wrong.

## Out-of-scope observation (market-data, noted not owned)
`marketdata.candles` has TWO 1d rows per day for SBCL (e.g. 07-03: 794.05 and 792.85; 07-02: 762.20 and 763.20)
— likely dual `source` provenance (bhavcopy vs tick-agg). Belongs to a market-data audit, flagged for awareness.

## Bottom line
Every money/metric that CAN be checked reconciles to the DB and the documented formula. Graduation,
rejections, journal, orders, signals composite math and per-book isolation are all correct. Two real defects:
- **S-1 (MED, data loss):** `/strategies` list silently caps at 50 of 73 rows → 1 published+enabled strategy
  hidden; the list shows 44 live where graduation shows 45. One-line fix (`limit=200`).
- **P-1 (LOW now / MED forward):** the paper account header marks non-ticking swing positions at entry price,
  fabricating breakeven equity/dayPnl that disagrees with its own positions table (which honestly shows `—`)
  and will understate forward swing P&L between EOD batch settlements.

## Findings ledger
| ID | Sev | Page | Defect | Fix |
|---|---|---|---|---|
| S-1 | MED | /strategies | 50-row default cap hides 23 rows incl. 1 live strategy (44 vs 45) | `strategies.ts:73` add `limit=200` |
| P-1 | LOW→MED | /paper | account header marks unticked swing equities at entry → fake breakeven equity/dayPnl; disagrees w/ positions table | mark from latest daily close, or surface "unpriced" in header |
| P-2 | INFO | (paper aggregate) | all-books sum includes manual+other (750k) vs 3-book selector; not reachable via /paper page | scope aggregate to the 3 real books if ever surfaced |
| Signals-UX | LOW | /signals | Strategy column shows version-UUID fragment (no name); Live view empty today (data, not bug) | emit strategyName/strategyId on the row |
| MD-dup | INFO | (market-data, not owned) | SBCL/others have 2× 1d candle rows per day (dual source) | market-data audit |
