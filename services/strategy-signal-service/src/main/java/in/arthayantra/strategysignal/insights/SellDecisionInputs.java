package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The pure inputs to the SELL_DECISION generator (INT design §5.3) — the durable, not-yet-acknowledged
 * SELL rows the swing batch wrote (V037 {@code sell_decisions}, §13 row 8 now shipped). Assembled at
 * the edge by {@link StrategyEvidenceReader}. The generator turns each into a SELL_DECISION insight
 * carrying the acknowledge action; a SELL un-acknowledged for {@code >= 1} session escalates to WARN
 * (§5.3 follow-through).
 */
public record SellDecisionInputs(LocalDate istDate, List<SellRow> sells) {

  /**
   * One persisted, unacknowledged SELL decision (a projection of the V037 row). {@code sessionsOpen} =
   * IST days since the run date the batch evaluated (0 = evaluated today), the escalation clock.
   */
  public record SellRow(
      long sellDecisionId,
      String book,
      LocalDate runDate,
      long signalId,
      String exchange,
      String symbol,
      String setup,
      String verdict,
      String sellReason,
      BigDecimal entryPrice,
      BigDecimal currentPrice,
      BigDecimal unrealizedPct,
      long sessionsOpen) {}
}
