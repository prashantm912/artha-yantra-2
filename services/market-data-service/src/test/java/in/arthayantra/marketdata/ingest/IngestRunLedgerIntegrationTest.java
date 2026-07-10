package in.arthayantra.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A4 {@code ingest_runs} ledger IT against the real Timescale container with the real
 * {@code deploy/flyway} marketdata lineage (so V040 runs exactly as it will in prod): the table
 * exists post-Flyway, a successful run records a SUCCESS row with its row count, a throwing job
 * records a FAILURE row with the error AND rethrows, and the capture-session daily summary keeps one
 * accumulating row per IST day.
 *
 * <p>Shared singleton DB with NO per-method cleanup — each method uses a unique source (UUID) or
 * deletes its own synthetic capture day, so state left by a prior method or a surefire rerun cannot
 * leak in.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class IngestRunLedgerIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired IngestRunLedger ledger;
  @Autowired JdbcTemplate jdbc;

  @Test
  void tableExistsAfterFlyway() {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'marketdata' AND table_name = 'ingest_runs'",
            Integer.class);
    assertThat(n).isEqualTo(1);
  }

  @Test
  void aSuccessfulRunRecordsASuccessRowWithItsCount() {
    String source = "TEST_OK_" + UUID.randomUUID();

    Long id = ledger.start(source);
    assertThat(id).isNotNull();
    ledger.succeed(id, 42);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, rows_written, error, finished_at FROM ingest_runs WHERE id = ?", id);
    assertThat(row.get("status")).isEqualTo("SUCCESS");
    assertThat(((Number) row.get("rows_written")).longValue()).isEqualTo(42L);
    assertThat(row.get("error")).isNull();
    assertThat(row.get("finished_at")).isNotNull();
  }

  @Test
  void aThrowingJobRecordsAFailureRowWithTheErrorAndStillPropagates() {
    String source = "TEST_FAIL_" + UUID.randomUUID();

    // record() must mark FAILURE AND rethrow — the caller's exception handling is preserved.
    assertThatThrownBy(
            () ->
                ledger.record(
                    source,
                    () -> {
                      throw new IllegalStateException("boom-" + source);
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("boom-" + source);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, error, rows_written, finished_at FROM ingest_runs WHERE source = ?",
            source);
    assertThat(row.get("status")).isEqualTo("FAILURE");
    assertThat((String) row.get("error")).contains("boom-" + source);
    assertThat(row.get("rows_written")).isNull();
    assertThat(row.get("finished_at")).isNotNull();
  }

  @Test
  void recordStampsTheRowCountOnTheSuccessPath() {
    String source = "TEST_REC_" + UUID.randomUUID();

    ledger.record(source, () -> 7L);

    Map<String, Object> row =
        jdbc.queryForMap("SELECT status, rows_written FROM ingest_runs WHERE source = ?", source);
    assertThat(row.get("status")).isEqualTo("SUCCESS");
    assertThat(((Number) row.get("rows_written")).longValue()).isEqualTo(7L);
  }

  @Test
  void captureSessionKeepsOneAccumulatingRowPerDay() {
    // A synthetic far-future session day; delete first so a surefire rerun starts clean (the partial
    // unique index is global for the capture source, so the row would otherwise re-accumulate).
    OffsetDateTime day = OffsetDateTime.parse("2099-01-15T00:00:00+05:30");
    jdbc.update(
        "DELETE FROM ingest_runs WHERE source = 'OPTIONS_SNAPSHOT_CAPTURE' AND window_start = ?", day);

    ledger.recordCaptureSession(day, 100);
    ledger.recordCaptureSession(day, 50);

    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM ingest_runs "
                + "WHERE source = 'OPTIONS_SNAPSHOT_CAPTURE' AND window_start = ?",
            Integer.class,
            day);
    assertThat(count).isEqualTo(1); // one row per day, not one per pass

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT status, rows_written FROM ingest_runs "
                + "WHERE source = 'OPTIONS_SNAPSHOT_CAPTURE' AND window_start = ?",
            day);
    assertThat(row.get("status")).isEqualTo("SUCCESS");
    assertThat(((Number) row.get("rows_written")).longValue()).isEqualTo(150L); // 100 + 50
  }
}
