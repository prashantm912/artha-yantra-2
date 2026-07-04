package in.arthayantra.marketdata.screener.minervini;

import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpFootprint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The Phase-9 Minervini swing BACKTEST sim (MV-8.x follow-on): a faithful, event-driven replay of the
 * 4 live swing setups over one equity's full daily history. The live swing engine ({@code
 * MinerviniSwingEngine}) fires on the seeded VCP pivot, which the parity replay engine cannot supply
 * (it goes NEUTRAL) — so a naive backtest of the setups produces 0 trades. This sim seeds the geometry
 * itself: it recomputes the {@link VcpDetector} pivot/cheat/thrust at a weekly cadence (the live-screen
 * rhythm) and applies the SAME entry gates + the SAME 8%-stop / 50-day-MA-trail exit doctrine the live
 * strategies use, so the trade-level stats (win-rate / payoff / expectancy / hold-time) reflect the
 * real strategy over ~11 years — what the buy-and-hold hit-rate harness cannot show.
 *
 * <p>The sim runs one or more {@link Variant}s over the SAME single pass of the bars, so an A/B is a
 * clean apples-to-apples comparison (identical geometry, MAs, and setup triggers; only the entry
 * FILTER differs):
 *
 * <ul>
 *   <li><b>v1 (technical-only)</b> — the 7 price-structure Trend-Template gates + the setup trigger;
 *       the cross-sectional RS gate is relaxed (a per-symbol stream cannot compute a per-date
 *       percentile) and there is no tradability floor.
 *   <li><b>v2 (rs + turnover)</b> — adds the real cross-sectional RS-rank gate (the caller supplies
 *       the per-bar percentile it computed across the universe) and a turnover/liquidity floor (the
 *       only unbiased fundamentals proxy — real fundamentals have no point-in-time history).
 * </ul>
 *
 * Pure + deterministic (no IO, no clock); the caller supplies the bars, the detector, and the
 * pre-computed per-bar RS-rank series.
 */
final class MinerviniSwingBacktest {

  /** One closed backtest trade, tagged with the {@link Variant} that took it. */
  record BtTrade(
      String variant,
      String setup,
      String symbol,
      LocalDate entryDate,
      double entryPrice,
      LocalDate exitDate,
      double exitPrice,
      double pnlPct,
      int barsHeld,
      String exitReason) {}

  /**
   * One backtest configuration. {@code useRealRs} gates on the caller-supplied per-bar RS-rank at
   * {@code rsMin} (else the RS gate is relaxed — fed a passing constant); {@code turnoverFloor} in
   * rupees/day gates on the 20-day average traded value (0 = off).
   */
  record Variant(String name, boolean useRealRs, double rsMin, double turnoverFloor) {}

  /** The technical-only baseline (reproduces the original v1 numbers exactly). */
  static final Variant V1 = new Variant("v1-technical", false, 0, 0);

  // the live doctrine, as config-tunable constants mirrored from the production YAMLs
  private static final double STOP_PCT = 0.08; // MV-7.2 protective stop
  private static final double VOL_MIN = 1.2; // §4.5 expanding-volume gate
  private static final int WEEKLY = 5; // geometry recompute cadence (sessions)
  private static final int VCP_LOOKBACK = 400; // detector window
  private static final int MIN_BARS = 260; // need 252 for the 52-week gate + a little slack
  private static final int TURNOVER_LOOKBACK = 20; // avg traded-value window for the liquidity floor

  // the 4 setups
  static final List<String> SETUPS = List.of("vcp", "primary-base", "cheat-3c", "power-play");

  private MinerviniSwingBacktest() {}

  /** Backward-compatible single-variant (v1) entry point — used by the unit test. */
  static List<BtTrade> simulate(
      String symbol, List<DailyBar> bars, VcpDetector detector, LocalDate from) {
    return simulate(symbol, bars, detector, from, null, List.of(V1));
  }

