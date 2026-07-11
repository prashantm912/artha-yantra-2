package in.arthayantra.backtest.jobs;

/** Discriminator for the single authoritative {@code jobs} table (§D.3). */
public enum JobKind {
  BACKTEST,
  OPTIMIZATION,
  TRIAL,
  /**
   * Counterfactual premium replay (EVO E3 item 9, §3.3.4): replays REAL observed entries' captured
   * option premium under alternative exit-knob sets. Carries no strategy replay / no single instrument
   * — a self-contained job whose entries + variants ride in the request JSONB. Rides the existing
   * queue/WorkerPool (dispatched onto {@code jobs.backtest}, claimed like a BACKTEST); results land in
   * {@code counterfactual_runs}, not {@code backtest_runs}. Widened into the jobs.kind CHECK by V013.
   */
  COUNTERFACTUAL
}
