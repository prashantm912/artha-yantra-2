package in.arthayantra.marketdata.screener;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The DEEP_SWING internal endpoint's response (research-fidelity audit P0-3): the deep-sim {@link
 * DeepSwingRunResult} plus THIS market-data jar's engine CODE identity — the git commit SHA + build
 * image baked into the jar that actually computed the numbers. The backtest-service worker stamps
 * these onto the {@code backtest_runs} row (V008): the deep-sim executes in market-data, so market-
 * data's SHA is the honest "engine that produced this run" (the job row keeps backtest-service's SHA,
 * the V008 submit-vs-execute distinction). Both are {@code null} on a jar built without git.properties
 * (mock / test), leaving the additive-nullable columns NULL — fail-soft, exactly like {@code
 * EngineIdentity}.
 */
public record DeepSwingRunResponse(
    @Schema(types = {"string", "null"}) String engineSha,
    @Schema(types = {"string", "null"}) String engineImage,
    DeepSwingRunResult result) {}
