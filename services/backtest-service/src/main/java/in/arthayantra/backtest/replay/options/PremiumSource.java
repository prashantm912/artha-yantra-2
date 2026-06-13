package in.arthayantra.backtest.replay.options;

/**
 * Options-premium provenance for a backtest run (§D.15 fidelity contract). Written to {@code
 * backtest_runs.premium_source} on EVERY run and echoed by {@code GET /backtests/{id}/results}, so a
 * synthetic-premium run can never masquerade as snapshot-grade — rendering one without its {@link
 * #SYNTHETIC_B76} flag is the FAIL condition. Leaderboard/compare views flag mixed-source
 * comparisons exactly like {@code dataHash} mismatches via {@link #likeForLike(PremiumSource,
 * PremiumSource)}.
 */
public enum PremiumSource {

  /**
   * Premiums replayed from {@code marketdata.options_chain_snapshots} LTP at the archive's native
   * 5-minute granularity — the highest-fidelity, archive-coverage-gated source.
   */
  SNAPSHOT,

  /**
   * Premiums reconstructed from underlying 1m candles via Black-76 (a clearly-labelled approximate
   * mode). IV from the nearest archived snapshot, or a flat-IV assumption for windows predating the
   * archive — both surfaced as run-level caveats, never buried.
   */
  SYNTHETIC_B76,

  /** Not applicable — the run is on a non-options strategy (no premium series replayed). */
  NA;

  /**
   * Like-for-like test for cross-run comparison: two runs are comparable only when their premium
   * provenance matches, exactly as {@code dataHash} equality gates like-for-like comparison (§D.9).
   * A {@code SNAPSHOT}-vs-{@code SYNTHETIC_B76} comparison is NOT like-for-like and must be flagged.
   *
   * @param a one run's premium source (may be null for legacy rows)
   * @param b the other run's premium source (may be null for legacy rows)
   * @return {@code true} only when both are non-null and equal
   */
  public static boolean likeForLike(PremiumSource a, PremiumSource b) {
    return a != null && a == b;
  }
}
