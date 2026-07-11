package in.arthayantra.backtest.replay.deepswing;

/**
 * The DEEP_SWING submission body (research-fidelity audit P0-3). {@code family} selects the deep-sim
 * ({@code manas} | {@code minervini}); {@code from} is the sim start date (a plain ISO date, e.g.
 * {@code 2015-01-01} — the deep-sim is date-based over {@code candles}@1d); {@code variant} picks the
 * phased-grid variant (null ⇒ the family's primary full-filter variant). Validated + pinned into the
 * job request JSONB at submission.
 */
public record DeepSwingRunRequest(String family, String from, String variant) {}
