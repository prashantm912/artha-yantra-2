package in.arthayantra.backtest.jobs;

/** Discriminator for the single authoritative {@code jobs} table (§D.3). */
public enum JobKind {
  BACKTEST,
  OPTIMIZATION,
  TRIAL
}
