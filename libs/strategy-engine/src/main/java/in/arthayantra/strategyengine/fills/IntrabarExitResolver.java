package in.arthayantra.strategyengine.fills;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.util.List;

/**
 * The A9 [FP-5] intra-bar exit-touch rule, defined at the JAR level (the replay's 1m drill-down
 * wiring lands in Phase 30). Exits (stop / take-profit / trailing) are evaluated on each closed 1m
 * bar when {@code exit_intrabar}; where 1m coverage is missing the primary-bar high/low worst-of is
 * used with a gap-through fill at the bar open; with intra-bar off the exit is a close evaluation.
 * The parity floor is the closed 1m bar — NEVER tick-level. {@code upward} is the breach direction:
 * true for a long take-profit / short stop (price rising into the level), false for a long stop /
 * short take-profit (price falling into the level).
 */
public final class IntrabarExitResolver {

  private IntrabarExitResolver() {}

  /** The outcome: whether the level was touched, the fill price, and how it was detected. */
  public record Resolution(boolean touched, BigDecimal exitPrice, TouchBasis basis) {}

  /** Resolves an exit-level touch over a primary bar (optionally drilling into its 1m bars). */
  public static Resolution resolve(
      boolean upward,
      BigDecimal level,
      EngineCandle primaryBar,
      List<EngineCandle> oneMinuteBars,
      boolean exitIntrabar) {
    if (!exitIntrabar) {
      boolean hit = breachedByClose(upward, primaryBar, level);
      return new Resolution(hit, hit ? primaryBar.close() : null, TouchBasis.CLOSE_EVAL);
    }
    if (oneMinuteBars != null && !oneMinuteBars.isEmpty()) {
      for (EngineCandle bar : oneMinuteBars) {
        if (breached(upward, bar, level)) {
          return new Resolution(true, fillAt(upward, bar, level), TouchBasis.INTRABAR_1M);
        }
      }
      return new Resolution(false, null, TouchBasis.INTRABAR_1M);
    }
    boolean hit = breached(upward, primaryBar, level);
    return new Resolution(
        hit, hit ? fillAt(upward, primaryBar, level) : null, TouchBasis.BAR_HL_WORSTOF);
  }

  private static boolean breached(boolean upward, EngineCandle bar, BigDecimal level) {
    return upward ? bar.high().compareTo(level) >= 0 : bar.low().compareTo(level) <= 0;
  }

  private static boolean breachedByClose(boolean upward, EngineCandle bar, BigDecimal level) {
    return upward ? bar.close().compareTo(level) >= 0 : bar.close().compareTo(level) <= 0;
  }

  /** Gap-through at open: if the bar opened beyond the level, fill at the (adverse) open. */
  private static BigDecimal fillAt(boolean upward, EngineCandle bar, BigDecimal level) {
    if (upward) {
      return bar.open().compareTo(level) >= 0 ? bar.open() : level;
    }
    return bar.open().compareTo(level) <= 0 ? bar.open() : level;
  }
}
