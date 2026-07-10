package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.eval.SeriesProvider;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The live {@link SeriesProvider}: one {@link EngineSeries} per (instrument, interval), warmed
 * from market-data REST and appended live off the candle channel. This map IS the shared
 * context-series cache (§C-2.3) — N strategies referencing NIFTY 1d cost one series. Out-of-order
 * or replayed bars are dropped at append (the series enforces increasing buckets), and still-forming
 * (in-progress) intraday buckets are excluded from REST reads (see {@link #inProgress}) so a frozen
 * first-minute partial can never accrue (audit B1 / FID P0-1).
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
            if (inProgress(k.interval(), candle)) {
              continue; // a warm-tail row for a still-forming bucket is a partial — skip it
            }
            appendQuietly(fresh, candle);
          }
          return fresh;
        });
  }

  /**
   * Appends a live bar (creates the series cold when warm-up was unavailable). Returns {@code
   * false} for a duplicate/stale bar — the engine must skip evaluation for those, never re-run it.
   */
  public boolean append(SeriesKey key, EngineCandle candle) {
    EngineSeries target = series.computeIfAbsent(key, EngineSeries::new);
    return appendQuietly(target, candle);
  }

  @Override
  public EngineSeries series(SeriesKey key) {
    return series.get(key);
  }

  /**
   * A point-in-time snapshot of the warmed series keys — the {@link PartialBucketCanary} walks the
   * 3m series and cross-checks each against its in-memory 1m series. Read-only; never mutate.
   */
  public Set<SeriesKey> keys() {
    return Set.copyOf(series.keySet());
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
      if (inProgress(key.interval(), candle)) {
        continue; // the fetch window ends ~mid-bucket; that trailing partial must not accrue
      }
      appendQuietly(target, candle);
    }
  }

  /**
   * True when {@code candle}'s bucket is still IN PROGRESS at {@link #clock} — its end
   * ({@code bucketStart + interval}) has not yet passed. A cache-first cagg / 1m-rollup read whose
   * window ends mid-bucket returns that forming bucket as a partial; appending it FREEZES the
   * partial forever (the completed version arriving next boundary collides with the strictly
   * increasing {@link EngineSeries#append} and is swallowed by {@link #appendQuietly}) until a
   * restart — audit B1 / FID P0-1. Filtered for the intraday coarse intervals (1m/3m/5m/15m/1h);
   * 1d, 1w, and any unhandled interval fail OPEN (not filtered) — the 1d btst pre-close append +
   * daily-context semantics are FID P1-9, deliberately out of scope here.
   */
  private boolean inProgress(String interval, EngineCandle candle) {
    Duration intervalDuration = filterDuration(interval);
    return intervalDuration != null
        && candle.bucketStart().toInstant().plus(intervalDuration).isAfter(clock.instant());
  }

  private static Duration filterDuration(String interval) {
    return switch (interval) {
      case "1m" -> Duration.ofMinutes(1);
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      default -> null; // 1d, 1w, unknown → fail-open to the old (unfiltered) behavior
    };
  }

  private static boolean appendQuietly(EngineSeries target, EngineCandle candle) {
    try {
      target.append(candle);
      return true;
    } catch (IllegalArgumentException outOfOrder) {
      // replayed/stale bar — the series stays strictly increasing
      return false;
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
