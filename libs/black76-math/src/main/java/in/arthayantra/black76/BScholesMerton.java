package in.arthayantra.black76;

import in.arthayantra.black76.Black76.OptionType;
import java.math.BigDecimal;

/**
 * Black-Scholes-Merton on the SPOT with a continuous dividend/carry yield {@code q} — the correct
 * model for STOCK (equity) options, whose underlying is the cash spot (vs {@link Black76}, which
 * prices index/commodity options on the FORWARD). §6.3 seam.
 *
 * <p><b>Price + IV reuse Black-76 exactly.</b> BSM and Black-76 are the same model in different
 * coordinates: the BSM price on spot {@code S} equals the Black-76 price on the forward {@code F =
 * S·e^((r-q)T)} (both discount by {@code e^-rT}), and the implied vol that solves one solves the
 * other unchanged. So {@link #price} delegates to {@code Black76.price} on {@link #forward}, and an
 * IV solve is just {@code IvSolver.solve} on {@code forward(S,r,q,t)} — no duplicate numerics.
 *
 * <p><b>Only the greeks differ</b>, because they are taken w.r.t. the SPOT {@code S}, not the forward
 * {@code F}. Those are the standard BSM closed forms below, in the SAME reporting units as
 * {@code Black76.greeks} (theta per CALENDAR DAY ÷365, vega per 1 VOL POINT ÷100, rho per 1% ÷100;
 * ACT/365; the {@link Black76#T_MIN} T→0 clamp). Dormant until equity-option pricing is wired
 * (stock chains capture OI only today) — additive, index pricing is untouched.
 */
public final class BScholesMerton {

  private BScholesMerton() {}

  /** BSM first-order greeks on spot (a distinct shape from {@code Black76.Greeks} — spot, not forward). */
  public record Greeks(
      BigDecimal price,
      BigDecimal delta,
      BigDecimal gamma,
      BigDecimal theta,
      BigDecimal vega,
      BigDecimal rho) {}

  /** The Black-76-equivalent forward for a dividend/carry-bearing spot: {@code F = S·e^((r-q)T)}. */
  public static double forward(double s, double r, double q, double t) {
    return s * Math.exp((r - q) * Math.max(t, Black76.T_MIN));
  }

  /** BSM price on spot — delegates to the audited Black-76 price on the equivalent forward. */
  public static double price(
      OptionType type, double s, double k, double t, double r, double q, double sigma) {
    return Black76.price(type, forward(s, r, q, t), k, t, r, sigma);
  }

  /** BSM price + the five first-order greeks w.r.t. SPOT, in the platform's py_vollib-style units. */
  public static Greeks greeks(
      OptionType type, double s, double k, double t, double r, double q, double sigma) {
    double tt = Math.max(t, Black76.T_MIN);
    double sqrtT = Math.sqrt(tt);
    double dq = Math.exp(-q * tt); // dividend discount on the spot leg
    double dr = Math.exp(-r * tt); // rate discount on the strike leg
    double d1 = (Math.log(s / k) + (r - q + 0.5 * sigma * sigma) * tt) / (sigma * sqrtT);
    double d2 = d1 - sigma * sqrtT;
    double pdfD1 = Black76.pdf(d1);

    double price =
        type == OptionType.CE
            ? s * dq * Black76.cdf(d1) - k * dr * Black76.cdf(d2)
            : k * dr * Black76.cdf(-d2) - s * dq * Black76.cdf(-d1);
    double delta = type == OptionType.CE ? dq * Black76.cdf(d1) : -dq * Black76.cdf(-d1);
    double gamma = dq * pdfD1 / (s * sigma * sqrtT);
    double vega = s * dq * pdfD1 * sqrtT / 100.0;
    // theta per calendar day: the common decay term − S·q/r discount terms (sign flips by call/put).
    double decay = -s * dq * pdfD1 * sigma / (2.0 * sqrtT);
    double theta =
        (type == OptionType.CE
                ? decay - r * k * dr * Black76.cdf(d2) + q * s * dq * Black76.cdf(d1)
                : decay + r * k * dr * Black76.cdf(-d2) - q * s * dq * Black76.cdf(-d1))
            / 365.0;
    double rho =
        (type == OptionType.CE ? k * tt * dr * Black76.cdf(d2) : -k * tt * dr * Black76.cdf(-d2))
            / 100.0;

    return new Greeks(
        BigDecimal.valueOf(price),
        BigDecimal.valueOf(delta),
        BigDecimal.valueOf(gamma),
        BigDecimal.valueOf(theta),
        BigDecimal.valueOf(vega),
        BigDecimal.valueOf(rho));
  }
}
