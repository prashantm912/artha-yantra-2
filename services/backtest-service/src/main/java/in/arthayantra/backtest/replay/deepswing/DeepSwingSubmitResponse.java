package in.arthayantra.backtest.replay.deepswing;

/** The 202 body for a submitted DEEP_SWING job: the queued jobId + its status (audit P0-3). */
public record DeepSwingSubmitResponse(String jobId, String status) {}
