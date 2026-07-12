package in.arthayantra.strategysignal.insights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The pure inputs to the STRATEGY_EVIDENCE generator (INT design §5.2) — today's graduation board vs
 * yesterday's persisted snapshot, per strategy. Assembled at the edge by {@link StrategyEvidenceReader}
 * (the 21:10 nightly sweep, after the 21:00 graduation evaluator): {@code board} is the current
 * {@code GraduationService.board()} read, {@code yesterday} is the last suppressed-INFO snapshot row's
 * parsed pass-map per strategy (the "yesterday exists" guarantee that lets the crossing compare run
 * with NO new table), and {@code graduated} is the {@code strategy_graduations} marker set.
 *
 * <p>The generator is a pure function of these — it emits one suppressed-INFO snapshot per strategy
 * (the next day's baseline) plus, when a graduation criterion or stage crossed since yesterday, one
 * visible STRATEGY_EVIDENCE crossing insight (NOTICE up / WARN down). No re-scoring: the board metrics
 * ARE {@code GraduationService}'s (§0 hard boundary).
 */
public record StrategyEvidenceInputs(
    LocalDate istDate, List<BoardRow> board, Map<UUID, PriorSnapshot> yesterday, Set<UUID> graduated) {

  /** One strategy's current graduation-board readiness (a projection of {@code StrategyGraduation}). */
  public record BoardRow(
      UUID strategyId,
      String slug,
      String name,
      String stage,
      int trades,
      BigDecimal profitFactor,
      BigDecimal expectancy,
      BigDecimal maxDrawdownPct,
      List<Criterion> criteria) {}

  /** One scored graduation criterion (name / human bound / actual / pass) — mirrors the board's. */
  public record Criterion(String name, String required, String actual, boolean pass) {}

  /**
   * Yesterday's snapshot as needed for crossing detection — the stage and the per-criterion pass-map,
   * parsed back out of the prior snapshot insight's evidence (§5.2, no new table). Only pass/fail +
   * stage are needed; the crossing title/explanation quote TODAY's board values.
   */
  public record PriorSnapshot(String stage, Map<String, Boolean> criteriaPass) {}
}
