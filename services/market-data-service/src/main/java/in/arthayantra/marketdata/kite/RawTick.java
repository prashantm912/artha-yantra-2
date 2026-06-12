package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A tick as produced by a {@link MarketFeed} (live Kite WS or the mock feed) before
 * normalization: token-keyed, exact-decimal price, cumulative day volume (Kite semantics —
 * per-bucket volume derives as bucket-end minus bucket-start, A.7.2) and open interest for F&O
 * instruments ({@code null} elsewhere).
 */
public record RawTick(
    long instrumentToken,
    BigDecimal lastPrice,
    long cumulativeDayVolume,
    Long openInterest,
    Instant exchangeTimestamp) {

  /** OI-less tick (cash/index). */
  public RawTick(
      long instrumentToken, BigDecimal lastPrice, long cumulativeDayVolume, Instant exchangeTimestamp) {
    this(instrumentToken, lastPrice, cumulativeDayVolume, null, exchangeTimestamp);
  }
}
