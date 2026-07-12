package in.arthayantra.backtest.provenance;

import java.util.List;

/**
 * The {@code GET /api/v1/backtests/dataset-epochs} payload: the recorded epochs (newest first) plus the
 * current {@code head} (max id, {@code 0} when none recorded) — the value a fresh run stamps. A TYPED
 * record (never a {@code Map}).
 */
public record DatasetEpochListResponse(List<DatasetEpoch> epochs, long head) {}
