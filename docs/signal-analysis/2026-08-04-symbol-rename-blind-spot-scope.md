# Symbol-rename blind spot in the equity screens — scope measurement

**Date:** 2026-08-04 (measured across the 2026-08-03/04 IST boundary; screen `asOf` throughout is
**2026-08-03**, the latest bhavcopy trade date).
**Type:** investigation only. No production code was written or changed.
**Why:** the owner chose "investigate scope first" over building symbol-continuity mapping, because a
fix touches the instrument identity model and identity is load-bearing across every data source.

---

## Verdict

**61 symbols are invisible to the equity screens right now because they are post-rename successors
with fewer than 252 sessions. The pattern is a trickle with ETF-shaped lumps: ~3 equity renames a
month, plus three single-day fund-house rebrands that alone contributed 25 of the 66 renames
observed.**

Sized against what the screens actually serve: attaching each successor's predecessor history would
move the live Minervini screen from **1776 scanned / 278 candidates** to **1813 scanned / 286
candidates**. Nine renamed names enter the candidate list; one incumbent leaves on the RS-percentile
shift.

**A second, opposite-direction defect surfaced from the same measurement and is arguably more
urgent: the screens keep scoring the dead predecessor.** `JBCHEPHARM` is being served by the live
Minervini endpoint **right now** as `passesAll: true`, 8/8 gates, Stage 2, `rsRank 86.65`, at
₹2408.90 — a price from **2026-07-16**, on a symbol that has not traded for 18 days. That is a false
positive an owner could act on, where the rename gap only produces false negatives.

---

## STEP 0 — premise verification

The brief's premise was `sourced` from another agent's measurement. Re-verified line by line.

| Premise claim | Verdict | Evidence |
|---|---|---|
| Both screens require 252 sessions | **CONFIRMED** | `computed` — below |
| `min-sessions` default 252, applies to both | **CONFIRMED** | `computed` — below |
| GUJGASLTD → GUJENERGY, successor's first bar next trading day | **CONFIRMED** | `computed` — below |
| Nine Axis ETFs stopped 2026-07-02, nine successors started 2026-07-03 | **CONFIRMED** (naming rule only approximate — see below) | `computed` |
| `JBCHEPHARM` stopped with no identifiable successor | **CONFIRMED** | `computed` |
| `LYPSAGEMS` stopped with no identifiable successor | **WRONG** | `computed` — successor is `AURUS` |
| A successor starts from zero sessions, inherits nothing | **CONFIRMED** | `computed` |
| The gate is "sessions since listing" | **IMPRECISE — materially so** | `computed` — it is a 420-day trailing window |

### The 252 gate is real, on both screens — `computed`

`services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/minervini/TrendTemplateService.java:49`
declares `@Value("${artha.minervini.min-sessions:252}")`, bound at `:178` and applied at `:146` as
`AND calc2.sessions >= ?`.
`services/market-data-service/src/main/java/in/arthayantra/marketdata/screener/manas/ManasScreenService.java:47`
declares `@Value("${artha.manas-arora.min-sessions:252}")`, bound at `:195` and applied at `:155`.
The same gate also sits in `MinerviniHitRateService.java:80,158`.

A YAML default is not a deployed value, so the deployed container's env was read:

```
$ docker inspect ay-market-data-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i "minervini\|manas\|screen"
(no output)
```

No override — the 252 default is what runs. **Then probed what the service actually serves**, which
is the decisive check (below): a SQL replica of the screen reproduces the live `coverage` and
candidate count exactly, which it could not do if the deployed threshold differed.

### The gate is a 420-day WINDOW count, not a since-listing count — `computed`

`sessions` is `count(*) OVER (PARTITION BY symbol)` (`TrendTemplateService.java:124`) evaluated over
the `base` CTE, and that CTE is bounded to a trailing 420 **calendar** days —
`services/market-data-service/src/main/java/in/arthayantra/marketdata/equitydaily/AdjustedEquityDailySql.java:77-78`:

```sql
AND trade_date <= ?::date
AND trade_date >  (?::date - 420)
```

The window holds 276 trading sessions as of 2026-08-03, so `sessions >= 252` means "traded at least
252 of the last 276 sessions". Two consequences the premise did not carry:

1. A successor needs ~252 trading sessions (~12 months) to re-enter. The premise's "roughly a year"
   is right.
2. **A predecessor stays in the universe until its own session count decays out of the 420-day
   window** — roughly a month of being scored on a frozen price after it stops trading. This is the
   source of the stale-candidate defect below, and the premise missed it entirely.

```sql
SELECT count(DISTINCT trade_date) FROM marketdata.nse_eod_bhavcopy
WHERE series IN ('EQ','BE') AND trade_date > date '2026-08-03' - 420;
-- 276
```

### GUJGASLTD → GUJENERGY — `computed`

