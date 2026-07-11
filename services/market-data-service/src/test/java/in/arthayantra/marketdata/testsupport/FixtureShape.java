package in.arthayantra.marketdata.testsupport;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic synthetic daily-candle shapes for the swing-backtest ITs. Every value is a pure
 * function of {@code (symbolIndex, barIndex)} — no clock, no randomness — so re-seeding is exact and
 * the F2 batched-vs-serial read equality is byte-reproducible.
 */
public final class FixtureShape {

  private FixtureShape() {}

  /**
   * A per-symbol-VARIED rising panel: an exponential uptrend (growth rate keyed to {@code k}, so the
   * cross-sectional RS distribution is non-degenerate) modulated by a sinusoidal wiggle, with a real
   * intrabar range (non-zero ATR) and a varied volume. Exercises the Minervini setups (a run at 60
   * symbols closes real trades that flow through the order-dependent portfolio) while keeping every
   * value a pure function of {@code (symbol index, bar index)} — so both read strategies see identical
   * bars and the F2 equality is byte-reproducible.
   *
   * @return {@code candles} insert rows shaped {@code {symbol, bucket, open, high, low, close, volume}}
   *     for {@code days} consecutive calendar sessions ending on {@code end} (oldest first).
   */
  public static List<Object[]> variedUptrend(String symbol, int k, int days, LocalDate end) {
    double drift = 0.0004 + 0.0016 * (k % 12) / 11.0; // per-bar growth, varied by symbol
    double[] close = new double[days];
    for (int i = 0; i < days; i++) {
      double trend = 100.0 * Math.exp(drift * i);
      double wiggle = 1.0 + 0.03 * Math.sin(i * (0.20 + 0.01 * (k % 7)));
      close[i] = Math.round(trend * wiggle * 100.0) / 100.0;
    }
    List<Object[]> rows = new ArrayList<>(days);
    for (int i = 0; i < days; i++) {
      LocalDate day = end.minusDays(days - 1L - i);
      Timestamp bucket = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
      double o = i == 0 ? close[i] : close[i - 1];
      double c = close[i];
      double hi = Math.round(Math.max(o, c) * 1.005 * 100.0) / 100.0;
      double lo = Math.round(Math.min(o, c) * 0.995 * 100.0) / 100.0;
      long vol = 500_000L + 1000L * ((i * 7 + k * 13) % 400);
      rows.add(new Object[] {symbol, bucket, o, hi, lo, c, vol});
    }
    return rows;
  }
}
