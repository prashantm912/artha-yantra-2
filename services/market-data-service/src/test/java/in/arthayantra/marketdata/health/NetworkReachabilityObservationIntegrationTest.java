package in.arthayantra.marketdata.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.arthayantra.marketdata.testsupport.MarketDataIntegrationTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * NEW-13 — the observation table's guarantees, proved against the real Timescale container and the
 * real {@code deploy/flyway} marketdata lineage (V061).
 *
 * <p>⚠ <b>This cannot be a unit test.</b> The guarantee that matters is a CHECK constraint, and a
 * mocked repository accepts anything. What is being pinned is that "only quorum-met passes are
 * stored" is a rule the DATABASE enforces rather than a convention the writer is trusted to keep —
 * so a future caller that starts recording below-quorum passes is refused rather than quietly
 * flooding the table with vendor noise.
 *
 * <p>⚠ <b>What this table deliberately does NOT have, since its absence is the design.</b> There is
 * no open/closed state, no episode key, no unique partial index and no close path. Five earlier
 * revisions had all of them, and six review rounds produced thirteen findings against that
 * machinery — ending in a Critical where a failed close plus a new outage merged two incidents and
 * the healthy gap between them into one authoritative row. A row here is a statement about one
 * instant that no later pass can falsify, so there is no cross-row invariant left to enforce.
 *
 * <p>The probe itself is disabled for every IT context on the shared base — it blocks on real
 * sockets to external origins, and an enabled cron would write this table from inside unrelated
 * tests.
 */
@SpringBootTest
class NetworkReachabilityObservationIntegrationTest extends MarketDataIntegrationTestBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NetworkReachabilityRepository repository;

  private static final Instant T0 = Instant.parse("2026-09-01T07:12:00Z");

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM network_reachability_observations");
  }

  @Test
  @DisplayName("the repository writes a row the real schema accepts")
  void recordsAgainstTheRealSchema() {
    assertThat(repository.record(T0, 5, 3, 3, "kite,telegram,ntfy")).isTrue();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT observed_at, probed_count, unreachable_count, quorum_count, failed_names"
                + " FROM network_reachability_observations");
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(((Timestamp) row.get("observed_at")).toInstant()).isEqualTo(T0);
    // ⚠ JDBC widens SMALLINT to Integer here, so the numbers are compared as ints rather than
    // shorts — an isEqualTo((short) 5) fails on TYPE while printing "expected 5 but was 5".
    assertThat(((Number) row.get("probed_count")).intValue()).isEqualTo(5);
    assertThat(((Number) row.get("unreachable_count")).intValue()).isEqualTo(3);
    assertThat(((Number) row.get("quorum_count")).intValue()).isEqualTo(3);
    assertThat(row.get("failed_names")).isEqualTo("kite,telegram,ntfy");
  }

  @Test
  @DisplayName("consecutive passes accumulate as SEPARATE rows — nothing merges them")
  void consecutivePassesAccumulateAsSeparateRows() {
    // The whole point of the shape. Three passes during one outage are three rows; grouping them
    // into an incident is a read-time decision, and no write can retroactively change what any of
    // them says.
    repository.record(T0, 5, 3, 3, "kite,telegram,ntfy");
    repository.record(T0.plusSeconds(300), 5, 4, 3, "kite,nse,telegram,ntfy");
    repository.record(T0.plusSeconds(600), 5, 3, 3, "kite,telegram,ntfy");

    assertThat(
            jdbc.queryForList(
                "SELECT observed_at FROM network_reachability_observations"
                    + " ORDER BY observed_at",
                Instant.class))
        .containsExactly(T0, T0.plusSeconds(300), T0.plusSeconds(600));
  }

  @Test
  @DisplayName("a BELOW-quorum row is refused by the database, not merely by the writer")
  void theDatabaseRefusesABelowQuorumObservation() {
    // ⚠ 2 unreachable under a quorum of 3 is the "one vendor is down" shape. The probe already
    // declines to record it, but that is one `if` away from being lost in a future edit — and the
    // cost of losing it is 288 rows a day whenever a single vendor starts refusing our probe,
    // burying the host outages this table exists to make findable.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO network_reachability_observations"
                        + " (observed_at, probed_count, unreachable_count, quorum_count,"
                        + " failed_names) VALUES (?, ?, ?, ?, ?)",
                    Timestamp.from(T0),
                    5,
                    2,
                    3,
                    "kite,telegram"))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM network_reachability_observations",
            Long.class))
        .isZero();
  }

  @Test
  @DisplayName("more unreachable than probed is refused — the counts must stay readable")
  void theDatabaseRefusesIncoherentCounts() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO network_reachability_observations"
                        + " (observed_at, probed_count, unreachable_count, quorum_count,"
                        + " failed_names) VALUES (?, ?, ?, ?, ?)",
                    Timestamp.from(T0),
                    3,
                    5,
                    3,
                    "a,b,c,d,e"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
