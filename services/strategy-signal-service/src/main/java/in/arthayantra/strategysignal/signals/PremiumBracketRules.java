package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resolves a strategy version's {@code exit_rules} premium_pct percentages against a long option's
 * entry premium into absolute bracket levels: {@code stop_loss 50} → SL = ltp×0.50; {@code
 * take_profit 35} → TP = ltp×1.35 (2dp HALF_UP). Non-premium_pct bases and absent rules yield no
 * level. Same semantics as the paper take's bracket derivation ({@code PaperSignalListener}) —
 * duplicated here because the signals slice cannot import the paper slice (Modulith direction),
 * the same small-math-duplication trade the pre-open scanner made (#470).
 */
final class PremiumBracketRules {

  private PremiumBracketRules() {}

  /** Premium bracket levels; either side may be null (no premium_pct rule of that type). */
  record Brackets(BigDecimal stopLoss, BigDecimal takeProfit) {
    static final Brackets NONE = new Brackets(null, null);
  }

  /** Derives the bracket levels from the version config; null-safe on any malformed shape. */
  static Brackets resolve(JsonNode config, BigDecimal entryLtp) {
    if (config == null || entryLtp == null) {
      return Brackets.NONE;
    }
    BigDecimal sl = null;
    BigDecimal tp = null;
    for (JsonNode rule : config.path("exit_rules")) {
      if (!"premium_pct".equals(rule.path("params").path("basis").asText())) {
        continue;
      }
      BigDecimal pct = decimal(rule.path("params").path("value"));
      if (pct == null) {
        continue;
      }
      BigDecimal fraction = pct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
      String type = rule.path("type").asText();
      if ("stop_loss".equals(type)) {
        sl = entryLtp.multiply(BigDecimal.ONE.subtract(fraction)).setScale(2, RoundingMode.HALF_UP);
      } else if ("take_profit".equals(type)) {
        tp = entryLtp.multiply(BigDecimal.ONE.add(fraction)).setScale(2, RoundingMode.HALF_UP);
      }
    }
    return new Brackets(sl, tp);
  }

  private static BigDecimal decimal(JsonNode n) {
    if (n == null || n.isMissingNode() || n.isNull()) {
      return null;
    }
    try {
      return new BigDecimal(n.asText());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
