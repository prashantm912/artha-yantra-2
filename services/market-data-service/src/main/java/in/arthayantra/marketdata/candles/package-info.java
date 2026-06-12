/**
 * Stage-B placeholder (Phase 10): the 1m candle builder (idempotent upserts keyed
 * {@code (exchange, tradingsymbol, interval, bucket)}) and the TimescaleDB hypertable cache land
 * here. Subscribing to {@code candles.1m.*} is already legal — silence is fine (A.7.2).
 */
package in.arthayantra.marketdata.candles;
