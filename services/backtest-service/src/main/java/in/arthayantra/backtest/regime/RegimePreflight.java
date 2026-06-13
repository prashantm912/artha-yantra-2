package in.arthayantra.backtest.regime;

import in.arthayantra.backtest.replay.CandleReader;
import in.arthayantra.common.web.error.ApiException;
import in.arthayantra.common.web.error.ErrorCodes;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The §D.4 / §D.6 benchmark coverage pre-flight for regime attribution (guard 6): the benchmark
 * daily series must carry the full warm-up depth ({@value RegimeLabeler#WARMUP_SESSIONS} sessions =
 * ~200 trading days for the trend SMA, ~1 year + 20 days for the vol median) <b>before the run
 * window start</b>, or the run fails fast with <b>422 {@code DATA_GAP}</b> naming the benchmark
 * series. Labels never silently degrade to partial coverage — a window that cannot be fully attributed
 * is refused, not half-labeled.
 */
@Component
public class RegimePreflight {

  private final CandleReader reader;

  /** Wires the candle reader. */
  public RegimePreflight(CandleReader reader) {
    this.reader = reader;
  }

  /**
   * Throws 422 {@code DATA_GAP} when the benchmark daily series lacks the warm-up depth before {@code
   * from}. Counts {@code candles_1d} buckets strictly before the window start.
   *
   * @param benchmark the resolved benchmark series
   * @param from the run window start (inclusive)
   */
  public void check(BenchmarkSeries benchmark, OffsetDateTime from) {
    long present =
        reader.count1dBucketsBefore(benchmark.exchange(), benchmark.tradingsymbol(), from);
    if (present < RegimeLabeler.WARMUP_SESSIONS) {
      throw new ApiException(
          422,
          ErrorCodes.DATA_GAP,
          "insufficient benchmark warm-up coverage for regime attribution: "
              + benchmark.raw(),
          Map.of(
              "series", "benchmark",
              "exchange", benchmark.exchange(),
              "tradingsymbol", benchmark.tradingsymbol(),
              "interval", "1d",
              "before", from.toString(),
              "requiredWarmupSessions", RegimeLabeler.WARMUP_SESSIONS,
              "presentSessions", present,
              "missingSessions", RegimeLabeler.WARMUP_SESSIONS - present));
    }
  }
}
