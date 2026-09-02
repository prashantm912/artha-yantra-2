package in.arthayantra.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NEW-13 — the ONE-OPEN-EPISODE rule, proved against the real Timescale container and the real
 * {@code deploy/flyway} marketdata lineage (V061).
 *
 * <p>⚠ <b>This cannot be a unit test, and that is the point.</b> The rule is enforced by a UNIQUE
 * PARTIAL INDEX; a mocked repository would happily accept two overlapping open rows and report
 * green. An earlier revision believed {@code UNIQUE (episode_key)} delivered this guarantee — it
 * does not, because each attempt mints a key from its own start millisecond, so the second insert
 * carries a different key and sails past. Only a real database can tell those two apart.
 *
 * <p>Why the rule is load-bearing: the writer asks "is an episode already open?" and that read FAILS
 * SOFT to "no" on a transient DB error. The failure mode this table exists to record is therefore
 * the very one that makes the process try to open a second episode. Two overlapping open rows would
 * each look authoritative, and the recorded history of an outage would be unusable.
 *
 * <p>The probe itself is disabled here: it blocks on real sockets, and this test is about the
 * schema, not the network.
 */
@SpringBootTest(properties = "artha.health.reachability.enabled=false")
class NetworkReachabilityEpisodeIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NetworkReachabilityRepository repository;

  private static final Instant T0 = Instant.parse("2026-09-01T07:12:00Z");

  /**
   * The one-open rule is GLOBAL to the table, so per-method key scoping cannot isolate these — the
   * table is truncated instead. Nothing else in the suite writes it (the probe is disabled above).
   */
  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM network_reachability_episodes");
  }

  @Test
  void theDatabaseRefusesASecondOPENEpisodeEvenUnderADifferentKey() {
    insertOpen("reach-1", T0);

    // ⚠ A DIFFERENT key — exactly what the probe generates on a second pass, and exactly what
    // UNIQUE (episode_key) does NOT stop.
    assertThatThrownBy(() -> insertOpen("reach-2", T0.plusSeconds(300)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(openRowCount()).isEqualTo(1);
  }

  @Test
  void closingAnEpisodeReleasesTheSlot() {
    insertOpen("reach-1", T0);
    jdbc.update(
        "UPDATE network_reachability_episodes SET ended_at = ? WHERE episode_key = ?",
        Timestamp.from(T0.plusSeconds(600)),
        "reach-1");

    insertOpen("reach-2", T0.plusSeconds(900));

    assertThat(openRowCount()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM network_reachability_episodes", Long.class))
        .isEqualTo(2L);
  }

  @Test
  void theRepositoryDECLINESASecondOpenRatherThanThrowingIntoTheScheduledPass() {
    // The index is the backstop; the repository's WHERE NOT EXISTS is what keeps the normal path
    // quiet. A recorder that throws into its caller can take out the pass it rides on.
    repository.open("reach-1", T0, 5, 3, 3, "kite,telegram,ntfy", "quorum 3/5 unreachable");
    repository.open("reach-2", T0.plusSeconds(300), 5, 4, 3, "kite,telegram,ntfy,nse", "later");

    assertThat(openRowCount()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT episode_key FROM network_reachability_episodes WHERE ended_at IS NULL",
                String.class))
        .isEqualTo("reach-1");
  }

  @Test
  void aRetryOfTheSAMEOpenIsIdempotent() {
    // The probe retains the observed start, so a retry re-derives the SAME key. ON CONFLICT makes
    // that a no-op rather than an error the caller would have to distinguish from a real failure.
    repository.open("reach-1", T0, 5, 3, 3, "kite,telegram,ntfy", "quorum 3/5 unreachable");
    repository.open("reach-1", T0, 5, 3, 3, "kite,telegram,ntfy", "quorum 3/5 unreachable");

    assertThat(jdbc.queryForObject("SELECT count(*) FROM network_reachability_episodes", Long.class))
        .isEqualTo(1L);
  }

  @Test
  void closeReportsSuccessSoAFailedWriteCanBeRetried() {
    repository.open("reach-1", T0, 5, 3, 3, "kite,telegram,ntfy", "quorum 3/5 unreachable");

    assertThat(repository.close("reach-1", T0.plusSeconds(600))).isTrue();
    assertThat(repository.openEpisodeKey()).isEmpty();
    // Already closed is still SUCCESS — the desired state holds, so there is nothing to retry.
    assertThat(repository.close("reach-1", T0.plusSeconds(900))).isTrue();
  }

  @Test
  void theStoredThresholdIsConstrainedAgainstTheObservedCounts() {
    // quorum_count exists so a row can be re-judged after the configuration changes; a row claiming
    // fewer failures than its own threshold would be incoherent.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO network_reachability_episodes (episode_key, started_at,"
                        + " probed_count, unreachable_count, quorum_count, failed_names, detail)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    "reach-bad",
                    Timestamp.from(T0),
                    5,
                    2,
                    3,
                    "kite,ntfy",
                    "incoherent"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aBackwardClockCannotPermanENTLYPoisonAnEpisode() {
    // ⚠ The retained recovery instant is REPLAYED unchanged on every retry. If the host clock steps
    // backwards between an outage starting and its recovery being observed, an unclamped value
    // violates CHECK (ended_at >= started_at) — so the write fails, is retained, and is retried with
    // the same invalid value forever, leaving the row open and merging every later outage into it.
    // Clamping makes the worst case a zero-length episode instead of an unrecoverable one.
    repository.open("reach-1", T0, 5, 3, 3, "kite,telegram,ntfy", "quorum 3/5 unreachable");

    assertThat(repository.close("reach-1", T0.minusSeconds(600))).isTrue();

    assertThat(openRowCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT ended_at = started_at FROM network_reachability_episodes"
                    + " WHERE episode_key = ?",
                Boolean.class,
                "reach-1"))
        .isTrue();
  }

  private void insertOpen(String key, Instant startedAt) {
    jdbc.update(
        "INSERT INTO network_reachability_episodes (episode_key, started_at, probed_count,"
            + " unreachable_count, quorum_count, failed_names, detail)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        key,
        Timestamp.from(startedAt),
        5,
        3,
        3,
        "kite,telegram,ntfy",
        "quorum 3/5 unreachable");
  }

  private long openRowCount() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM network_reachability_episodes WHERE ended_at IS NULL", Long.class);
  }
}
