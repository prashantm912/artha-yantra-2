/**
 * Intelligence-layer context plane (design 2026-07-10 §6.1 / §6.6) — the market-data decision-support
 * digests. I1 ships the two foundation reads (options digest + day-context one-call, {@link
 * in.arthayantra.marketdata.context.MarketContextController}) as typed folds over the EXISTING
 * analytics folds (no new raw data), plus the one new persisted table {@code market_context_days}
 * ({@link in.arthayantra.marketdata.context.MarketContextDayRepository}) written by the EOD job
 * ({@link in.arthayantra.marketdata.context.MarketContextEodJob}, registered in {@code ingest_runs}).
 *
 * <p>A leaf module: it consumes other modules' APIs (the options digest, candles, the ingest-health
 * board, the VIX/global quote ports, the world-indices client, the market calendar) and nothing in the
 * service depends back on it.
 */
package in.arthayantra.marketdata.context;
