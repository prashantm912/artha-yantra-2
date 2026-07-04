package in.arthayantra.marketdata.screener.minervini;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure Minervini Trend-Template gate + RS math (§4.2/§4.10/§4.1). One definition, two consumers:
 * the live daily screener ({@link TrendTemplateService}, over {@code nse_eod_bhavcopy}) and the
 * hit-rate harness ({@link MinerviniHitRateService}, over {@code candles}@1d) — so the harness
 * replicates the screen faithfully and the two can never silently drift. No Spring, no IO; the
 * caller supplies the (config-tunable) thresholds.
 */
final class MinerviniGates {

  private MinerviniGates() {}

  /**
   * The 8 Trend-Template gates (§4.2) as a {@code boolean[8]}: (0) close &gt; 150d &amp; 200d;
   * (1) 150d &gt; 200d; (2) 200d rising; (3) 50d &gt; 150d &amp; 200d; (4) close &gt; 50d;
   * (5) close ≥ 52w-low·(1+pctAboveLow%); (6) close ≥ 52w-high·(1−within52wHigh%);
   * (7) rsRank ≥ rsMin. {@code rsRank} is the cross-sectional percentile 0..100 the caller computed.
   */
  static boolean[] gates(
      BigDecimal close,
      BigDecimal sma50,
      BigDecimal sma150,
      BigDecimal sma200,
      BigDecimal sma200Ago,
      BigDecimal high52w,
      BigDecimal low52w,
      BigDecimal rsRank,
      BigDecimal rsMin,
      BigDecimal pctAbove52wLow,
      BigDecimal within52wHigh) {
    BigDecimal lowGate = low52w == null ? null : low52w.multiply(onePlus(pctAbove52wLow));
    BigDecimal highGate = high52w == null ? null : high52w.multiply(oneMinus(within52wHigh));
    boolean[] g = new boolean[8];
    g[0] = gt(close, sma150) && gt(close, sma200);
    g[1] = gt(sma150, sma200);
    g[2] = gt(sma200, sma200Ago);
    g[3] = gt(sma50, sma150) && gt(sma50, sma200);
    g[4] = gt(close, sma50);
    g[5] = lowGate != null && close != null && close.compareTo(lowGate) >= 0;
    g[6] = highGate != null && close != null && close.compareTo(highGate) >= 0;
    g[7] = rsRank != null && rsRank.compareTo(rsMin) >= 0;
    return g;
  }

  /** How many of the 8 gates passed. */
  static int passed(boolean[] g) {
    int p = 0;
    for (boolean b : g) {
      if (b) {
        p++;
      }
    }
    return p;
  }

  /** Weighted trailing relative strength (§4.10): {@code 0.4·r63 + 0.2·r126 + 0.2·r189 + 0.2·r252}. */
  static BigDecimal weightedRs(
      BigDecimal close, BigDecimal c63, BigDecimal c126, BigDecimal c189, BigDecimal c252) {
    BigDecimal rs = BigDecimal.ZERO;
    rs = rs.add(ret(close, c63).multiply(new BigDecimal("0.4")));
    rs = rs.add(ret(close, c126).multiply(new BigDecimal("0.2")));
    rs = rs.add(ret(close, c189).multiply(new BigDecimal("0.2")));
    rs = rs.add(ret(close, c252).multiply(new BigDecimal("0.2")));
    return rs;
  }

  /** Simple return {@code (now−past)/past}; 0 when {@code past} is null/zero. */
  static BigDecimal ret(BigDecimal now, BigDecimal past) {
    if (now == null || past == null || past.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return now.subtract(past).divide(past, 6, RoundingMode.HALF_UP);
  }

  /**
   * Stage 1-4 (§4.1) from the moving-average structure: Stage 2 = above a rising 200-day and above
   * the 50-day; Stage 4 = below a falling 200-day; Stage 3 = above the 200-day but it has stopped
   * rising; else Stage 1.
   */
  static int stage(BigDecimal close, BigDecimal sma50, BigDecimal sma200, BigDecimal sma200Ago) {
    boolean above200 = gt(close, sma200);
    boolean rising200 = gt(sma200, sma200Ago);
    if (!above200 && !rising200) {
      return 4;
    }
    if (above200 && rising200 && gt(close, sma50)) {
      return 2;
    }
    if (above200 && !rising200) {
      return 3;
    }
    return 1;
  }

  static boolean gt(BigDecimal a, BigDecimal b) {
    return a != null && b != null && a.compareTo(b) > 0;
  }

  private static BigDecimal onePlus(BigDecimal pct) {
    return BigDecimal.ONE.add(pct.movePointLeft(2));
  }

  private static BigDecimal oneMinus(BigDecimal pct) {
    return BigDecimal.ONE.subtract(pct.movePointLeft(2));
  }
}
