# Upstox live-stack migration — wave spec

- **Status:** **W-U1 + W-U2 + W-U3 MERGED 2026-06-24** (PRs #137 / #139 / #141 — all flag-gated,
  **default Kite, code on main but UNDEPLOYED**). Supporting fixes also merged: the `nse↔upstox` Modulith
  cycle (#138) + per-expiry backfill resilience (#140). **W-U4 cutover is the only remaining wave** (gated
  on the live latency A/B + deploy off-hours). Owner decision: "go further — move live ticks/quotes to Upstox."
- **Authority:** this doc + master-plan §21 (Kite-vs-Upstox verdict) + §4.3–4.4 (per-capability source
  flags) + §17.3 (scalp-latency gate) + [ADR-0002] + memory [[kite-vs-upstox-api-verdict]].
- **Trigger:** today the daily **Kite token expired → live OI capture died** (`no live Kite session for
  quotes`). Owner wants the live stack on Upstox so this doesn't recur.

## The decisive finding (proven 2026-06-24, live probe)
The **1-yr Upstox analytics token authorizes the LIVE option-chain** (`GET /v2/option/chain` → HTTP 200,
whole chain in ONE call: per-strike OI + `prev_oi` + LTP/bid/ask/volume + greeks + PCR). So **live OI
capture can run on the long-lived token with NO daily-login dependency** — the surgical fix.

> **Key correction:** routing live capture *through OpenAlgo* to Upstox does **NOT** remove the daily
> login — OpenAlgo's Upstox connection is itself daily-OAuth (no refresh-token path in
> `broker/upstox/api/auth_api.py`). Only the **direct analytics-token** path (ADR-0002 side-channel,
> like `UpstoxExpiredInstrumentsClient`) is login-free. So capture goes **direct Upstox**, not via OpenAlgo.

## Capability map (what moves, how, risk)
| Cap | Today | Target | Token | Status | Risk |
|---|---|---|---|---|---|
| **OI option-chain capture** (D) | Kite quotes → `OptionsSnapshotService` | **direct Upstox `/v2/option/chain`** (1 call, OI+greeks+PCR) | **analytics (1-yr)** | NEW gateway | LOW — additive source flag, A/B vs Kite |
| **Futures OI capture** (E) | Kite quotes → `FuturesOiSnapshotService` | direct Upstox full-market-quote | analytics (1-yr) | NEW gateway | LOW |
| **Live spot/FUT quotes** (C) | `LiveQuoteGateway` Kite | direct Upstox quotes | analytics (1-yr) | NEW gateway | MED — no batch (fan-out) |
| **Historical candles** (A) | Kite / OpenAlgo | Upstox intraday+historical | analytics | mostly built | LOW |
| **WS live ticker** (F) | `LiveTickerFeed` (Kite SDK) | Upstox **v3** WS market-feed | **analytics (1-yr) — VERIFIED login-free** | MERGED #141 (default Kite) | **HIGH — scalp latency gate §17.3** |
| **Orders** (G) | gated/disabled | OpenAlgo→Upstox | daily live | spec only | latency-gated |

## Waves (each: flag-gated, default-off → no behaviour change until flipped + deployed)
1. **W-U1 — direct-Upstox live OI capture** (option-chain + futures) on the analytics token, behind
   `artha.marketdata.source.optionchain=upstox` / `oi-capture=upstox` (default = current). The login-free
   fix. WireMock-tested + a live A/B note (compare Upstox vs Kite OI for a session before flipping). **← build first.**
2. **W-U2 — direct-Upstox live quotes** (spot/FUT) on the analytics token (`source.quotes=upstox`),
   virtual-thread fan-out (no batch endpoint). Unblocks moving any remaining Kite-quote consumers.
3. **W-U3 — Upstox WS live ticker** (real-time ticks). **GATED:** first VERIFY the WS auth model
   (analytics token vs daily) + **measure scalp place-ack latency ≥1 session** (§17.3/§21.3) vs Kite
   direct. Kite WS stays the default until the gate is green. Build the path; do not cut over blind.
   **BUILT 2026-06-24** (branch `feat/upstox-ws-ticker`): flag `artha.marketdata.source.ticker`
   (`kite`|`upstox`, default `kite`). Direct-Upstox v3 WS on the **analytics token** (VERIFIED: the
   authorize-GET returns a wss URL on the 1-yr token — login-free). Proto = the canonical
   `MarketDataFeedV3.FeedResponse`, decoded by `upstox.ws.FeedFrameDecoder` (low-level
   `CodedInputStream`, no protoc); WS mode = `full` (LTP+vol+OI). REUSES the `LiveTickerFeed`
   supervisor + shared `SubscriptionRegistry` via the `kite`-declared `UpstoxLiveTickFeed` port.
   Latency metric `ay_upstox_ws_tick_latency` exposed for the gate; A/B runbook =
   `docs/manual-tests/wave-u3-upstox-ws-ticker.md`. **REMAINING before cutover:** run the live A/B
   (median+p95 tick staleness Upstox vs Kite, ≥1 session) — index/spot measurable today, a full
   option-strike scalp A/B needs an F&O token→Upstox-key map (U4 follow-up).
4. **W-U4 — cutover + retire** — flip the defaults to Upstox, keep Kite impls as `@ConditionalOnProperty`
   fallback (never deleted, owner directive 6f/6g). Update the `ContractCanary` to the Upstox shapes.

## Deploy / safety
- **All of this is build-now, deploy-later:** every cutover needs a **market-data restart**, which would
  kill the **running expired backfill** — so deploy only AFTER the backfill finishes (off-hours).
- Additive + flag-gated: merging the code changes nothing live until a flag is flipped + deployed.
- **A/B validate before flipping** OI capture: run Upstox capture alongside Kite for a session, diff the
  per-strike OI (both are NSE-official → should match) — only flip when they reconcile.
- Greeks stay computed in `black76-math` for parity (Upstox greeks are a cross-check, not the source).

## Open VERIFY items
- ~~Upstox **WS** auth: analytics token vs daily?~~ **RESOLVED 2026-06-24:** `GET /v3/feed/market-data-feed/authorize`
  → HTTP 200 + `authorized_redirect_uri` wss URL on the 1-yr analytics token → **the v3 WS is login-free**
  (ticks survive a missed login too, not just REST capture). The **v2** WS is discontinued (410 `UDAPI1153`).
- Upstox quote **rate** under the analytics token shared with the (then-finished) backfill — non-issue
  once the backfill completes; during overlap the capture is a few calls/min (negligible).
- **W-U4 cutover gate (remaining):** (1) run the live tick-staleness A/B (`ay_upstox_ws_tick_latency`,
  runbook) over ≥1 session — flip `ARTHA_MD_SOURCE_TICKER=upstox` only if Upstox ≤ Kite at p95; (2) an
  index/spot A/B is measurable today, a full option-strike scalp A/B needs an **F&O token→Upstox-key map**
  from the instrument master (the token→key bridge currently covers indices + NSE cash only); (3) deploy
  off-hours (a market-data restart kills the running expired backfill).
