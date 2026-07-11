package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The conditional-claim contract is the heart of D12. These ITs hit a real TimescaleDB through the
 * raw repository (no Spring context) so the SQL semantics — claim-once, re-queue, terminal guards —
 * are asserted directly.
 */
class JobRepositoryIntegrationTest extends BacktestIntegrationTestBase {

  private static JobRepository repo;
  private static JdbcTemplate jdbc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void wire() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword());
    jdbc = new JdbcTemplate(ds);
    // The lifecycle tests don't care about the identity; a fixed one keeps them deterministic.
    repo = new JobRepository(jdbc, MAPPER, EngineIdentity.of("shared-sha", "backtest-service:it"));
  }

  private Job newQueuedBacktest() {
    return repo.insertQueued(
        JobKind.BACKTEST,
        null,
        UUID.randomUUID(),
        MAPPER.createObjectNode().put("strategyId", "demo").put("from", "2026-01-05"),
        UUID.randomUUID().toString(),
        "owner");
  }

  @Test
  void insertedJobIsQueuedWithZeroProgress() {
    Job job = newQueuedBacktest();
    assertThat(job.id()).isNotNull();
    assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(job.progress()).isZero();
    assertThat(job.createdAt()).isNotNull();
    assertThat(job.request().path("strategyId").asText()).isEqualTo("demo");
  }

  @Test
  void claimSucceedsOnceThenLosesTheRace() {
    Job job = newQueuedBacktest();

    boolean first = repo.claim(job.id(), "worker-A");
    boolean second = repo.claim(job.id(), "worker-B");

    assertThat(first).as("first claim wins").isTrue();
    assertThat(second).as("second claim loses — already running").isFalse();
    Job reread = repo.find(job.id()).orElseThrow();
    assertThat(reread.status()).isEqualTo(JobStatus.RUNNING);
    assertThat(reread.workerId()).isEqualTo("worker-A");
    assertThat(reread.startedAt()).isNotNull();
  }

  @Test
  void progressOnlyAdvancesWhileRunning() {
    Job job = newQueuedBacktest();
    repo.updateProgress(job.id(), 40); // queued — ignored
    assertThat(repo.find(job.id()).orElseThrow().progress()).isZero();

    repo.claim(job.id(), "w");
    repo.updateProgress(job.id(), 40);
    assertThat(repo.find(job.id()).orElseThrow().progress()).isEqualTo(40);

    repo.updateProgress(job.id(), 250); // clamps to 100
    assertThat(repo.find(job.id()).orElseThrow().progress()).isEqualTo(100);
  }

  @Test
  void completeForcesProgressTo100AndIsTerminal() {
    Job job = newQueuedBacktest();
    repo.claim(job.id(), "w");
    repo.markCompleted(job.id());

    Job done = repo.find(job.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
    assertThat(done.progress()).isEqualTo(100);
    assertThat(done.finishedAt()).isNotNull();

    // a late claim cannot revive a terminal job
    assertThat(repo.claim(job.id(), "late")).isFalse();
  }

  @Test
  void requeueStaleRunningResetsOrphansToQueued() {
    Job job = newQueuedBacktest();
    repo.claim(job.id(), "dead-worker");

    int requeued = repo.requeueStaleRunning();

    assertThat(requeued).isGreaterThanOrEqualTo(1);
    Job reread = repo.find(job.id()).orElseThrow();
    assertThat(reread.status()).isEqualTo(JobStatus.QUEUED);
    assertThat(reread.workerId()).isNull();
    assertThat(reread.startedAt()).isNull();
    assertThat(repo.findQueuedIds()).contains(job.id());
  }

  @Test
  void cancelIfQueuedOnlyWorksBeforeClaim() {
    Job job = newQueuedBacktest();
    assertThat(repo.cancelIfQueued(job.id())).isTrue();
    assertThat(repo.find(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);

    Job running = newQueuedBacktest();
    repo.claim(running.id(), "w");
    assertThat(repo.cancelIfQueued(running.id())).as("running job cannot be queue-cancelled").isFalse();
  }

  // Audit P0-2 / R1: a queued job row carries the creating service's engine identity.
  @Test
  void insertStampsEngineIdentityWhenPresent() {
    JobRepository stamped =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("abc123def456", "backtest-service:9.9.9"));
    Job job =
        stamped.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "sha-present"),
            UUID.randomUUID().toString(),
            "owner");

    assertThat(engineColumn(job.id(), "engine_sha")).isEqualTo("abc123def456");
    assertThat(engineColumn(job.id(), "engine_image")).isEqualTo("backtest-service:9.9.9");
  }

  // Fail-soft: a jar built without git.properties (mock/test) leaves the columns NULL, not an error.
  @Test
  void insertLeavesEngineIdentityNullWhenAbsent() {
    JobRepository unstamped = new JobRepository(jdbc, MAPPER, EngineIdentity.of(null, null));
    Job job =
        unstamped.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "sha-absent"),
            UUID.randomUUID().toString(),
            "owner");

    assertThat(engineColumn(job.id(), "engine_sha")).isNull();
    assertThat(engineColumn(job.id(), "engine_image")).isNull();
  }

  // Audit T3 / EVO §13 row 4: a queued job row carries + maps back its submitting actor.
  @Test
  void insertStampsAndMapsTheCreatedByActor() {
    Job owner =
        repo.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "actor-owner"),
            UUID.randomUUID().toString(),
            "owner");
    assertThat(owner.createdBy()).isEqualTo("owner");
    assertThat(engineColumn(owner.id(), "created_by")).isEqualTo("owner");
    assertThat(repo.find(owner.id()).orElseThrow().createdBy()).isEqualTo("owner");

    // a machine actor (the optimizer's prefix vocabulary) round-trips just the same (null parent —
    // parent_job_id is a self-FK, so a synthetic id would violate it; the actor is what's under test)
    Job trial =
        repo.insertQueued(
            JobKind.TRIAL,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "actor-optimizer"),
            UUID.randomUUID().toString(),
            "optimizer:sweep-42");
    assertThat(engineColumn(trial.id(), "created_by")).isEqualTo("optimizer:sweep-42");
  }

  private static String engineColumn(UUID jobId, String column) {
    return jdbc.queryForObject("SELECT " + column + " FROM jobs WHERE id=?", String.class, jobId);
  }
}
