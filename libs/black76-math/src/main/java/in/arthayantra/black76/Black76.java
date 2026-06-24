package in.arthayantra.black76;

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

  /**
   * Price + the five first-order greeks AND the three second-order greeks for one quote.
   *
   * <p>The higher-order trio (vanna/charm/vomma, plan §17.6) rides as ADDITIVE fields — the
   * first-order values are byte-identical to the pre-existing record, so the golden vectors and any
   * downstream parity stay unchanged. Reporting conventions mirror the first-order scaling:
   *
   * <ul>
   *   <li><b>vanna</b> ∂Δ/∂σ ≡ ∂vega/∂F — per 1 VOL POINT (÷100), like vega; call/put-identical.
   *   <li><b>charm</b> ∂Δ/∂t — Δ-decay per CALENDAR DAY (÷365), like theta; the discount term flips
   *       sign by call/put.
   *   <li><b>vomma</b> (volga) ∂vega/∂σ — per 1 VOL POINT of vega per 1 VOL POINT of σ (÷10 000);
   *       call/put-identical.
   * </ul>
   */
  public record Greeks(
      BigDecimal price,
      BigDecimal delta,
      BigDecimal gamma,
      BigDecimal theta,
      BigDecimal vega,
      BigDecimal rho,
      BigDecimal vanna,
      BigDecimal charm,
      BigDecimal vomma) {}

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

    // SECOND-ORDER greeks (§17.6) — closed forms, same scaling family as their first-order parents.
    // vanna = ∂Δ/∂σ ≡ ∂vega/∂F = −df·φ(d1)·d2/σ; reported per VOL POINT (÷100) like vega. The cross
    // derivative is call/put-identical (the put/call delta differ only by a σ-independent −df).
    double vanna = -df * pdfD1 * d2 / sigma / 100.0;
    // vomma/volga = ∂vega/∂σ = vega_raw·d1·d2/σ where vega_raw = df·F·φ(d1)·√T; reported per VOL
    // POINT of vega per VOL POINT of σ (÷100 twice ⇒ ÷10 000). Also call/put-identical.
    double vomma = df * f * pdfD1 * sqrtT * d1 * d2 / sigma / 10000.0;
    // charm = ∂Δ/∂t (Δ-decay), reported per CALENDAR DAY (÷365) like theta. ∂d1/∂t carries the
    // forward-moneyness drift; the −r·df·N(±d1) discount term flips sign by call/put (put delta is
    // call delta − df, whose ∂/∂t is +r·df).
    double dd1Dt = -Math.log(f / k) / (2.0 * sigma * tt * sqrtT) + 0.25 * sigma / sqrtT;
    double charm =
        (type == OptionType.CE
                ? -r * df * cdf(d1) + df * pdfD1 * dd1Dt
                : r * df * cdf(-d1) + df * pdfD1 * dd1Dt)
            / 365.0;

    return new Greeks(
        BigDecimal.valueOf(price),
        BigDecimal.valueOf(delta),
        BigDecimal.valueOf(gamma),
        BigDecimal.valueOf(theta),
        BigDecimal.valueOf(vega),
        BigDecimal.valueOf(rho),
        BigDecimal.valueOf(vanna),
        BigDecimal.valueOf(charm),
        BigDecimal.valueOf(vomma));
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
