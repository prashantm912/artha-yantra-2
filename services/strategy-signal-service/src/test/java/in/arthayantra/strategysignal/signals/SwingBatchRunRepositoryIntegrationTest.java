package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pins the V025 {@code swing_batch_runs} marker upsert against the real schema (audit P0-4 review):
 * the marker table's only consumer is a SAFETY mechanism — a silent SQL/schema defect here
 * disarms the did-not-run canary permanently (record() fails soft, lastRunDate stays empty, the
 * never-recorded guard skips the batch forever). So the round-trip + re-stamp are worth pinning.
 * Since V034 (ledger F3) the same row carries the admission probe — {@link #recentProbesRoundTrips}
 * pins the JSONB dropped-by-cap column + the newest-first read.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class SwingBatchRunRepositoryIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private SwingBatchRunRepository repo;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void recordThenReadBackTheWatermarkAndReStampSameDate() {
    // Unique batch name so the singleton-DB, no-cleanup IT convention holds.
    String batch = "it-mv-" + java.util.UUID.randomUUID();
    LocalDate d1 = LocalDate.of(2026, 7, 3);
    LocalDate d2 = LocalDate.of(2026, 7, 6);

    repo.record(batch, d1, 4, 20, 3, 1, 0, 2, 5, 3, 2, true, List.of());
    assertThat(repo.lastRunDate(batch)).contains(d1);
    assertThat(repo.hasRun(batch, d1)).isTrue();
    assertThat(repo.hasRun(batch, d2)).isFalse();

    // A later date advances the watermark.
    repo.record(batch, d2, 4, 25, 5, 2, 1, 3, 7, 5, 2, true, List.of());
    assertThat(repo.lastRunDate(batch)).contains(d2);
    assertThat(repo.hasRun(batch, d2)).isTrue();

    // Re-stamping the SAME date is an upsert (no duplicate-key blow-up) that overwrites the counters.
    repo.record(batch, d2, 4, 30, 6, 4, 0, 0, 6, 6, 0, false, List.of());
    assertThat(repo.lastRunDate(batch)).contains(d2);
    Integer entries =
        jdbc.queryForObject(
            "SELECT entries FROM swing_batch_runs WHERE batch = ? AND run_date = ?",
            Integer.class, batch, java.sql.Date.valueOf(d2));
    assertThat(entries).isEqualTo(6);
    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM swing_batch_runs WHERE batch = ?", Integer.class, batch);
    assertThat(rows).isEqualTo(2); // two distinct dates, the re-stamp did NOT add a third row
  }

  @Test
  void recentProbesRoundTrips() {
    String batch = "it-probe-" + java.util.UUID.randomUUID();
    LocalDate d1 = LocalDate.of(2026, 7, 3);
    LocalDate d2 = LocalDate.of(2026, 7, 6);

    // d1: the cap did NOT bind (nobody dropped). d2: it bound, dropping two RS-ordered names.
    repo.record(batch, d1, 4, 20, 3, 1, 0, 2, 3, 3, 0, false, List.of());
    repo.record(
        batch, d2, 4, 25, 12, 2, 0, 4, 15, 12, 3, true,
        List.of(new DroppedCandidate("ZEEL", 13), new DroppedCandidate("IDEA", 14),
            new DroppedCandidate("PNB", 15)));

    List<SwingBatchRunRepository.ProbeRow> probes = repo.recentProbes(batch, 10);
    assertThat(probes).hasSize(2);
    // Newest first.
    SwingBatchRunRepository.ProbeRow latest = probes.get(0);
    assertThat(latest.runDate()).isEqualTo(d2);
    assertThat(latest.wouldEnter()).isEqualTo(15);
    assertThat(latest.admitted()).isEqualTo(12);
    assertThat(latest.capExceedance()).isEqualTo(3);
    assertThat(latest.capBound()).isTrue();
    assertThat(latest.droppedByCap())
        .extracting(DroppedCandidate::symbol)
        .containsExactly("ZEEL", "IDEA", "PNB");
    assertThat(latest.droppedByCap().get(2).admissionRank()).isEqualTo(15);
    // The non-binding day round-trips an empty JSONB array (not null).
    assertThat(probes.get(1).capBound()).isFalse();
    assertThat(probes.get(1).droppedByCap()).isEmpty();
  }

  @Test
  void lastRunDateIsEmptyForANeverRecordedBatch() {
    assertThat(repo.lastRunDate("it-never-" + java.util.UUID.randomUUID()))
        .isEqualTo(Optional.empty());
  }
}