```sql
SELECT symbol, series, trade_date, prev_close, close_price, ttl_trd_qnty
FROM marketdata.nse_eod_bhavcopy
WHERE (symbol='GUJGASLTD' AND trade_date BETWEEN '2026-06-26' AND '2026-06-30')
   OR (symbol='GUJENERGY' AND trade_date BETWEEN '2026-07-01' AND '2026-07-03')
ORDER BY trade_date;
```
```
 GUJGASLTD | EQ | 2026-06-30 |   335.3000 |    327.0500 | 1750950
 GUJENERGY | EQ | 2026-07-01 |   327.0500 |    340.0000 | 2029887   <- prev_close == predecessor close, exact
 GUJENERGY | BE | 2026-07-02 |   340.0000 |    300.0500 |  715025
```

Corroborated by `marketdata.instruments`: `GUJGASLTD` / "GUJARAT GAS" `is_active=f`,
`last_seen_at 2026-06-30`; `GUJENERGY` / "GUJARAT ENERGY" `is_active=t`,
`first_seen_at 2026-07-01`. Both NSE and BSE rows.

### The premise is WRONG on LYPSAGEMS — `computed`

`LYPSAGEMS` last traded **2026-07-13**. `AURUS` first traded **2026-07-14** with
`prev_close = 4.6300`, exactly `LYPSAGEMS`'s last close. Corroborated three independent ways:

- `marketdata.instruments` names: `LYPSA GEMS & JEWEL` → `AURUS GEM CORP` (same industry, and the
  predecessor row went `is_active=f` on `2026-07-12` while the successor's `first_seen_at` is
  `2026-07-14`).
- BSE `scrip_code` continuity (below): one BSE scrip carried both tickers.
- BSE `isin` continuity: same ISIN across the ticker change.

`JBCHEPHARM` genuinely has no successor — no symbol anywhere in the table has a `prev_close` equal
to its final close of 2408.9000 on or after 2026-07-16, and its BSE ISIN `INE572A01036` never
re-appears under another ticker. It is a merger/delisting, not a rename.

### The Axis ETF naming rule does not generalise — `computed`

Nine pairs, all switching on 2026-07-03, established from the data rather than assumed:

`AXISBNKETF→BNKETFAXIS` · `AXISCETF→CONSUMAXIS` · `AXISGOLD→GOLDAXIS` · `AXISHCETF→HEALTHAXIS` ·
`AXISTECETF→ITAXIS` · `AXISNIFTY→NIFTYAXIS` · `AXSENSEX→SENSEXAXIS` · `AXISILVER→SILVERAXIS` ·
`AXISVALUE→VALUEAXIS`

`AXSENSEX→SENSEXAXIS` is not a word reversal, and `AXISBNKETF→BNKETFAXIS` moves a suffix rather than
reversing words. A string-shape heuristic would have mis-paired both. The brief was right to warn
against assuming the rule.

---

## The rename-identification rule

**A pair `(P, S)` is a rename iff all three hold:**

1. `P`'s last row in the 420-day window is at trading-day rank `d_P`, and `P` is absent from the
   latest trade date (it stopped).
2. `S`'s first row in the window is at rank `d_S`, with `1 ≤ d_S − d_P ≤ 5`.
3. `S`'s first-row `prev_close` is **exactly** equal to `P`'s last-row `close_price` (numeric
   equality at the stored 4dp).

Rationale: NSE carries the price series across a symbol change, so the successor's day-1
`prev_close` is the predecessor's day-0 close. A genuine new listing also carries a non-zero
`prev_close` (the issue price), so non-nullness proves nothing — only the *match against a symbol
that just stopped* does. Verified: all 534 symbols that started after the data floor have a non-null,
non-zero first `prev_close`.

### False-positive direction

- **Cannot distinguish a pure rename from a demerger or amalgamation that carries the price
  series.** `TATAMOTORS→TMPV` (2025-10-24) is the Tata Motors demerger; `PEL→PIRAMALFIN` is an
  amalgamation. For the screens the effect is identical — the history is stranded either way — but
  economically the merged series may not be the right series for a demerged entity. Flagged, not
  resolved.
- **Penny stocks are the collision risk.** Three matched pairs sit at ₹0.47, ₹0.56, ₹1.02, where a
  coincidental `prev_close` collision is far more plausible than at ₹3142.20.
- **Two symbols can change identity at once.** Not observed — see the ambiguity check.

### False-negative direction

- **Anything before 2025-06-20 is unmeasurable.** `nse_eod_bhavcopy` starts there.
- **Suspension gaps longer than 5 sessions are excluded by construction.** `PEL→PIRAMALFIN` sat at
  gap 31 (six-week suspension) and my rule drops it. This is a deliberate precision/recall trade —
  widening the gap admits penny-price collisions (see the placebo).
- **A rename where the two symbols trade concurrently.** One candidate observed
  (`IEL`/`HINDNATGLS`), and it is more likely a coincidence than a rename.
- **A rename NSE did not carry `prev_close` across.** None observed; cannot be excluded.
- **Series moves outside EQ/BE** (SME/Emerge boards) are outside the screened universe entirely.

### Rule validation

**Ambiguity — `computed`, zero.** At gap ≤ 5, no successor matched more than one predecessor and no
predecessor matched more than one successor. Every match is 1:1.

**Placebo — `computed`.** Match count by gap band, against the same 534 starters × 157 stoppers:

