package in.arthayantra.marketdata.screener.minervini.geometry;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Volatility Contraction Pattern detector (MV-5.2, §4.5/§4.6/§6.2). Given a symbol's daily bar
 * series (oldest→newest), extracts zig-zag swings, pairs each swing high with its following swing
 * low into contractions, and keeps the trailing run of successively narrowing contractions as the
 * base. Emits the Technical Footprint {@code [weeks]W [deepest%/tightest%] [count]T}, the buy pivot
 * (high of the final tightest contraction), and the volume-dry-up / shakeout flags.
 *
 * <p>Rules encoded (source §4.5/§4.6, Ch 10):
 *
 * <ul>
 *   <li>2–6 contractions, typically 2–4 (config {@code min/maxContractions}).
 *   <li>Each successive contraction ≈ half the prior (± reasonable): strictly narrowing, with the
 *       depth ratio inside {@code [ratioMin, ratioMax]} (default 0.2–0.9).
 *   <li>Volume dries up on the right: the final contraction's average volume below the 50-day
 *       average with at least one extremely-low day ({@code < finalVolLowFraction × 50d avg}).
 *   <li>Pivot = the high of the final, tightest contraction (§4.3 line of least resistance).
 *   <li>Rejects a V-shape / no-right-side base (fewer than {@code minContractions} narrowing
 *       contractions).
 * </ul>
 *
 * <p>Pure + deterministic (no I/O, no clock). All thresholds are config-tunable
 * ({@code artha.minervini.vcp.*}); the single constructor is directly callable from unit tests.
 */
@Component
public class VcpDetector {

  /** A single high→low pullback within the base. */
  private record Contraction(
      int peakIdx, double peak, int troughIdx, double trough, double depthPct) {}

  private final double zigzagPct;
  private final int minContractions;
  private final int maxContractions;
  private final double ratioMin;
  private final double ratioMax;
  private final double finalVolLowFraction;
  private final double cheatFraction;
  private final double thrustFraction;
  private final int thrustWindow;

  /** Wires the config-tunable VCP thresholds. */
  public VcpDetector(
      @Value("${artha.minervini.vcp.zigzag-pct:2.5}") double zigzagPctWhole,
      @Value("${artha.minervini.vcp.min-contractions:2}") int minContractions,
      @Value("${artha.minervini.vcp.max-contractions:6}") int maxContractions,
      @Value("${artha.minervini.vcp.ratio-min:0.2}") double ratioMin,
      @Value("${artha.minervini.vcp.ratio-max:0.9}") double ratioMax,
      @Value("${artha.minervini.vcp.final-vol-low-fraction:0.5}") double finalVolLowFraction,
      @Value("${artha.minervini.vcp.cheat-fraction:0.5}") double cheatFraction,
      @Value("${artha.minervini.vcp.thrust-pct:100}") double thrustPctWhole,
      @Value("${artha.minervini.vcp.thrust-window:40}") int thrustWindow) {
    this.zigzagPct = zigzagPctWhole / 100.0;
    this.minContractions = minContractions;
    this.maxContractions = maxContractions;
    this.ratioMin = ratioMin;
    this.ratioMax = ratioMax;
    this.finalVolLowFraction = finalVolLowFraction;
    this.cheatFraction = cheatFraction;
    this.thrustFraction = thrustPctWhole / 100.0;
    this.thrustWindow = thrustWindow;
  }

  /** Detects the most recent VCP base in {@code bars} (oldest→newest). */
  public VcpFootprint detect(List<DailyBar> bars) {
    if (bars == null || bars.size() < 30) {
      return VcpFootprint.rejected("series too short (<30 sessions)");
    }
    List<Pivot> pivots = new ZigZag(zigzagPct).extract(bars);
    List<Contraction> all = contractions(pivots);
    if (all.size() < minContractions) {
      return VcpFootprint.rejected(
          "fewer than " + minContractions + " contractions (V-shape / no base)");
    }
    List<Contraction> base = trailingContractingRun(all);
    if (base.size() < minContractions) {
      return VcpFootprint.rejected("right side not contracting (no VCP narrowing)");
    }
    if (base.size() > maxContractions) {
      base = base.subList(base.size() - maxContractions, base.size()); // keep the tightest tail
    }

    Contraction first = base.get(0);
    Contraction last = base.get(base.size() - 1);
    double deepest = 0;
    for (Contraction c : base) {
      deepest = Math.max(deepest, c.depthPct());
    }
    double tightest = last.depthPct();
    int durationDays = last.peakIdx() - first.peakIdx();
    int baseWeeks = (int) Math.round(durationDays / 5.0);
    boolean volumeDryUp = volumeDryUp(bars, last);
    boolean shakeout = shakeout(base);
    int baseCount = countContractingRuns(all);
    // §6.3 cheat: an earlier/lower breakout trigger than the final pivot. In a classic VCP the
    // contraction peaks DESCEND (e.g. 100→97→95→93), so an earlier peak sits ABOVE the pivot — the
    // wrong direction for a "cheat". A deterministic, always-below-pivot proxy: a cheatFraction step
    // up the FINAL contraction (trough → pivot), i.e. entering partway through the last tightening
    // rather than waiting for the full breakout. Guaranteed in (lastTrough, pivot); config-tunable.
    double cheatPivot = last.trough() + cheatFraction * (last.peak() - last.trough());
    // §6.4/§6.5 power-play precondition: a prior ≈+100% move in <8 weeks (thrustWindow sessions)
    // anywhere before the base begins (the base's first peak).
    boolean thrust = thrust(bars, first.peakIdx());
    String footprint =
        String.format("%dW %.0f/%.0f %dT", baseWeeks, deepest, tightest, base.size());
    return new VcpFootprint(
        true, base.size(), deepest, tightest, baseWeeks, durationDays, last.peak(), deepest,
        volumeDryUp, shakeout, baseCount, cheatPivot, thrust, footprint, null);
  }

