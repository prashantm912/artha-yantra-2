package in.arthayantra.backtest.replay.folds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end Phase-31 walk-forward folds against a real Timescale + Redis stack with the seeded
 * golden NIFTY 1m candles (5 trading days, 2026-01-05..09). A {@code foldContext} run with a
 * {@code walk_forward} config persists per-fold train + OOS metrics; {@code GET /folds} serves the
 * persisted shape; a {@code min_trades}-failing fold is excluded (and the exclusion observable as a
 * shorter/empty persisted array); a PLAIN run leaves the fold columns NULL and {@code /folds}
 * returns {@code []}.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class FoldsIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private in.arthayantra.backtest.replay.BacktestRunner runner;
  @Autowired private JobRepository jobs;
  @Autowired private RunRepository runs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /** Stubs the resolver with a walk_forward config and seeds the golden candles (once). */
  private void stub(JsonNode config) {
    when(versions.resolve(any(), any()))
        .thenReturn(new ResolvedVersion(STRATEGY_ID, "1.0.0", "checksum-wf", config));
  }

  @Test
  void walkForwardRunPersistsFoldsWithTrainAndOosMetrics() throws Exception {
    seedNiftyCandles();
    // min_trades = 0 -> every fold is valid regardless of how many trades the day produced, so the
    // persisted array carries both folds with BOTH metric sets (the core acceptance assertion).
    stub(walkForwardConfig(2, 1, 1, false, 0));
    UUID runId = runFold("2026-01-05", "2026-01-10");

    JsonNode foldMetrics = foldMetricsColumn(runId);
    assertThat(foldMetrics.isArray()).isTrue();
    assertThat(foldMetrics).hasSize(2); // 2 rolling folds fit in 5 trading days (train2/test1/step1)

    JsonNode fold0 = foldMetrics.get(0);
    assertThat(fold0.path("fold").asInt()).isZero();
    assertThat(fold0.path("train").path("from").asText()).startsWith("2026-01-05");
    assertThat(fold0.path("train").path("to").asText()).startsWith("2026-01-07");
    assertThat(fold0.path("test").path("from").asText()).startsWith("2026-01-07");
    assertThat(fold0.path("test").path("to").asText()).startsWith("2026-01-08");
    assertThat(fold0.path("trainMetrics").has("sharpe")).isTrue();
    assertThat(fold0.path("oosMetrics").has("sharpe")).isTrue();
    assertThat(fold0.has("regimeMix")).isTrue();
    assertThat(fold0.path("regimeMix").isNull()).isTrue(); // null until Phase 32

    // the across-fold columns are written for a fold run.
    assertThat(numeric(runId, "oos_fold_mean")).isNotNull();
    assertThat(numeric(runId, "oos_fold_std")).isNotNull();

    // guard 3/6: the min_trades exclusion count is surfaced (here 0 — both folds valid) on the
    // run's metrics JSONB and served via /results, so a consumer can tell "2 valid, 0 excluded".
    assertThat(metricsColumn(runId).path("foldsExcluded").asInt()).isZero();

    // GET /folds returns the SAME persisted array.
    String body =
        mockMvc
            .perform(get("/api/v1/backtests/" + runId + "/folds"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].fold").value(0))
            .andExpect(jsonPath("$[0].trainMetrics.sharpe").exists())
            .andExpect(jsonPath("$[0].oosMetrics.sharpe").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(body)).isEqualTo(foldMetrics);
  }

  @Test
  void underTradingFoldsAreExcludedFromThePersistedArray() throws Exception {
    seedNiftyCandles();
    // min_trades = 30: single-day OOS windows close far fewer than 30 trades, so EVERY fold is
    // excluded -> the persisted array is empty (the exclusion is not silently mixed in) and the
    // across-fold columns are NULL (no valid folds).
    stub(walkForwardConfig(2, 1, 1, false, 30));
    UUID runId = runFold("2026-01-05", "2026-01-10");

    JsonNode foldMetrics = foldMetricsColumn(runId);
    assertThat(foldMetrics.isArray()).isTrue();
    assertThat(foldMetrics).isEmpty(); // excluded folds never appear
    assertThat(numeric(runId, "oos_fold_mean")).isNull();
    assertThat(numeric(runId, "sharpe_degradation")).isNull();
    // the empty array alone cannot distinguish "0 folds built" from "2 folds, all excluded": the
    // foldsExcluded flag (here 2 — the same 2 rolling folds, all under min_trades) makes the
    // exclusion explicit and traceable, never a silent drop (guard 3/6).
    assertThat(metricsColumn(runId).path("foldsExcluded").asInt()).isEqualTo(2);

    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/folds"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void implicitSeventyThirtyFoldWhenNoWalkForward() throws Exception {
    seedNiftyCandles();
    // foldContext but NO walk_forward -> the implicit 70/30 split as a single fold (guard 1).
    stub(seventyThirtyConfig(0));
    UUID runId = runFold("2026-01-05", "2026-01-10");

    JsonNode foldMetrics = foldMetricsColumn(runId);
    assertThat(foldMetrics).hasSize(1);
    // 5 trading days, floor(5*0.7)=3 train days -> train[Jan05,Jan08), test[Jan08,Jan10).
    assertThat(foldMetrics.get(0).path("train").path("from").asText()).startsWith("2026-01-05");
    assertThat(foldMetrics.get(0).path("train").path("to").asText()).startsWith("2026-01-08");
    assertThat(foldMetrics.get(0).path("test").path("from").asText()).startsWith("2026-01-08");
    // test.to is pinned to the run's OWN requested `to` (Jan10) — never nextTradingDay(lastDay)
    // (Jan12), which would overshoot the requested window. Matches the walk-forward convention.
    assertThat(foldMetrics.get(0).path("test").path("to").asText()).startsWith("2026-01-10");
    assertThat(foldMetrics.get(0).path("trainMetrics").has("sharpe")).isTrue();
    assertThat(foldMetrics.get(0).path("oosMetrics").has("sharpe")).isTrue();
  }

  @Test
  void plainRunLeavesFoldColumnsNullAndFoldsEndpointReturnsEmpty() throws Exception {
    seedNiftyCandles();
    stub(walkForwardConfig(2, 1, 1, false, 0)); // config HAS walk_forward, but...
    // ...a PLAIN run (no foldContext) must stay full-window: fold columns NULL.
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
            "corr-plain");
    runner.run(job, pct -> {}, () -> false);
    UUID runId = runs.findRunIdByJobId(job.id()).orElseThrow();

    assertThat(numeric(runId, "sharpe_degradation")).isNull();
    assertThat(numeric(runId, "oos_fold_mean")).isNull();
    assertThat(numeric(runId, "oos_fold_std")).isNull();
    assertThat(jdbc.queryForObject(
            "SELECT fold_metrics FROM backtest_runs WHERE id=?", String.class, runId))
        .isNull();
    // a full-window run has no train/test structure, so the foldsExcluded flag is absent entirely
    // (not 0) — the key is written ONLY on the fold path.
    assertThat(metricsColumn(runId).has("foldsExcluded")).isFalse();

    // /folds on a fold-less run returns [] (NOT 404 — the run exists, it just had no walk_forward).
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/folds"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void foldsEndpointIs404ForUnknownRun() throws Exception {
    seedNiftyCandles();
    stub(walkForwardConfig(2, 1, 1, false, 0));
    mockMvc
        .perform(get("/api/v1/backtests/" + UUID.randomUUID() + "/folds"))
        .andExpect(status().isNotFound());
  }

  // ----- helpers -----

  private UUID runFold(String from, String to) {
    Job job =
        jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            STRATEGY_ID,
            objectMapper
                .createObjectNode()
                .put("strategyId", STRATEGY_ID.toString())
                .put("from", from)
                .put("to", to)
                .put("foldContext", true),
            "corr-fold");
    runner.run(job, pct -> {}, () -> false);
    return runs.findRunIdByJobId(job.id()).orElseThrow();
  }

  private JsonNode foldMetricsColumn(UUID runId) throws Exception {
    String raw =
        jdbc.queryForObject("SELECT fold_metrics FROM backtest_runs WHERE id=?", String.class, runId);
    assertThat(raw).isNotNull();
    return objectMapper.readTree(raw);
  }

  private JsonNode metricsColumn(UUID runId) throws Exception {
    String raw =
        jdbc.queryForObject("SELECT metrics FROM backtest_runs WHERE id=?", String.class, runId);
    assertThat(raw).isNotNull();
    return objectMapper.readTree(raw);
  }

  private java.math.BigDecimal numeric(UUID runId, String column) {
    return jdbc.queryForObject(
        "SELECT " + column + " FROM backtest_runs WHERE id=?", java.math.BigDecimal.class, runId);
  }

  private JsonNode walkForwardConfig(
      int trainDays, int testDays, int stepDays, boolean anchored, int minTrades) {
    ObjectNode config = baseConfig();
    ObjectNode optimize = optimizeNode(config, minTrades);
    ObjectNode wf = optimize.putObject("walk_forward");
    wf.put("train_days", trainDays);
    wf.put("test_days", testDays);
    wf.put("step_days", stepDays);
    wf.put("anchored", anchored);
    return config;
  }

  private JsonNode seventyThirtyConfig(int minTrades) {
    ObjectNode config = baseConfig();
    optimizeNode(config, minTrades); // NO walk_forward -> implicit 70/30
    return config;
  }

  /** Creates (or reuses) {@code backtest.optimize} with constraints + objective and returns it. */
  private ObjectNode optimizeNode(ObjectNode config, int minTrades) {
    ObjectNode backtest = config.putObject("backtest");
    ObjectNode optimize = backtest.putObject("optimize");
    optimize.putObject("constraints").put("min_trades", minTrades);
    optimize.putObject("objective").put("metric", "sharpe").put("direction", "maximize");
    return optimize;
  }

  private ObjectNode baseConfig() {
    try {
      JsonNode parsed =
          StrategyDocuments.parse(
                  Files.readString(
                      golden().resolve("strategies/ema-crossover.yaml"), StandardCharsets.UTF_8))
              .config();
      return parsed.deepCopy();
    } catch (Exception e) {
      throw new IllegalStateException("failed loading golden config", e);
    }
  }

  private void seedNiftyCandles() throws Exception {
    Long existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles WHERE tradingsymbol='NIFTY 50' "
                + "AND \"interval\"='1m'",
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
              + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, "
              + "source) VALUES ('NSE','NIFTY 50','1m', ?, ?, ?, ?, ?, ?, 'MOCK')",
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
