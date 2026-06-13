package in.arthayantra.backtest.replay.options;

import in.arthayantra.black76.Black76;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A CLEARLY-LABELLED approximate reconstruction of an option-premium series from underlying 1m
 * candles via {@code libs/black76-math} ({@link Black76#price} on the FORWARD) — the {@code
 * SYNTHETIC_B76} mode of the §D.15 fidelity contract. This is NEVER snapshot-grade: the result
 * carries explicit run-level caveats so a synthetic run can never masquerade as snapshot-grade.
 *
 * <p>The underlying candle close is taken as the forward {@code F} (the 1m close on the same series
 * the candle replay reads); the year-fraction to expiry is ACT/365; IV comes from the nearest
 * archived snapshot (time-nearest, matching the expiry/strike bucket) when available, otherwise a
 * FLAT-IV assumption supplied by the caller applies (the only honest answer for windows predating
 * the archive). Fully deterministic: the same {@code (underlying candles, strike, type, expiry, iv,
 * r)} yields a byte-identical premium series — no wall-clock, no randomness.
 */
public final class SyntheticPremium {

  private static final BigDecimal MS_PER_YEAR =
      BigDecimal.valueOf(365L * 24L * 60L * 60L * 1000L);

  private SyntheticPremium() {}

  /** One reconstructed premium observation aligned to an underlying candle's bucket start. */
  public record PremiumPoint(OffsetDateTime ts, BigDecimal premium) {}

  /** Why a synthetic run is approximate — surfaced as a run-level caveat, never buried. */
  public enum Caveat {
    /** IV held flat across the window (caller-supplied) — the window predates the snapshot archive. */
    FLAT_IV,
    /** IV taken from the nearest archived snapshot (time-nearest, matching expiry/strike bucket). */
    NEAREST_SNAPSHOT_IV
  }

  /**
   * A reconstructed premium series plus the caveats that qualify it. The {@link #caveats()} are the
   * run-level honesty flags the results payload surfaces. The agreed carrier is the metrics JSONB
   * {@code caveats} array: when the premium-as-primary swap wires this path live, {@code
   * BacktestRunner} MUST write {@link #caveats()} into {@code metrics.full().putArray("caveats")}
   * before {@code RunRepository.insert}, because {@code RunRepository.findResult} reads run-level
   * caveats from exactly that array.
   */
  public record Result(List<PremiumPoint> series, List<String> caveats) {}

  /**
   * Reconstructs the premium series from underlying candles under a flat IV — the only honest mode
   * for windows predating the snapshot archive. Surfaces the {@link Caveat#FLAT_IV} caveat.
   *
   * @param underlying the underlying 1m candles (close used as the forward), in bucket order
   * @param type CE or PE
   * @param strike the option strike
   * @param expiry the contract expiry date (ACT/365 year-fraction)
   * @param flatIv the flat implied vol assumption (e.g. {@code 0.15} for 15%)
   * @param riskFreeRate the annualized risk-free rate (e.g. {@code 0.065})
   * @return the reconstructed series with the flat-IV caveat
   */
  public static Result flatIv(
      List<EngineCandle> underlying,
      Black76.OptionType type,
      BigDecimal strike,
      LocalDate expiry,
      BigDecimal flatIv,
      BigDecimal riskFreeRate) {
    List<PremiumPoint> series =
        reconstruct(underlying, type, strike, expiry, flatIv, riskFreeRate);
    return new Result(series, List.of(caveatMessage(Caveat.FLAT_IV, flatIv)));
  }

  /**
   * Reconstructs the premium series using a single IV taken from the nearest archived snapshot
   * (time-nearest, matching the expiry/strike bucket). Surfaces the {@link Caveat#NEAREST_SNAPSHOT_IV}
   * caveat. Determinism is preserved because the nearest-snapshot IV is resolved deterministically
   * by the caller and passed in as a fixed value.
   *
   * @param underlying the underlying 1m candles (close used as the forward), in bucket order
   * @param type CE or PE
   * @param strike the option strike
   * @param expiry the contract expiry date (ACT/365 year-fraction)
   * @param nearestSnapshotIv the IV from the time-nearest archived snapshot
   * @param riskFreeRate the annualized risk-free rate
   * @return the reconstructed series with the nearest-snapshot-IV caveat
   */
  public static Result nearestSnapshotIv(
      List<EngineCandle> underlying,
      Black76.OptionType type,
      BigDecimal strike,
      LocalDate expiry,
      BigDecimal nearestSnapshotIv,
      BigDecimal riskFreeRate) {
    List<PremiumPoint> series =
        reconstruct(underlying, type, strike, expiry, nearestSnapshotIv, riskFreeRate);
    return new Result(
        series, List.of(caveatMessage(Caveat.NEAREST_SNAPSHOT_IV, nearestSnapshotIv)));
  }

  /**
   * The deterministic core: prices each underlying candle's close through Black-76 with a constant
   * IV. Expiry is taken at the session close (15:30 IST) of the expiry date; year-fractions before
   * that are ACT/365, clamped to {@link Black76#T_MIN} by the lib on expiry day.
   */
  static List<PremiumPoint> reconstruct(
      List<EngineCandle> underlying,
      Black76.OptionType type,
      BigDecimal strike,
      LocalDate expiry,
      BigDecimal iv,
      BigDecimal riskFreeRate) {
    requirePositive("iv", iv);
    requirePositive("strike", strike);
    OffsetDateTime expiryInstant = expiryClose(underlying, expiry);
    double k = strike.doubleValue();
    double sigma = iv.doubleValue();
    double r = riskFreeRate.doubleValue();
    List<PremiumPoint> out = new ArrayList<>(underlying.size());
    for (EngineCandle candle : underlying) {
      double f = candle.close().doubleValue();
      if (!(f > 0.0)) {
        throw new IllegalArgumentException(
            "SYNTHETIC_B76 requires a positive underlying close (forward); got " + f);
      }
      double t = yearFraction(candle.bucketStart(), expiryInstant);
      double price = Black76.price(type, f, k, t, r, sigma);
      if (!Double.isFinite(price)) {
        throw new IllegalArgumentException(
            "SYNTHETIC_B76 produced a non-finite premium (f="
                + f
                + ", k="
                + k
                + ", t="
                + t
                + ", sigma="
                + sigma
                + ")");
      }
      out.add(new PremiumPoint(candle.bucketStart(), BigDecimal.valueOf(price)));
    }
    return out;
  }

  /** Guards a Black-76 input that must be strictly positive (sigma=0 or k&le;0 yield NaN). */
  private static void requirePositive(String name, BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(
          "SYNTHETIC_B76 requires a positive " + name + "; got " + value);
    }
  }

  /** The expiry close instant in the candle series' own offset (15:30 IST on the expiry date). */
  private static OffsetDateTime expiryClose(List<EngineCandle> underlying, LocalDate expiry) {
    OffsetDateTime sample =
        underlying.isEmpty()
            ? OffsetDateTime.parse(expiry + "T15:30:00+05:30")
            : underlying.get(0).bucketStart();
    return expiry.atTime(15, 30).atOffset(sample.getOffset());
  }

  /** ACT/365 year-fraction from {@code now} to {@code expiry}, computed in milliseconds. */
  static double yearFraction(OffsetDateTime now, OffsetDateTime expiry) {
    long millis = ChronoUnit.MILLIS.between(now, expiry);
    if (millis <= 0) {
      return 0.0;
    }
    return BigDecimal.valueOf(millis)
        .divide(MS_PER_YEAR, java.math.MathContext.DECIMAL64)
        .doubleValue();
  }

  private static String caveatMessage(Caveat caveat, BigDecimal iv) {
    return switch (caveat) {
      case FLAT_IV ->
          "SYNTHETIC_B76: premiums reconstructed from underlying candles via Black-76 under a "
              + "FLAT-IV assumption (iv="
              + iv.toPlainString()
              + "); window predates the snapshot archive — approximate, not snapshot-grade";
      case NEAREST_SNAPSHOT_IV ->
          "SYNTHETIC_B76: premiums reconstructed from underlying candles via Black-76 using the "
              + "nearest archived-snapshot IV (iv="
              + iv.toPlainString()
              + "); approximate, not snapshot-grade";
    };
  }
}