| Gap band | Width (sessions) | Matches | Matches per session of gap |
|---|---|---|---|
| gap = 1 (rule core) | 1 | **65** | 65.0 |
| gap 2–5 (rule tail) | 4 | 1 | 0.25 |
| gap 6–40 (placebo) | 35 | 2 | 0.057 |
| gap 41–200 (placebo) | 160 | 2 | 0.013 |

At gap = 1 there were 504 candidate `(starter, stopper)` combinations of any price; 65 matched
(12.9%). The signal concentrates ~5000× at gap = 1 relative to the far placebo band, so the rule is
not matching price noise. The two far-band matches include `CYBER-RE`/`KBCGLOBAL` at ₹0.47 — the
expected penny collision, and the reason the gap cap stays at 5.

**Independent cross-check against BSE — `computed`.** `marketdata.bse_eod_bhavcopy` has PK
`(trade_date, scrip_code)` and carries a per-day `isin` and `ticker`. `scrip_code` is a stable BSE
identifier, so a BSE rename is directly observable as *one scrip_code carrying two tickers* — a
signal completely independent of the NSE price-continuity rule.

| Check | Result |
|---|---|
| NSE pairs found by the rule | 66 |
| Confirmed by a shared BSE `scrip_code` | **58** |
| Confirmed by a shared BSE `isin` | **58** (same 58) |
| Neither symbol listed on BSE (unconfirmable) | 6 |
| Both on BSE but no shared scrip_code/ISIN | 2 |

The two unconfirmed are `WORTH→WORTHPERI` and `CREATIVE→CNL`. Both show a **new BSE scrip with a new
ISIN** appearing within a session or two of the NSE switch, and both have decisive independent
evidence of continuity: NSE prices and volumes run straight through the boundary
(`CREATIVE` 675.60 → 739.40 close, `CNL` opens with `prev_close` 739.40, comparable volume), and
`marketdata.instruments` names `CNL` as "CREATIVE NEWTECH" and `WORTHPERI` as "WORTH PERIPHERALS".
The reading is an amalgamation into a new legal entity with a 1:1 swap. **That is an important
finding on its own: ISIN continuity has its own false-negative direction — the ISIN changes on an
entity change even when the price series does not break.**

**Recall check — `computed`, zero misses.** Running the BSE detector *independently* (segment the
per-`scrip_code` ticker history, take consecutive ticker changes) found 232 BSE ticker transitions
across 231 scrip_codes in the window. Of those, 58 have both the old and new ticker present in `nse_eod_bhavcopy`. **All 58
were caught by the NSE rule; 0 were missed.** The other 174 are BSE-only listings or BSE tickers that
differ in text from the NSE symbol, so the NSE rule was never eligible to see them.

Net: on the BSE-checkable population the rule has 58/60 confirmed precision and 58/58 recall. On the
NSE-only population (6 pairs, mostly ETFs) there is no independent evidence either way.

---

## Q1 — how many renames occurred

**66 rename pairs, over 13.5 months — not 1–2 years.** `computed`.

The brief asked for 1–2 years. That is **not measurable from this database**: `nse_eod_bhavcopy`
begins **2025-06-20** and holds 276 trading sessions. Anything earlier is invisible. Reported rate is
therefore ~**59 renames/year**, extrapolated from 66 over 13.5 months (`assumed` extrapolation over a
`computed` count).

```sql
SELECT min(trade_date), max(trade_date), count(*) FROM marketdata.nse_eod_bhavcopy;
-- 2025-06-20 | 2026-08-03 | 854429
```

Population framing (all `computed`, `asOf` 2026-08-03):

| Quantity | Count |
|---|---|
| Distinct EQ/BE symbols in the 420-day window | 2862 |
| Trading on the latest date | 2705 |
| Passing `sessions >= 252` | 2274 |
| Trading today but below 252 sessions | **443** |
| …of which are rename successors | **61 (14%)** |
| …the remaining 382 | IPOs / new listings — not a rename problem |

---

## Q2 — how many are invisible RIGHT NOW

**61 rename successors are trading today with fewer than 252 sessions.** `computed`.

That is the raw count. Three progressively tighter framings, because 61 overstates the cost:

| Framing | Count |
|---|---|
| Rename successors trading today, `sessions < 252` | **61** |
| …that would clear 252 if predecessor history were attached | 57 |
| …that also clear the Minervini price (≥₹30) + liquidity (₹9.375L 50-day turnover) gates | **46** |
| …of the 46, non-ETF equities | **28** |
| …of the 46, ETFs (fund-house rebrands) | 18 |
| **…that would actually become Minervini CANDIDATES** (all 8 gates) | **9** |

The four that stay short even when merged are chains and short-lived predecessors: `ASHIKA`+`ASHIKAG`
= 73 sessions, `AMIRCHAND`+`AEROPLANE` = 83, `YAARI`+`IBULLSLTD` = 236, `BINANIIND`+`BILVYAPAR` = 251.

### The counterfactual is decisive — `computed`

The screen SQL was replicated exactly (CA-adjusted base CTE, all four MA windows, the 52-week
extremes, the weighted RS, the ordinal `i*100/(n-1)` percentile with the symbol tie-break, all 8
gates). The replica reproduces the deployed service **exactly**, which is the proof that the model —
and therefore every number below it — matches production:

