package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.provenance.DatasetProvenance;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DecisionTraceRepositoryIntegrationTest extends BacktestIntegrationTestBase {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void wire() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword()));
  }

  @Test
  void insertsReadsInOrderAndEnforcesOneReasonPerRunDay() {
    UUID runId = insertRun();
    DecisionTraceRepository repository = new DecisionTraceRepository(jdbc, MAPPER);
    List<DecisionTraceCollector.Trace> traces =
        List.of(
            new DecisionTraceCollector.Trace(
                LocalDate.parse("2026-01-06"),
                "position_open",
                3,
                null,
                OffsetDateTime.parse("2026-01-06T09:16:00+05:30"),
                null),
            new DecisionTraceCollector.Trace(
                LocalDate.parse("2026-01-05"),
                "entered",
                1,
                new BigDecimal("0.75"),
                OffsetDateTime.parse("2026-01-05T10:01:00+05:30"),
                MAPPER.createObjectNode().put("composite", "0.75")));

    repository.insertAll(runId, traces);

    assertThat(repository.findByRun(runId).orElseThrow())
        .extracting(DecisionTraceCollector.Trace::sessionDate, DecisionTraceCollector.Trace::reason)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-01-05"), "entered"),
            org.assertj.core.groups.Tuple.tuple(LocalDate.parse("2026-01-06"), "position_open"));
    assertThat(repository.findByRun(UUID.randomUUID())).isEmpty();
    assertThatThrownBy(() -> repository.insertAll(runId, List.of(traces.get(0))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private static UUID insertRun() {
    UUID jobId =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"))
            .insertQueued(
                JobKind.BACKTEST,
                null,
                UUID.randomUUID(),
                MAPPER.createObjectNode().put("strategyId", "decision-trace-it"),
                UUID.randomUUID().toString(),
                "owner")
            .id();
    ReplayResult result =
        new ReplayResult(
            List.of(), List.of(), List.of(), List.of(),
            new BigDecimal("100000"), new BigDecimal("100000"), 0L, 0L);
    Metrics metrics =
        new Metrics(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ZERO, 0, MAPPER.createObjectNode());
    return new RunRepository(jdbc, MAPPER, EngineIdentity.of(null, null))
        .insert(
            jobId,
            UUID.randomUUID(),
            "NSE",
            "NIFTY 50",
            "1m",
            OffsetDateTime.parse("2026-01-05T09:15:00+05:30"),
            OffsetDateTime.parse("2026-01-06T15:30:00+05:30"),
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
            DatasetProvenance.none(),
            "owner");
  }
}
