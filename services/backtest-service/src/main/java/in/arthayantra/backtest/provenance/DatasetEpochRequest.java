package in.arthayantra.backtest.provenance;

import java.util.List;

/**
 * The body of {@code POST /api/v1/backtests/dataset-epochs} — records a data-rewrite epoch (§11.3). The
 * caller is whoever rewrote candle data (a CA re-adjust / authoritative re-fetch / backfill job, or the
 * owner). {@code reason} is required and validated against the ledger's enum; everything else is optional
 * scope narrowing ({@code symbols}/{@code exchange}/{@code window*}/{@code interval} absent ⇒ a global,
 * whole-history rewrite). A TYPED record (never a {@code Map}).
 */
public record DatasetEpochRequest(
    String reason,
    List<String> symbols,
    String exchange,
    String windowStart,
    String windowEnd,
    String interval,
    String jobLink,
    String note) {}
