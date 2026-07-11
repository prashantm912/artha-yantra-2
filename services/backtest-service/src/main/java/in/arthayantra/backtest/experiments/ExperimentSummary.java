package in.arthayantra.backtest.experiments;

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
    String parentJobId,
    String strategyVersionId,
    String strategyId,
    String strategyVersion,
    String purpose,
    String exchange,
    String tradingsymbol,
    String interval,
    String startTs,
    String endTs,
    String totalReturn,
    String sharpe,
    String maxDrawdown,
    int tradeCount,
    String dataHash,
    String universeChecksum,
    String engineSha,
    String premiumSource,
    String createdBy,
    String completedAt) {}
