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
 * IngestHealthBoard} derive their entire source list from that constant. Measured over the 21
 * weekdays 2026-07-21..2026-08-18: 20 SUCCESS, and 2026-08-12 with no run at all on a full trading
 * day. Nothing in the repo would have failed if the source had been dropped again, which is the
 * shape this file closes.
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
            new ExpectedSource(IngestRunLedger.SOURCE_EQUITY_BREADTH, Policy.REQUIRE_SUCCESS));
  }

  /**
   * The two sources that write to {@code ingest_runs} and are deliberately NOT registered yet.
   *
   * <p>Named here rather than left implicit because the EQUITY_BREADTH gap was invisible precisely
   * because nothing wrote down that it was missing. Registering these is a separate decision — both
   * would need their own policy reasoning, and neither has the measured miss that justified
   * EQUITY_BREADTH — but the next person to read this file should find out from the file, not from
   * an outage.
   */
  @Test
  @DisplayName("the deliberately-unregistered writers are still deliberately unregistered")
  void theKnownUnregisteredSourcesAreRecordedRatherThanForgotten() {
    List<String> registered =
        IngestCoverageCanary.EXPECTED.stream().map(ExpectedSource::source).toList();
    assertThat(registered)
        .doesNotContain(
            IngestRunLedger.SOURCE_MARKET_CONTEXT_DAY, IngestRunLedger.SOURCE_DATA_QUALITY);
  }
}