| | Live probe (`GET /api/v1/market/screener/minervini`) | SQL replica |
|---|---|---|
| `coverage` | **1776** | **1776** |
| candidates (`passesAll`) | **278** | **278** |
| Manas `coverage` | **2274** | **2274** |

Re-running the identical replica with each predecessor's rows relabelled to its successor symbol:

| | Today | With rename mapping | Δ |
|---|---|---|---|
| Minervini `coverage` | 1776 | 1813 | +37 |
| Minervini candidates | 278 | **286** | **+8 net** |
| Rename successors entering the candidate list | — | **9** | |

The nine: `SIGMAADV` (ex-MEGASOFT), `UFBL` (ex-BARBEQUE), `CEMPRO` (ex-ITDCEM), `CNL`
(ex-CREATIVE), `AEROENTER` (ex-SATINDLTD), `ANTELOPUS` (ex-SELAN), `VIYASH` (ex-SEQUENT),
`HALEOSLABS` (ex-SMSLIFE), `HEALTHAXIS` (ex-AXISHCETF — the only ETF of the nine). Net is +8 not +9
because merging shifts the RS percentile across a 1813-name universe and one incumbent falls below
`rsRank 70`.

### What is being missed is not marginal — `computed`

The 46 screen-eligible invisibles include, by 50-day rupee turnover:

| Successor | Predecessor | Sessions | 50d turnover |
|---|---|---|---|
| `TMPV` | TATAMOTORS | 190 | ₹4177 cr/day |
| `LTM` | LTIM | 104 | ₹2177 cr/day |
| `CEMPRO` | ITDCEM | 215 | ₹997 cr/day |
| `SMLMAH` | SMLISUZU | 180 | ₹702 cr/day |
| `VIYASH` | SEQUENT | 128 | ₹627 cr/day |
| `ASHIKAG` | ASHIKA | 6 | ₹481 cr/day |
| `GVPIL` | GEPIL | 229 | ₹351 cr/day |
| `SWANCORP` | SWANENERGY | 224 | ₹309 cr/day |
| `CNL` | CREATIVE | 158 | ₹305 cr/day |
| `GUJENERGY` | GUJGASLTD | 24 | ₹243 cr/day |

Tata Motors PV and LTIMindtree are large-cap index constituents currently outside both screens'
universes entirely.

---

## Q3 — distribution over time: trickle plus ETF-shaped lumps

`computed`. All 66 by switch month:

| Month | Renames | Notes |
|---|---|---|
| 2025-06 | 3 | |
| 2025-07 | 2 | |
| 2025-08 | 3 | |
| 2025-09 | 6 | |
| 2025-10 | 6 | incl. TATAMOTORS→TMPV demerger |
| 2025-11 | 3 | |
| **2025-12** | **17** | **15 of them ETF fund-house rebrands** (DSP ×4, UTI ×11); the other 2 are equities |
| 2026-01 | 3 | |
| 2026-02 | 5 | incl. LTIM→LTM |
| 2026-03 | 0 | |
| 2026-04 | 3 | |
| 2026-05 | 1 | |
| 2026-06 | 1 | |
| **2026-07** | **13** | **9 of them the Axis ETF rebrand on one day** |

Days carrying more than one rename:

| Day | Count | What |
|---|---|---|
| 2025-12-24 | **12** | UTI ETF rebrand (`*BETA` family), 11 ETFs + JCHAC→BOSCH-HCIL |
| 2026-07-03 | **9** | Axis ETF rebrand (`*AXIS` family) |
| 2025-12-15 | **4** | DSP ETF rebrand (`*ADD` family) |
| 2025-09-15, 2025-10-13, 2025-11-10, 2026-02-09 | 2 each | coincidence, unrelated names |

**25 of 66 (38%) arrived in three single-day batches, and all three are ETF fund-house rebrands** —
24 of those 25 are ETFs; 2025-12-24 happened to carry one unrelated equity rename
(`JCHAC→BOSCH-HCIL`) alongside the eleven UTI ETFs. Strip the ETFs (26 of the 66) and the equity
rename stream is a clean trickle: **40 equity renames over 13.5 months ≈ 3/month**, with no day
carrying more than two.

So the answer is *both*, and the split matters for the build decision: **the lumpy part is ETFs,
which a stock screen arguably should not carry at all; the equity part — which is what the screens
exist for — is a steady trickle.** Tonight's nine-ETF event looked like an exchange-wide
reclassification and was in fact a single AMC rebranding its product line.

---

## Q4 — does anything already handle this

**No. Nothing.** Established by reading the code paths, not by grep alone.

