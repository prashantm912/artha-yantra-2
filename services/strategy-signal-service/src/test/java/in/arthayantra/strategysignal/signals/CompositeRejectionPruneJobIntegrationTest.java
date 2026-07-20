package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V044 retention prune: a pass deletes rows past the horizon, spares recent rows, and a misconfigured
 * non-positive horizon is GUARDED rather than wiping the tuning history. Mirrors
 * {@link RiskSuppressionPruneJobIntegrationTest}. Shared singleton DB — every seeded row uses a
 * unique slug and the assertions key off explicit row ids. Runs the package-visible {@code prune()}
 * directly to bypass the live-profile gate.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
class CompositeRejectionPruneJobIntegrationTest extends StrategySignalIntegrationTestBase {

  @Autowired private CompositeRejectionPruneJob pruneJob;
  @Autowired private CompositeRejectionRepository repository;
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
            "INSERT INTO composite_rejections"
                + " (strategy_version_id, strategy_slug, exchange, tradingsymbol, \"interval\","
                + " composite, threshold, margin, score_breakdown, bar_time, generated_at)"
                + " VALUES (?, ?, 'NFO', 'NIFTY26JULFUT', '3m', 0.125, 0.2, -0.075, '{}'::jsonb,"
                + " now() - make_interval(days => ?), now() - make_interval(days => ?)) RETURNING id",
            Long.class,
            anyVersionId(),
            "cprune-" + slugSuffix + "-" + UUID.randomUUID(),
            ageDays,
            ageDays);
    return id == null ? -1 : id;
  }

  private boolean rowExists(long id) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM composite_rejections WHERE id = ?", Long.class, id);
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
  void nonPositiveRetentionIsAQuietNoOpThatNeitherDeletesNorAlerts() {
    long ancientId = seedRow("ancient", 500);
    assertThat(rowExists(ancientId)).isTrue();

    // application.yml documents 0/negative as DISABLING the prune. Two things must hold, and the
    // second is what a cross-vendor review caught: a 0-day horizon would otherwise make the cutoff
    // now() and delete EVERY row, so nothing may be deleted — AND the daily tick must stay SILENT.
    // An earlier cut logged ERROR + published an ops alert on every scheduled run, which would page
    // the owner once a day forever for a setting they deliberately chose. With ntfy delivery being
    // repaired, that false alert would land in a just-restored channel and teach them to ignore it.
    ApplicationEventPublisher spyEvents = mock(ApplicationEventPublisher.class);
    Logger jobLog = (Logger) LoggerFactory.getLogger(CompositeRejectionPruneJob.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    jobLog.addAppender(logs);
    try {
      CompositeRejectionPruneJob zeroRetention =
          new CompositeRejectionPruneJob(repository, spyEvents, environment, 0);
      logs.list.clear(); // drop the one-off construction INFO; only the TICK is under test here
      int deleted = zeroRetention.prune();

      assertThat(deleted).isZero();
      assertThat(rowExists(ancientId)).as("a disabled prune deletes nothing").isTrue();
      verifyNoInteractions(spyEvents); // no ops alert — disabling a prune is not a fault
      assertThat(logs.list)
          .as("the daily tick emits nothing at WARN or above when the prune is disabled")
          .noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    } finally {
      jobLog.detachAppender(logs);
      // Clean up the ancient seed so it can't confound a later prune assertion in the shared DB.
      repository.deleteOlderThanDays(180);
    }
  }

  @Test
  void theDisabledStateIsAnnouncedOnceAtStartupSoItStaysDiscoverable() {
    // Quiet must not mean invisible: "why is nothing being pruned?" has to be answerable from the
    // boot log. Announced ONCE on construction, at INFO — never on the recurring tick.
    Logger jobLog = (Logger) LoggerFactory.getLogger(CompositeRejectionPruneJob.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    jobLog.addAppender(logs);
    try {
      new CompositeRejectionPruneJob(repository, events, environment, 0);

      assertThat(logs.list)
          .anyMatch(
              e ->
                  e.getLevel() == Level.INFO
                      && e.getFormattedMessage().contains("prune DISABLED"));
    } finally {
      jobLog.detachAppender(logs);
    }
  }

  @Test
  void aPositiveHorizonStillAnnouncesNothingAtStartup() {
    Logger jobLog = (Logger) LoggerFactory.getLogger(CompositeRejectionPruneJob.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    jobLog.addAppender(logs);
    try {
      new CompositeRejectionPruneJob(repository, events, environment, 180);
      assertThat(logs.list).as("the normal case is silent at boot").isEmpty();
    } finally {
      jobLog.detachAppender(logs);
    }
  }
}
