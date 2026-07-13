package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.EngineIdentity;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.provenance.DatasetProvenance;
import in.arthayantra.backtest.replay.MetricsCalculator.Metrics;
import in.arthayantra.backtest.replay.options.PremiumSource;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.strategyengine.fills.Side;
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
 * chip task_cf5d58c8: {@link TradeRepository#insertAll} persists every replay path's
 * {@code exit_reason} in the canonical UPPERCASE vocabulary, so the shared {@code backtest_trades}
 * table (candle / options / deep-swing all write it) never mixes casing. Raw-JDBC against the
 * singleton IT DB with the real backtest lineage.
 */
class TradeRepositoryIntegrationTest extends BacktestIntegrationTestBase {

  private static JdbcTemplate jdbc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void wire() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl("backtest"), dbUser(), dbPassword()));
  }

  @Test
  void insertAllUpperCasesLowercaseCandleReasonsAndLeavesUppercaseUntouched() {
    UUID runId = insertRun();
    TradeRepository trades = new TradeRepository(jdbc, MAPPER);

    trades.insertAll(
        runId,
        List.of(
            trade(1, "stop_loss"), // lowercase candle path → must persist STOP_LOSS
            trade(2, "trailing_stop"),
            trade(3, "STOP_LOSS"), // already-UPPERCASE options/deep-swing path → unchanged
            trade(4, null))); // open-at-end, no reason → stays null

    List<Trade> loaded = trades.loadByRun(runId);
    assertThat(loaded).extracting(Trade::exitReason)
        .containsExactly("STOP_LOSS", "TRAILING_STOP", "STOP_LOSS", null);

    // the paged read path surfaces the same canonical casing
    List<Map<String, Object>> rows = trades.findByRun(runId, 100, 0, null, null, null);
    assertThat(rows).extracting(r -> r.get("exitReason"))
        .containsExactly("STOP_LOSS", "TRAILING_STOP", "STOP_LOSS", null);
  }

  private static Trade trade(int seq, String exitReason) {
    return new Trade(
        seq, Side.BUY, 50L,
        OffsetDateTime.parse("2026-01-05T09:15:00+05:30"), new BigDecimal("100.00"),
        OffsetDateTime.parse("2026-01-05T09:45:00+05:30"), new BigDecimal("101.00"),
        new BigDecimal("50.00"), new BigDecimal("1.00"),
        exitReason, 30, null, null, "NSE", "NIFTY 50", null, null);
  }

  /** A run row to hang the trades off (backtest_trades.run_id is a FK to backtest_runs.id). */
  private static UUID insertRun() {
    UUID jobId =
        new JobRepository(jdbc, MAPPER, EngineIdentity.of("job-sha", "backtest-service:it"))
            .insertQueued(
                JobKind.BACKTEST, null, UUID.randomUUID(),
                MAPPER.createObjectNode().put("strategyId", "trade-repo-it"),
                UUID.randomUUID().toString(), "owner")
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
            jobId, UUID.randomUUID(), "NSE", "NIFTY 50", "1m",
            OffsetDateTime.parse("2026-01-05T09:15:00+05:30"),
            OffsetDateTime.parse("2026-01-05T15:30:00+05:30"),
            result, metrics, null, 7L, "test-hash", null, "strategy-engine/test",
            PremiumSource.NA, null, null, DatasetProvenance.none(), "owner");
  }
}
