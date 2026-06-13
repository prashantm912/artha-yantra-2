package in.arthayantra.strategyengine.eval;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * THE BPB single-record contract (§C-2.6, ADR A1) — serialized identically by the live signal
 * engine and the backtest engine (one shape, two producers; byte-identity is the Stage-D parity
 * assertion). FREEZES in Phase 20, before any consumer exists. Renderer invariant:
 * {@code composite = Σ contributions / weightDenominator}. {@code indicators[]} holds the
 * scoring participants (instances with a normalize mapping); gate-only indicators appear in the
 * {@code gate} tree with their operand values.
 */
public record ScoreBreakdown(
    BigDecimal composite,
    BigDecimal threshold,
    boolean passed,
    BigDecimal requiredComposite,
    BigDecimal optionalMinScore,
    BigDecimal optionalGateMargin,
    BigDecimal weightDenominator,
    GateResult gate,
    List<IndicatorEntry> indicators) {

  /** Why an indicator did or did not count (§C-2.6). */
  public enum ActivationReason {
    REQUIRED,
    ACTIVATED,
    SCORE_BELOW_MIN,
    MARGIN_NOT_MET
  }

  /** One scoring indicator's line in the reasoning panel. */
  public record IndicatorEntry(
      String alias,
      String name,
      String timeframe,
      BigDecimal score,
      BigDecimal weight,
      BigDecimal contribution,
      boolean optional,
      boolean activated,
      ActivationReason activationReason,
      BigDecimal rawValue,
      Map<String, Object> params) {}

  /**
   * Recursive gate evaluation result mirroring the YAML tree; leaves carry the rule text and
   * actual operand values (null while warming up — an unresolved leaf fails).
   */
  public record GateResult(
      String kind,
      String rule,
      boolean passed,
      Map<String, BigDecimal> operands,
      List<GateResult> children) {}
}
