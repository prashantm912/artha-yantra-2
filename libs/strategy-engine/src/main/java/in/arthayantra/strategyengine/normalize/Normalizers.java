package in.arthayantra.strategyengine.normalize;

import in.arthayantra.strategyengine.indicators.EngineMath;
import java.math.BigDecimal;

/**
 * Raw indicator value → score {@code s ∈ [0,1]} (§C-2.3). All outputs clamped and rounded to the
 * engine boundary scale. {@code linear} with {@code from > to} inverts (higher raw = worse);
 * {@code rsi_momentum} is the built-in 50→0, 70→1 long-side preset; {@code direction} maps
 * positive to 1, everything else to 0.
 */
public final class Normalizers {

  private Normalizers() {}

  /** Applies a spec; null in → null out (a not-ready indicator can never score). */
  public static BigDecimal apply(NormalizeSpec spec, BigDecimal raw) {
    if (raw == null) {
      return null;
    }
    return switch (spec.type()) {
      case LINEAR -> linear(spec.from(), spec.to(), raw);
      case STEP -> step(spec, raw);
      case DIRECTION -> EngineMath.round(raw.signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
      case RSI_MOMENTUM -> linear(BigDecimal.valueOf(50), BigDecimal.valueOf(70), raw);
    };
  }

  private static BigDecimal linear(BigDecimal from, BigDecimal to, BigDecimal raw) {
    if (from.compareTo(to) == 0) {
      throw new IllegalArgumentException("linear normalize needs from != to");
    }
    BigDecimal score = raw.subtract(from).divide(to.subtract(from), EngineMath.MC);
    return EngineMath.round(clamp(score));
  }

  private static BigDecimal step(NormalizeSpec spec, BigDecimal raw) {
    for (NormalizeSpec.Band band : spec.bands()) {
      if (band.upto() == null || raw.compareTo(band.upto()) <= 0) {
        return EngineMath.round(clamp(band.score()));
      }
    }
    // value above every bounded band and no catch-all: score of the last band
    return EngineMath.round(clamp(spec.bands().get(spec.bands().size() - 1).score()));
  }

  private static BigDecimal clamp(BigDecimal score) {
    if (score.compareTo(BigDecimal.ZERO) < 0) {
      return BigDecimal.ZERO;
    }
    if (score.compareTo(BigDecimal.ONE) > 0) {
      return BigDecimal.ONE;
    }
    return score;
  }
}
