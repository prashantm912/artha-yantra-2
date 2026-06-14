package in.arthayantra.strategysignal.signals;

import in.arthayantra.strategyengine.config.StrategyDefinition;
import java.math.BigDecimal;

/**
 * SPI the engine consults at ENTRY emission (A12). DEFINED here in the signals module and
 * IMPLEMENTED by the paper module (the adapter holds the capital base + risk limits), so the module
 * graph stays acyclic — signals depends only on this port, never on paper. Absent (paper disabled)
 * ⇒ the engine treats entries as allowed and stamps no suggested qty.
 */
public interface EmissionGuard {

  /** False when global risk pauses ENTRY emission (daily-loss trip, kill switch, max open). */
  boolean entryAllowed();

  /**
   * The strategy's position-sizing run against the paper-account equity, lot-rounded for the
   * instrument; null when it sizes to zero or the equity is unknown. Stamped on the signal OUTSIDE
   * the frozen score breakdown.
   */
  BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance);
}
