package in.arthayantra.marketdata.kite;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Port 4/5 (A.7a): historical candles. Live = Kite historical API behind the 3 req/s budget
 * (Stage B, Phase 11); mock returns nothing in Stage A (its consumers arrive with the candle
 * cache).
 */
public interface HistoricalCandleGateway {

  /** One OHLCV bar; prices are exact decimals, bucket start is IST, OI for F&O. */
  record Candle(
      InstrumentKey key,
      String interval,
      OffsetDateTime bucketStart,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      long volume,
      Long oi) {}

  /** Candles for {@code key} at {@code interval} within {@code [from, to)}. */
  List<Candle> fetch(InstrumentKey key, String interval, Instant from, Instant to);
}
