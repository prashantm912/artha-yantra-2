package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.black76.Black76;
import in.arthayantra.black76.IvSolver;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/**
 * The B-10 edge corpus: degenerate quotes → null IV + reason code, NEVER NaN/Infinity; T→0 stays
 * finite via the documented clamp; the price-input rule and forward precedence behave exactly as
 * pinned.
 */
class Black76EdgeCorpusTest {

  private static final double F = 22_000;
  private static final double R = 0.065;

  @Test
  void belowDiscountedIntrinsicYieldsNullIvWithReason() {
    // deep ITM call quoted below its discounted intrinsic — no vol can produce this price
    IvSolver.IvResult result =
        IvSolver.solve(Black76.OptionType.CE, F, 18_000, 30 / 365.0, R, 3_900);

    assertThat(result.iv()).isNull();
    assertThat(result.reason()).isEqualTo(IvSolver.Reason.BELOW_INTRINSIC);
  }

  @Test
  void zeroQuoteYieldsNullIvWithReason() {
    IvSolver.IvResult result = IvSolver.solve(Black76.OptionType.PE, F, 22_000, 7 / 365.0, R, 0);

    assertThat(result.iv()).isNull();
    assertThat(result.reason()).isEqualTo(IvSolver.Reason.ZERO_QUOTE);
  }

  @Test
  void absurdPriceBeyondMaxVolYieldsNoConvergenceNeverNan() {
    IvSolver.IvResult result =
        IvSolver.solve(Black76.OptionType.CE, F, 22_000, 0.5 / 365.0, R, 21_999);

    assertThat(result.iv()).isNull();
    assertThat(result.reason()).isEqualTo(IvSolver.Reason.NO_CONVERGENCE);
  }

  @Test
  void expiryDayTimeToZeroStaysFiniteViaTheClamp() {
    // walk T from 5 trading minutes down to literal zero — greeks must stay finite
    for (double t : new double[] {5.0 / (365 * 24 * 60), 1.0 / (365 * 24 * 60), 0.0}) {
      Black76.Greeks g = Black76.greeks(Black76.OptionType.CE, F, F, t, R, 0.24);
      for (BigDecimal value :
          new BigDecimal[] {g.price(), g.delta(), g.gamma(), g.theta(), g.vega(), g.rho()}) {
        assertThat(value).as("finite at T=%s", t).isNotNull();
        // BigDecimal.valueOf(NaN/Infinity) would have thrown — reaching here proves finiteness
      }
      assertThat(g.delta().doubleValue()).isBetween(0.0, 1.0);
    }
  }

  @Test
  void afterExpiryCutoffIvAndGreeksAreNullByDefinition() {
    LocalDate expiry = LocalDate.parse("2026-02-03");
    Instant after = OffsetDateTime.parse("2026-02-03T15:31:00+05:30").toInstant();
    Instant before = OffsetDateTime.parse("2026-02-03T15:25:00+05:30").toInstant();

    assertThat(ExpiryClock.yearsToExpiry(after, expiry)).isEmpty();
    OptionalDouble t = ExpiryClock.yearsToExpiry(before, expiry);
    assertThat(t).isPresent();
    assertThat(t.getAsDouble()).isPositive();
  }

  @Test
  void priceInputRulePrefersLiveUncrossedMid() {
    Optional<QuotePriceRule.PriceInput> mid =
        QuotePriceRule.choose(
            new BigDecimal("100.00"), new BigDecimal("101.00"),
            new BigDecimal("99.55"), Duration.ofSeconds(5));
    assertThat(mid).isPresent();
    assertThat(mid.get().source()).isEqualTo(QuotePriceRule.PriceSource.MID);
    assertThat(mid.get().price()).isEqualByComparingTo("100.50");

    // crossed book falls back to a fresh LTP
    Optional<QuotePriceRule.PriceInput> ltp =
        QuotePriceRule.choose(
            new BigDecimal("101.00"), new BigDecimal("100.00"),
            new BigDecimal("99.55"), Duration.ofSeconds(5));
    assertThat(ltp).isPresent();
    assertThat(ltp.get().source()).isEqualTo(QuotePriceRule.PriceSource.LTP);

    // zero book + stale LTP → nothing (the row records a null IV)
    assertThat(
            QuotePriceRule.choose(
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("99.55"), Duration.ofMinutes(11)))
        .isEmpty();
  }

  @Test
  void forwardPrecedenceIsPcpThenMonthlyFuturesThenCarry() {
    BigDecimal spot = new BigDecimal("22000");
    double t = 30 / 365.0;
    BigDecimal r = new BigDecimal("0.065");
    ForwardCalculator.PcpLegs liveLegs =
        new ForwardCalculator.PcpLegs(
            new BigDecimal("22000"),
            new BigDecimal("310.00"), new BigDecimal("312.00"),
            new BigDecimal("190.00"), new BigDecimal("192.00"));

    // (a) PCP wins when both legs are live two-sided
    ForwardCalculator.ForwardResult pcp =
        ForwardCalculator.resolve(
            spot, t, r, Optional.of(liveLegs), Optional.of(new BigDecimal("22120")));
    assertThat(pcp.source()).isEqualTo(ForwardCalculator.ForwardSource.PCP_IMPLIED);
    // F = K + e^{rT}(C−P) = 22000 + e^{0.065·30/365}·120 ≈ 22120.64
    assertThat(pcp.forward().doubleValue()).isCloseTo(22_120.64, org.assertj.core.data.Offset.offset(0.05));

    // (b) dead PCP legs + monthly futures → futures LTP
    ForwardCalculator.PcpLegs deadLegs =
        new ForwardCalculator.PcpLegs(
            new BigDecimal("22000"), BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("190.00"), new BigDecimal("192.00"));
    ForwardCalculator.ForwardResult fut =
        ForwardCalculator.resolve(
            spot, t, r, Optional.of(deadLegs), Optional.of(new BigDecimal("22120")));
    assertThat(fut.source()).isEqualTo(ForwardCalculator.ForwardSource.FUTURES_LTP);
    assertThat(fut.forward()).isEqualByComparingTo("22120");

    // (c) weekly expiry (no futures passed) with dead legs → S·e^{rT}
    ForwardCalculator.ForwardResult carry =
        ForwardCalculator.resolve(spot, t, r, Optional.of(deadLegs), Optional.empty());
    assertThat(carry.source()).isEqualTo(ForwardCalculator.ForwardSource.SPOT_CARRY);
    assertThat(carry.forward().doubleValue())
        .isCloseTo(22_000 * Math.exp(0.065 * t), org.assertj.core.data.Offset.offset(0.01));
  }
}
