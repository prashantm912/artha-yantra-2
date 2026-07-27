package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Pure parse + sizing for the #11 long-straddle two-leg paper open. A straddle signal stamps {@code
 * side:"NEUTRAL"} and a {@code legs[]} array carrying BOTH ATM BUY legs (CE + PE) in its {@code
 * scalper_detail} side-channel; this reads them out and sizes the COMBINED-premium lot count so the two
 * legs together spend the strategy's premium budget (not 2×). No I/O — the listener does the open.
 */
public final class StraddleLegs {

  private StraddleLegs() {}

  /** One straddle leg from the side-channel: where + what + its entry premium. */
  public record Leg(String exchange, String tradingsymbol, String optionType, BigDecimal ltp) {}

  /** The ATM CE + PE pair of a long straddle. */
  public record Pair(Leg ce, Leg pe) {}

  /** The straddle's market reference — the underlying + expiry + ATM strike (for the straddle-chart). */
  public record Ref(String name, String expiry, BigDecimal strike) {}

  /**
   * The straddle market reference (underlying / expiry / ATM strike) from a signal's {@code
   * scalper_detail} tree, or empty when it is not a NEUTRAL straddle or any field is missing — used by
   * the live combined-prem exit monitor to fetch the {@code slLevel}.
   */
  public static Optional<Ref> ref(JsonNode root) {
    if (root == null || !"NEUTRAL".equals(root.path("side").asText())) {
      return Optional.empty();
    }
    String name = text(root, "underlying");
    String expiry = text(root, "expiry");
    BigDecimal strike = decimal(root, "strike");
    if (name == null || expiry == null || strike == null) {
      return Optional.empty();
    }
    return Optional.of(new Ref(name, expiry, strike));
  }

  /**
   * The combined-premium stop: exit when the live combined premium is at/below the {@code slLevel}
   * (the combined-premium session VWAP − the §3.11 buffer). False when either operand is absent.
   */
  public static boolean shouldExit(BigDecimal combinedPremium, BigDecimal slLevel) {
    return combinedPremium != null && slLevel != null && combinedPremium.compareTo(slLevel) <= 0;
  }

  /**
   * Extracts the CE+PE pair from a signal's {@code scalper_detail} tree, or empty when it is not a
   * NEUTRAL straddle carrying exactly two legs (one CE, one PE). Never throws — an absent/malformed
   * detail returns empty (the caller falls back to the single-leg open).
   */
  public static Optional<Pair> parse(JsonNode root) {
    if (root == null || root.isNull() || root.isMissingNode()) {
      return Optional.empty();
    }
    if (!"NEUTRAL".equals(root.path("side").asText())) {
      return Optional.empty();
    }
    JsonNode legs = root.path("legs");
    if (!legs.isArray() || legs.size() != 2) {
      return Optional.empty();
    }
    Leg ce = null;
    Leg pe = null;
    for (JsonNode n : legs) {
      Leg leg =
          new Leg(
              text(n, "exchange"),
              text(n, "tradingsymbol"),
              text(n, "option_type"),
              decimal(n, "option_ltp"));
      if ("CE".equals(leg.optionType())) {
        ce = leg;
      } else if ("PE".equals(leg.optionType())) {
        pe = leg;
      }
    }
    if (ce == null || pe == null || ce.tradingsymbol() == null || pe.tradingsymbol() == null) {
      return Optional.empty();
    }
    return Optional.of(new Pair(ce, pe));
  }

  /**
   * The per-leg quantity for the two legs of the straddle, ALWAYS a whole multiple of {@code lot}.
   * {@code suggestedQty} was sized against the PRIMARY (CE) premium alone (budget / (ceLtp × lot));
   * the combined straddle must spend that same budget across BOTH legs, so the split is
   * {@code suggestedQty × ceLtp / (ceLtp + peLtp)} — then floored DOWN TO A WHOLE LOT, min one lot.
   * Both legs open at this qty. Returns 0 when either premium is missing / non-positive, or when the
   * lot is unknown (the caller then skips the straddle open rather than mis-sizing the pair).
   *
   * <p><b>The lot floor is load-bearing</b> (cross-vendor review C1). Flooring to a whole UNIT
   * yielded quantities that cannot exist — CE 130 / PE 125 / lot 65 gives
   * {@code floor(65 × 130/255) = 33}, i.e. 33 units of a 65-lot contract on BOTH legs. This branch
   * was unreachable while every straddle scalper's suggested_qty was NULL; the sizing fix in this
   * same PR makes it live, and a non-lot-aligned qty is a broker reject the moment
   * {@code artha.scalper.execution} arms.
   */
  public static int combinedQty(int suggestedQty, BigDecimal ceLtp, BigDecimal peLtp, long lot) {
    if (suggestedQty <= 0
        || ceLtp == null
        || peLtp == null
        || ceLtp.signum() <= 0
        || peLtp.signum() <= 0
        || lot <= 0) {
      return 0;
    }
    BigDecimal units =
        BigDecimal.valueOf(suggestedQty)
            .multiply(ceLtp)
            .divide(ceLtp.add(peLtp), 0, RoundingMode.FLOOR);
    long lots = units.longValue() / lot;
    return Math.toIntExact(Math.max(1L, lots) * lot);
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static BigDecimal decimal(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    try {
      return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }
}
