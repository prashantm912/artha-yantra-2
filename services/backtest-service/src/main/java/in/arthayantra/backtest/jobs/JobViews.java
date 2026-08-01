package in.arthayantra.backtest.jobs;

import in.arthayantra.backtest.provenance.ProvenanceBlock;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/** Typed response views for the backtest job list and detail endpoints. */
public final class JobViews {

  private JobViews() {}

  /** The paged job-list envelope. */
  public record BacktestJobPage(List<BacktestJobSummary> items, int limit, int offset) {}

  /** One job-list row, including the optional completed-run return. */
  public record BacktestJobSummary(
      String jobId,
      String kind,
      String status,
      int progress,
      OffsetDateTime createdAt,
      @Schema(types = {"string", "null"}) String strategyId,
      @Schema(types = {"string", "null"}) String strategyVersion,
      @Schema(types = {"string", "null"}) String totalReturn,
      @Schema(types = {"string", "null"}) String testFrom,
      @Schema(types = {"string", "null"}) String testTo,
      @Schema(types = {"string", "null"}) String interval,
      @Schema(types = {"string", "null"}) String initialCapital,
      @Schema(types = {"integer", "null"}) Long seed,
      List<String> tags,
      @Schema(types = {"string", "null"}) String note) {}

  /**
   * The 202 submission body (ledger D3 Map-return burn-down). Formerly a MULTI-key {@code Map.of},
   * whose iteration order is JVM-salted — so this component order is NORMALISED, not preserved:
   * there was no stable emitted order to keep. The KEY SET is unchanged (all three were
   * unconditional; {@code Map.of} throws on a null value, so none could ever have been absent or
   * null), and {@code provenance} keeps the same already-typed {@code ProvenanceBlock} it always
   * carried — typing the envelope only stops the spec publishing it as a bare object.
   */
  public record BacktestRunAccepted(String jobId, String status, ProvenanceBlock provenance) {}

  /**
   * The 202 cancel body — one key, {@code "cancelling"}. The other cancel outcome is a 204 with NO
   * body, so this record describes the 202 branch only and nothing gains a present-as-null key.
   */
  public record JobCancelAccepted(String status) {}

  /** One job-status/detail payload. */
  public record BacktestJobDetail(
      String jobId,
      String kind,
      String status,
      int progress,
      @Schema(types = {"string", "null"}) OffsetDateTime startedAt,
      @Schema(types = {"string", "null"}) OffsetDateTime finishedAt,
      @Schema(types = {"string", "null"}) String error,
      @Schema(types = {"string", "null"}) String resultRef) {}
}
