package in.arthayantra.backtest.experiments;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One experiment instance in the {@code GET /api/v1/backtests/experiments} list (audit §11.4 /
 * §13 #8): a completed engine replay (one {@code backtest_runs} row) enriched with its originating
 * job context — the discriminator {@code kind} (BACKTEST vs a sweep TRIAL), the sweep it belongs to
 * ({@code parentJobId}), the resolved strategy/version, the submission {@code purpose}, the actor
 * ({@code createdBy}, V009) and the run's engine identity + data provenance. Decimal metrics are
 * plain strings (precision-preserving, as the rest of the results surface). Nullable columns
 * (universeChecksum, engineSha, purpose, strategyVersion) carry {@code null} on the rows that lack
 * them — a legacy or single-instrument run.
 */
public record ExperimentSummary(
    String runId,
    String jobId,
    String kind,
    @Schema(types = {"string", "null"}) String parentJobId,
    String strategyVersionId,
    String strategyId,
    @Schema(types = {"string", "null"}) String strategyVersion,
    @Schema(types = {"string", "null"}) String purpose,
    String exchange,
    String tradingsymbol,
    String interval,
    String startTs,
    String endTs,
    @Schema(types = {"string", "null"}) String totalReturn,
    @Schema(types = {"string", "null"}) String sharpe,
    @Schema(types = {"string", "null"}) String maxDrawdown,
    int tradeCount,
    String dataHash,
    @Schema(types = {"string", "null"}) String universeChecksum,
    @Schema(types = {"string", "null"}) String engineSha,
    String premiumSource,
    @Schema(types = {"string", "null"}) String createdBy,
    String completedAt) {}
