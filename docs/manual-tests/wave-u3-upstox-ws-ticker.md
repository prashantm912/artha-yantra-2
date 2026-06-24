# Manual test / runbook — Wave U3 direct-Upstox v3 WS live ticker (§17.3 scalp-latency gate)

Wave U3 builds a **direct-Upstox v3 WebSocket market-data feed** behind the flag
`artha.marketdata.source.ticker` (env `ARTHA_MD_SOURCE_TICKER`), **default `kite`**. The Upstox path
is BUILT but **dormant** — it must NOT be cut over until the §17.3 scalp-latency gate is green and the
owner flips the flag. This runbook is the gate: a live A/B of Upstox-WS vs Kite-WS **tick latency**
over ≥1 session.

> **Do NOT flip the flag during the running expired backfill** — any ticker-source change needs a
> market-data restart, which kills the backfill (memory [[expired-backfill-live-db-incident]]).
> Deploy/flip only off-hours after the backfill finishes.

## What was built (all additive, default = Kite)

- Flag `artha.marketdata.source.ticker` = `kite | upstox` (default `kite`, `matchIfMissing`). Exactly
  one `MarketFeed` binds — the Kite `LiveTickerFeed` (`LiveKiteConfig#liveMarketFeed`) or the Upstox
  feed (`kite.upstoxfeed.UpstoxMarketFeedConfig#upstoxMarketFeed`). The `FeedPipeline` autowires the
  single bound feed, so candles/charts/signals are source-agnostic.
- The Upstox feed REUSES the existing `LiveTickerFeed` supervisor verbatim (registry replay, the
  `kite-ticker` breaker, reconnect counting, post-reconnect gap-heal, `FeedRearm`) via an
  Upstox-backed `TickerHandle` over the `kite`-declared `UpstoxLiveTickFeed` port. The subscribed
  symbol set (`SubscriptionRegistry`, Redis-persisted) is shared with the Kite path.
- Transport: `nv-websocket-client` (already on the classpath via kiteconnect). Frames are v3
  protobuf (`MarketDataFeedV3.FeedResponse`), decoded by `upstox.ws.FeedFrameDecoder` (low-level
  `CodedInputStream`, no protoc / no generated classes). Mode = `full` (LTP + volume + OI — the
  minimal mode the candle/sink path needs).
- Authorize is login-free on the **1-yr analytics token**: `GET
  /v3/feed/market-data-feed/authorize` → `data.authorized_redirect_uri` (wss URL). Reconnect
  re-fetches the authorize URL (survives the ~3 AM daily rollover).

## Prereqs

- LIVE stack up, instruments synced (`POST /api/v1/market/instruments/sync`), and the Upstox
  analytics token secret file present (`/run/secrets/upstox_analytics_token`). The same token the
  U1/U2 paths already use.
- Market OPEN (this is a live-tick latency measurement — run during a session).

## A. Baseline — Kite WS (default, no flag change)

1. With `ARTHA_MD_SOURCE_TICKER` unset (or `kite`), bring the stack up and let the morning ritual
   complete (Kite login). Confirm ticks flow: `redis-cli -n 0 SUBSCRIBE ticks.NSE.NIFTY\ 50`
   (or watch the chart).
2. Record the Kite WS tick **staleness** baseline for the index spots + the pinned futures you scalp.
   There is no built-in Kite per-tick latency metric, so capture it the same way as Upstox below: the
   tick's `exchangeTimestamp` vs the `currentTs`/receive time. (A quick approximation: compare the
   chart's last-tick wall-clock lag.) Note the median + p95 over ~10 min.

## B. Candidate — Upstox WS

1. **Off-hours / backfill finished.** Set the flag and recreate ONLY market-data:
   ```powershell
   # live values; mock = artha_mock / 1 (see CLAUDE.md)
   $env:ARTHA_DB_NAME='artha'; $env:ARTHA_REDIS_DB='0'
   $env:ARTHA_MD_SOURCE_TICKER='upstox'
   docker compose -f deploy/docker-compose.yml --env-file .env up -d market-data-service
   ```
   (Or set `ARTHA_MD_SOURCE_TICKER=upstox` in `.env` and `./ay.ps1 up`.)
2. Confirm the Upstox feed bound and connected — market-data logs show
   `upstox ws connected` (not the Kite ticker). Confirm index ticks flow on the same Redis channels
   (`ticks.NSE.NIFTY 50`) — the sink is shared, so the UI/candles are unchanged in shape.
3. Read the latency metrics from the actuator/Prometheus endpoint:
   - `ay_upstox_ws_tick_latency` — a Timer of **tick staleness** (receive-time − exchange `ltt`).
     This is the gate's primary input. Read `…_seconds_count`, `…_seconds_sum`, and the p95/p99
     from `/actuator/prometheus` (or `/actuator/metrics/ay_upstox_ws_tick_latency`).
   - `ay_upstox_ws_ticks_received_total` — tick rate (sanity: should track the subscribed set).
   - `ay_kite_ws_reconnects_total` / `ay_kite_circuit_state` — reuse from the shared supervisor.
4. Let it run ≥10 min during active trading. Record median + p95 staleness for the SAME instruments
   measured in step A.

## C. Decide the gate (§17.3 / §21.3)

- **PASS (flip eligible):** Upstox-WS tick staleness is **≤ Kite-WS** (or within an owner-acceptable
  margin) at p95 for the scalp instruments, with no reconnect storms / gaps (`ay_kite_ws_reconnects_total`
  flat, no `tick gap … scheduling 1m backfill` floods). Only then is `ARTHA_MD_SOURCE_TICKER=upstox`
  eligible to become the deployed default (Wave U4 cutover).
- **FAIL / inconclusive:** revert by unsetting the flag (or `=kite`) + recreate market-data. The Kite
  WS path is the unchanged default; no data is lost (the candle store + registry are source-agnostic).

## Known gap to close before a real scalp A/B

Token→Upstox-`instrument_key` mapping currently covers **indices + NSE cash equities** (the verified
U2 map). Exchange-traded **F&O FUT/option** symbols have a numeric Upstox key not derivable from the
tradingsymbol, so they are logged + skipped (`no upstox instrument_key for … — not streamed over
upstox`) and do not stream over Upstox. Index-spot latency is measurable today; a full **option-strike
scalp** A/B needs a token→Upstox-key map for F&O built from the Upstox instrument master (the U4
follow-up). Until then, treat the gate as measuring **index/spot** tick latency only.
