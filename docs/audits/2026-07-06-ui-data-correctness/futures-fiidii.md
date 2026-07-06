# UI Data-Correctness Audit — Futures OI + FII/DII pages

Date: 2026-07-06 (~10:45 IST, market LIVE). Read-only. Method: read `.tsx` + `api/*` hook →
exact endpoint/params → call live in-container (`ay-market-data-service:8081`) → reconcile to DB
(`ay-timescaledb` / `artha`, times filtered by explicit `+05:30`).

Internal base path = `/api/v1/market/...` on 8081 (gateway strips nothing extra). Futures OI + LTP +
day-range come from `marketdata.futures_oi_snapshots` (3-min forward-capture, live since ~2026-06-15,
bank stocks 2026-06-16); the OI-chart PRICE candles come from `marketdata.candles` (1m base, rolled).
FII/DII cash → `marketdata.nse_eod_fii_dii`; FII derivative → `marketdata.fii_derivative_stats`;
participant + long-short → `marketdata.nse_eod_participant_oi`.

Snapshot bucketing = **end-of-window** (`time_bucket(ts - 1s)`): a capture at 09:18:00 labels into the
09:15 window it terminates. Consistent across series/chart/movers/spurt/banks (documented convention).

Freshness at audit time: futures snapshots latest ts = **10:47 IST, 23 underlyings** (fresh). FII/DII +
participant + derivative EOD latest = **2026-07-03** (last publish; EOD lags one session — normal, not stale).

---

## Futures pages

### 1. Futures OI Analysis — `GET /futures/oi-analysis-series?mode=live&name=NIFTY 50&interval=3m`
- Serves the **DATED FRONT contract `NIFTY26JULFUT`** (expiry 2026-07-28), NOT the stale CONT. PASS.
- Checked 09:15 bucket: `ltp 24405.5 / oi 17044365 / oiChange −3705 / dayHigh 24408.9 / volume 135005`
  — byte-identical to the 09:18:00 snapshot row in DB (end-of-window bucketing). PASS.
- Fold `totalChngInOi` baselines on the FIRST CAPTURED bucket OI (not NSE prior-close day-open OI) — a
  documented forward-capture divergence (can differ in SIGN on a declining-OI session). WORKING AS DESIGNED.
- **PASS.**

### 2. Futures EOD OI Analyzer — `GET /futures/eod?name=NIFTY 50&from=..&to=..`
- Per-contract per-IST-day rollup. Checked 06-15/06-16 front (JUN): endpoint `oiClose 18025410 / 17633200`,
  `close 23927 / 24012`, `oiChange 152945 / −192855` — exact DB match. PASS.
- 06-15 (first capture day) `open/high/low = null` (only close/OI captured) — documented forward-only. OK.
- Continuous stitch picks the lowest-expiry contract producing rows that day (front) → correct. PASS.
- **PASS.**

### 3. Futures Market Movers — `GET /futures/movers?mode=live&name=NIFTY 50`
- `pricePct` = day % off captured `prevClose` (NIFTY26JULFUT 24352.7→24464 = +0.46%) — DB-correct. PASS.
- Reduced universe = index-future monthlies only (JUL/AUG/SEP), `losers:[]` (all 3 up). Documented. OK.
- ⚠ **LOW (semantic mismatch):** `oiPct` + `interpretation` use the **latest-two-3m-bucket** OI delta,
  while `pricePct` is **day-cumulative**. So a front contract down net-OI on the day can read `oiPct 0.00`
  / `LONG_UNWINDING` from just the last interval. Header says only "OI Chg %" (period unlabelled) — same as
  oipulse, but price(day) vs OI(interval) is inconsistent. Numbers themselves reconcile to the 3m diff. Not wrong, worth a note.
- **PASS (with LOW note).**

### 4. Futures OI Spurt — `GET /futures/spurt?mode=live&name=NIFTY 50`
- 2×2 by 4-state; `oi`/`oiChange` = latest 3m bucket. NIFTY26JULFUT `oi 16964480 / oiChange −325` = terminal
  10:42–10:45 bucket in DB. `pricePct` off prevClose. PASS. Same reduced universe as movers.
- **PASS.**

### 5. Futures OI Chart — `GET /futures/oi-chart?mode=live&name=NIFTY 50&interval=3`
- Front contract `NIFTY26JULFUT`. Price candles rolled from 1m base; verified 09:15 3m bucket:
  `O 24345 / H 24400.1 / L 24330 / C 24396.7 / V 94900` = exact aggregate of DB 1m 09:15+09:16+09:17. PASS.
- OI line = snapshot `series` re-keyed onto the candle grid (last-wins, floored to 3m); 09:15 OI 17044365 =
  09:18:00 snapshot (end-of-window). A candle bucket with no OI sample gaps. Documented, faithful. PASS.
- **PASS.**

### 6. Banks Analysis — `GET /futures/banks-analysis?mode=live&interval=3m`
- 6 config banks (HDFCBANK/ICICIBANK/AXISBANK/SBIN/KOTAKBANK/INDUSINDBK), name-less/expiry-less. Front
  contract per bank. `ltpPct` = cumulative vs captured day-open; `oiPct` = cumulative vs first captured OI.
- ⚠ **INFO (deliberate asymmetry, not a bug):** the cell `interpretation` badge is a **per-interval** read
  (curLtp−prevLtp, curOi−prevOi), so it can disagree with the cumulative %s — e.g. KOTAKBANK cell showed
  `ltpPct −1.62` (down on day) but `LONG_BUILDUP` (last interval price↑/OI↑). Explicitly documented in
  `FuturesBankAnalysisService` class doc. Faithful to oipulse. NO ACTION.
