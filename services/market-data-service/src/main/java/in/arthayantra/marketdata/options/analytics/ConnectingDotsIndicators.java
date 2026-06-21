package in.arthayantra.marketdata.options.analytics;

/**
 * Self-contained intraday indicators for the Connecting Dots factor matrix (§20.7.8). market-data has
 * no ta4j/strategy-engine dependency, so the small set the matrix needs — Wilder RSI, session VWAP,
 * Wilder ATR, and the Supertrend direction — is computed locally over the resampled bar series.
 *
 * <p>These feed the per-factor 3-state CLASSIFICATION (a signal, not a displayed money value), so the
 * maths runs in {@code double} — the single sanctioned non-BigDecimal boundary here, mirroring how the
 * straddle chart converts to numeric coordinates. {@code Double.NaN} marks a not-yet-warm bar.
 */
final class ConnectingDotsIndicators {

  private ConnectingDotsIndicators() {}

  /** Wilder RSI over {@code closes}; NaN for the first {@code period} bars (warmup). */
  static double[] rsi(double[] closes, int period) {
    int n = closes.length;
    double[] out = new double[n];
    java.util.Arrays.fill(out, Double.NaN);
    if (n <= period) {
      return out;
    }
    double avgGain = 0;
    double avgLoss = 0;
    for (int i = 1; i <= period; i++) {
      double ch = closes[i] - closes[i - 1];
      if (ch >= 0) {
        avgGain += ch;
      } else {
        avgLoss -= ch;
      }
    }
    avgGain /= period;
    avgLoss /= period;
    out[period] = rsiFrom(avgGain, avgLoss);
    for (int i = period + 1; i < n; i++) {
      double ch = closes[i] - closes[i - 1];
      double gain = ch > 0 ? ch : 0;
      double loss = ch < 0 ? -ch : 0;
      avgGain = (avgGain * (period - 1) + gain) / period;
      avgLoss = (avgLoss * (period - 1) + loss) / period;
      out[i] = rsiFrom(avgGain, avgLoss);
    }
    return out;
  }

  private static double rsiFrom(double avgGain, double avgLoss) {
    if (avgLoss == 0) {
      return 100.0;
    }
    double rs = avgGain / avgLoss;
    return 100.0 - 100.0 / (1.0 + rs);
  }

  /** Cumulative session VWAP: Σ(typical·vol)/Σvol, typical = (h+l+c)/3. Carries the last value over a
   *  zero-volume bar. */
  static double[] vwap(double[] highs, double[] lows, double[] closes, double[] volumes) {
    int n = closes.length;
    double[] out = new double[n];
    double pv = 0;
    double vol = 0;
    for (int i = 0; i < n; i++) {
      double typical = (highs[i] + lows[i] + closes[i]) / 3.0;
      pv += typical * volumes[i];
      vol += volumes[i];
      out[i] = vol > 0 ? pv / vol : typical;
    }
    return out;
  }

  /** Wilder ATR over {@code period}; NaN until the seed bar at index {@code period-1}. */
  static double[] atr(double[] highs, double[] lows, double[] closes, int period) {
    int n = closes.length;
    double[] out = new double[n];
    java.util.Arrays.fill(out, Double.NaN);
    if (n < period) {
      return out;
    }
    double[] tr = new double[n];
    tr[0] = highs[0] - lows[0];
    for (int i = 1; i < n; i++) {
      double hl = highs[i] - lows[i];
      double hc = Math.abs(highs[i] - closes[i - 1]);
      double lc = Math.abs(lows[i] - closes[i - 1]);
      tr[i] = Math.max(hl, Math.max(hc, lc));
    }
    double seed = 0;
    for (int i = 0; i < period; i++) {
      seed += tr[i];
    }
    out[period - 1] = seed / period;
    for (int i = period; i < n; i++) {
      out[i] = (out[i - 1] * (period - 1) + tr[i]) / period;
    }
    return out;
  }

  /**
   * Supertrend trend direction: {@code +1} uptrend / {@code -1} downtrend per bar (0 until ATR warms).
   * Standard band-flip rule (ta4j-equivalent) over {@code (period, multiplier)}.
   */
  static int[] supertrendDirection(
      double[] highs, double[] lows, double[] closes, int period, double multiplier) {
    int n = closes.length;
    int[] dir = new int[n];
    double[] atr = atr(highs, lows, closes, period);
    double prevUpper = Double.NaN;
    double prevLower = Double.NaN;
    int prevDir = 1;
    for (int i = 0; i < n; i++) {
      if (Double.isNaN(atr[i])) {
        dir[i] = 0;
        continue;
      }
      double hl2 = (highs[i] + lows[i]) / 2.0;
      double basicUpper = hl2 + multiplier * atr[i];
      double basicLower = hl2 - multiplier * atr[i];
      double upper =
          Double.isNaN(prevUpper) || basicUpper < prevUpper || closes[i - 1] > prevUpper
              ? basicUpper
              : prevUpper;
      double lower =
          Double.isNaN(prevLower) || basicLower > prevLower || closes[i - 1] < prevLower
              ? basicLower
              : prevLower;
      int d;
      if (closes[i] > upper) {
        d = 1;
      } else if (closes[i] < lower) {
        d = -1;
      } else {
        d = prevDir; // inside the bands → carry the prior trend
      }
      dir[i] = d;
      prevDir = d;
      prevUpper = upper;
      prevLower = lower;
    }
    return dir;
  }
}
