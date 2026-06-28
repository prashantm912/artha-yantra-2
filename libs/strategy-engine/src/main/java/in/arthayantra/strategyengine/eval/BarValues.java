package in.arthayantra.strategyengine.eval;

import java.math.BigDecimal;

/**
 * Alias/built-in value access at a primary bar — the seam between the evaluators and the
 * {@link IndicatorBank} (tests drive truth tables through stubs; production uses the bank).
 */
public interface BarValues {

  /** Indicator value by alias at a primary index (cross-timeframe mapped); null while warming. */
  BigDecimal valueAt(String alias, int primaryIndex);

  /** The previous mapped bar's value (crossover leg); null at the series edge. */
  BigDecimal previousValueAt(String alias, int primaryIndex);

  /** Built-in operand (close, volume, vwap) at a primary index. */
  BigDecimal builtin(String name, int primaryIndex);

  /**
   * True when an indicator {@code alias} is present in the bank. Default false so existing stubs keep
   * compiling; the concrete {@link IndicatorBank} overrides it. A higher-TF gate reads an optional alias
   * only when declared ({@code has(alias) ? valueAt(alias, i) : null}), so a strategy that omits the
   * higher-TF indicator never trips {@code valueAt}'s "alias not in the bank" guard.
   */
  default boolean has(String alias) {
    return false;
  }

  /** True when {@code name} is a built-in operand of the closed grammar. */
  static boolean isBuiltin(String name) {
    return "close".equals(name) || "volume".equals(name) || "vwap".equals(name);
  }
}
