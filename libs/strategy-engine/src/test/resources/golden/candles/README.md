# Golden candle fixtures — FROZEN (docs/golden-vectors.md §1)

Generated ONCE by `GoldenCandleFixtureGenerator` (market-data-service test
scope, gated behind `-Dgolden.generate=true`) and committed; **never
regenerate** — that is a format-breaking event requiring a fixture-format
bump and a single PR updating every consumer.

Generation record:

- Generator: `MockTickGenerator` (CD-10 seeded random walk), **seed 42**,
  scenario **trend-up**, generator version = Stage A Phase 7 implementation
  (services/market-data-service `mockfeed`).
- Roll-up: 4 ticks per minute folded into 1m OHLC bars; volume is the
  cumulative-day-volume delta per bar (A.7.2 convention).
- Sessions: five trading days 2026-01-05 .. 2026-01-09 (Mon–Fri, no NSE
  holidays), 375 one-minute buckets each, 09:15–15:29 IST.
- Instruments: `NSE:NIFTY 50` (token 256265, the primary fixture) and
  `NSE:INDIA VIX` (token 264969, the A7 context-series fixture).
- Prices are exact decimal strings snapped to the 0.05 tick; identity is
  `(exchange, tradingsymbol)`; buckets are IST starts with explicit `+05:30`.
