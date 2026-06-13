package in.arthayantra.backtest.dispatch;

/**
 * Redis Streams + pub/sub names (D12 / §D.2). Streams are transport only; the Postgres {@code jobs}
 * table is the source of truth. backtest-service consumes {@link #BACKTEST} (single runs) and, from
 * Phase 30, {@link #TRIALS} (optimizer trial fan-out); it declares all three groups up front so the
 * optimizer can attach in Stage D. Progress is published per-job on {@code jobs.<jobId>} so the
 * gateway's generic STOMP relay (/topic/jobs.&lt;jobId&gt;) forwards it unchanged.
 */
public final class Streams {

  private Streams() {}

  /** Single-run dispatch stream. */
  public static final String BACKTEST = "jobs.backtest";

  /** Optimizer trial fan-out stream (consumed from Phase 30/33). */
  public static final String TRIALS = "jobs.backtest.trials";

  /** Trial-results stream read by the optimizer (declared here, consumed by optimizer-service). */
  public static final String OPT_RESULTS = "optimizations.results";

  /** Consumer group for {@link #BACKTEST}. */
  public static final String GROUP_BACKTEST = "cg-backtest";

  /** Consumer group for {@link #TRIALS}. */
  public static final String GROUP_TRIALS = "cg-trials";

  /** Consumer group the optimizer reads {@link #OPT_RESULTS} with. */
  public static final String GROUP_OPTUNA = "cg-optuna";

  /** Per-job progress pub/sub channel prefix ({@code jobs.<jobId>}). */
  public static final String PROGRESS_CHANNEL_PREFIX = "jobs.";

  /** Redis key holding queued/running counts for the Stage-B system-status rollup. */
  public static final String SUMMARY_KEY = "jobs:summary";

  /** Per-job cancellation flag prefix observed at worker checkpoints. */
  public static final String CANCEL_KEY_PREFIX = "bt:cancel:";

  /** The progress pub/sub channel for one job. */
  public static String progressChannel(Object jobId) {
    return PROGRESS_CHANNEL_PREFIX + jobId;
  }

  /** The cancellation flag key for one job. */
  public static String cancelKey(Object jobId) {
    return CANCEL_KEY_PREFIX + jobId;
  }
}
