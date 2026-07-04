package in.arthayantra.marketdata.screener.minervini.geometry;

import java.util.ArrayList;
import java.util.List;

/**
 * Percentage zig-zag swing extractor (MV-5.1) — the primitive every base detector needs. Walks a
 * daily series left-to-right tracking the running extreme of the current leg; when price reverses
 * from that extreme by at least the reversal threshold, the extreme is confirmed as a pivot and the
 * leg flips. Peaks read off bar {@code high}, troughs off bar {@code low}.
 *
 * <p>The threshold is a fraction (e.g. {@code 0.025} = 2.5%). It MUST be smaller than the tightest
 * contraction you want to resolve — a VCP's final pullback is often 2–4%, so a 5% zig-zag would miss
 * it (that is why {@link VcpDetector} defaults the threshold below 3%). A smaller threshold surfaces
 * more swings; {@link VcpDetector} then keeps only the contracting right-side tail, so extra noise
 * pivots are filtered downstream, not here.
 *
 * <p>Pure + deterministic (no I/O, no clock) — replay-safe and unit-testable on synthetic series.
 */
public final class ZigZag {

  private final double threshold;

  /**
   * Builds an extractor with a reversal threshold as a fraction (e.g. {@code 0.025} for 2.5%).
   */
  public ZigZag(double threshold) {
    this.threshold = threshold;
  }

  /**
   * Extracts the alternating swing highs/lows. The returned list alternates high/low and includes
   * the final running extreme as a trailing (still-forming) pivot so the last leg is visible to the
   * base detector. Returns empty for a series shorter than two bars.
   */
  public List<Pivot> extract(List<DailyBar> bars) {
    List<Pivot> pivots = new ArrayList<>();
    int n = bars.size();
    if (n < 2) {
      return pivots;
    }
    int dir = 0; // +1 up leg (seeking a high), -1 down leg (seeking a low), 0 = not yet decided
    int extHighIdx = 0;
    double extHigh = bars.get(0).high();
    int extLowIdx = 0;
    double extLow = bars.get(0).low();

    for (int i = 1; i < n; i++) {
      DailyBar b = bars.get(i);
      if (dir >= 0 && b.high() >= extHigh) {
        extHigh = b.high();
        extHighIdx = i;
      }
      if (dir <= 0 && b.low() <= extLow) {
        extLow = b.low();
        extLowIdx = i;
      }
      if (dir == 0) {
        // First threshold move off the seed bar decides the initial leg direction.
        if (b.low() <= extHigh * (1 - threshold)) {
          pivots.add(highPivot(bars, extHighIdx, extHigh));
          dir = -1;
          extLow = b.low();
          extLowIdx = i;
        } else if (b.high() >= extLow * (1 + threshold)) {
          pivots.add(lowPivot(bars, extLowIdx, extLow));
          dir = 1;
          extHigh = b.high();
          extHighIdx = i;
        }
      } else if (dir > 0) {
        if (b.low() <= extHigh * (1 - threshold)) {
          pivots.add(highPivot(bars, extHighIdx, extHigh));
          dir = -1;
          extLow = b.low();
          extLowIdx = i;
        }
      } else {
        if (b.high() >= extLow * (1 + threshold)) {
          pivots.add(lowPivot(bars, extLowIdx, extLow));
          dir = 1;
          extHigh = b.high();
          extHighIdx = i;
        }
      }
    }

    // Trailing tip: the last, still-forming extreme — added only if it is a fresh bar beyond the
    // last confirmed pivot (else it duplicates it).
    int lastIdx = pivots.isEmpty() ? -1 : pivots.get(pivots.size() - 1).index();
    if (dir > 0 && extHighIdx != lastIdx) {
      pivots.add(highPivot(bars, extHighIdx, extHigh));
    } else if (dir < 0 && extLowIdx != lastIdx) {
      pivots.add(lowPivot(bars, extLowIdx, extLow));
    }
    return pivots;
  }

  private static Pivot highPivot(List<DailyBar> bars, int idx, double price) {
    return new Pivot(idx, bars.get(idx).date(), price, true);
  }

  private static Pivot lowPivot(List<DailyBar> bars, int idx, double price) {
    return new Pivot(idx, bars.get(idx).date(), price, false);
  }
}
