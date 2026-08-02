package in.arthayantra.marketdata.screener.manas;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.Pivot;
import in.arthayantra.marketdata.screener.minervini.geometry.ZigZag;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manas §3.2 breakout detector: a 4–8-week (min ~2-week) range-bound consolidation whose pivot is the
 * nearest swing high. Reuses the shared {@link ZigZag} swing extractor (from the minervini.geometry
 * package) — walks its trailing pivots back over a window that is genuinely range-bound (its highs
 * and lows sit inside a tolerance band) and takes the highest confirmed swing high in that window as
 * the buy trigger.
 *
 * <p>Rules encoded (source §3.2):
 *
 * <ul>
 *   <li>The consolidation must span at least {@code minBaseDays} (~2 weeks) and at most {@code
 *       maxBaseDays} (~8 weeks) — shorter/tighter is better (urgency of demand).
 *   <li>Range-bound = the window's high-to-low spread ≤ {@code maxRangePct}% of the window high.
 *   <li>Pivot = the nearest (most recent) swing high of the consolidation window (the line of least
 *       resistance).
 *   <li>Rejects a still-trending / no-range recent tape (spread wider than the tolerance) and a
 *       too-short pause.
 * </ul>
 *
 * <p>Pure + deterministic (no I/O, no clock). All thresholds are config-tunable
 * ({@code artha.manas-arora.breakout.*}); the single constructor is directly callable from unit tests.
 */
@Component
public class ConsolidationBreakout {

  private final double zigzagPct;
  private final int minBaseDays;
  private final int maxBaseDays;
  private final double maxRangePct;

  /** Wires the config-tunable breakout thresholds. */
  public ConsolidationBreakout(
      @Value("${artha.manas-arora.breakout.zigzag-pct:2.5}") double zigzagPctWhole,
      @Value("${artha.manas-arora.breakout.min-base-days:10}") int minBaseDays,
      @Value("${artha.manas-arora.breakout.max-base-days:40}") int maxBaseDays,
      @Value("${artha.manas-arora.breakout.max-range-pct:25}") double maxRangePct) {
    this.zigzagPct = zigzagPctWhole / 100.0;
    this.minBaseDays = minBaseDays;
    this.maxBaseDays = maxBaseDays;
    this.maxRangePct = maxRangePct;
  }

  /** Detects the most recent consolidation-breakout base in {@code bars} (oldest→newest). */
  public BreakoutFootprint detect(List<DailyBar> bars) {
    if (bars == null || bars.size() < minBaseDays + 1) {
      return BreakoutFootprint.rejected("series too short for a consolidation base");
    }
    int n = bars.size();
    // The consolidation is the trailing window: the largest [start, n) window (up to maxBaseDays) whose
    // high-to-low spread stays inside the tolerance band. Grow it back from the last bar while tight.
    int start = n - 1;
    double hi = bars.get(n - 1).high();
    double lo = bars.get(n - 1).low();
    for (int i = n - 2; i >= 0 && (n - 1 - i) < maxBaseDays; i--) {
      double nhi = Math.max(hi, bars.get(i).high());
      double nlo = Math.min(lo, bars.get(i).low());
      if (nhi <= 0 || (nhi - nlo) / nhi * 100.0 > maxRangePct) {
        break; // window would breach the range tolerance — stop growing
      }
      hi = nhi;
      lo = nlo;
      start = i;
    }
    int durationDays = (n - 1) - start;
    if (durationDays < minBaseDays) {
      return BreakoutFootprint.rejected("consolidation shorter than " + minBaseDays + " sessions");
    }
    // Pivot = the nearest swing high inside the window (highest confirmed zig-zag peak); fall back to
    // the window high if the zig-zag surfaces no peak (a very tight range with no resolved swing).
    List<Pivot> pivots = new ZigZag(zigzagPct).extract(bars.subList(start, n));
    double pivot = 0;
    for (Pivot p : pivots) {
      if (p.high() && p.price() > pivot) {
        pivot = p.price();
      }
    }
    if (pivot <= 0) {
      pivot = hi;
    }
    double rangePct = hi <= 0 ? 0 : (hi - lo) / hi * 100.0;
    int baseWeeks = (int) Math.round(durationDays / 5.0);
    String footprint = String.format("%dW range %.0f%%", baseWeeks, rangePct);
    return new BreakoutFootprint(true, pivot, rangePct, baseWeeks, durationDays, footprint, null);
  }
}
