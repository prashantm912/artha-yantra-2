package in.arthayantra.marketdata.canary;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.canary.IngestCoverageCanary.ExpectedSource;
import in.arthayantra.marketdata.canary.IngestCoverageCanary.Policy;
import in.arthayantra.marketdata.ingest.IngestRunLedger;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins WHICH ingest sources the coverage canary expects, and therefore which ones it can report a
 * miss for at all.
 *
 * <p>⚠️ Why this exists. {@code EQUITY_BREADTH} wrote to {@code marketdata.ingest_runs} from #686
 * and was never in {@link IngestCoverageCanary#EXPECTED}, so a session where it did not run was not
 * merely un-alerted — it was <b>unobservable</b>, because both the canary and {@link
 * IngestHealthBoard} derive their entire source list from that constant. Nothing in the repo would
 * have failed if the source had been dropped again, which is the shape this file closes.
 *
 * <p>⚠️ An earlier version of this note cited "2026-08-12 with no run at all on a full trading day"
 * as the justifying miss. That is literally true and reads as a miss, but it was NOT one — the day
 * was materialized late and stamped 2026-08-13. See {@code Policy#MATERIALIZED_DAY}. The datum is
 * load-bearing for the POLICY choice, not for this ratchet, which stands on its own.
 *
 * <p>The set is asserted by CONTENT, not just size: a count alone is satisfied by swapping one
 * source for another, and the thing worth pinning is that a specific source is still watched.
 */
class IngestCoverageExpectedSetTest {

  /**
   * ⚠️ RATCHET. Lowering this is a deliberate act: it means a source the canary used to watch is no
   * longer watched, and nothing else in the codebase will say so.
   */
  private static final int EXPECTED_SOURCE_COUNT = 9;

  @Test
  @DisplayName("every audited ingest source is still registered, by name and policy")
  void theExpectedSetIsNotSilentlyShrunk() {
    assertThat(IngestCoverageCanary.EXPECTED)
        .hasSize(EXPECTED_SOURCE_COUNT)
        .containsExactlyInAnyOrder(
            new ExpectedSource(IngestRunLedger.SOURCE_NSE_FII_DII, Policy.REQUIRE_SUCCESS),
            new ExpectedSource(IngestRunLedger.SOURCE_NSE_PARTICIPANT_OI, Policy.REQUIRE_SUCCESS),
            new ExpectedSource(IngestRunLedger.SOURCE_NSE_FII_DERIVATIVE, Policy.REQUIRE_SUCCESS),
            new ExpectedSource(
                IngestRunLedger.SOURCE_BHAVCOPY, Policy.BHAVCOPY_BOTH_EXCHANGES_ADVANCED),
            new ExpectedSource(IngestRunLedger.SOURCE_INSTRUMENT_SYNC, Policy.REQUIRE_SUCCESS),
            new ExpectedSource(IngestRunLedger.SOURCE_MINERVINI_SCREEN, Policy.SCREENER),
            new ExpectedSource(IngestRunLedger.SOURCE_MANAS_SCREEN, Policy.SCREENER),
            new ExpectedSource(IngestRunLedger.SOURCE_OPTIONS_SNAPSHOT_CAPTURE, Policy.CAPTURE),
            new ExpectedSource(IngestRunLedger.SOURCE_EQUITY_BREADTH, Policy.MATERIALIZED_DAY));
  }

  /**
   * The THREE sources that write to {@code ingest_runs} and are deliberately NOT registered.
   *
   * <p>Named here rather than left implicit because the EQUITY_BREADTH gap was invisible precisely
   * because nothing wrote down that it was missing. Registering any of them is a separate decision:
   * each needs its own policy reasoning, and none has the measured miss that prompted EQUITY_BREADTH.
   *
   * <p>⚠️ This said "two" in the first cut, and review caught the third. {@code
   * OPTIONS_SNAPSHOT_PRUNE} declares its source as a LOCAL constant ({@code
   * OptionsSnapshotPruneJob:57}) rather than an {@link IngestRunLedger} one, so a sweep of that
   * class's constants — which is how the first list was built — cannot see it. A file whose stated
   * purpose is "find out from the file, not from an outage" must not itself be the incomplete
   * record, so the literal is spelled out here.
   */
  @Test
  @DisplayName("the deliberately-unregistered writers are still deliberately unregistered")
  void theKnownUnregisteredSourcesAreRecordedRatherThanForgotten() {
    List<String> registered =
        IngestCoverageCanary.EXPECTED.stream().map(ExpectedSource::source).toList();
    assertThat(registered)
        .doesNotContain(
            IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY,
            IngestRunLedger.SOURCE_DATA_QUALITY,
            "OPTIONS_SNAPSHOT_PRUNE");
  }
}
