package in.arthayantra.backtest.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.BacktestRunner;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.backtest.testsupport.BenchmarkSeeder;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * §D.16 Phase-32A run analytics end-to-end: the benchmark buy-and-hold curve overlays the strategy
 * equity curve on an identical time base, the benchmark-relative block is marked present when the
 * benchmark daily series is covered, and the Monte Carlo endpoint computes-once-persists-serves with
 * a 422 on a trade-less run.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class BenchmarkMonteCarloIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private BacktestRunner runner;
  @Autowired private JobRepository jobs;
  @Autowired private RunRepository runs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void stubAndSeed() throws Exception {
    JsonNode config =
        StrategyDocuments.parse(
                Files.readString(
                    golden().resolve("strategies/ema-crossover.yaml"), StandardCharsets.UTF_8))
            .config();
    when(versions.resolve(any(), any()))
        .thenReturn(new ResolvedVersion(STRATEGY_ID, null, "1.0.0", "checksum-ema", config));
    seedNiftyCandles();
    BenchmarkSeeder.seedDefault(jdbc, LocalDate.of(2026, 1, 10)); // daily NIFTY 50 for the benchmark
  }

  @Test
  void benchmarkCurveOverlaysEquityCurveOnTheSameTimeBase() throws Exception {
    UUID runId = runStandardBacktest("corr-bench");

    JsonNode results =
        objectMapper.readTree(
            mockMvc
                .perform(get("/api/v1/backtests/" + runId + "/results"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    JsonNode equity = results.path("equityCurve");
    JsonNode benchmark = results.path("benchmarkCurve");
    assertThat(equity.isArray()).isTrue();
    assertThat(benchmark.isArray()).isTrue();
    assertThat(benchmark.size()).isEqualTo(equity.size());
    for (int i = 0; i < equity.size(); i++) {
      assertThat(benchmark.get(i).path("ts").asText()).isEqualTo(equity.get(i).path("ts").asText());
    }
    // benchmark covered -> present + relative metrics on the payload, never silently zero.
    assertThat(results.path("metrics").path("benchmarkCoverage").asText()).isEqualTo("present");
    assertThat(results.path("metrics").has("excessCagr")).isTrue();
  }

  @Test
  void monteCarloComputesOncePersistsAndServes() throws Exception {
    UUID runId = insertSyntheticRun(List.of("100.00", "-50.00", "200.00", "-30.00", "80.00"));

    String first =
        mockMvc
            .perform(get("/api/v1/backtests/" + runId + "/montecarlo?n=200&seed=42"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // persisted on first computation
    assertThat(jdbc.queryForObject(
                "SELECT montecarlo_summary IS NOT NULL FROM backtest_runs WHERE id=?",
                Boolean.class, runId))
        .isTrue();

    String second =
        mockMvc
            .perform(get("/api/v1/backtests/" + runId + "/montecarlo?n=200&seed=42"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // served from the persisted summary — semantically identical (jsonb reorders keys, so compare
    // parsed trees, not raw bytes).
    assertThat(objectMapper.readTree(second)).isEqualTo(objectMapper.readTree(first));
    assertThat(objectMapper.readTree(first).path("mcSeed").asLong()).isEqualTo(42L);
  }

  @Test
  void monteCarloOnEmptyRunReturns422() throws Exception {
    UUID runId = insertSyntheticRun(List.of());
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/montecarlo"))
        .andExpect(status().is(422))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("VALIDATION_FAILED"));
  }

  private UUID runStandardBacktest(String corr) {
    Job job =
        jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            STRATEGY_ID,
            objectMapper
                .createObjectNode()
                .put("strategyId", STRATEGY_ID.toString())
                .put("from", "2026-01-05")
                .put("to", "2026-01-10"),
            corr,
            "owner");
    runner.run(job, pct -> {}, () -> false);
    return runs.findRunIdByJobId(job.id()).orElseThrow();
  }

  /** Inserts a minimal completed run (job + run) plus the given closed-trade P&amp;Ls. */
  private UUID insertSyntheticRun(List<String> pnls) {
    Job job =
        jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            STRATEGY_ID,
            objectMapper.createObjectNode().put("strategyId", STRATEGY_ID.toString()),
            "corr-mc-" + UUID.randomUUID(),
            "owner");
    UUID runId =
        jdbc.queryForObject(
            """
            INSERT INTO backtest_runs (
              job_id, strategy_version_id, exchange, tradingsymbol, "interval", start_ts, end_ts,
              initial_equity, final_equity, seed, data_hash,
              total_return, sharpe, sortino, max_drawdown, win_rate, profit_factor, trade_count,
              metrics, equity_curve, drawdown_curve, engine_version, premium_source)
            VALUES (?, ?, 'NSE', 'NIFTY 50', '1d', '2026-01-01T00:00:00+05:30', '2026-02-01T00:00:00+05:30',
                    1000000.00, 1010000.00, 0, 'hash-mc',
                    0, 0, 0, 0, 0, 0, ?, '{}'::jsonb, '[]'::jsonb, '[]'::jsonb, 'strategy-engine/v1', 'NA')
            RETURNING id
            """,
            (rs, n) -> UUID.fromString(rs.getString("id")),
            job.id(),
            STRATEGY_ID,
            pnls.size());
    int seq = 0;
    for (String pnl : pnls) {
      jdbc.update(
          "INSERT INTO backtest_trades (run_id, seq, side, qty, entry_ts, entry_price, exit_ts, "
              + "exit_price, pnl, pnl_pct, bars_held, touch_basis) "
              + "VALUES (?, ?, 'BUY', 1, '2026-01-02T09:15:00+05:30', 100, "
              + "'2026-01-02T15:15:00+05:30', 110, ?, 0, 1, 'CLOSE_EVAL')",
          runId,
          ++seq,
          new java.math.BigDecimal(pnl));
    }
    return runId;
  }

  private void seedNiftyCandles() throws Exception {
    Long existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles WHERE tradingsymbol='NIFTY 50' AND \"interval\"='1m'",
            Long.class);
    if (existing != null && existing > 0) {
      return;
    }
    for (int day = 1; day <= 5; day++) {
      Path csv = golden().resolve("candles/NSE_NIFTY50_1m_day" + day + ".csv");
      List<EngineCandle> candles;
      try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
        candles = GoldenCandleCsv.parse(reader);
      }
      jdbc.batchUpdate(
          "INSERT INTO marketdata.candles "
              + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, source) "
              + "VALUES ('NSE','NIFTY 50','1m', ?, ?, ?, ?, ?, ?, 'MOCK')",
          candles,
          candles.size(),
          (ps, c) -> {
            ps.setObject(1, c.bucketStart());
            ps.setBigDecimal(2, c.open());
            ps.setBigDecimal(3, c.high());
            ps.setBigDecimal(4, c.low());
            ps.setBigDecimal(5, c.close());
            ps.setLong(6, c.volume());
          });
    }
  }

  private static Path golden() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("libs/strategy-engine/src/test/resources/golden");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("golden root not found");
  }
}
