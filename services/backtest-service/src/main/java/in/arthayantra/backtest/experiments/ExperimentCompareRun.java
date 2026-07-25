package in.arthayantra.backtest.experiments;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One run's row in the compare matrix (audit §13 #11): its identity + the provenance axes the
 * like-for-like flags are computed over ({@code dataHash}, {@code universeChecksum}, {@code engineSha},
 * {@code premiumSource}, and the roadmap-#22a/#30 additions {@code contentHash}, {@code datasetEpoch},
 * {@code evidencePolicy}) + the actor + the {@link CompareMetrics} cells. Runs are returned in the
 * caller's requested {@code runIds} order; ids that resolve to no completed run are simply absent.
 * {@code datasetEpoch} is the BIGINT epoch head as text (null on pre-V015 rows).
 * {@code premiumContentUnverified} (review F2) is {@code true} when the run consumed traded-option
 * premium legs, which contribute bar-count only to {@code contentHash} — equality of the content-hash
 * axes does not guarantee premium-data equality for such runs; {@code null} = candle-path / pre-flag row.
 */
public record ExperimentCompareRun(
    String runId,
    String strategyVersionId,
    String strategyId,
    @Schema(types = {"string", "null"}) String strategyVersion,
    String dataHash,
    @Schema(types = {"string", "null"}) String universeChecksum,
    @Schema(types = {"string", "null"}) String engineSha,
    String premiumSource,
    @Schema(types = {"string", "null"}) String contentHash,
    @Schema(types = {"string", "null"}) String datasetEpoch,
    @Schema(types = {"string", "null"}) String evidencePolicy,
    @Schema(types = {"boolean", "null"}) Boolean premiumContentUnverified,
    @Schema(types = {"string", "null"}) String createdBy,
    String completedAt,
    CompareMetrics metrics) {}