| Place checked | Finding |
|---|---|
| `docs/symbol-normalization.md` | Canonical identity is `(exchange, tradingsymbol)` (`:13-15`). The doc never contemplates a tradingsymbol *changing while the company keeps trading*. "rename" appears only about **source JSON field** renames (`:73-74`). The "Known-thin areas" section (`:81-83`) — where such a caveat would live — lists only F&O coverage and the strike/expiry tuple. |
| `marketdata.instruments` | No `isin`, no `superseded_by`, no alias column. On disappearance a row is **tombstoned** (`is_active=false`, `InstrumentRepository.java:130-141`), never linked. The successor arrives as a brand-new PK with a fresh `first_seen_at` (`:95-124`). Nothing writes a link. |
| Kite instrument dump | Carries no ISIN at all — `kite/wire/KiteInstrument.java:12-24` mirrors all twelve documented columns; `kite-contract-manifest.json:21-34` pins the same twelve. |
| Upstox masters | `UpstoxInstrumentMaster.java:33-43` maps ten fields, none an ISIN, and is `@JsonIgnoreProperties(ignoreUnknown=true)` so an upstream `isin` is silently discarded. The `NSE_EQ\|<ISIN>` key *does* embed an ISIN, but it lives only in a `volatile` in-memory map (`UpstoxEquityMasterClient.java:45`), never persisted. |
| `marketdata.eod_corporate_actions` | `kind` is CHECK-constrained to `SPLIT`/`BONUS`. **The NSE CA feed is fetched with no purpose filter** (`LiveNseCorporateActionFetcher.java:48-52`), so "Change in Name" rows arrive **with their ISIN attached** — and are then silently dropped at `BhavcopyBackfillService.java:486` (`continue; // dividend / buyback / AGM — not a price adjustment`). Not logged, not counted, not stored. |
| `marketdata.equity_fundamentals` | Has an `isin` column, but it is **useless as a spine** — `computed`: 169 rows total; of the 66 pairs, 1 successor has an ISIN, **0 predecessors do, 0 pairs match**. PK is `symbol`, `isin` is unindexed nullable payload, overwritten each refresh. |
| `nse_eod_bhavcopy` ingest | We fetch `sec_bhavdata_full`, which has **no ISIN column** — so nothing is being dropped at parse. NSE's UDiFF file does carry ISIN; we simply do not fetch it for NSE. |
| `marketdata.bse_eod_bhavcopy` | **The one asset that already exists.** PK `(trade_date, scrip_code)` + a per-day `isin` + an index `idx_bse_bhavcopy_isin`. This is the only per-day identity time series in the platform. Its sole reader (`EodCorporateActionRepository.bseTickerForIsin`) takes `ORDER BY trade_date DESC LIMIT 1` — deliberately the *latest* ticker, never the history. |
| Screener package | No carve-out, exception list, override, or symbol map — YAML, JSON, or SQL seed. Every `CREATE TABLE` in `deploy/flyway/marketdata/` was enumerated; none is an alias/identity-history table. |
| Docs | The problem is acknowledged **only** as an unhandled bias caveat: `docs/signal-analysis/2026-08-02-m40-risk-cap-backtest.md:376` ("Survivorship-biased — delisted/renamed symbols since 2015 are likely undercounted"), `docs/adr/0005-minervini-universe-low-cap-equities.md:48`. Never as machinery. |

---

## The second defect: dead symbols are still being scored

Found while measuring the above, verified live. `computed`.

Because `sessions` counts rows in a 420-day window rather than recency, a symbol that stops trading
keeps its session count — and its frozen last price — for roughly a month before decaying out of the
gate. **9 dead symbols are in the live Minervini universe of 1776 today:**

| Symbol | Last traded | Days stale | Sessions | Frozen close |
|---|---|---|---|---|
| `GUJGASLTD` | 2026-06-30 | 34 | 252 | 327.05 |
| `AXISGOLD`, `AXISHCETF`, `AXISILVER`, `AXISNIFTY`, `AXISTECETF`, `AXISVALUE`, `AXISBNKETF` | 2026-07-02 | 32 | 254 | — |
| `JBCHEPHARM` | 2026-07-16 | 18 | 264 | 2408.90 |

Eight of the nine are the renamed predecessors themselves — so a rename currently costs *twice*: the
successor vanishes AND the corpse keeps being ranked. The ninth is the true delisting.

**One of them is being served as a candidate right now.** Live probe:

```
$ docker exec ay-market-data-service sh -c \
    "wget -qO- 'http://127.0.0.1:8081/api/v1/market/screener/minervini?limit=500'"
...
{'symbol': 'JBCHEPHARM', 'close': '2408.9000', 'rsRank': '86.65',
 'gatesPassed': 8, 'passesAll': True, 'stage': 2}
```

Manas scans 12 dead symbols in its 2274-name universe but surfaces none in its output today
(`computed` — its gates happen to exclude them; that is luck, not a guard).

---

## Recommendation

**Build it — but build the small thing, and build the staleness guard first.**

Sizing: 9 candidates gained out of 278 (+3%), 46 universe members restored out of 1776 (+2.6%), on a
~59/year event rate that will not stop. Against that, the fix does **not** require touching the
instrument identity model at all — which was the owner's stated reason for investigating first.

**Priority 1 — a recency guard on the screen universe (small, and it fixes a live false positive).**
Add `last bar is the screen date` (or within N sessions) to the universe predicate on both screens.
This closes the `JBCHEPHARM` case, removes all 9 ghosts, and is independent of any rename work. It
is the one thing that should not wait. **Caveat, and it is the same one the merge counterfactual
exposed:** shrinking the universe from 1776 to 1767 re-computes the cross-sectional RS percentile
over a smaller denominator, so a borderline name can cross `rsRank 70` in either direction. "Strictly
smaller universe" does **not** mean "strictly fewer candidates" — the change needs its candidate
delta measured, not assumed.

