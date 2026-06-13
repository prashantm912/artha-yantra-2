package in.arthayantra.strategyengine.fills;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.time.LocalTime;
import java.util.List;

/**
 * Assembles the deterministic BTST pre-close bar view (A9 [FP-6]) — a single OHLC bar aggregated
 * from the session's 1m candles up to {@code risk.session.pre_close_at} (default 15:20). Built
 * byte-identically in live and replay, so the pre-close clock plus {@code at_close} fills capture
 * the overnight gap that an after-close evaluation structurally misses.
 */
public final class PreCloseBarView {

  private PreCloseBarView() {}

  /** Aggregates an ordered, non-empty 1m slice into one OHLC bar. */
  public static EngineCandle assemble(List<EngineCandle> oneMinuteBars) {
    if (oneMinuteBars == null || oneMinuteBars.isEmpty()) {
      throw new IllegalArgumentException("pre-close view needs at least one 1m bar");
    }
    EngineCandle first = oneMinuteBars.get(0);
    var high = first.high();
    var low = first.low();
    var close = first.close();
    long volume = 0;
    var oi = first.oi();
    for (EngineCandle bar : oneMinuteBars) {
      high = high.max(bar.high());
      low = low.min(bar.low());
      close = bar.close();
      volume += bar.volume();
      oi = bar.oi();
    }
    return new EngineCandle(first.bucketStart(), first.open(), high, low, close, volume, oi);
  }

  /** Filters a day's 1m bars to those closing at or before {@code preCloseAt}, then assembles. */
  public static EngineCandle assembleUpTo(List<EngineCandle> dayBars, LocalTime preCloseAt) {
    List<EngineCandle> slice =
        dayBars.stream()
            .filter(bar -> bar.bucketStart().toLocalTime().isBefore(preCloseAt))
            .toList();
    return assemble(slice);
  }
}
