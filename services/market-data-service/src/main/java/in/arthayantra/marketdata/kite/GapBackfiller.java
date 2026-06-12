package in.arthayantra.marketdata.kite;

import java.time.Instant;

/**
 * Schedules a 1m candle backfill for an instrument silent through a reconnect (B-6 gap rule).
 * Implemented by the candles module over the rate-limited {@link HistoricalCandleGateway};
 * backfilled bars carry {@code source='BACKFILL'} so analytics can tell replayed from streamed.
 */
public interface GapBackfiller {

  /** Backfills 1m bars for {@code [from, to)} asynchronously through the rate-limited gateway. */
  void backfill(InstrumentKey key, Instant from, Instant to);

  /**
   * Gap-aware coverage pass (Phase 15A EOD backfill): fetches only missing buckets at
   * {@code baseInterval} (1m or 1d) — already-cached closed bars are never re-fetched.
   */
  void prefetch(InstrumentKey key, String baseInterval, Instant from, Instant to);
}
