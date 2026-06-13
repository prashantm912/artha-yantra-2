package in.arthayantra.strategyengine.indicators;

import java.math.BigDecimal;
import java.util.Map;

/** Typed access to an indicator's {@code params{}} map (canonical-tree values). */
public final class Params {

  private final String indicator;
  private final Map<String, Object> values;

  /** Wraps a params map (may be empty, never null). */
  public Params(String indicator, Map<String, Object> values) {
    this.indicator = indicator;
    this.values = values == null ? Map.of() : values;
  }

  /** Integer param with default. */
  public int intValue(String name, int defaultValue) {
    Object raw = values.get(name);
    if (raw == null) {
      return defaultValue;
    }
    if (raw instanceof Number n) {
      return n.intValue();
    }
    throw new IllegalArgumentException(indicator + " param '" + name + "' must be a number");
  }

  /** Decimal param with default. */
  public BigDecimal decimalValue(String name, BigDecimal defaultValue) {
    Object raw = values.get(name);
    if (raw == null) {
      return defaultValue;
    }
    if (raw instanceof BigDecimal bd) {
      return bd;
    }
    if (raw instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    throw new IllegalArgumentException(indicator + " param '" + name + "' must be a number");
  }

  /** Rejects params outside the indicator's declared vocabulary (typo guard at publish). */
  public void allowOnly(String... names) {
    for (String key : values.keySet()) {
      boolean known = false;
      for (String name : names) {
        if (name.equals(key)) {
          known = true;
          break;
        }
      }
      if (!known) {
        throw new IllegalArgumentException(
            indicator + " does not accept param '" + key + "'");
      }
    }
  }
}
