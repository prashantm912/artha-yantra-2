package in.arthayantra.backtest.jobs;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code GET /api/v1/backtests/stress-window} body: the suggested clean stress window for a
 * strategy plus its family {@code evidencePolicy} (roadmap #22b).
 *
 * <p>A PURE RETYPING of the {@code LinkedHashMap} this endpoint used to return (ledger D3 slice 1):
 * same keys, same order ({@code from}, {@code to}, {@code evidencePolicy}), same value types, so the
 * wire is unchanged and only the SPEC gains the shape — springdoc cannot enumerate a
 * {@code Map<String, Object>}, so the contract gate was blind to this response.
 *
 * @param from first untested day (ISO date), or {@code null} when lineage exists but no window has
 *     been tested — i.e. the cache is clean
 * @param to the latest cached candle's date (ISO), or {@code null} when the strategy's instrument
 *     could not be resolved, in which case the owner picks the end manually
 * @param evidencePolicy the strategy's structural evidence policy; fail-soft {@code null} when
 *     unresolvable. SIM_FIRST families route final validation through this window; LIVE_FIRST
 *     families treat any sim as smoke-only, so the pick is advisory for them.
 */
public record StressWindow(
    @Schema(types = {"string", "null"}) String from,
    @Schema(types = {"string", "null"}) String to,
    @Schema(types = {"string", "null"}) String evidencePolicy) {}
