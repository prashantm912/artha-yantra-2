package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.provenance.DatasetProvenance;
import in.arthayantra.backtest.provenance.EvidencePolicy;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Audit P0-2 / R1: {@link RunRepository} stamps the engine CODE identity (git SHA + build image)
 * onto every {@code backtest_runs} row and echoes it through {@code /results}. Raw-JDBC, no Spring
 * context — the singleton IT DB with the real backtest lineage (incl. V008).
 */
class RunRepositoryIntegrationTest extends BacktestIntegrationTestBase {

  private static JdbcTemplate jdbc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void wire() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword());
    jdbc = new JdbcTemplate(ds);
  }

  /** A fresh queued job to hang the run off (backtest_runs.job_id is a FK). */
  private static UUID newJobId() {
    JobRepository jobs =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"));
    return jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "run-it"),
            UUID.randomUUID().toString(),
            "owner")
        .id();
  }

  private static UUID insertRun(EngineIdentity identity) {
    return insertRun(identity, "owner");
  }

  private static UUID insertRun(EngineIdentity identity, String createdBy) {
    return insertRun(identity, createdBy, DatasetProvenance.none());
  }

  private static UUID insertRun(
      EngineIdentity identity, String createdBy, DatasetProvenance datasetProvenance) {
    return insertRunForJob(identity, newJobId(), createdBy, datasetProvenance);
  }

  /** Inserts a run hanging off {@code jobId} (so a test can control the joined job request JSONB). */
  private static UUID insertRunForJob(EngineIdentity identity, UUID jobId, String createdBy) {
    return insertRunForJob(identity, jobId, createdBy, DatasetProvenance.none());
  }

  private static UUID insertRunForJob(
      EngineIdentity identity, UUID jobId, String createdBy, DatasetProvenance datasetProvenance) {
    RunRepository runs = new RunRepository(jdbc, MAPPER, identity);
    ReplayResult result =
        new ReplayResult(
            List.of(), List.of(), List.of(), List.of(),
            new BigDecimal("100000"), new BigDecimal("100000"), 0L, 0L);
    Metrics metrics =
        new Metrics(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, MAPPER.createObjectNode());
    return runs.insert(
        jobId,
        UUID.randomUUID(),
        "NSE",
        "NIFTY 50",
        "1m",
        OffsetDateTime.parse("2026-01-05T09:15:00+05:30"),
        OffsetDateTime.parse("2026-01-05T15:30:00+05:30"),
        result,
        metrics,
        null,
        7L,
        "test-hash",
        null,
        "strategy-engine/test",
        PremiumSource.NA,
        null,
        null,
        datasetProvenance,
        createdBy);
  }

  /** A queued job whose request JSONB carries the given universeAsOf (a funnel-chosen screen date). */
  private static UUID newJobIdWithUniverseAsOf(String asOf) {
    JobRepository jobs =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"));
    return jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            UUID.randomUUID(),
            MAPPER.createObjectNode().put("strategyId", "asof-it").put("universeAsOf", asOf),
            UUID.randomUUID().toString(),
            "owner")
        .id();
  }

  @Test
  void runRowCarriesEngineIdentityAndResultsEchoesIt() {
    UUID runId = insertRun(EngineIdentity.of("cafebabe1234", "backtest-service:9.9.9"));

    assertThat(jdbc.queryForObject("SELECT engine_sha FROM backtest_runs WHERE id=?", String.class, runId))
        .isEqualTo("cafebabe1234");
    assertThat(jdbc.queryForObject("SELECT engine_image FROM backtest_runs WHERE id=?", String.class, runId))
        .isEqualTo("backtest-service:9.9.9");

    Map<String, Object> results =
        new RunRepository(jdbc, MAPPER, EngineIdentity.of(null, null)).findResult(runId).orElseThrow();
    assertThat(results.get("engineSha")).isEqualTo("cafebabe1234");
    assertThat(results.get("engineImage")).isEqualTo("backtest-service:9.9.9");
  }

  // Audit T3 / EVO §13 row 4: a run row carries its actor (inherited from the job by the worker).
  @Test
  void runRowCarriesTheCreatedByActor() {
    UUID runId = insertRun(EngineIdentity.of(null, null), "optimizer:sweep-7");
    assertThat(jdbc.queryForObject("SELECT created_by FROM backtest_runs WHERE id=?", String.class, runId))
        .isEqualTo("optimizer:sweep-7");
  }

  // Audit P1-1 / R2: a run row carries the content-stable hash + the dataset epoch head + the family
  // evidence policy (V015); DatasetProvenance.none() persists all three NULL (byte-behavior for the
  // other tests, which use the default helper).
  @Test
  void runRowCarriesDatasetProvenance() {
    UUID runId =
        insertRun(
            EngineIdentity.of(null, null),
            "owner",
            new DatasetProvenance("content-abc123", 5L, EvidencePolicy.SIM_FIRST));
    assertThat(jdbc.queryForObject("SELECT content_hash FROM backtest_runs WHERE id=?", String.class, runId))
        .isEqualTo("content-abc123");
    assertThat(jdbc.queryForObject("SELECT dataset_epoch FROM backtest_runs WHERE id=?", Long.class, runId))
        .isEqualTo(5L);
    assertThat(jdbc.queryForObject("SELECT evidence_policy FROM backtest_runs WHERE id=?", String.class, runId))
        .isEqualTo("SIM_FIRST");
  }

  @Test
  void runRowIsNullSafeWhenIdentityAbsent() {
    UUID runId = insertRun(EngineIdentity.of(null, null));

    assertThat(jdbc.queryForObject("SELECT engine_sha FROM backtest_runs WHERE id=?", String.class, runId))
        .isNull();
    Map<String, Object> results =
        new RunRepository(jdbc, MAPPER, EngineIdentity.of(null, null)).findResult(runId).orElseThrow();
    assertThat(results.get("engineSha")).isNull();
    assertThat(results.get("engineImage")).isNull();
    // A non-funnel run's job request carries no universeAsOf → the results read surfaces NULL, never
    // a spurious value (task_03b9f52d / task_9062b5f1).
    assertThat(results.get("universeAsOf")).isNull();
  }

  // task_03b9f52d / task_9062b5f1: a funnel-mode submission stamps the chosen screen date into the job
  // request JSONB (JobsService); findResult joins the job and surfaces it as universeAsOf on the run
  // read (GET /api/v1/backtests/{runId}/results), making a weekend/holiday funnel run interpretable.
  @Test
  void resultsEchoesTheFunnelUniverseAsOfFromTheJobRequest() {
    UUID jobId = newJobIdWithUniverseAsOf("2026-07-10");
    UUID runId = insertRunForJob(EngineIdentity.of(null, null), jobId, "owner");

    Map<String, Object> results =
        new RunRepository(jdbc, MAPPER, EngineIdentity.of(null, null)).findResult(runId).orElseThrow();
    assertThat(results.get("universeAsOf")).isEqualTo("2026-07-10");
  }
}
