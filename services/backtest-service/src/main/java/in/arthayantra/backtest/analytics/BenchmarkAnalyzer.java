package in.arthayantra.backtest.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import in.arthayantra.backtest.regime.BenchmarkSeries;
import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.backtest.replay.EquityPoint;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Computes the §D.16 benchmark-relative block (pure post-processing — no replay change). It reads
 * the run's benchmark ({@code backtest.defaults.benchmark}, default {@code NSE:NIFTY 50}) daily from
 * {@code marketdata} read-only — the same series guard 6 already pre-flights — builds the benchmark
 * buy-and-hold curve <b>on the strategy {@code equity_curve}'s own timestamps</b> (so the two
 * overlay on an identical time base), and regresses daily-resampled strategy returns on daily
 * benchmark returns for alpha/beta/IR. When the benchmark has no daily coverage for the window it
 * returns {@link BenchmarkAnalytics#absent()} — NULL columns, flagged, never silently zero.
 *
 * <p>Price-index caveat (documented, not hidden): NIFTY indices on Kite are price indices, so every
 * benchmark-relative number is mildly flattered (dividends excluded). The caveat string is surfaced
 * by {@code BacktestRunner} on the results payload.
 */
@Component
public class BenchmarkAnalyzer {

  /** Benchmark returns are computed on a daily basis (the benchmark series is daily). */
  private static final double PERIODS_PER_YEAR = 252.0;

  private static final double YEAR_SECONDS = 365.25 * 24 * 3600;

  private final CandleReader candleReader;

  /** Wires the read-only candle reader. */
  public BenchmarkAnalyzer(CandleReader candleReader) {
    this.candleReader = candleReader;
  }

  /**
   * Resolves the benchmark block for one completed run.
   *
   * @param config the resolved strategy config (for {@code backtest.defaults.benchmark})
   * @param equityCurve the persisted (downsampled) strategy equity curve — the alignment time base
   * @param from run window start (inclusive)
   * @param to run window end (exclusive)
   * @param initialEquity starting equity, the normalization base for the benchmark curve
   * @param strategyCagrFraction the strategy's CAGR as a fraction (e.g. 0.25), for excess CAGR
   * @return the benchmark block, or {@link BenchmarkAnalytics#absent()} when coverage is missing
   */
  public BenchmarkAnalytics analyze(
      JsonNode config,
      List<EquityPoint> equityCurve,
      OffsetDateTime from,
      OffsetDateTime to,
      BigDecimal initialEquity,
      BigDecimal strategyCagrFraction) {
    if (equityCurve == null || equityCurve.isEmpty()) {
      return BenchmarkAnalytics.absent();
    }
    BenchmarkSeries benchmark = BenchmarkSeries.resolve(config);
    // D7: read the NATIVE daily source (candles@1d — the same series guard 6 pre-flights), not the
    // 1m-rolled candles_1d cagg, so benchmark analytics use the dense daily bars the regime plane
    // and chart draw rather than a series that is thin on a fresh boot.
    List<EngineCandle> daily =
        candleReader.readDaily(benchmark.exchange(), benchmark.tradingsymbol(), from, to);
    if (daily.isEmpty()) {
      return BenchmarkAnalytics.absent();
    }

    TreeMap<LocalDate, BigDecimal> closeByDate = new TreeMap<>();
    for (EngineCandle c : daily) {
      closeByDate.put(c.bucketStart().toLocalDate(), c.close());
    }
    BigDecimal firstClose = closeByDate.firstEntry().getValue();
    if (firstClose.signum() <= 0) {
      return BenchmarkAnalytics.absent();
    }

    List<EquityPoint> benchmarkCurve = benchmarkCurve(equityCurve, closeByDate, initialEquity, firstClose);

    // daily-aligned return series: last strategy equity per calendar date vs the benchmark close
    // on-or-before that date; index-aligned (same date sequence) for the regression.
    List<LocalDate> dates = new ArrayList<>();
    List<BigDecimal> strategyDailyEquity = new ArrayList<>();
    LocalDate lastDate = null;
    for (EquityPoint p : equityCurve) {
      LocalDate d = p.ts().toLocalDate();
      if (!d.equals(lastDate)) {
        dates.add(d);
        strategyDailyEquity.add(p.equity());
        lastDate = d;
      } else {
        strategyDailyEquity.set(strategyDailyEquity.size() - 1, p.equity());
      }
    }

    double[] strat = returns(strategyDailyEquity);
    double[] bench = benchmarkReturns(dates, closeByDate);
    BigDecimal benchmarkCagr = benchmarkCagr(equityCurve, closeByDate);

    BenchmarkMetrics.Result m =
        BenchmarkMetrics.compute(strat, bench, PERIODS_PER_YEAR, strategyCagrFraction, benchmarkCagr);

    return new BenchmarkAnalytics(
        true, m.alpha(), m.beta(), m.informationRatio(), m.excessCagr(), m.upCapture(),
        m.downCapture(), benchmarkCurve);
  }

  /** The price-index (not total-return) caveat string surfaced with every present benchmark block. */
  public static String priceIndexCaveat() {
    return "Benchmark is a price index (dividends excluded); benchmark-relative metrics are mildly "
        + "flattered.";
  }

  private List<EquityPoint> benchmarkCurve(
      List<EquityPoint> equityCurve,
      TreeMap<LocalDate, BigDecimal> closeByDate,
      BigDecimal initialEquity,
      BigDecimal firstClose) {
    List<EquityPoint> curve = new ArrayList<>(equityCurve.size());
    for (EquityPoint p : equityCurve) {
      BigDecimal close = closeOnOrBefore(closeByDate, p.ts().toLocalDate(), firstClose);
      BigDecimal normalized =
          initialEquity
              .multiply(close)
              .divide(firstClose, 2, RoundingMode.HALF_UP);
      curve.add(new EquityPoint(p.ts(), normalized));
    }
    return curve;
  }

  private double[] benchmarkReturns(
      List<LocalDate> dates, TreeMap<LocalDate, BigDecimal> closeByDate) {
    if (dates.size() < 2) {
      return new double[0];
    }
    BigDecimal first = closeOnOrBefore(closeByDate, dates.get(0), closeByDate.firstEntry().getValue());
    double[] out = new double[dates.size() - 1];
    BigDecimal prev = first;
    for (int i = 1; i < dates.size(); i++) {
      BigDecimal cur = closeOnOrBefore(closeByDate, dates.get(i), prev);
      out[i - 1] = prev.signum() == 0 ? 0 : cur.subtract(prev).doubleValue() / prev.doubleValue();
      prev = cur;
    }
    return out;
  }

  private BigDecimal benchmarkCagr(
      List<EquityPoint> equityCurve, TreeMap<LocalDate, BigDecimal> closeByDate) {
    OffsetDateTime start = equityCurve.get(0).ts();
    OffsetDateTime end = equityCurve.get(equityCurve.size() - 1).ts();
    double years = Duration.between(start, end).toSeconds() / YEAR_SECONDS;
    if (years <= 0) {
      return null;
    }
    BigDecimal first = closeOnOrBefore(closeByDate, start.toLocalDate(), closeByDate.firstEntry().getValue());
    BigDecimal last = closeOnOrBefore(closeByDate, end.toLocalDate(), first);
    if (first.signum() <= 0 || last.signum() <= 0) {
      return null;
    }
    double ratio = last.doubleValue() / first.doubleValue();
    double cagr = Math.pow(ratio, 1.0 / years) - 1.0;
    if (Double.isNaN(cagr) || Double.isInfinite(cagr)) {
      return null;
    }
    return BigDecimal.valueOf(cagr).setScale(6, RoundingMode.HALF_UP);
  }

  private static double[] returns(List<BigDecimal> equity) {
    if (equity.size() < 2) {
      return new double[0];
    }
    double[] out = new double[equity.size() - 1];
    for (int i = 1; i < equity.size(); i++) {
      double prev = equity.get(i - 1).doubleValue();
      double cur = equity.get(i).doubleValue();
      out[i - 1] = prev == 0 ? 0 : (cur - prev) / prev;
    }
    return out;
  }

  private static BigDecimal closeOnOrBefore(
      TreeMap<LocalDate, BigDecimal> closeByDate, LocalDate date, BigDecimal fallback) {
    var entry = closeByDate.floorEntry(date);
    return entry == null ? fallback : entry.getValue();
  }
}
