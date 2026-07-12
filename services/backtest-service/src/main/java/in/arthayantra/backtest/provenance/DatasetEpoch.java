package in.arthayantra.backtest.provenance;

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
    List<String> symbols,
    String exchange,
    String windowStart,
    String windowEnd,
    String interval,
    String jobLink,
    String source,
    String note,
    String createdAt) {}
