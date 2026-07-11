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
  COUNTERFACTUAL,
  /**
   * Swing deep-sim lineage (research-fidelity audit P0-3, §12 item 16). A self-contained job whose
   * {@code {family, from, variant}} rides the request JSONB; its worker CALLS market-data's existing
   * ~11-year Manas Arora / Minervini deep-sim over HTTP and persists the outcome into the SHIPPED
   * backtest lineage — a {@code backtest_runs} row (headline metrics + the full deep-sim report kept
   * intact in the metrics JSONB) plus per-trade {@code backtest_trades} rows, stamped with engine SHA
   * (V008) + actor (V009). Rides the existing queue/WorkerPool (dispatched onto {@code jobs.backtest},
   * claimed like a BACKTEST — the COUNTERFACTUAL precedent); results land in {@code backtest_runs}, so
   * the standard {@code /results} + {@code /trades} + {@code experiment_runs} surfaces serve it for
   * free. Widened into the jobs.kind CHECK by V014.
   */
  DEEP_SWING
}
