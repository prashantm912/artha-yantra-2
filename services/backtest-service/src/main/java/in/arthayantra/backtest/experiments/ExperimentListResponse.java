package in.arthayantra.backtest.experiments;

import java.util.List;

/**
 * The {@code {items:[…]}} envelope for the experiment list (a TYPED record, never a {@code Map} — the
 * per-service Map-return ratchet is at capacity, and a record enumerates into the contract spec).
 */
public record ExperimentListResponse(List<ExperimentSummary> items, int limit, int offset) {}
