package in.arthayantra.backtest.regime;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Computes (never declares) the daily regime label for each benchmark session (guard 6, §D.4). The
 * label for day {@code T} is the cross product of:
 *
 * <ul>
 *   <li><b>trend</b> = benchmark close vs its {@value #TREND_SMA}-day simple moving average — {@code
 *       UP} when close ≥ SMA, else {@code DOWN};
 *   <li><b>volatility</b> = {@value #VOL_WINDOW}-day realized volatility (std-dev of daily log
 *       returns) vs the trailing {@value #VOL_MEDIAN_WINDOW}-day median of that same rolling vol
 *       series — {@code TURBULENT} when current vol &gt; median, else {@code QUIET}.
 * </ul>
 *
 * <p><b>T−1 boundary (the FAIL condition is same-session look-ahead).</b> Both dimensions for entry
 * day {@code T} are computed from benchmark closes <b>strictly before {@code T}</b> — i.e. through
 * the prior session's close ({@code T−1}). Day {@code T}'s own benchmark close is NEVER consulted.
 * Concretely: the labeled day at series index {@code i} is keyed by {@code dates[i]} but its trend
 * and vol are read off the window ending at index {@code i−1}.
 *
 * <p><b>Warm-up depth.</b> A label is only emitted for an entry day that has, before it, ≥ {@value
 * #TREND_SMA} closes for the trend SMA and ≥ {@value #VOL_MEDIAN_WINDOW} {@code +} {@value
 * #VOL_WINDOW} closes for the vol-median window — whichever is larger. The {@link RegimePreflight}
 * coverage gate guarantees that depth exists before the run window, so labels never silently degrade
 * to partial coverage.
 *
 * <p>Deterministic: a pure walk of the bucket-ordered benchmark series in BigDecimal-derived double
 * statistics rounded nowhere visible; no wall-clock, no randomness, insertion-ordered output map.
 */
@Component
public class RegimeLabeler {

  /** Trend SMA length (≈ 200 trading days). */
  public static final int TREND_SMA = 200;
  /** Realized-volatility window (20 trading days). */
  public static final int VOL_WINDOW = 20;
  /** Trailing median window for the volatility threshold (≈ 1 trading year). */
  public static final int VOL_MEDIAN_WINDOW = 252;

  /**
   * The minimum number of benchmark sessions that must precede the FIRST labeled entry day — the
   * larger of the trend-SMA depth and the vol-median depth. A run's pre-flight asserts at least this
   * many benchmark sessions exist before the window start (§D.4 / §D.6).
   */
  public static final int WARMUP_SESSIONS = Math.max(TREND_SMA, VOL_MEDIAN_WINDOW + VOL_WINDOW);

  /**
   * Labels every benchmark session that has sufficient prior warm-up. The benchmark daily series
   * must be in {@code bucketStart} order (oldest first), as read from {@code marketdata.candles_1d}.
   *
   * @param dailyBenchmark the benchmark 1d candles in bucket order (oldest first)
   * @return entry-day → label, insertion-ordered by entry day; days lacking warm-up are absent
   */
  public Map<LocalDate, RegimeLabel> label(List<EngineCandle> dailyBenchmark) {
    Map<LocalDate, RegimeLabel> out = new LinkedHashMap<>();
    int n = dailyBenchmark.size();
    if (n < 2) {
      return out;
    }

    double[] closes = new double[n];
    LocalDate[] dates = new LocalDate[n];
    for (int i = 0; i < n; i++) {
      EngineCandle c = dailyBenchmark.get(i);
      closes[i] = c.close().doubleValue();
      dates[i] = c.bucketStart().toLocalDate();
    }

    // Rolling VOL_WINDOW realized vol at the END of each index (vol[i] uses returns up to and
    // including close[i]). vol[i] is defined only for i >= VOL_WINDOW (needs VOL_WINDOW returns).
    double[] vol = realizedVol(closes);

    // Entry day at index i is labeled off the window ending at i-1 (the T-1 boundary). The first
    // labelable entry day needs i-1 >= WARMUP_SESSIONS-1, i.e. i >= WARMUP_SESSIONS.
    for (int i = WARMUP_SESSIONS; i < n; i++) {
      int priorEnd = i - 1; // last index allowed to feed day i's label (== close of T-1)
      boolean up = closes[priorEnd] >= sma(closes, priorEnd, TREND_SMA);
      boolean turbulent = vol[priorEnd] > trailingVolMedian(vol, priorEnd, VOL_MEDIAN_WINDOW);
      out.put(dates[i], RegimeLabel.of(up, turbulent));
    }
    return out;
  }

  /** Simple moving average of {@code closes[end-length+1 .. end]} inclusive. */
  private static double sma(double[] closes, int end, int length) {
    double sum = 0;
    for (int i = end - length + 1; i <= end; i++) {
      sum += closes[i];
    }
    return sum / length;
  }

  /**
   * Rolling realized volatility (sample, n−1 std-dev of daily log returns) ending at each index.
   * {@code vol[i]} for {@code i &lt; VOL_WINDOW} is {@code NaN} (insufficient returns) and is never
   * read by a labeled entry day (warm-up guarantees it).
   */
  private static double[] realizedVol(double[] closes) {
    int n = closes.length;
    double[] logRet = new double[n];
    logRet[0] = Double.NaN;
    for (int i = 1; i < n; i++) {
      logRet[i] = closes[i - 1] <= 0 ? 0.0 : Math.log(closes[i] / closes[i - 1]);
    }
    double[] vol = new double[n];
    for (int i = 0; i < n; i++) {
      if (i < VOL_WINDOW) {
        vol[i] = Double.NaN;
        continue;
      }
      // VOL_WINDOW returns ending at i: indices [i-VOL_WINDOW+1 .. i].
      double sum = 0;
      for (int j = i - VOL_WINDOW + 1; j <= i; j++) {
        sum += logRet[j];
      }
      double mean = sum / VOL_WINDOW;
      double sq = 0;
      for (int j = i - VOL_WINDOW + 1; j <= i; j++) {
        double d = logRet[j] - mean;
        sq += d * d;
      }
      vol[i] = Math.sqrt(sq / (VOL_WINDOW - 1));
    }
    return vol;
  }

  /**
   * Trailing median of the rolling vol series over {@code [end-window+1 .. end]}, skipping the {@code
   * NaN} warm-up entries. Median of an even count is the mean of the two middle values (deterministic
   * tie-handling).
   */
  private static double trailingVolMedian(double[] vol, int end, int window) {
    List<Double> sample = new ArrayList<>(window);
    for (int i = Math.max(0, end - window + 1); i <= end; i++) {
      if (!Double.isNaN(vol[i])) {
        sample.add(vol[i]);
      }
    }
    Collections.sort(sample);
    int m = sample.size();
    if (m == 0) {
      return Double.POSITIVE_INFINITY; // no comparable history -> never turbulent (defensive)
    }
    if (m % 2 == 1) {
      return sample.get(m / 2);
    }
    return (sample.get(m / 2 - 1) + sample.get(m / 2)) / 2.0;
  }

  /** Exposes the per-fold regime-mix distribution as ordered label→count over a set of entry days. */
  public static Map<RegimeLabel, Integer> mix(
      List<LocalDate> entryDays, Map<LocalDate, RegimeLabel> labels) {
    Map<RegimeLabel, Integer> counts = new LinkedHashMap<>();
    for (RegimeLabel l : RegimeLabel.values()) {
      counts.put(l, 0);
    }
    for (LocalDate d : entryDays) {
      RegimeLabel l = labels.get(d);
      if (l != null) {
        counts.merge(l, 1, Integer::sum);
      }
    }
    return counts;
  }
}