  /**
   * Simulates every {@code variant} of all 4 setups over {@code bars} (oldest→newest) for {@code
   * symbol}, from the first bar on or after {@code from}. Each (variant, setup) runs an independent
   * single-position book (flat→long→flat); an open-at-end position is dropped (not counted). {@code
   * rsRank} is the per-bar cross-sectional RS percentile the caller computed (may be null when no
   * variant needs it). Returns every closed trade across the variants and setups.
   */
  static List<BtTrade> simulate(
      String symbol,
      List<DailyBar> bars,
      VcpDetector detector,
      LocalDate from,
      double[] rsRank,
      List<Variant> variants) {
    int n = bars.size();
    if (n < MIN_BARS) {
      return List.of();
    }
    double[] close = new double[n];
    double[] high = new double[n];
    double[] low = new double[n];
    double[] volume = new double[n];
    for (int i = 0; i < n; i++) {
      DailyBar b = bars.get(i);
      close[i] = b.close();
      high[i] = b.high();
      low[i] = b.low();
      volume[i] = b.volume();
    }
    double[] sma20 = sma(close, 20);
    double[] sma50 = sma(close, 50);
    double[] sma150 = sma(close, 150);
    double[] sma200 = sma(close, 200);
    double[] high52wPrior = rollingMaxPrior(high, 252); // prior-252 high, EXCLUDING the current bar
    double[] low52w = rollingMin(low, 252);
    double[] volRatio20 = volumeRatio(volume, 20);
    double[] volRatio50 = volumeRatio(volume, 50);
    double[] turnover20 = avgTurnover(close, volume, TURNOVER_LOOKBACK);
    double[] rs = rsRank != null ? rsRank : new double[n]; // absent → 0 (only used when useRealRs)

    // weekly geometry: pivot / cheat / thrust / is-vcp, valid until the next weekly recompute
    double[] pivot = new double[n];
    double[] cheat = new double[n];
    boolean[] thrust = new boolean[n];
    boolean[] isVcp = new boolean[n];
    double curPivot = 0;
    double curCheat = 0;
    boolean curThrust = false;
    boolean curVcp = false;
    for (int i = 0; i < n; i++) {
      if (i >= MIN_BARS && (i % WEEKLY == 0 || i == MIN_BARS)) {
        int start = Math.max(0, i - VCP_LOOKBACK + 1);
        VcpFootprint f = detector.detect(bars.subList(start, i + 1));
        curVcp = f.vcp();
        curPivot = f.pivot();
        curCheat = f.cheatPivot();
        curThrust = f.thrust();
      }
      pivot[i] = curPivot;
      cheat[i] = curCheat;
      thrust[i] = curThrust;
      isVcp[i] = curVcp;
    }

    List<BtTrade> trades = new ArrayList<>();
    for (Variant v : variants) {
      for (String setup : SETUPS) {
        simulateSetup(
            v, setup, symbol, bars, from, close, sma20, sma50, sma150, sma200, high52wPrior, low52w,
            volRatio20, volRatio50, turnover20, rs, pivot, cheat, thrust, isVcp, trades);
      }
    }
    return trades;
  }

  private static void simulateSetup(
      Variant v, String setup, String symbol, List<DailyBar> bars, LocalDate from, double[] close,
      double[] sma20, double[] sma50, double[] sma150, double[] sma200, double[] high52wPrior,
      double[] low52w, double[] volRatio20, double[] volRatio50, double[] turnover20, double[] rsRank,
      double[] pivot, double[] cheat, boolean[] thrust, boolean[] isVcp, List<BtTrade> out) {
    int n = close.length;
    boolean inTrade = false;
    int entryIdx = 0;
    double entryPrice = 0;
    for (int i = MIN_BARS; i < n; i++) {
      LocalDate date = bars.get(i).date();
      if (!inTrade) {
        if (date.isBefore(from)) {
          continue;
        }
        if (entryFires(v, setup, i, close, sma20, sma50, sma150, sma200, high52wPrior, low52w,
            volRatio20, volRatio50, turnover20, rsRank, pivot, cheat, thrust, isVcp)) {
          inTrade = true;
          entryIdx = i;
          entryPrice = close[i];
        }
      } else {
        String reason = exitFires(i, close, sma50, entryPrice);
        if (reason != null) {
          double exitPrice = close[i];
          out.add(
              new BtTrade(
                  v.name(), setup, symbol, bars.get(entryIdx).date(), entryPrice, date, exitPrice,
                  (exitPrice - entryPrice) / entryPrice * 100.0, i - entryIdx, reason));
          inTrade = false;
        }
      }
    }
    // an open-at-end position is intentionally dropped (unrealized — not a closed trade)
  }

  /** The setup-specific entry gate on bar {@code i} — the Trend-Template price gates + the setup trigger. */
  private static boolean entryFires(
      Variant v, String setup, int i, double[] close, double[] sma20, double[] sma50, double[] sma150,
      double[] sma200, double[] high52wPrior, double[] low52w, double[] volRatio20,
      double[] volRatio50, double[] turnover20, double[] rsRank, double[] pivot, double[] cheat,
      boolean[] thrust, boolean[] isVcp) {
    if (!trendTemplate(v, i, close, sma50, sma150, sma200, high52wPrior, low52w, rsRank)) {
      return false;
    }
    if (v.turnoverFloor() > 0
        && (Double.isNaN(turnover20[i]) || turnover20[i] < v.turnoverFloor())) {
      return false;
    }
    return switch (setup) {
      case "vcp" ->
          isVcp[i] && crossover(close, pivot, i) && volRatio20[i] > VOL_MIN && close[i] > sma20[i];
      case "primary-base" ->
          crossoverArr(close, high52wPrior, i) && volRatio50[i] > VOL_MIN;
      case "cheat-3c" ->
          isVcp[i] && crossover(close, cheat, i) && volRatio20[i] > VOL_MIN;
      case "power-play" ->
          isVcp[i] && thrust[i] && crossover(close, pivot, i) && volRatio20[i] > VOL_MIN;
      default -> false;
    };
  }

