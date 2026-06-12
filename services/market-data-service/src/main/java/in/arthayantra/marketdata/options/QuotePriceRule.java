package in.arthayantra.marketdata.options;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.Optional;

/**
 * The solver price-input rule (B-10): bid/ask MID when both sides are live and uncrossed, else
 * LTP behind a staleness guard. The chosen {@code price_source} provenance code is persisted with
 * every snapshot row, so each stored IV records which input produced it.
 */
public final class QuotePriceRule {

  /** Provenance of the solver input price. */
  public enum PriceSource {
    MID,
    LTP
  }

  /** The chosen input. */
  public record PriceInput(BigDecimal price, PriceSource source) {}

  /** LTPs older than this are unusable — better a null IV than a poisoned archive. */
  public static final Duration MAX_LTP_AGE = Duration.ofMinutes(10);

  private QuotePriceRule() {}

  /** Chooses the solver input; empty means the row records a null IV with its reason. */
  public static Optional<PriceInput> choose(
      BigDecimal bid, BigDecimal ask, BigDecimal ltp, Duration ltpAge) {
    boolean liveUncrossed =
        bid != null
            && ask != null
            && bid.signum() > 0
            && ask.signum() > 0
            && ask.compareTo(bid) >= 0;
    if (liveUncrossed) {
      BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
      return Optional.of(new PriceInput(mid, PriceSource.MID));
    }
    if (ltp != null && ltp.signum() > 0 && ltpAge != null && ltpAge.compareTo(MAX_LTP_AGE) <= 0) {
      return Optional.of(new PriceInput(ltp, PriceSource.LTP));
    }
    return Optional.empty();
  }
}
