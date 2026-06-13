package in.arthayantra.strategyengine.cache;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * D11 indicator value cache, keyed {@code (instrument, interval, indicator, paramsHash,
 * lastBarTime)} — LIVE path only; replay computes in-stream. The default in-memory impl is
 * bounded LRU; services may back it with Redis TTL keys (CD-16).
 */
public interface IndicatorValueCache {

  /** Cache key — lastBarTime pins the value to the bar it was computed on. */
  record Key(
      String exchange, String tradingsymbol, String interval, String indicatorId,
      String paramsHash, long lastBarEpochSeconds) {}

  /** Cached value, if present (cached nulls are not stored). */
  Optional<BigDecimal> get(Key key);

  /** Stores a computed value (no-op for null). */
  void put(Key key, BigDecimal value);
}