**Priority 2 — a symbol-continuity map as DATA, not as identity.** A single table
`marketdata.symbol_lineage (exchange, successor, predecessor, switch_date, evidence, confidence)`,
populated by the rule in this document and reviewed once before use. The screens then read a
lineage-expanded base CTE. Explicitly:

- **Do NOT change `(exchange, tradingsymbol)`.** It stays the canonical key exactly as
  `docs/symbol-normalization.md` describes. Lineage is a *view-time* join for history-hungry
  readers, not a new identity. This is what makes the change small and what keeps it away from the
  live feed, the ticker, the order path, and every cross-source mapper.
- Populate it from **two** signals, not one: the NSE price-continuity rule (recall 58/58 on the
  checkable population) and BSE `scrip_code`/`isin` continuity (independent, already in the DB).
  Where they agree, auto-accept. Where only one fires, flag for a one-line owner confirmation. 66
  rows over 13.5 months is a hand-reviewable volume.
- **Cheapest possible first cut**, if even that is too much: a static seeded map of the 66 pairs.
  Zero new machinery, and it buys the entire measured benefit today. The generator only pays for
  itself over the following year.

**Priority 3 — stop dropping the evidence at the door.** `BhavcopyBackfillService.java:486`
currently discards NSE "Change in Name" corporate actions *that arrive with their ISIN attached*.
Recording them (a new `kind`, or a sibling table) turns rename detection from inference into a
primary source. This is the highest-quality signal available and we are already fetching it.

**Explicitly do NOT:** lower or remove the 252-session gate. The gate is correct — a 24-session name
has no meaningful SMA200, 52-week extreme, or 12-month RS leg, and the window functions would
silently compute all three over a partial frame. The problem is not the threshold, it is that the
history exists in the database under a key nobody joins to. `computed`: for 57 of the 61 invisible
successors, predecessor sessions + successor sessions already ≥ 252.

**Also worth a separate decision (out of scope here):** 18 of the 46 blocked names and 26 of the 66
renames are ETFs. Neither screen excludes ETFs from a stock-screening universe. That is a
pre-existing design question, not a rename defect, and it is noted rather than proposed.

---

## Open doubts

1. **The 13.5-month window is the hard limit on everything here.** The brief asked for 1–2 years;
   half of that does not exist in the database. The 59/year rate is one year's observation
   extrapolated, and 2025-12 and 2026-07 being the two heavy months could be seasonality (AMC
   rebrands cluster near financial year-ends and product relaunches) or could be chance in a
   13-month sample. Do not treat 59/year as stable.
2. **Rename vs demerger vs amalgamation is not resolved.** `TATAMOTORS→TMPV` is a demerger and
   `PEL→PIRAMALFIN` an amalgamation, yet both carry a continuous NSE price series. Stitching a
   demerged entity's pre-demerger history is arguably *wrong* — the business changed — but NSE's own
   `prev_close` says continue. This needs an owner call before any auto-populated map is trusted for
   demergers specifically. It affects at least 1 of the 9 counterfactual candidates' plausibility.
3. **The 9-candidate counterfactual assumes stitched history is correct history.** It merges raw
   price series with no re-adjustment at the boundary. If a rename coincided with a ratio event that
   NSE handled inside `prev_close`, the merged MA/RS legs are subtly wrong. Not checked — the
   CA-adjustment lateral keys on the *original* symbol in my replica, which is right for splits
   already recorded but says nothing about unrecorded boundary adjustments.
4. **`prev_close` was never independently validated as NSE's rename convention** — it was inferred
   from the GUJGASLTD case and then found to hold 65 times with a 5000× placebo margin and 58/58 BSE
   agreement. That is strong evidence *that it holds*, not documentation of *why*. If NSE ever
   changes the convention the detector goes silent with no alarm.
5. **A full-market re-fetch landed during this investigation.** 33,059 rows across 13 trade dates
   (2025-10-21 … 2026-06-25) and 2745 symbols were re-upserted at 2026-08-04 IST, mid-measurement.
   Every headline number was **re-run afterwards and was byte-identical** (276 dates / 2862 universe
   / 2274 pass252 / 443 below / 66 pairs / 61 invisible), so the conclusions survived the mutation —
   but `fetched_at` is an upsert timestamp, not first-seen, so this bounds rather than pins. A
   re-fetch that *added* trade dates rather than re-writing existing ones would move the session
   counts and hence the 61.
6. **Six of the 66 pairs have no independent confirmation** (neither symbol is BSE-listed): mostly
   NSE-only ETFs, plus `SUNDARMHLD→TSFINV` and `GODHA→AURIGROW`. They rest on price continuity
   alone.
7. **`ASHIKA` and `AMIRCHAND` are suspiciously short predecessors** (67 and 72 sessions). Either
   they are themselves recent successors my rule did not chain (chain check found 0 within the
   detected set, so any chain reaches back before the data floor), or they were suspended. Not
   resolved; it only affects whether those two ever recover, not the headline count.
