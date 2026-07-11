package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
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
        newJobId(),
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
        createdBy);
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

  @Test
  void runRowIsNullSafeWhenIdentityAbsent() {
    UUID runId = insertRun(EngineIdentity.of(null, null));

    assertThat(jdbc.queryForObject("SELECT engine_sha FROM backtest_runs WHERE id=?", String.class, runId))
        .isNull();
    Map<String, Object> results =
        new RunRepository(jdbc, MAPPER, EngineIdentity.of(null, null)).findResult(runId).orElseThrow();
    assertThat(results.get("engineSha")).isNull();
    assertThat(results.get("engineImage")).isNull();
  }
}
