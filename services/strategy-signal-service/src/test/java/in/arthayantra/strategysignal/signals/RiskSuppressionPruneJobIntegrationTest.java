package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PF-03 retention prune (task_f168c23c): a pass deletes rows past the horizon and spares recent rows.
 * Shared singleton DB — every seeded row uses a unique slug and the assertions key off explicit row
 * ids, so the prune's blast radius is bounded to this test's own rows (no other IT seeds an aged
 * {@code generated_at}). Runs the package-visible {@code prune()} directly to bypass the live gate.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class RiskSuppressionPruneJobIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private RiskSuppressionPruneJob pruneJob;
  @Autowired private RiskSuppressionRepository repository;
  @Autowired private ApplicationEventPublisher events;
  @Autowired private Environment environment;
  @Autowired private JdbcTemplate jdbc;

  private UUID anyVersionId() {
    return jdbc.queryForObject("SELECT id FROM strategy_versions LIMIT 1", UUID.class);
  }

  /** Seeds a row at a fixed age (days back from now()) and returns its id. */
  private long seedRow(String slugSuffix, int ageDays) {
    Long id =
        jdbc.queryForObject(
            "INSERT INTO risk_suppressions"
                + " (strategy_version_id, strategy_slug, book, rail, exchange, tradingsymbol,"
                + " \"interval\", bar_time, generated_at)"
                + " VALUES (?, ?, 'scalper', 'kill_switch', 'NFO', 'NIFTY26JULFUT', '3m',"
                + " now() - make_interval(days => ?), now() - make_interval(days => ?)) RETURNING id",
            Long.class,
            anyVersionId(),
            "prune-" + slugSuffix + "-" + UUID.randomUUID(),
            ageDays,
            ageDays);
    return id == null ? -1 : id;
  }

  private boolean rowExists(long id) {
    Long count =
        jdbc.queryForObject("SELECT count(*) FROM risk_suppressions WHERE id = ?", Long.class, id);
    return count != null && count > 0;
  }

  @Test
  void pruneDeletesRowsPastHorizonAndSparesRecentRows() {
    long oldId = seedRow("old", 200); // > 180d horizon
    long recentId = seedRow("recent", 3); // well inside the horizon
    assertThat(rowExists(oldId)).isTrue();
    assertThat(rowExists(recentId)).isTrue();

    int deleted = pruneJob.prune();

    assertThat(deleted).isGreaterThanOrEqualTo(1);
    assertThat(rowExists(oldId)).as("aged row pruned").isFalse();
    assertThat(rowExists(recentId)).as("recent row retained").isTrue();
  }

  @Test
  void nonPositiveHorizonIsGuardedAndDeletesNothing() {
    long ancientId = seedRow("ancient", 500);
    assertThat(rowExists(ancientId)).isTrue();

    // A 0-day horizon would otherwise delete every row (cutoff == now()); the guard must skip.
    RiskSuppressionPruneJob zeroRetention =
        new RiskSuppressionPruneJob(repository, events, environment, 0);
    int deleted = zeroRetention.prune();

    assertThat(deleted).isZero();
    assertThat(rowExists(ancientId)).as("guard prevented the wipe").isTrue();

    // Clean up the ancient seed so it can't confound a later prune assertion in the shared DB.
    repository.deleteOlderThanDays(180);
  }
}