  /** The exit doctrine on bar {@code i}: the 8% protective stop first, then the 50-day-MA close trail. */
  private static String exitFires(int i, double[] close, double[] sma50, double entryPrice) {
    if (close[i] <= entryPrice * (1.0 - STOP_PCT)) {
      return "STOP_LOSS";
    }
    if (!Double.isNaN(sma50[i]) && close[i] <= sma50[i]) {
      return "TRAILING_STOP";
    }
    return null;
  }

  /**
   * The 8 Trend-Template gates. v1 relaxes the cross-sectional RS gate (fed a passing constant); v2
   * feeds the caller-computed per-bar RS percentile at {@code rsMin} (a NaN percentile — pre-warmup or
   * no universe distribution yet — fails the gate, so an unconfirmed-RS bar takes no trade).
   */
  private static boolean trendTemplate(
      Variant v, int i, double[] close, double[] sma50, double[] sma150, double[] sma200,
      double[] high52wPrior, double[] low52w, double[] rsRank) {
    double rsForGate = v.useRealRs() ? rsRank[i] : 100.0;
    if (Double.isNaN(rsForGate)) {
      return false;
    }
    double rsMin = v.useRealRs() ? v.rsMin() : 0.0;
    boolean[] g =
        MinerviniGates.gates(
            bd(close[i]), bd(sma50[i]), bd(sma150[i]), bd(sma200[i]),
            i >= 20 ? bd(sma200[i - 20]) : null, // 200-day rising over ~1 month
            bd(high52wPrior[i]), bd(low52w[i]),
            BigDecimal.valueOf(rsForGate), BigDecimal.valueOf(rsMin),
            new BigDecimal("30"), // pctAbove52wLow (must be ≥30% above the low)
            new BigDecimal("25")); // within 25% of the 52w high
    return MinerviniGates.passed(g) == 8;
  }

  /** crossover(close, level) at i: prev close ≤ level AND current close > level (level fixed for the week). */
  private static boolean crossover(double[] close, double[] level, int i) {
    return i > 0 && level[i] > 0 && close[i - 1] <= level[i] && close[i] > level[i];
  }

  /** crossover against a per-bar level array (52-week high): prev close ≤ prev level AND close > level. */
  private static boolean crossoverArr(double[] close, double[] level, int i) {
    return i > 0
        && !Double.isNaN(level[i])
        && !Double.isNaN(level[i - 1])
        && close[i - 1] <= level[i - 1]
        && close[i] > level[i];
  }

  private static BigDecimal bd(double v) {
    return Double.isNaN(v) ? null : BigDecimal.valueOf(v);
  }

  // ---- rolling series helpers -------------------------------------------------------------------

  private static double[] sma(double[] v, int period) {
    double[] out = new double[v.length];
    double sum = 0;
    for (int i = 0; i < v.length; i++) {
      sum += v[i];
      if (i >= period) {
        sum -= v[i - period];
      }
      out[i] = i >= period - 1 ? sum / period : Double.NaN;
    }
    return out;
  }

  private static double[] rollingMaxPrior(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.NEGATIVE_INFINITY;
      for (int j = i - period; j < i; j++) {
        m = Math.max(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] rollingMin(double[] v, int period) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < period - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double m = Double.POSITIVE_INFINITY;
      for (int j = i - period + 1; j <= i; j++) {
        m = Math.min(m, v[j]);
      }
      out[i] = m;
    }
    return out;
  }

  private static double[] volumeRatio(double[] v, int lookback) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      if (i < lookback) {
        out[i] = 0;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback; j < i; j++) {
        sum += v[j];
      }
      double avg = sum / lookback;
      out[i] = avg > 0 ? v[i] / avg : 0;
    }
    return out;
  }

  /** Trailing {@code lookback}-day average traded value (close·volume) — the liquidity/tradability floor. */
  private static double[] avgTurnover(double[] close, double[] volume, int lookback) {
    double[] out = new double[close.length];
    for (int i = 0; i < close.length; i++) {
      if (i < lookback - 1) {
        out[i] = Double.NaN;
        continue;
      }
      double sum = 0;
      for (int j = i - lookback + 1; j <= i; j++) {
        sum += close[j] * volume[j];
      }
      out[i] = sum / lookback;
    }
    return out;
  }
}
