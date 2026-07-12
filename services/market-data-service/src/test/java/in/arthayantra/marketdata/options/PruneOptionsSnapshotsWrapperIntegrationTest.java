package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Direct IT for V010's manual prune wrapper as REPAIRED by V046, against the real Timescale
 * container + real {@code deploy/flyway} marketdata lineage. V010 declared
 * {@code marketdata.prune_options_snapshots} as a LANGUAGE-sql function returning the
 * {@code SETOF regclass} that {@code drop_chunks} yields; on return the executor re-resolves each
 * just-dropped chunk's regclass OID to a name, but the chunk is gone, so the documented manual call
 * errored {@code relation "_timescaledb_internal._hyper_N_M_chunk" does not exist} whenever anything
 * was eligible to drop (reproduced on Timescale 2.17.2-pg17). V046's plpgsql PERFORM variant
 * discards the result set, so the re-resolution never happens.
 *
 * <p>This test exercises the exact path V010 failed on: an ANCIENT ({@code 1999}) chunk that IS
 * eligible to drop, so a regression back to LANGUAGE-sql would throw here. Shared singleton DB, no
 * per-method cleanup: a unique synthetic underlying ({@link #UL}) isolates rows, and the 5-year
 * default cutoff (~2021) sits far below every other suite's 2024-2027 fixtures, so
 * {@code drop_chunks} (whole-chunk, time-scoped) touches only this test's ancient chunk.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class PruneOptionsSnapshotsWrapperIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String UL = "PRUNE_WRAPPER_TEST";
  // Deep past: its own 1-day chunk, well before the 5-year default cutoff and every other fixture.
  private static final OffsetDateTime ANCIENT =
      OffsetDateTime.of(1999, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  @Autowired JdbcTemplate jdbc;

  @Test
  void documentedManualCallDropsAnOldChunkWithoutTheRegclassError() {
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
    insertSnapshot(ANCIENT);
    insertSnapshot(recent);
    assertThat(rowCountAt(ANCIENT)).isEqualTo(1); // the eligible chunk exists before the prune

    // The ancient chunk is on drop_chunks' list at the 5-year default — so the call WILL drop and
    // WILL hit V010's regclass re-resolution path.
    List<String> preview =
        jdbc.queryForList(
            "SELECT c::text FROM public.show_chunks('marketdata.options_chain_snapshots',"
                + " older_than => INTERVAL '5 years') AS c",
            String.class);
    assertThat(preview).isNotEmpty();

    // The documented manual usage, verbatim: SELECT marketdata.prune_options_snapshots();
    // Pre-V046 this errored "relation ... does not exist"; post-V046 it returns an empty set.
    List<String> returned =
        jdbc.queryForList("SELECT marketdata.prune_options_snapshots()", String.class);

    assertThat(returned).isEmpty(); // plpgsql PERFORM variant returns nothing (no regclass echo)
    assertThat(rowCountAt(ANCIENT)).isZero(); // the old chunk was actually dropped
    assertThat(rowCountAt(recent)).isEqualTo(1); // recent data survives (whole-chunk, time-scoped)
  }

  @Test
  void callWithNothingOldEnoughDropsNothingCleanly() {
    // With only a recent row present and an explicit long horizon (nothing older than 100y), the
    // call drops nothing and must still return cleanly — the one case V010 also handled without
    // erroring, kept byte-identical by V046 (empty set).
    insertSnapshot(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

    assertThatCode(
            () -> {
              List<String> returned =
                  jdbc.queryForList(
                      "SELECT marketdata.prune_options_snapshots(INTERVAL '100 years')",
                      String.class);
              assertThat(returned).isEmpty();
            })
        .doesNotThrowAnyException();
  }

  private void insertSnapshot(OffsetDateTime ts) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts, underlying, expiry, strike, option_type, tradingsymbol)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        ts, UL, LocalDate.of(2027, 1, 28), new BigDecimal("20000.00"), "CE", "PRUNE-WRAP");
  }

  private Integer rowCountAt(OffsetDateTime ts) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM options_chain_snapshots WHERE underlying = ? AND ts = ?",
        Integer.class, UL, ts);
  }
}