  /**
   * The power-play thrust (§6.4): true iff some low in the run-up BEFORE the base ({@code [0,
   * baseStartIdx)}) is followed within {@code thrustWindow} sessions (and not past the base start) by
   * a high at least {@code (1 + thrustFraction)}× that low — a ≈+100%-in-&lt;8-weeks blast-off. O(n·w).
   */
  private boolean thrust(List<DailyBar> bars, int baseStartIdx) {
    double target = 1.0 + thrustFraction;
    for (int i = 0; i < baseStartIdx; i++) {
      double low = bars.get(i).low();
      if (low <= 0) {
        continue;
      }
      int lookaheadEnd = Math.min(i + thrustWindow, baseStartIdx);
      for (int j = i + 1; j <= lookaheadEnd; j++) {
        if (bars.get(j).high() >= target * low) {
          return true;
        }
      }
    }
    return false;
  }

  /** Pairs each swing high with its following swing low into a high→low contraction. */
  private static List<Contraction> contractions(List<Pivot> pivots) {
    List<Contraction> out = new ArrayList<>();
    for (int i = 0; i + 1 < pivots.size(); i++) {
      Pivot p = pivots.get(i);
      Pivot q = pivots.get(i + 1);
      if (p.high() && !q.high() && p.price() > 0) {
        double depth = (p.price() - q.price()) / p.price() * 100.0;
        if (depth > 0) {
          out.add(new Contraction(p.index(), p.price(), q.index(), q.price(), depth));
        }
      }
    }
    return out;
  }

  /**
   * The trailing run of successively narrowing contractions ending at the most recent one: walk
   * backwards including a prior contraction while it is deeper than the current and the current/prior
   * depth ratio is inside {@code [ratioMin, ratioMax]} (the "≈ half ± reasonable" halving rule).
   */
  private List<Contraction> trailingContractingRun(List<Contraction> all) {
    int end = all.size() - 1;
    int start = end;
    for (int i = end; i > 0; i--) {
      Contraction cur = all.get(i);
      Contraction prev = all.get(i - 1);
      double ratio = prev.depthPct() == 0 ? 1 : cur.depthPct() / prev.depthPct();
      boolean narrowing = prev.depthPct() > cur.depthPct() && ratio >= ratioMin && ratio <= ratioMax;
      if (!narrowing) {
        break;
      }
      start = i - 1;
    }
    return new ArrayList<>(all.subList(start, end + 1));
  }

  /**
   * Final-contraction volume gate (§4.5): the average volume across the final contraction's pullback
   * is below the 50-session average AS OF THE PIVOT, AND at least one pullback day is extremely low
   * ({@code < finalVolLowFraction × 50d avg}). The baseline is the 50 sessions ENDING at the pivot
   * (before the pullback), and the pullback is measured over the days AFTER the pivot — so the
   * drying-up volume never contaminates the baseline it is compared against.
   */
  private boolean volumeDryUp(List<DailyBar> bars, Contraction last) {
    int pivotIdx = last.peakIdx();
    int troughIdx = last.troughIdx();
    if (troughIdx <= pivotIdx) {
      return false;
    }
    double avg50 = avgVolume(bars, Math.max(0, pivotIdx - 49), pivotIdx);
    if (avg50 <= 0) {
      return false;
    }
    double pullbackAvg = avgVolume(bars, pivotIdx + 1, troughIdx);
    boolean oneVeryLow = false;
    for (int i = pivotIdx + 1; i <= troughIdx && i < bars.size(); i++) {
      if (bars.get(i).volume() < finalVolLowFraction * avg50) {
        oneVeryLow = true;
        break;
      }
    }
    return pullbackAvg < avg50 && oneVeryLow;
  }

  private static double avgVolume(List<DailyBar> bars, int from, int to) {
    long sum = 0;
    int n = 0;
    for (int i = from; i <= to && i < bars.size(); i++) {
      sum += bars.get(i).volume();
      n++;
    }
    return n == 0 ? 0 : (double) sum / n;
  }

  /** True if any right-side contraction trough undercuts the prior contraction trough (a shakeout). */
  private static boolean shakeout(List<Contraction> base) {
    for (int i = 1; i < base.size(); i++) {
      if (base.get(i).trough() < base.get(i - 1).trough()) {
        return true;
      }
    }
    return false;
  }

  /** Heuristic base count: the number of maximal narrowing runs (≥2) across the whole pivot series. */
  private int countContractingRuns(List<Contraction> all) {
    int runs = 0;
    int len = 1;
    for (int i = 1; i < all.size(); i++) {
      double ratio = all.get(i - 1).depthPct() == 0 ? 1 : all.get(i).depthPct() / all.get(i - 1).depthPct();
      boolean narrowing =
          all.get(i - 1).depthPct() > all.get(i).depthPct() && ratio >= ratioMin && ratio <= ratioMax;
      if (narrowing) {
        len++;
      } else {
        if (len >= minContractions) {
          runs++;
        }
        len = 1;
      }
    }
    if (len >= minContractions) {
      runs++;
    }
    return Math.max(runs, 1);
  }
}
