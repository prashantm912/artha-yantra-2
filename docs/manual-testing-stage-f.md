# Manual testing — Stage F (Options analytics + Paper trading + Universe pinning + Journal)

Walks every Stage-F phase against the **running mock stack** (zero Kite credentials, ADR D13).
Phases: 42 (options chain UI), 42A (futures workbench), 42B (IV rank/percentile), 43 (paper
ledger), 43A (paper account + risk), 43B (expiry settlement + T-1), 44 (universe pinning), 44A
(trade journal). The gateway is the only published port (`127.0.0.1:8080`); all curls go through it.

## 0. Boot + prerequisites

```bash
./ay.ps1 up                      # 9+ healthy containers, flyway-init exit 0, mock profile
# log in (owner password = your .env ARTHA_OWNER_PASSWORD_HASH source; e2e default e2e-owner-password)
curl -s -c /tmp/ay.txt -X POST 127.0.0.1:8080/api/v1/auth/login -H 'content-type: application/json' \
  -d '{"password":"<owner-password>"}' -i | head -1     # -> 204 + Set-Cookie
```

**CSRF (curl mutations):** the gateway requires an `X-XSRF-TOKEN` header on every non-GET (the SPA
sends it automatically). After login, grab the cookie and send it on POST/PUT/DELETE:

```bash
XT=$(grep -i XSRF-TOKEN /tmp/ay.txt | tail -1 | awk '{print $NF}')   # then add: -H "X-XSRF-TOKEN: $XT"
```

> **Off-hours note:** on a weekend / outside 09:15–15:30 IST the mock serves a **zeroed options book**
> (bid/ask 0) and the futures term-structure falls back to stale — so chain IV renders `null`
> (`ivReason: ZERO_QUOTE`) and `/futures` basis is stale. That is correct off-hours behaviour; non-zero
> IV / basis show during market hours (and are pinned by the JUnit ITs + the Playwright ci-e2e run).

The mock feed is **boot-rolling** — candles/snapshots accrue from start. Seed the data the read
paths need (derive a recent covered window; never hardcode dates):

```bash
S=/tmp/ay.txt
# 1) NIFTY 50 + INDIA VIX 1d history (VIX chip, IV-history, basis spot leg) — cache-first GET warms it
curl -s -b $S '127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=NIFTY%2050&interval=1d&from=2026-05-01T00:00:00%2B05:30&to=2026-06-14T00:00:00%2B05:30&limit=400' | head -c 200
curl -s -b $S '127.0.0.1:8080/api/v1/market/candles?exchange=NSE&tradingsymbol=INDIA%20VIX&interval=1d&from=2025-06-01T00:00:00%2B05:30&to=2026-06-14T00:00:00%2B05:30&limit=400' | head -c 200
# 2) force a couple of options snapshots so /chain/history + the IV rollup have rows
curl -s -b $S -X POST 127.0.0.1:8080/api/v1/market/options/snapshot -H 'content-type: application/json' -d '{"underlying":"NIFTY 50"}'   # -> 202 {jobId}
sleep 3
curl -s -b $S -X POST 127.0.0.1:8080/api/v1/market/options/snapshot -H 'content-type: application/json' -d '{"underlying":"NIFTY 50"}'
```

---

## Phase 42 — Options chain UI + analytics + VIX chip

