package in.arthayantra.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The boot reaper for {@code ingest_runs} rows stranded {@code RUNNING} by a dead process (H12).
 *
 * <p>⚠️ Shared singleton DB with NO per-method cleanup, and the reaper's own predicate is
 * table-wide by design — so every assertion here is scoped to a per-method UUID source. Counting
 * {@code status='FAILURE'} across the whole table would be an assertion about every other test's
 * rows, which is exactly how {@code OiBackfillIntegrationTest} came to fail only on CI.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.feed.autostart=false",
      "artha.instruments.bootstrap-sync=false"
    })
class IngestRunReaperIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired IngestRunReaper reaper;
  @Autowired JdbcTemplate jdbc;

  private String uniqueSource() {
    return "REAPER_IT_" + UUID.randomUUID();
  }

  private Long insertRunning(String source, OffsetDateTime startedAt) {
    return jdbc.queryForObject(
        "INSERT INTO ingest_runs (source, status, started_at) VALUES (?, 'RUNNING', ?) RETURNING id",
        Long.class,
        source,
        startedAt);
  }

  private String statusOf(long id) {
    return jdbc.queryForObject("SELECT status FROM ingest_runs WHERE id = ?", String.class, id);
  }

  @Test
  void aRowStrandedByAPreviousProcessIsClosedOut() {
    // The 2026-08-11 incident shape: a job killed 28 s in by a container recreate, its row left
    // RUNNING with a NULL finished_at, read as in-flight forever by any terminal-status check.
    OffsetDateTime boot = OffsetDateTime.now();
    long stranded = insertRunning(uniqueSource(), boot.minusMinutes(5));

    reaper.reap(boot);

    assertThat(statusOf(stranded))
        .as("a RUNNING row that predates this boot cannot belong to this process — it must be closed")
        .isEqualTo("FAILURE");
    String error =
        jdbc.queryForObject("SELECT error FROM ingest_runs WHERE id = ?", String.class, stranded);
    assertThat(error)
        .as("the reaped row must be identifiable, so duration readers can exclude it deterministically")
        .startsWith(IngestRunReaper.REAPED_MARKER);
    assertThat(
            jdbc.queryForObject(
                "SELECT finished_at IS NOT NULL FROM ingest_runs WHERE id = ?", Boolean.class, stranded))
        .as("a terminal row with a NULL finished_at still reads as in-flight to some consumers")
        .isTrue();
  }

  @Test
  void aRunStartedAfterBootIsLeftAlone() {
    // ⚠️ Without this the reaper could close EVERY RUNNING row and the test above would still pass —
    // and it would then kill live runs on every boot, which is far worse than the stall it repairs.
    // This is the assertion that makes "started before boot" mean something.
    OffsetDateTime boot = OffsetDateTime.now();
    long live = insertRunning(uniqueSource(), boot.plusSeconds(30));

    reaper.reap(boot);

    assertThat(statusOf(live))
        .as("a run this process started after boot is ours and still going, however long it takes")
        .isEqualTo("RUNNING");
  }

  @Test
  void aRunAlreadyTerminalIsNotRewritten() {
    // The reaper must not overwrite a real outcome — a SUCCESS row reaped into FAILURE would
    // manufacture an incident out of a clean run.
    String source = uniqueSource();
    OffsetDateTime boot = OffsetDateTime.now();
    Long done = insertRunning(source, boot.minusMinutes(10));
    jdbc.update(
        "UPDATE ingest_runs SET status='SUCCESS', rows_written=7, finished_at=now() WHERE id=?", done);

    reaper.reap(boot);

    assertThat(statusOf(done)).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT error FROM ingest_runs WHERE id = ?", String.class, done))
        .as("a completed run keeps its own record")
        .isNull();
  }

  @Test
  void aCleanBootReapsNothingAndSaysSo() {
    // The normal case, asserted rather than assumed: with nothing stranded the reaper is a no-op. A
    // reaper that always reports work done is indistinguishable from one silently reaping live rows.
    //
    // ⚠️ Driven by an ANCIENT boot time rather than by clearing the table first. The obvious version
    // — `UPDATE ingest_runs SET status='FAILURE' WHERE status='RUNNING'` — is a table-wide write on a
    // shared singleton DB, which would clobber a concurrent class's live row and convert this into a
    // WRITE-flake: harder to attribute than the read-flake it imitates, and capable of breaking a
    // test that was fine. No row can predate 2000, so this reaps nothing and touches nothing.
    assertThat(reaper.reap(OffsetDateTime.parse("2000-01-01T00:00:00Z")))
        .as("no ingest run predates 2000, so nothing may be reaped")
        .isZero();
  }
}
