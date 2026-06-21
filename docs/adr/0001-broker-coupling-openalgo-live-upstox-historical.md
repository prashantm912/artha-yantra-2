# Broker coupling: OpenAlgo for the live/swappable path, direct Upstox-Java for historical/expired

Status: accepted (2026-06-21)

Master-plan §21 set a rule — "never import a broker SDK; route everything through OpenAlgo so the broker
stays swappable." We are **keeping** that for the live/execution/swappable path (quotes, chain OI,
candles), but **deviating** for the **historical OHLC+OI import** path: when we need deep / expired-
contract OI (the backtesting "data-foundation B" milestone), we will depend on the **direct Upstox-Java
SDK** (`com.upstox.api:upstox-java-sdk`, Apache-licensed, importable) behind a `HistoricalCandleGateway`
impl, scoped by a **second Upstox token**. Reason: OpenAlgo simply cannot serve expired-contract OI —
there is no swappable alternative — so the swap-ability the §21 rule protects does not apply here, and an
isolated, behind-a-port historical importer is a different concern from the live critical path.

## Considered options (historical OHLC + OI source)

- **OpenAlgo `/history` only** — rejected: returns per-bar OI but **active contracts only**; cannot reach
  expired F&O (the whole point of deep history). Fine for the verify-now (A) milestone on *recent active*
  sessions, which is why (A) needs no SDK at all.
- **Direct Upstox-Java SDK** (chosen for B) — Upstox's own `/historical-candle` (V3) and
  `/expired-instruments/historical-candle` both return `candle[6] = Open Interest`, active + expired, deep
  to Jan 2022. Apache-licensed → importable behind our port; one isolated impl; 2nd token isolates the
  heavy backfill from live rate limits.
- **ExpiryTrack appliance** (kept as an option for the *bulk* archive) — AGPL Flask app that wraps the
  same Upstox expired API with enumeration + DuckDB/Parquet bulk storage. AGPL ⇒ appliance-only (consume
  output, never import), same boundary as OpenAlgo. Use it if the bulk-backfill ergonomics beat
  hand-rolling the enumeration; otherwise the direct SDK suffices.

## Consequences

- The live path stays broker-swappable (OpenAlgo); only the **historical-import** impl is Upstox-specific,
  isolated behind `HistoricalCandleGateway` (the existing anti-corruption pattern). A future reader seeing
  a direct Upstox dependency should read this ADR, not "fix" it back to OpenAlgo.
- (A) verify-now milestone takes on **no** new broker dependency — it rides the existing OpenAlgo scaffold.
  The Upstox-SDK / ExpiryTrack coupling lands only with the (B) backtesting milestone.