- **PASS.**

### 7. Futures OI Buzz — `GET /futures/oi-buzz-heatmap?name=<index>` (+ `oi-buzz-indices`)
- Indices seeded = NIFTY 50 / NIFTY BANK / NIFTY 200. Tiles = constituent **near-month FUTURE** %change off
  its `prevClose` (HDFCBANK 826.55 vs prevClose 805.20 = +2.65%). PASS.
- OI per tile = the front future's captured OI (HDFCBANK26JULFUT `oi 326180400`) — matches DB snapshot exactly
  (large but real; big-lot stock). NOT inflated. PASS.
- advance/decline recomputed from tiles == reported (36/14 at recheck; the earlier 33/17 was a live-tick delta,
  self-consistent each call). PASS.
- **PASS.**

### 8. Futures Pre-Open Market — `GET /futures/pre-open`
- Live phase `NORMAL_OPEN`, `preOpen:false` (audited post-open ~10:46 → shows last quotes, as designed).
- `change`/`changePct` off `prevClose`; `prevDayBreak` verified: HDFCBANK preOpen 826.55 > prev-day(07-03)
  high 820.00 → `"H"` correct; SBIN within range → null. PASS.
- Radar = captured bank-stock futures + indices (reduced vs full NIFTY-50; documented v2 widening). OK.
- **PASS.**

---

## FII/DII pages

### 9. FII/DII Capital Market — `GET /fii-dii/cash?from=..&to=..`
- Source label `"FII/FPI"` (Upstox) — fold matches by PREFIX `startsWith('FII')` (the documented trap; exact
  `==='FII'` would blank the column). WORKING. PASS.
- Latest 07-03: `FII/FPI net +1355.33`, `DII net −1953.89` — exact DB match. FE metric strip `In Market` =
  FII Net + DII Net = **−598.56** (correct formula). PASS. Chart/table read the same folded rows.
- **PASS.**

### 10. FII Derivative Stats — `GET /fii-dii/derivative-stats?from=..&to=..`
- Per (date, segment) net pivoted to 4 columns. Latest 07-03: `INDEX_FUTURES +934.22 / INDEX_OPTIONS
  +4398.81 / STOCK_FUTURES +499.98 / STOCK_OPTIONS −477.97` — exact DB match. Index-options notional
  magnitudes (~10^6 cr buy/sell, small net) are correct gross-notional, not a unit error. PASS.
- **PASS.**

### 11. FII Long-Short Ratio — `GET /fii-dii/long-short?from=..&to=..`
- Endpoint gives `fiiLong`/`fiiShort`/`ratio`(=long/short). Latest 07-03 `fiiLong 29772 / fiiShort 280539`
  = exact DB (participant FII future_index long/short). PASS.
- FE **ignores** the endpoint `ratio` and computes its own **LSR% = long/(long+short)×100 = 9.59%** → title
  "FII are long for 9.59%" (heavily net-short index futures = bearish). Correct, no sign/unit error. PASS.
- **PASS.**

### 12. Participant-Wise OI — `GET /fii-dii/participant-oi?from=..&to=..`  ❌ DISCREPANCY
- Absolute long/short per (date, participant) pivoted to 4 groups × 6 segments; latest-vs-prior diff. The raw
  long/short/TotalDiff/change numbers reconcile to DB (FII 07-03 futureIndex L 29772 / S 280539 → TotalDiff
  −250767; ΔLong +329, ΔShort −5804, ΔTotal +6133 → Bullish). Those columns PASS.
- **❌ MEDIUM — spurious `TOTAL` row is not filtered:** the endpoint returns a synthetic `clientType:"TOTAL"`
  row (the market-clearing aggregate, where long==short). `foldParticipantOi` groups by every distinct
  clientType, so it **renders a 5th "TOTAL" participant table** (sorts last; PARTICIPANT_ORDER has no TOTAL).
- **❌ MEDIUM — Long%/Short% shares are HALVED:** `segmentTotals` sums `s.long(r)` over ALL latest rows,
  INCLUDING the TOTAL row (whose value already equals the sum of the four real participants). Denominator is
  thus 2× too large. Verified for FII Future Index Long on 07-03: real denom (4 participants) = 390678 →
  correct share **7.6%**; buggy denom (incl. TOTAL) = 781356 → rendered **3.8%**. Every Long%/Short% bracket
  on the page reads ~half the true market share. (`participantOiFold.ts` lines 82, 91–100.)
  - Fix: exclude `clientType === 'TOTAL'` in the fold (both the participant list and the `segmentTotals` sum),
    or filter it server-side. Note the header help says the bracket = "share of ALL participants' longs in the
    segment" — currently it's share of 2× that.
- **DISCREPANCY (Severity MEDIUM): TOTAL row rendered as a group + all long/short % roughly halved.**

---

## Summary
11/12 pages PASS on numbers vs DB + formula. All futures pages correctly serve the DATED FRONT contract
(`NIFTY26JULFUT`), never the stale CONT; OI/price/EOD/FII-cash/derivative/long-short reconcile exactly.
One real defect: **Participant-Wise OI** includes the synthetic `TOTAL` clientType — it renders as an extra
group AND double-counts the %-share denominator, halving every Long%/Short% (MEDIUM). Two informational
notes: Movers `oiPct`/interp is interval-based while `pricePct` is day-based (LOW); Banks cell badge is a
per-interval read that can disagree with the cumulative % (documented, INFO).