8. **`IEL`/`HINDNATGLS` — the one overlap match — was not investigated.** The predecessor kept
   trading past the successor's start with a matching price. Probably coincidence, possibly an
   overlap-style rename the rule is structurally blind to. If it is the latter, the true rename count
   is higher than 66 by an unknown amount.
9. **The "would become candidates" figure is a same-day snapshot.** Nine today; it will differ
   tomorrow, and the renamed population is plausibly *enriched* in momentum names (companies often
   rename after a transformative event), so the long-run value could exceed +3%. That cannot be
   measured, because the history needed to measure it is exactly what is missing.

---

## Appendix A — the detector SQL

Read-only. Run against `artha` in `ay-timescaledb`. Bounded aggregates + `DISTINCT ON` only; no
per-session join across the hypertable (the wide-join form OOM'd this DB during an earlier attempt).

```sql
WITH cal AS (                       -- trading-day calendar, so "next session" is expressible
  SELECT trade_date, row_number() OVER (ORDER BY trade_date) AS dn
  FROM (SELECT DISTINCT trade_date FROM marketdata.nse_eod_bhavcopy WHERE series IN ('EQ','BE')) d),
w AS (
  SELECT b.symbol, b.trade_date, b.prev_close, b.close_price, c.dn
  FROM marketdata.nse_eod_bhavcopy b JOIN cal c USING (trade_date)
  WHERE b.series IN ('EQ','BE') AND b.trade_date > date '2026-08-03' - 420),
firstrow AS (SELECT DISTINCT ON (symbol) symbol, trade_date AS first_d, dn AS first_dn,
                    prev_close AS pc FROM w ORDER BY symbol, trade_date ASC),
lastrow  AS (SELECT DISTINCT ON (symbol) symbol, trade_date AS last_d,  dn AS last_dn,
                    close_price AS lc FROM w ORDER BY symbol, trade_date DESC)
SELECT p.symbol AS predecessor, p.last_d, s.symbol AS successor, s.first_d,
       s.first_dn - p.last_dn AS gap_sessions, p.lc AS boundary_price
FROM firstrow s JOIN lastrow p
  ON p.last_dn < s.first_dn
 AND s.first_dn - p.last_dn <= 5      -- gap cap: 65 of 66 land at exactly 1
 AND s.pc = p.lc                      -- exact prev_close continuity
WHERE s.first_d > date '2025-06-20'   -- data floor: earlier "starts" are ingestion artifacts
  AND p.last_d  < date '2026-08-03'
ORDER BY s.first_d, s.symbol;
```

Independent BSE confirmation for any pair:

```sql
SELECT b1.scrip_code, b1.ticker AS pred, b2.ticker AS succ, b1.isin
FROM (SELECT DISTINCT scrip_code, ticker, isin FROM marketdata.bse_eod_bhavcopy WHERE ticker IS NOT NULL) b1
JOIN (SELECT DISTINCT scrip_code, ticker, isin FROM marketdata.bse_eod_bhavcopy WHERE ticker IS NOT NULL) b2
  USING (scrip_code)
WHERE b1.ticker = 'GUJGASLTD' AND b2.ticker = 'GUJENERGY';
```

## Appendix B — all 66 pairs (`computed`, `asOf` 2026-08-03)

Generated directly by the Appendix A query joined to the BSE cross-check — not hand-transcribed.

`Conf.` — **BSE**: one BSE `scrip_code` carried both tickers (also ISIN-identical). **ISIN-changed**:
both BSE-listed but the ISIN changed at the boundary (amalgamation into a new entity). **none**:
at least one symbol is not BSE-listed, so no independent confirmation is possible.

`State` — **invisible**: trading today with `sessions < 252`. **recovered**: has re-accumulated 252.
**gone**: the successor has itself since stopped trading.

| Switch date | Predecessor | Successor | Sessions | State | Conf. | Notes |
|---|---|---|---|---|---|---|
| 2025-06-23 | KAVVERITEL | `KAVDEFENCE` | 275 | recovered | BSE |  |
| 2025-06-25 | SATINDLTD | `AEROENTER` | 273 | recovered | BSE |  |
| 2025-06-26 | KBCGLOBAL | `DHARAN` | 68 | gone | BSE |  |
| 2025-07-14 | BINANIIND | `BILVYAPAR` | 235 | invisible | BSE |  |
| 2025-07-21 | QUALITY | `QUALITY30` | 255 | recovered | none | ETF |
| 2025-08-13 | TIMESGTY | `TEAMGTY` | 237 | invisible | BSE |  |
| 2025-08-22 | SAH | `AERONEU` | 232 | invisible | BSE |  |
| 2025-08-28 | GEPIL | `GVPIL` | 229 | invisible | BSE |  |
| 2025-09-03 | HOVS | `HGM` | 225 | invisible | BSE |  |
| 2025-09-04 | SWANENERGY | `SWANCORP` | 224 | invisible | BSE |  |
| 2025-09-15 | GANESHHOUC | `GANESHHOU` | 217 | invisible | BSE |  |
| 2025-09-15 | SILVRETF | `SILVERAG` | 217 | invisible | BSE | ETF |
| 2025-09-17 | ITDCEM | `CEMPRO` | 215 | invisible | BSE |  |
| 2025-09-22 | SELAN | `ANTELOPUS` | 212 | invisible | BSE |  |
| 2025-10-10 | WORTH | `WORTHPERI` | 199 | invisible | ISIN-changed |  |
| 2025-10-13 | BARBEQUE | `UFBL` | 198 | invisible | BSE |  |
| 2025-10-13 | AARVEEDEN | `VGL` | 198 | invisible | BSE |  |
| 2025-10-16 | SUNDARMHLD | `TSFINV` | 195 | invisible | none |  |
| 2025-10-17 | GODHA | `AURIGROW` | 175 | gone | none |  |
| 2025-10-24 | TATAMOTORS | `TMPV` | 190 | invisible | BSE | demerger |
| 2025-11-10 | YAARI | `IBULLSLTD` | 164 | invisible | BSE | gap 4 |
| 2025-11-10 | SMLISUZU | `SMLMAH` | 180 | invisible | BSE |  |
| 2025-11-26 | SMSLIFE | `HALEOSLABS` | 168 | invisible | BSE |  |
| 2025-12-10 | CREATIVE | `CNL` | 158 | invisible | ISIN-changed |  |
| 2025-12-15 | BANKETFADD | `BANKADD` | 155 | invisible | BSE | ETF |
| 2025-12-15 | GOLDETFADD | `GOLDADD` | 155 | invisible | BSE | ETF |
| 2025-12-15 | ITETFADD | `ITADD` | 155 | invisible | BSE | ETF |
| 2025-12-15 | NIFTY50ADD | `NIFTYADD` | 155 | invisible | BSE | ETF |
| 2025-12-24 | UTIBANKETF | `BANKBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | JCHAC | `BOSCH-HCIL` | 148 | invisible | BSE |  |
| 2025-12-24 | NIF10GETF | `GILT10BETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | NIF5GETF | `GILT5BETA` | 139 | invisible | BSE | ETF |
| 2025-12-24 | GOLDSHARE | `GOLDBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | NIFITETF | `ITBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | NIFMID150 | `MIDCAPBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | UTINEXT50 | `NEXT50BETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | UTINIFTETF | `NIFTYBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | UTISENSETF | `SENSEXBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | SILVERETF | `SILVERBETA` | 148 | invisible | BSE | ETF |
| 2025-12-24 | UTISXN50 | `SNXT50BETA` | 148 | invisible | BSE | ETF |
| 2026-01-19 | SGLTL | `SETL` | 132 | invisible | BSE |  |
| 2026-01-20 | HEUBACHIND | `SUDARCOLOR` | 131 | invisible | BSE |  |
| 2026-01-23 | SEQUENT | `VIYASH` | 128 | invisible | BSE |  |
| 2026-02-03 | INFIBEAM | `CCAVENUE` | 122 | invisible | BSE |  |
| 2026-02-09 | ARISINFRA | `ARIS` | 118 | invisible | BSE |  |
| 2026-02-09 | MEGASOFT | `SIGMAADV` | 118 | invisible | BSE |  |
| 2026-02-11 | SABTNL | `AQYLON` | 116 | invisible | BSE |  |
| 2026-02-27 | LTIM | `LTM` | 104 | invisible | BSE |  |
| 2026-04-02 | EXCEL | `LANDSMILL` | 83 | invisible | BSE |  |
| 2026-04-15 | AKZOINDIA | `JSWDULUX` | 76 | invisible | BSE |  |
| 2026-04-30 | SASTASUNDR | `HEALTHX` | 65 | invisible | BSE |  |
| 2026-05-20 | VISASTEEL | `VISACHROME` | 52 | invisible | BSE |  |
| 2026-06-19 | MIRCELECTR | `ONIDA` | 31 | invisible | BSE |  |
| 2026-07-01 | GUJGASLTD | `GUJENERGY` | 24 | invisible | BSE |  |
| 2026-07-03 | AXISBNKETF | `BNKETFAXIS` | 21 | invisible | none | ETF |
| 2026-07-03 | AXISCETF | `CONSUMAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-03 | AXISGOLD | `GOLDAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-03 | AXISHCETF | `HEALTHAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-03 | AXISTECETF | `ITAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-03 | AXISNIFTY | `NIFTYAXIS` | 22 | invisible | none | ETF |
| 2026-07-03 | AXSENSEX | `SENSEXAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-03 | AXISILVER | `SILVERAXIS` | 22 | invisible | none | ETF |
| 2026-07-03 | AXISVALUE | `VALUEAXIS` | 22 | invisible | BSE | ETF |
| 2026-07-14 | LYPSAGEMS | `AURUS` | 15 | invisible | BSE |  |
| 2026-07-20 | AMIRCHAND | `AEROPLANE` | 11 | invisible | BSE |  |
| 2026-07-27 | ASHIKA | `ASHIKAG` | 6 | invisible | BSE |  |

**61 invisible · 3 recovered · 2 gone = 66 pairs.** 26 of the 66 successors are ETFs; the other 40 are equities.
