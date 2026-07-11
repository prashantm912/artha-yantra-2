package in.arthayantra.backtest.replay.counterfactual;

/**
 * {@code POST /api/v1/backtests/counterfactual} 202 body — the queued job id + status. A typed record
 * (not {@code Map<String,Object>}) so it enumerates into the OpenAPI spec and never trips edge-gateway's
 * {@code MapReturnRatchetTest}.
 */
public record CounterfactualSubmitResponse(String jobId, String status) {}
