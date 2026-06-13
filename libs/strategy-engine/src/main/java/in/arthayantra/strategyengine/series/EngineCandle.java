package in.arthayantra.strategyengine.series;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One OHLCV bar as the engine consumes it — exact decimals (never floats), IST bucket start,
 * open interest for F&amp;O (null elsewhere). Both producers (the live candle cache and the golden
 * CSV fixtures) map into this shape, so live and replay evaluate identical inputs (D7).
 */
public record EngineCandle(
    OffsetDateTime bucketStart,
    BigDecimal open,
    BigDecimal high,
    BigDecimal low,
    BigDecimal close,
    long volume,
    BigDecimal oi) {

  /** Without OI. */
  public EngineCandle(
      OffsetDateTime bucketStart, BigDecimal open, BigDecimal high, BigDecimal low,
      BigDecimal close, long volume) {
    this(bucketStart, open, high, low, close, volume, null);
  }
}
