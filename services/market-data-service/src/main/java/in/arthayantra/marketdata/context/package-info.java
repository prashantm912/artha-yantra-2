/**
 * Intelligence-layer context plane (design 2026-07-10 §6.1 / §6.4 / §6.6) — the market-data
 * decision-support digests. I1 ships the two foundation reads (options digest + day-context one-call,
 * {@link in.arthayantra.marketdata.context.MarketContextController}) as typed folds over the EXISTING
 * analytics folds (no new raw data), plus the one new persisted table {@code market_context_days}
 * ({@link in.arthayantra.marketdata.context.MarketContextDayRepository}) written by the EOD job
 * ({@link in.arthayantra.marketdata.context.MarketContextEodJob}, registered in {@code ingest_runs}).
 * I2 adds the futures / equity / FII digests ({@link
 * in.arthayantra.marketdata.context.FuturesDigestService}, {@link
 * in.arthayantra.marketdata.context.EquityDigestService}, {@link
 * in.arthayantra.marketdata.context.FiiDigestService}) and the cross-expiry compare ({@link
 * in.arthayantra.marketdata.context.ExpiryCompareService}), including the three NEW bhavcopy folds
 * §6.1.3 names ({@link in.arthayantra.marketdata.context.EquityContextRepository}: above-MA counts,
 * universe delivery-z, and the date-parameterized sector rotation).
 *
 * <p>A leaf module: it consumes other modules' APIs (the options digest, candles, the ingest-health
 * board, the futures term-structure read, the sector/index-weight reference, the VIX/global quote
 * ports, the world-indices client, the market calendar) and nothing in the service depends back on
 * it. Per the market-data cross-module convention it reaches only into base-package types; the
 * snapshot/EOD folds the digests need from other modules' internal {@code .analytics} sub-packages
 * are re-expressed here as self-contained JDBC folds ({@link
 * in.arthayantra.marketdata.context.EquityContextRepository} / {@link
 * in.arthayantra.marketdata.context.FiiContextRepository}) rather than importing those internal types.
 */
package in.arthayantra.marketdata.context;
