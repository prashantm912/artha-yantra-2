package in.arthayantra.black76;

import java.math.BigDecimal;

/**
 * Implied vol via bracketed Newton–Raphson with bisection fallback ([CD-6]). Degenerate inputs
 * return a NULL IV with a reason code — NEVER NaN/Infinity (B-10 edge corpus): at/below
 * discounted intrinsic, zero quotes, or non-convergence inside the bracket.
 */
public final class IvSolver {

  /** Why a row has no IV (persisted alongside the null, Phase 15). */
  public enum Reason {
    OK,
    BELOW_INTRINSIC,
    ZERO_QUOTE,
    NO_CONVERGENCE
  }

  /** Solved IV or the reason there is none. */
  public record IvResult(BigDecimal iv, Reason reason) {

    static IvResult of(double iv) {
      return new IvResult(BigDecimal.valueOf(iv), Reason.OK);
    }

    static IvResult none(Reason reason) {
      return new IvResult(null, reason);
    }
  }

  private static final double SIGMA_LO = 1e-4;
  private static final double SIGMA_HI = 5.0; // 500% — beyond any quotable Indian index vol
  private static final double PRICE_TOLERANCE = 1e-7; // far inside the Rs 0.01 gate
  private static final int MAX_ITERATIONS = 100;

  private IvSolver() {}

  /** Solves Black-76 IV for a market price; clamps T via {@link Black76#T_MIN}. */
  public static IvResult solve(
      Black76.OptionType type, double f, double k, double t, double r, double marketPrice) {
    if (marketPrice <= 0) {
      return IvResult.none(Reason.ZERO_QUOTE);
    }
    double tt = Math.max(t, Black76.T_MIN);
    double df = Math.exp(-r * tt);
    double intrinsic =
        type == Black76.OptionType.CE ? Math.max(f - k, 0) * df : Math.max(k - f, 0) * df;
    if (marketPrice <= intrinsic) {
      return IvResult.none(Reason.BELOW_INTRINSIC);
    }
    double lo = SIGMA_LO;
    double hi = SIGMA_HI;
    double priceAtHi = Black76.price(type, f, k, tt, r, SIGMA_HI);
    if (marketPrice > priceAtHi) {
      return IvResult.none(Reason.NO_CONVERGENCE);
    }

    double sigma = 0.3; // sane Indian index seed; Newton converges in a handful of steps
    for (int i = 0; i < MAX_ITERATIONS; i++) {
      double price = Black76.price(type, f, k, tt, r, sigma);
      double diff = price - marketPrice;
      if (Math.abs(diff) <= PRICE_TOLERANCE) {
        return IvResult.of(sigma);
      }
      // maintain the bracket: price is monotonically increasing in sigma
      if (diff > 0) {
        hi = sigma;
      } else {
        lo = sigma;
      }
      double vega = Black76.rawVega(f, k, tt, r, sigma);
      double next = vega > 1e-12 ? sigma - diff / vega : Double.NaN;
      // bisection fallback whenever Newton leaves the bracket or vega degenerates
      sigma = (Double.isFinite(next) && next > lo && next < hi) ? next : 0.5 * (lo + hi);
    }
    double finalPrice = Black76.price(type, f, k, tt, r, sigma);
    return Math.abs(finalPrice - marketPrice) <= 0.01
        ? IvResult.of(sigma)
        : IvResult.none(Reason.NO_CONVERGENCE);
  }
}
