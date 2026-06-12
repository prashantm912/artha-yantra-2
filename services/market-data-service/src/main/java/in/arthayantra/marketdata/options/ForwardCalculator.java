package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;

/**
 * Forward construction with the B-10 precedence: (a) put-call-parity-implied forward from the
 * nearest-ATM strike when BOTH legs have live two-sided quotes; (b) matching-expiry futures LTP
 * — MONTHLY expiries only (NSE lists monthly futures; weekly rows fall straight through to (c));
 * (c) {@code S·e^{rT}} carry under the pinned configurable {@code r} (default 6.5%). The chosen
 * source is part of every persisted row's provenance (B-7).
 */
public final class ForwardCalculator {

  /** Where the forward came from — persisted provenance. */
  public enum ForwardSource {
    PCP_IMPLIED,
    FUTURES_LTP,
    SPOT_CARRY
  }

  /** The resolved forward. */
  public record ForwardResult(BigDecimal forward, ForwardSource source) {}

  /** The nearest-ATM PCP legs; quotes may be zero/crossed (then PCP is unavailable). */
  public record PcpLegs(
      BigDecimal strike,
      BigDecimal callBid,
      BigDecimal callAsk,
      BigDecimal putBid,
      BigDecimal putAsk) {

    boolean bothLegsLiveTwoSided() {
      return live(callBid, callAsk) && live(putBid, putAsk);
    }

    private static boolean live(BigDecimal bid, BigDecimal ask) {
      return bid != null
          && ask != null
          && bid.signum() > 0
          && ask.signum() > 0
          && ask.compareTo(bid) >= 0;
    }
  }

  private ForwardCalculator() {}

  /**
   * Resolves the forward. {@code monthlyFuturesLtp} must be empty for weekly expiries — the
   * caller owns the monthly check (NSE has no weekly futures to match).
   */
  public static ForwardResult resolve(
      BigDecimal spot,
      double yearsToExpiry,
      BigDecimal riskFreeRate,
      Optional<PcpLegs> pcpLegs,
      Optional<BigDecimal> monthlyFuturesLtp) {
    if (pcpLegs.isPresent() && pcpLegs.get().bothLegsLiveTwoSided()) {
      PcpLegs legs = pcpLegs.get();
      BigDecimal callMid = mid(legs.callBid(), legs.callAsk());
      BigDecimal putMid = mid(legs.putBid(), legs.putAsk());
      // F = K + e^{rT}·(C − P)
      double carry = Math.exp(riskFreeRate.doubleValue() * yearsToExpiry);
      BigDecimal forward =
          legs.strike()
              .add(
                  callMid
                      .subtract(putMid)
                      .multiply(BigDecimal.valueOf(carry), MathContext.DECIMAL64));
      return new ForwardResult(forward, ForwardSource.PCP_IMPLIED);
    }
    if (monthlyFuturesLtp.isPresent() && monthlyFuturesLtp.get().signum() > 0) {
      return new ForwardResult(monthlyFuturesLtp.get(), ForwardSource.FUTURES_LTP);
    }
    double carry = Math.exp(riskFreeRate.doubleValue() * yearsToExpiry);
    return new ForwardResult(
        spot.multiply(BigDecimal.valueOf(carry), MathContext.DECIMAL64), ForwardSource.SPOT_CARRY);
  }

  private static BigDecimal mid(BigDecimal bid, BigDecimal ask) {
    return bid.add(ask).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
  }
}
