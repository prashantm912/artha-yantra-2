package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/backtests/run} body (§D.5). Only {@code strategyId}, {@code from} and
 * {@code to} are load-bearing in Phase 28; the cost/seed/universe fields are carried into the job's
 * {@code request} JSONB for the Phase 30 replay. {@code purpose} defaults to {@code backtest}
 * ({@code stress_test} is honored from Phase 32).
 *
 * <p>{@code stressOverrides} is the EVO §3.2.5 cost-stress hook: an optional block whose {@code
 * slippageMultiplier} scales the effective slippage at fill construction so the evolution engine can
 * re-run a candidate at 2×/4× slippage without touching the parameter-path grammar (which
 * deliberately has no costs/fills production). Absent ⇒ byte-identical to an unstressed run.
 */
public record BacktestRunRequest(
    String strategyId,
    String strategyVersion,
    String from,
    String to,
    String interval,
    JsonNode universeOverride,
    BigDecimal initialCapital,
    JsonNode costs,
    Long seed,
    String purpose,
    StressOverrides stressOverrides) {

  /**
   * Request-level cost-stress overrides (EVO §3.2.5). {@code slippageMultiplier} (≥ 1) multiplies the
   * effective slippage of every fill; {@code 1} is unstressed. Validated at submission
   * ({@code JobsService}), recorded in the job's {@code request} JSONB, and surfaced on the run's
   * result metrics so the optimizer can label stressed runs.
   */
  public record StressOverrides(BigDecimal slippageMultiplier) {}
}
