package in.arthayantra.backtest.jobs;

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
