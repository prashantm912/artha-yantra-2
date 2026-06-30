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
   * The per-leg lot count for the two legs of the straddle. {@code suggestedQty} was sized against the
   * PRIMARY (CE) premium alone (budget / (ceLtp × lot)); the combined straddle must spend that same
   * budget across BOTH legs, so {@code combinedQty = suggestedQty × ceLtp / (ceLtp + peLtp)} (floored,
   * min 1). Both legs open at this qty. Returns 0 when either premium is missing / non-positive (the
   * caller then skips the straddle open). Derives purely from the two premiums — no budget/lot lookup.
   */
  public static int combinedQty(int suggestedQty, BigDecimal ceLtp, BigDecimal peLtp) {
    if (suggestedQty <= 0
        || ceLtp == null
        || peLtp == null
        || ceLtp.signum() <= 0
        || peLtp.signum() <= 0) {
      return 0;
    }
    BigDecimal q =
        BigDecimal.valueOf(suggestedQty)
            .multiply(ceLtp)
            .divide(ceLtp.add(peLtp), 0, RoundingMode.FLOOR);
    return Math.max(1, q.intValue());
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
