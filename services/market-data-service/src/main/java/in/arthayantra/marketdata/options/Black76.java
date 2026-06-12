package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import org.apache.commons.math3.distribution.NormalDistribution;

/**
 * Black-76 on the FORWARD (B-10 / CD-6) — never Black-Scholes on spot. Conventions pinned by the
 * committed py_vollib golden vectors (amendment A4): theta per CALENDAR DAY, vega per 1 VOL
 * POINT, rho per 1% rate move; ACT/365 year fractions. {@code BigDecimal} at the API surface;
 * solver internals are {@code double} with the final values carried through
 * {@link BigDecimal#valueOf(double)} (documented chosen-by-default rounding — persistence trims
 * to NUMERIC(12,6) at write time, Phase 15).
 *
 * <p>Expiry-day T→0 stays finite via the documented {@code T_MIN} clamp of 5 calendar minutes
 * (≈9.5e-6 years) — below it, d1/d2 degenerate and greeks would blow up.
 */
public final class Black76 {

  /** CE or PE (COMMON instrument-type spellings). */
  public enum OptionType {
    CE,
    PE
  }

  /** 5 calendar minutes in ACT/365 years — the documented T→0 clamp. */
  public static final double T_MIN = 5.0 / (365.0 * 24.0 * 60.0);

  private static final NormalDistribution NORMAL = new NormalDistribution(null, 0, 1);

  private Black76() {}

  /** Price + the five greeks for one quote. */
  public record Greeks(
      BigDecimal price,
      BigDecimal delta,
      BigDecimal gamma,
      BigDecimal theta,
      BigDecimal vega,
      BigDecimal rho) {}

  /** Undiscounted-forward Black-76 price (discounted by {@code e^-rT}). */
  public static double price(OptionType type, double f, double k, double t, double r, double sigma) {
    double tt = Math.max(t, T_MIN);
    double df = Math.exp(-r * tt);
    double sqrtT = Math.sqrt(tt);
    double d1 = (Math.log(f / k) + 0.5 * sigma * sigma * tt) / (sigma * sqrtT);
    double d2 = d1 - sigma * sqrtT;
    if (type == OptionType.CE) {
      return df * (f * cdf(d1) - k * cdf(d2));
    }
    return df * (k * cdf(-d2) - f * cdf(-d1));
  }

  /** All greeks in py_vollib analytical units (theta/day, vega/vol-point, rho/1%). */
  public static Greeks greeks(
      OptionType type, double f, double k, double t, double r, double sigma) {
    double tt = Math.max(t, T_MIN);
    double df = Math.exp(-r * tt);
    double sqrtT = Math.sqrt(tt);
    double d1 = (Math.log(f / k) + 0.5 * sigma * sigma * tt) / (sigma * sqrtT);
    double d2 = d1 - sigma * sqrtT;
    double pdfD1 = pdf(d1);

    double price =
        type == OptionType.CE
            ? df * (f * cdf(d1) - k * cdf(d2))
            : df * (k * cdf(-d2) - f * cdf(-d1));
    double delta = type == OptionType.CE ? df * cdf(d1) : -df * cdf(-d1);
    double gamma = df * pdfD1 / (f * sigma * sqrtT);
    // dV/dcalendar-day: r·V − df·F·φ(d1)·σ/(2√T), per ACT/365 day (py_vollib black convention)
    double theta = (r * price - df * f * pdfD1 * sigma / (2.0 * sqrtT)) / 365.0;
    double vega = df * f * pdfD1 * sqrtT / 100.0;
    // in Black-76 only the discount factor sees r: ∂V/∂r = −T·V, per 1% move
    double rho = -tt * price / 100.0;

    return new Greeks(
        BigDecimal.valueOf(price),
        BigDecimal.valueOf(delta),
        BigDecimal.valueOf(gamma),
        BigDecimal.valueOf(theta),
        BigDecimal.valueOf(vega),
        BigDecimal.valueOf(rho));
  }

  /** The Newton denominator: dPrice/dSigma per 1.0 of vol, NOT the per-point reporting vega. */
  static double rawVega(double f, double k, double t, double r, double sigma) {
    double tt = Math.max(t, T_MIN);
    double sqrtT = Math.sqrt(tt);
    double d1 = (Math.log(f / k) + 0.5 * sigma * sigma * tt) / (sigma * sqrtT);
    return Math.exp(-r * tt) * f * pdf(d1) * sqrtT;
  }

  static double cdf(double x) {
    return NORMAL.cumulativeProbability(x);
  }

  static double pdf(double x) {
    return NORMAL.density(x);
  }
}
