package in.arthayantra.backtest.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/backtests/run} body (§D.5). Only {@code strategyId}, {@code from} and
 * {@code to} are load-bearing in Phase 28; the cost/seed/universe fields are carried into the job's
 * {@code request} JSONB for the Phase 30 replay. {@code purpose} defaults to {@code backtest}
 * ({@code stress_test} is honored from Phase 32).
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
    String purpose) {}