1. Browse `127.0.0.1:8080/options`.
   - **PASS:** the calls/strike/puts grid renders with **non-zero IV** for liquid strikes; ITM
     cells are highlighted; the table scrolls smoothly within a fixed-height viewport.
   - Change **Expiry**/**Strike-window** → the chain refetches (filters are wired — not the v1 dead
     filters). Change **Underlying** → expiries reload.
   - Header shows an **India-VIX chip** (level + 1-yr %ile) and, after market close, a **STALE** chip.
2. Analytics tabs: **IV smile**, **OI profile**, **PCR trend** render (ECharts). All derived from the
   store, never stored.
3. Toggle **History** → the chain swaps to the nearest stored snapshot (from step 0's snapshots).
4. Backend check: `curl -s -b $S '127.0.0.1:8080/api/v1/market/options/chain?underlying=NIFTY%2050' | head -c 300` → JSON with `"pcr"`, `"spot"`, non-zero `iv` strings.

---

## Phase 42A — Futures workbench

1. Browse `/futures`.
   - **PASS:** near/next/far **term-structure cards** show **non-zero basis** (absolute + annualized)
     and a **CONTANGO/BACKWARDATION** chip; days-to-expiry + OI present.
   - **Basis history** chart renders (from cached FUT vs spot 1d candles — sparse on a fresh boot is OK).
   - **Calendar-spread / rollover** panel shows next−front + % of spot.
   - **OI-buildup heat tiles** — long/short buildup, long unwinding, short covering — show counts
     (server-classified by the `oi_buildup` preset; the tiles only render it).
2. Backend: `curl -s -b $S '127.0.0.1:8080/api/v1/market/futures/term-structure?underlying=NIFTY%2050' | head -c 300` → 3 contracts + basis; `.../screener?preset=oi_buildup&window=1d` → labelled rows.

---

## Phase 42B — IV rank/percentile + IV-history endpoint

1. Run the rollup over the snapshots seeded in step 0:
   ```bash
   curl -s -b $S -X POST 127.0.0.1:8080/api/v1/market/options/iv-rollup -H 'content-type: application/json' -d '{}'   # -> {"recomputed":N}
   curl -s -b $S '127.0.0.1:8080/api/v1/market/options/iv-history?underlying=NIFTY%2050'
   ```
   - **PASS:** the response carries a daily `series`, `windowDays`, `floorDays:60` and — because the
     window is thin — `insufficientHistory:true` with **null `rank`** (the honest state, NOT a fake rank).
2. `/options` header shows an **IV-rank badge** reading `insufficient (n/60d)`; the **IV history** tab renders.
3. Re-run the rollup → idempotent (`recomputed` unchanged on identical data; only `computed_at` moves).

---

## Phase 43 — Paper trading ledger

1. Publish/seed a strategy that fires signals (or use the e2e helper). Open `/signals`, select a live
   signal, click **Journal**? no — click **Taken**. With a `suggested_qty` present (Phase 43A) the
   take opens a paper position.
   - Manual path: `curl -s -b $S -X POST 127.0.0.1:8080/api/v1/paper/orders -H 'content-type: application/json' -d '{"exchange":"NSE","tradingsymbol":"RELIANCE","side":"BUY","qty":1,"price":"1400.00"}'` → **201** position DTO with `fillPrice`, and a `paper_orders` row stamped `fill_simulator: ltp_slippage/v1`.
2. Browse `/paper`:
   - **Open positions** with **live unrealized P&L** that ticks (mark-to-market from the last-tick map).
   - Click **Close** → the trade moves to the **closed-trade ledger** with realized P&L; the
     **realized-equity curve** (lightweight-charts) updates.
   - **Dashboard** shows the **Paper P&L** tile.
3. **Parity check:** a paper option fill equals the backtest fill **to the paisa** — proven by
   `PaperFillServiceTest` (OPTION SELL 50@100 → fill 99.95, total cost 30.67, net 4966.83).
4. `POST /api/v1/paper/reset` with `{"confirm":true}` → **204** wipes the ledger; `{"confirm":false}` → 400.

---

## Phase 43A — Paper account, capital model, risk limits, kill switch

1. `/paper` **account header** — equity, free cash, day P&L, capital used.
   `curl -s -b $S 127.0.0.1:8080/api/v1/paper/account` → `equity` = starting capital + realized + MTM.
2. Edit **Starting capital** → equity recomputes.
3. **Risk panel:** set **Daily loss** (INR) and **Max open**; flip the **Kill switch**.
   ```bash
   curl -s -b $S -X PUT 127.0.0.1:8080/api/v1/risk/settings -H 'content-type: application/json' -d '{"key":"kill_switch","value":{"enabled":true}}'
   curl -s -b $S 127.0.0.1:8080/api/v1/risk/settings   # the limit row + an audit row
   ```
   - **PASS:** with the kill switch ON (or the daily-loss limit tripped), **ENTRY signals stop
     emitting** for the IST day while exits/stops continue; every trip/flip writes a `risk_audit` row.
   - Turn the kill switch **off** to resume.
4. **Buying-power warning:** set a tiny starting capital, place an oversized order → the response
   carries a non-blocking `buyingPowerWarning` (paper stays paper).
5. **suggested_qty:** a fresh signal's detail shows a **suggested qty** (engine-sized vs paper equity,
   lot-rounded); **Taken** opens the position at that qty.

---

## Phase 43B — Derivative expiry settlement + T-1 prompts

The mock instrument fixture pins option expiry **2026-06-16** (a Tuesday). The settlement job runs
15:35 IST on expiry dates; the T-1 push at 15:30 IST the session before.

1. Open a paper option position on a NIFTY contract. The **15:35 settlement** closes it at intrinsic
   vs the spot LTP, stamping `close_reason = EXPIRY_SETTLEMENT`; the same `(exchange, symbol, side)`
   re-opens on the next series. **Caveat (documented):** the spot-LTP settlement is an *approximation*
   of the official NSE settlement price (Kite exposes no settlement feed).
2. Index futures cash-settle at spot; **stock F&O** closes with a **physical-settlement warning**
   (delivery is never modeled). The **expiry STT leg** (0.125%) joins the shared engine fee schedule.
3. **T-1 push:** the session before expiry, an "expires tomorrow — roll or close?" notification goes
   out (Phase 41 notifier, deduped per position) and a `notification_events` row is recorded.
4. **Paper chart marks (FP-67):** on `/charts` for a symbol you paper-traded, closed paper trades show
   entry/exit markers (merged with signal marks); `GET /api/v1/paper/trades?symbol=EXCH:SYM` feeds them.

(All settlement math + the T-1 dedupe are IT-proven in `PaperExpiryIntegrationTest`.)

---

## Phase 44 — Universe pinning + checksum + editor label

1. Create + **publish** an `index_constituents` strategy (e.g. `universe: { mode: index_constituents,
   index: "NIFTY 100" }`). **PASS:** publish now **succeeds** (the Phase-21 guard is lifted).
2. `curl -s -b $S 127.0.0.1:8080/api/v1/strategies/<id>/universe` → `{mode, asOf, constituentCount,
   checksum, survivorshipCaveat}` — the **ordered list + SHA-256** resolved REST-only.
3. The **editor** (`/strategies/<id>/edit`) shows the **"Published Universe (as of …)"** banner with
   the constituent count + checksum + the **survivorship-bias caveat**.
4. Submit two backtests of the strategy: each `jobs.request` carries the **pinned copy + identical
   `universeChecksum`** (resolved once at submission, by copy — every sweep trial reuses it). A fresh
   submission after a constituent rebalance shows the new checksum.
   *(Persisting `universe_checksum` onto run rows + the leaderboard mismatch flag are parking items —
   the pinned copy already rides `jobs.request`.)*

---

## Phase 44A — Trade journal

1. On `/signals`, select a signal → **Journal** → fill note/tags/ratings → **Save**.
2. Browse `/journal`: the entry appears; filter by **tag** and **linked-entity** (signals / paper /
   backtest / free).
3. Free entry (no link) via **New entry** is first-class. Edit + delete round-trip.
4. Backend: `curl -s -b $S -X POST 127.0.0.1:8080/api/v1/journal -H 'content-type: application/json'
   -d '{"signalId":999999999,"note":"x"}'` → **422** (unknown same-schema link); a random
   `backtestRunId` is accepted (soft reference, no cross-schema FK).

---

## Stage-F exit gate

Walk the `PHASE_GATES.md` Stage-F checklist against this stack. The Playwright e2e (`ci-e2e`) mirrors
the demo-able acceptance criteria on the PR. An unchecked box extends the stage (S5 Friday gate).
