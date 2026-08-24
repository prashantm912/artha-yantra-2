package in.arthayantra.marketdata.canary;

import in.arthayantra.marketdata.ingest.IngestRunLedger;

/**
 * Where each swing screener's OUTPUT lives, and the watermark that output is labelled with — the one
 * home for both, shared by {@link IngestCoverageCanary} (T+1 coverage) and {@link EveningChainCanary}
 * ("can I shut down yet?").
 *
 * <p>Both canaries have to look PAST {@code ingest_runs} at the artifact for the screeners, for two
 * different reasons (a late catch-up run for one, a dedup-skipped no-op for the other), and both
 * therefore need the same source→table mapping. Two copies of that mapping is one rename away from a
 * canary silently assessing the wrong table, so it lives here once.
 *
 * <p>Read by raw SQL rather than through {@code MinerviniScreenRepository}/{@code
 * ManasScreenRepository} deliberately, matching what {@link IngestCoverageCanary} already did: a
 * detector that imports the producers it watches becomes un-startable exactly when they are broken,
 * and {@code ManasScheduler} is {@code @ConditionalOnProperty} default-OFF, so its beans are not
 * even guaranteed to exist.
 */
final class ScreenOutputTables {

  /**
   * The bhavcopy watermark both screeners screen against — a VERBATIM copy of {@code
   * TrendTemplateService#latestScreenDate()} / {@code ManasScreenService#latestScreenDate()}. If
   * either of those changes, this must change with them.
   *
   * <p>⚠️ {@code series IN ('EQ','BE')} is load-bearing, not decoration: {@code nse_eod_bhavcopy} is
   * multi-series (13 series exist) and the screen universe spans EQ AND BE. A watermark taken over a
   * different series set is a different date, and this comparison would then answer a question the
   * screeners never asked.
   *
   * <p>{@code max()} over a bare column on a compressed hypertable — no DISTINCT/ORDER BY/GROUP BY
   * over a computed expression under a LIMIT, so the Timescale 2.18.2 sorted-merge planner assertion
   * is not in play. The identical statement already runs in production on both screeners' hot path.
   */
  static final String BHAVCOPY_WATERMARK_SQL =
      "SELECT max(trade_date) FROM nse_eod_bhavcopy WHERE series IN ('EQ','BE')";

  private ScreenOutputTables() {}

  /**
   * The destination table a screener source writes, or {@code null} for a source with no artifact
   * mapped.
   *
   * <p>Table names are switch-selected literals over our own {@link IngestRunLedger} constants, never
   * interpolated user input — every caller concatenates the result straight into SQL.
   *
   * <p>⚠️ A {@code null} must never be read as healthy by any caller: a screener whose artifact
   * cannot be located is a check that cannot fire, which is catalogue trap #14 (a guard that
   * enumerates zero items and reports success). Both callers fail LOUD on null instead.
   */
  static String tableFor(String source) {
    if (IngestRunLedger.SOURCE_MINERVINI_SCREEN.equals(source)) {
      return "minervini_screen_results";
    }
    if (IngestRunLedger.SOURCE_MANAS_SCREEN.equals(source)) {
      return "manas_arora_screen_results";
    }
    return null;
  }
}
