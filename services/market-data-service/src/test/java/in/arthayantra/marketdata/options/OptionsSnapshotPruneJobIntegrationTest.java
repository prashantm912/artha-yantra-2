package in.arthayantra.marketdata.options;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A10 retention IT against the real Timescale container + real {@code deploy/flyway} marketdata
 * lineage — so V006's {@code options_chain_snapshots} hypertable and V010's
 * {@code prune_options_snapshots} function exist exactly as in prod. Proves the SQL wiring end to
 * end: {@code show_chunks} previews the drop set, {@code prune_options_snapshots} drops whole chunks
 * older than the horizon (chunk-wise via {@code drop_chunks}, NEVER a row-wise DELETE), recent data
 * survives, and each pass records an {@code OPTIONS_SNAPSHOT_PRUNE} {@code ingest_runs} row.
 *
 * <p>Shared singleton DB, no per-method cleanup: rows use a synthetic underlying ({@link #UL}) with
 * per-run timestamps. {@code drop_chunks} is time-scoped (only chunks whose whole range clears the
 * cutoff), so recent test data across the suite is safe; the no-op test first drains any pre-existing
 * old chunks left by sibling classes so it can assert a genuine zero-drop.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false",
      "artha.market.snapshot-retention-days=365"
    })
class OptionsSnapshotPruneJobIntegrationTest extends MarketDataIntegrationTestBase {

  private static final String UL = "PRUNE_TEST";

  @Autowired OptionsSnapshotPruneJob job;
  @Autowired JdbcTemplate jdbc;

  @Test
  void recentOnlyDataIsANoOpThatRecordsZeroDropped() {
    // Drain any >365d chunks a sibling IT class may have inserted, so this asserts a genuine no-op
    // (surefire runs classes serially, so nothing re-adds old chunks before the assertion below).
    job.prune();
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusDays(2);
    insertSnapshot(recent, "RECENT-NOOP");

    List<String> dropped = job.prune();

    assertThat(dropped).isEmpty(); // nothing older than 365d remains
    assertThat(rowCountAt(recent)).isEqualTo(1); // recent sentinel untouched
    Map<String, Object> run = latestPruneRun();
    assertThat(run.get("status")).isEqualTo("SUCCESS");
    assertThat(((Number) run.get("rows_written")).longValue()).isZero();
  }

  @Test
  void aChunkOlderThanTheHorizonIsDroppedChunkWise() {
    OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(400); // > 365d horizon
    OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).minusDays(3);
    insertSnapshot(old, "OLD-DROP");
    insertSnapshot(recent, "RECENT-KEEP");

    // Preview (read-only): the old chunk is on the drop list.
    List<String> preview =
        jdbc.queryForList(
            "SELECT c::text FROM public.show_chunks('marketdata.options_chain_snapshots',"
                + " older_than => make_interval(days => 365)) AS c",
            String.class);
    assertThat(preview).isNotEmpty();

    List<String> dropped = job.prune();

    assertThat(dropped).isNotEmpty();
    assertThat(rowCountAt(old)).isZero(); // the old chunk was dropped
    assertThat(rowCountAt(recent)).isEqualTo(1); // recent data survives
    Map<String, Object> run = latestPruneRun();
    assertThat(run.get("status")).isEqualTo("SUCCESS");
    assertThat(((Number) run.get("rows_written")).longValue()).isGreaterThanOrEqualTo(1);
  }

  private void insertSnapshot(OffsetDateTime ts, String tag) {
    jdbc.update(
        "INSERT INTO options_chain_snapshots"
            + " (ts, underlying, expiry, strike, option_type, tradingsymbol)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        ts, UL, LocalDate.of(2027, 1, 28), new BigDecimal("20000.00"), "CE", tag);
  }

  private Integer rowCountAt(OffsetDateTime ts) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM options_chain_snapshots WHERE underlying = ? AND ts = ?",
        Integer.class, UL, ts);
  }

  private Map<String, Object> latestPruneRun() {
    return jdbc.queryForMap(
        "SELECT status, rows_written FROM ingest_runs WHERE source = 'OPTIONS_SNAPSHOT_PRUNE'"
            + " ORDER BY id DESC LIMIT 1");
  }
}
