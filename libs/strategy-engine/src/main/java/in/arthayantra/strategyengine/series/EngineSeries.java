package in.arthayantra.strategyengine.series;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNumFactory;

/**
 * One evaluated series: a ta4j {@link BarSeries} (DecimalNum, precision 32 — exact decimals,
 * never doubles) plus the per-bar open-interest column ta4j bars cannot carry, plus the IST
 * session geometry (session start indices, previous-session pointers) the session-level
 * indicator family (ORB/PREV_DAY/DAY/GAP — A7 [FP-18]) reads. Bars are appended in bucket-start
 * order; "day" boundaries are IST trading sessions derived from bar timestamps, never
 * calendar/UTC days.
 */
public final class EngineSeries {

  /** IST — bars arrive with +05:30 bucket starts (platform convention). */
  public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  /** DecimalNum precision used across the engine (mirrors the vector generator). */
  public static final int PRECISION = 32;

  private final SeriesKey key;
  private final BarSeries barSeries;
  private final List<EngineCandle> candles = new ArrayList<>();
  private final List<Integer> sessionStartIndex = new ArrayList<>();
  private final List<Integer> prevSessionEndIndex = new ArrayList<>();
  private final Duration barDuration;

  /** Creates an empty series for a key. */
  public EngineSeries(SeriesKey key) {
    this.key = key;
    this.barSeries =
        new BaseBarSeriesBuilder()
            .withName(key.canonical())
            .withNumFactory(DecimalNumFactory.getInstance(PRECISION))
            .build();
    this.barDuration = intervalDuration(key.interval());
  }

  /** Builds a series from candles already in order. */
  public static EngineSeries of(SeriesKey key, List<EngineCandle> candles) {
    EngineSeries series = new EngineSeries(key);
    candles.forEach(series::append);
    return series;
  }

  /** Appends one bar (bucket starts must be strictly increasing). */
  public void append(EngineCandle candle) {
    if (!candles.isEmpty()
        && !candle.bucketStart().toInstant().isAfter(lastCandle().bucketStart().toInstant())) {
      throw new IllegalArgumentException(
          "bars must be appended in increasing bucket order: " + candle.bucketStart());
    }
    Instant begin = candle.bucketStart().toInstant();
    barSeries
        .barBuilder()
        .timePeriod(barDuration)
        .beginTime(begin)
        .endTime(begin.plus(barDuration))
        .openPrice(candle.open().toPlainString())
        .highPrice(candle.high().toPlainString())
        .lowPrice(candle.low().toPlainString())
        .closePrice(candle.close().toPlainString())
        .volume(String.valueOf(candle.volume()))
        .amount("0")
        .add();
    int index = candles.size();
    LocalDate session = sessionDate(candle);
    if (candles.isEmpty() || !session.equals(sessionDate(lastCandle()))) {
      prevSessionEndIndex.add(candles.isEmpty() ? -1 : index - 1);
      sessionStartIndex.add(index);
    }
    candles.add(candle);
  }

  /** The IST trading-session date of a bar. */
  public static LocalDate sessionDate(EngineCandle candle) {
    return candle.bucketStart().withOffsetSameInstant(IST).toLocalDate();
  }

  /** The underlying ta4j series. */
  public BarSeries barSeries() {
    return barSeries;
  }

  /** The series key. */
  public SeriesKey key() {
    return key;
  }

  /** Bar count. */
  public int size() {
    return candles.size();
  }

  /** Bar at an index. */
  public EngineCandle candle(int index) {
    return candles.get(index);
  }

  private EngineCandle lastCandle() {
    return candles.get(candles.size() - 1);
  }

  /** Open interest at an index; null when the instrument carries none. */
  public BigDecimal oiAt(int index) {
    return candles.get(index).oi();
  }

  /** Index of the first bar of the session containing {@code index}. */
  public int sessionStart(int index) {
    int start = 0;
    for (int s : sessionStartIndex) {
      if (s <= index) {
        start = s;
      } else {
        break;
      }
    }
    return start;
  }

  /** Last index of the PREVIOUS session, or -1 on the first covered session. */
  public int previousSessionEnd(int index) {
    int sessionStart = sessionStart(index);
    int position = sessionStartIndex.indexOf(sessionStart);
    return prevSessionEndIndex.get(position);
  }

  /** Greatest index whose bucket start is at or before {@code instant}; -1 when none. */
  public int indexAtOrBefore(Instant instant) {
    int lo = 0;
    int hi = candles.size() - 1;
    int result = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (!candles.get(mid).bucketStart().toInstant().isAfter(instant)) {
        result = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return result;
  }

  /** Bucket-start instant of the last bar; null when empty. */
  public Instant lastBarTime() {
    return candles.isEmpty() ? null : lastCandle().bucketStart().toInstant();
  }

  static Duration intervalDuration(String interval) {
    return switch (interval) {
      case "1m" -> Duration.ofMinutes(1);
      case "3m" -> Duration.ofMinutes(3);
      case "5m" -> Duration.ofMinutes(5);
      case "15m" -> Duration.ofMinutes(15);
      case "1h" -> Duration.ofHours(1);
      case "1d" -> Duration.ofDays(1);
      case "1w" -> Duration.ofDays(7);
      default -> throw new IllegalArgumentException("unsupported interval " + interval);
    };
  }
}
