package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The live {@link SeriesProvider}: one {@link EngineSeries} per (instrument, interval), warmed
 * from market-data REST and appended live off the candle channel. This map IS the shared
 * context-series cache (§C-2.3) — N strategies referencing NIFTY 1d cost one series. Out-of-order
 * or replayed bars are dropped at append (the series enforces increasing buckets).
 */
@Component
public class LiveSeriesStore implements SeriesProvider {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private final MarketDataCandlesClient candlesClient;
  private final Clock clock;
  private final Map<SeriesKey, EngineSeries> series = new ConcurrentHashMap<>();

  /** Wires the warm-up client. */
  public LiveSeriesStore(MarketDataCandlesClient candlesClient, Clock clock) {
    this.candlesClient = candlesClient;
    this.clock = clock;
  }

  /** Warms a series if absent (idempotent). */
  public void ensureWarm(SeriesKey key) {
    series.computeIfAbsent(
        key,
        k -> {
          OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
          OffsetDateTime from = now.minusDays(warmupDays(k.interval()));
          List<EngineCandle> warm =
              candlesClient.fetch(k.exchange(), k.tradingsymbol(), k.interval(), from, now);
          EngineSeries fresh = new EngineSeries(k);
          for (EngineCandle candle : warm) {
            appendQuietly(fresh, candle);
          }
          return fresh;
        });
  }

  /** Appends a live bar (creates the series cold when warm-up was unavailable). */
  public void append(SeriesKey key, EngineCandle candle) {
    EngineSeries target = series.computeIfAbsent(key, EngineSeries::new);
    appendQuietly(target, candle);
  }

  @Override
  public EngineSeries series(SeriesKey key) {
    return series.get(key);
  }

  /** Refreshes a non-1m series from the caggs (called at primary bucket boundaries). */
  public void refreshFromRest(SeriesKey key) {
    EngineSeries existing = series.get(key);
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(IST);
    OffsetDateTime from =
        existing == null || existing.lastBarTime() == null
            ? now.minusDays(warmupDays(key.interval()))
            : OffsetDateTime.ofInstant(existing.lastBarTime(), IST);
    List<EngineCandle> fresh =
        candlesClient.fetch(key.exchange(), key.tradingsymbol(), key.interval(), from, now);
    EngineSeries target = series.computeIfAbsent(key, EngineSeries::new);
    for (EngineCandle candle : fresh) {
      appendQuietly(target, candle);
    }
  }

  private static void appendQuietly(EngineSeries target, EngineCandle candle) {
    try {
      target.append(candle);
    } catch (IllegalArgumentException outOfOrder) {
      // replayed/stale bar — the series stays strictly increasing
    }
  }

  private static int warmupDays(String interval) {
    return switch (interval) {
      case "1m" -> 4;
      case "5m", "15m" -> 10;
      case "1h" -> 30;
      case "1d" -> 180;
      case "1w" -> 730;
      default -> 4;
    };
  }
}
