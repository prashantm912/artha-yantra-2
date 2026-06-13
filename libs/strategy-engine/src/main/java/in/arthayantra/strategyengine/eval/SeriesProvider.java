package in.arthayantra.strategyengine.eval;

import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;

/**
 * The series seam both engines implement: live backs it with the candle cache + the shared
 * context-series cache (§C-2.3 — N strategies referencing NIFTY 1d cost one series); replay
 * backs it with preloaded fixtures. Returning null means no coverage — indicators on that
 * series stay null and nothing scores.
 */
@FunctionalInterface
public interface SeriesProvider {

  /** The series for an instrument+interval, or null when uncovered. */
  EngineSeries series(SeriesKey key);
}
