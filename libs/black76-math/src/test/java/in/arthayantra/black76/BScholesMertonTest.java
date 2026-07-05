package in.arthayantra.black76;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.black76.Black76.OptionType;
import org.junit.jupiter.api.Test;

/**
 * BSM-on-spot verified against the audited Black-76: price + IV are the same model in forward
 * coordinates (exact equality); put-call parity holds; the q=0 case is plain Black-Scholes; and the
 * spot greeks match a central finite difference of BSM's own price.
 */
class BScholesMertonTest {

  private static final double S = 100.0;
  private static final double K = 105.0;
  private static final double T = 0.25;
  private static final double R = 0.065;
  private static final double Q = 0.02;
  private static final double SIG = 0.22;

  @Test
  void priceEqualsBlack76OnTheEquivalentForward() {
    double f = BScholesMerton.forward(S, R, Q, T);
    for (OptionType type : OptionType.values()) {
      assertThat(BScholesMerton.price(type, S, K, T, R, Q, SIG))
          .isCloseTo(Black76.price(type, f, K, T, R, SIG), within(1e-9));
    }
  }

  @Test
  void obeysPutCallParity() {
    double c = BScholesMerton.price(OptionType.CE, S, K, T, R, Q, SIG);
    double p = BScholesMerton.price(OptionType.PE, S, K, T, R, Q, SIG);
    // C − P = S·e^(−qT) − K·e^(−rT)
    double expected = S * Math.exp(-Q * T) - K * Math.exp(-R * T);
    assertThat(c - p).isCloseTo(expected, within(1e-9));
  }

  @Test
  void zeroDividendReducesToPlainBlackScholes() {
    // q=0 → delta_call = N(d1) with d1 the plain BS d1, and price uses no dividend discount.
    double d1 = (Math.log(S / K) + (R + 0.5 * SIG * SIG) * T) / (SIG * Math.sqrt(T));
    BScholesMerton.Greeks g = BScholesMerton.greeks(OptionType.CE, S, K, T, R, 0.0, SIG);
    assertThat(g.delta().doubleValue()).isCloseTo(Black76.cdf(d1), within(1e-9));
  }

  @Test
  void ivRoundTripsThroughTheForward() {
    double price = BScholesMerton.price(OptionType.CE, S, K, T, R, Q, SIG);
    IvSolver.IvResult iv =
        IvSolver.solve(OptionType.CE, BScholesMerton.forward(S, R, Q, T), K, T, R, price);
    assertThat(iv.reason()).isEqualTo(IvSolver.Reason.OK);
    assertThat(iv.iv().doubleValue()).isCloseTo(SIG, within(1e-4));
  }

  @Test
  void firstOrderGreeksMatchFiniteDifference() {
    for (OptionType type : OptionType.values()) {
      BScholesMerton.Greeks g = BScholesMerton.greeks(type, S, K, T, R, Q, SIG);
      double h = 0.01;

      double up = BScholesMerton.price(type, S + h, K, T, R, Q, SIG);
      double dn = BScholesMerton.price(type, S - h, K, T, R, Q, SIG);
      double mid = BScholesMerton.price(type, S, K, T, R, Q, SIG);
      assertThat(g.delta().doubleValue()).isCloseTo((up - dn) / (2 * h), within(1e-4));
      assertThat(g.gamma().doubleValue()).isCloseTo((up - 2 * mid + dn) / (h * h), within(1e-4));

      double dv = 1e-4;
      double vUp = BScholesMerton.price(type, S, K, T, R, Q, SIG + dv);
      double vDn = BScholesMerton.price(type, S, K, T, R, Q, SIG - dv);
      // greeks.vega is per 1 vol POINT (÷100) — FD dPrice/dσ is per 1.0 of σ, so ÷100 to compare.
      assertThat(g.vega().doubleValue()).isCloseTo((vUp - vDn) / (2 * dv) / 100.0, within(1e-4));

      double dr = 1e-5;
      double rUp = BScholesMerton.price(type, S, K, T, R + dr, Q, SIG);
      double rDn = BScholesMerton.price(type, S, K, T, R - dr, Q, SIG);
      // rho is per 1% (÷100).
      assertThat(g.rho().doubleValue()).isCloseTo((rUp - rDn) / (2 * dr) / 100.0, within(1e-3));

      double dt = 1e-5;
      double tUp = BScholesMerton.price(type, S, K, T + dt, R, Q, SIG);
      double tDn = BScholesMerton.price(type, S, K, T - dt, R, Q, SIG);
      // theta per CALENDAR DAY = −∂V/∂T_years ÷ 365 (calendar time advances as T shrinks).
      assertThat(g.theta().doubleValue())
          .isCloseTo(-(tUp - tDn) / (2 * dt) / 365.0, within(1e-4));
    }
  }
}
