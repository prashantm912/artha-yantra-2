package in.arthayantra.backtest.regime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.BacktestRunner;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.backtest.testsupport.BenchmarkSeeder;
import in.arthayantra.common.web.error.ApiException;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Phase-32 guard-6 end-to-end: a walk-forward fold run with the benchmark daily series seeded (full
 * warm-up depth) populates each fold's {@code regimeMix} (NOT null), per-regime OOS aggregates and
 * per-trade entry-day tags — all computed from persisted benchmark candles, never declared. Also
 * asserts the FAIL condition's flip side: with the benchmark warm-up ABSENT, the fold run refuses
 * (422 {@code DATA_GAP}) rather than silently attributing against partial coverage.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
class RegimeMixIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private BacktestRunner runner;
  @Autowired private JobRepository jobs;
  @Autowired private RunRepository runs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @Test
  void foldRunPopulatesRegimeMixAndPerRegimeOos() throws Exception {
    seedNiftyCandles();
    BenchmarkSeeder.seedDefault(jdbc, LocalDate.parse("2026-01-10"));
    stub(walkForwardConfig(2, 1, 1, 0)); // min_trades 0 so both folds persist

    UUID runId = runFold("2026-01-05", "2026-01-10");
    JsonNode foldMetrics = foldMetricsColumn(runId);
    assertThat(foldMetrics.isArray()).isTrue();
    assertThat(foldMetrics).isNotEmpty();

    JsonNode fold0 = foldMetrics.get(0);
    // regimeMix is POPULATED — an object with all four labels (the Phase-31 null seam is gone).
    JsonNode mix = fold0.path("regimeMix");
    assertThat(mix.isObject()).isTrue();
    assertThat(mix.isNull()).isFalse();
    assertThat(mix.has(RegimeLabel.UP_QUIET.name())).isTrue();
    assertThat(mix.has(RegimeLabel.UP_TURBULENT.name())).isTrue();
    assertThat(mix.has(RegimeLabel.DOWN_QUIET.name())).isTrue();
    assertThat(mix.has(RegimeLabel.DOWN_TURBULENT.name())).isTrue();
    // the OOS test window of fold 0 is one trading day -> the mix sums to exactly 1 labeled day.
    int sum = 0;
    for (RegimeLabel l : RegimeLabel.values()) {
      sum += mix.path(l.name()).asInt();
    }
    assertThat(sum).isEqualTo(1);

    // per-regime OOS aggregates + per-trade entry-day tags are present (shapes, not specific values).
    assertThat(fold0.has("regimeOos")).isTrue();
    assertThat(fold0.path("regimeOos").isObject()).isTrue();
    assertThat(fold0.has("tradeRegimes")).isTrue();
    assertThat(fold0.path("tradeRegimes").isArray()).isTrue();
    // every persisted trade tag carries a non-null regime (the OOS days are inside the seeded
    // benchmark coverage, so no entry day falls before warm-up).
    fold0
        .path("tradeRegimes")
        .forEach(t -> assertThat(t.path("regime").isNull()).as("entry-day tag resolved").isFalse());
  }

  @Test
  void deterministicRegimeMixAcrossRuns() throws Exception {
    seedNiftyCandles();
    BenchmarkSeeder.seedDefault(jdbc, LocalDate.parse("2026-01-10"));
    stub(walkForwardConfig(2, 1, 1, 0));

    JsonNode first = foldMetricsColumn(runFold("2026-01-05", "2026-01-10"));
    JsonNode second = foldMetricsColumn(runFold("2026-01-05", "2026-01-10"));
    // same fixtures -> identical regimeMix across runs (computed, deterministic).
    assertThat(second.get(0).path("regimeMix")).isEqualTo(first.get(0).path("regimeMix"));
  }

  @Test
  void foldRunWithoutBenchmarkWarmupRefusesWith422DataGap() throws Exception {
    seedNiftyCandles(); // primary 1m present, but NO benchmark 1d warm-up seeded
    jdbc.update(
        "DELETE FROM marketdata.candles WHERE tradingsymbol='NIFTY 50' AND \"interval\"='1d'");
    stub(walkForwardConfig(2, 1, 1, 0));

    Job job =
        jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            STRATEGY_ID,
            objectMapper
                .createObjectNode()
                .put("strategyId", STRATEGY_ID.toString())
                .put("from", "2026-01-05")
                .put("to", "2026-01-10")
                .put("foldContext", true),
            "corr-nobench");

    ApiException ex =
        org.junit.jupiter.api.Assertions.assertThrows(
            ApiException.class, () -> runner.run(job, pct -> {}, () -> false));
    assertThat(ex.httpStatus()).isEqualTo(422);
    assertThat(ex.code()).isEqualTo("DATA_GAP");
    assertThat(ex.getMessage()).contains("benchmark");
  }

  // ----- helpers -----

  private void stub(JsonNode config) {
    when(versions.resolve(any(), any()))
        .thenReturn(new ResolvedVersion(STRATEGY_ID, "1.0.0", "checksum-regime", config));
  }

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
            "corr-regime");
    runner.run(job, pct -> {}, () -> false);
    return runs.findRunIdByJobId(job.id()).orElseThrow();
  }

  private JsonNode foldMetricsColumn(UUID runId) throws Exception {
    String raw =
        jdbc.queryForObject("SELECT fold_metrics FROM backtest_runs WHERE id=?", String.class, runId);
    assertThat(raw).isNotNull();
    return objectMapper.readTree(raw);
  }

  private JsonNode walkForwardConfig(int trainDays, int testDays, int stepDays, int minTrades) {
    ObjectNode config = baseConfig();
    ObjectNode backtest = config.putObject("backtest");
    ObjectNode optimize = backtest.putObject("optimize");
    optimize.putObject("constraints").put("min_trades", minTrades);
    optimize.putObject("objective").put("metric", "sharpe").put("direction", "maximize");
    ObjectNode wf = optimize.putObject("walk_forward");
    wf.put("train_days", trainDays);
    wf.put("test_days", testDays);
    wf.put("step_days", stepDays);
    wf.put("anchored", false);
    return config;
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
