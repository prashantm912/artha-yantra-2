package in.arthayantra.backtest.experiments;

/**
 * One run's row in the compare matrix (audit §13 #11): its identity + the provenance axes the
 * like-for-like flags are computed over ({@code dataHash}, {@code universeChecksum}, {@code engineSha},
 * {@code premiumSource}) + the actor + the {@link CompareMetrics} cells. Runs are returned in the
 * caller's requested {@code runIds} order; ids that resolve to no completed run are simply absent.
 */
public record ExperimentCompareRun(
    String runId,
    String strategyVersionId,
    String strategyId,
    String strategyVersion,
    String dataHash,
    String universeChecksum,
    String engineSha,
    String premiumSource,
    String createdBy,
    String completedAt,
    CompareMetrics metrics) {}
