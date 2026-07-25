package in.arthayantra.backtest.provenance;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One row of the {@code dataset_epochs} append-only ledger (§11.3): a recorded DATA-REWRITE event. The
 * monotonic {@code id} IS the epoch — a run stamps the head (max id) at execution, and a later epoch row
 * whose scope intersects the run makes that run STALE. {@code symbols} {@code null}/empty = a global
 * (whole-store) rewrite; {@code windowStart}/{@code windowEnd}/{@code interval} {@code null} = whole
 * history / all intervals. A TYPED record (never a {@code Map}).
 */
public record DatasetEpoch(
    long id,
    String reason,
    @Schema(types = {"array", "null"}) List<String> symbols,
    @Schema(types = {"string", "null"}) String exchange,
    @Schema(types = {"string", "null"}) String windowStart,
    @Schema(types = {"string", "null"}) String windowEnd,
    @Schema(types = {"string", "null"}) String interval,
    @Schema(types = {"string", "null"}) String jobLink,
    @Schema(types = {"string", "null"}) String source,
    @Schema(types = {"string", "null"}) String note,
    String createdAt) {}
