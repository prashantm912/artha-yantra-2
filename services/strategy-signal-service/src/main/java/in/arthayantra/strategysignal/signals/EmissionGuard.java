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
   * False when the scalper 5-sub-account discipline pauses a fresh scalper ENTRY for the IST day
   * (§12.7 — 5 losses froze all sub-accounts, or 5 wins banked the day). Consulted IN ADDITION to
   * {@link #entryAllowed()}, only on the scalper entry path. Default true (non-scalper / no paper).
   */
  default boolean scalperEntryAllowed() {
    return true;
  }

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

  /**
   * The {@link #suggestedQty} variant that also applies an E8 §3.2 probability-graded size
   * {@code multiplier} (in {@code (0, 1]}) before lot-rounding. The default IGNORES the multiplier
   * (back-compat for impls that do not grade); the paper adapter overrides it to scale + re-lot-round
   * DOWN (never up, never below one lot for a fired entry). A null multiplier == the ungraded sizing.
   */
  default BigDecimal suggestedQty(
      StrategyDefinition.SizingSpec sizing,
      String exchange,
      String tradingsymbol,
      BigDecimal price,
      BigDecimal stopDistance,
      BigDecimal multiplier) {
    return suggestedQty(sizing, exchange, tradingsymbol, price, stopDistance);
  }
}
