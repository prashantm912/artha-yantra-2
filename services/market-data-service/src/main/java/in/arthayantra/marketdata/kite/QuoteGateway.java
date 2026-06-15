package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * Port 3/5 (A.7a): batched spot quotes. Live = Kite quote API with the KITE_QUOTE_BATCH_SIZE
 * budget (Stage B); mock = the last-tick map.
 */
public interface QuoteGateway {

  /**
   * A batched quote. Phase 15 widens the Stage-A shape with the chain fields: best bid/ask,
   * cumulative day volume and OI ({@code null} where the venue has none — e.g. indices).
   */
  record Quote(
      InstrumentKey key,
      BigDecimal lastPrice,
      BigDecimal bid,
      BigDecimal ask,
      Long volume,
      Long oi,
      Ohlc ohlc,
      OffsetDateTime timestamp) {

    /**
     * Day range from the venue quote (Kite {@code ohlc}); {@code close} is the PREVIOUS day's
     * close. {@code null} where the segment has none (Stage-G futures OHLC capture).
     */
    public record Ohlc(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}

    /** The Stage-A LTP-only shape (spot quotes). */
    public Quote(InstrumentKey key, BigDecimal lastPrice, OffsetDateTime timestamp) {
      this(key, lastPrice, null, null, null, null, null, timestamp);
    }

    /** The Phase-15 chain shape (bid/ask/volume/oi) without day OHLC. */
    public Quote(
        InstrumentKey key,
        BigDecimal lastPrice,
        BigDecimal bid,
        BigDecimal ask,
        Long volume,
        Long oi,
        OffsetDateTime timestamp) {
      this(key, lastPrice, bid, ask, volume, oi, null, timestamp);
    }
  }

  /** Last-known quotes for the requested instruments; absent keys are omitted. */
  Map<InstrumentKey, Quote> quotes(Collection<InstrumentKey> keys);
}
