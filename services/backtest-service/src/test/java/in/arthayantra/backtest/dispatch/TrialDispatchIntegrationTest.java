package in.arthayantra.backtest.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.BacktestRunner;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * §D.7 trial round-trip: a completed TRIAL replay emits its metrics onto {@code
 * optimizations.results} — the back-half the optimizer's ask/tell loop consumes (tagged with the
 * sweep parent + the full metric catalog). Closes the Phase-33 backtest-service half of the sweep.
 *
 * <p>The worker pool is disabled here and the runner driven directly so the assertion is
 * deterministic — with the shared singleton Redis, an ambient worker from another test context
 * could otherwise claim the trial first. The dual-stream consumption itself ({@code
 * WorkerPool.SOURCES} includes {@code jobs.backtest.trials}) is plain wiring exercised on the live
 * mock stack.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
class TrialDispatchIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private JobRepository jobs;
  @Autowired private BacktestRunner runner;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private StringRedisTemplate redis;
  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void stubAndSeed() throws Exception {
    JsonNode config =
        StrategyDocuments.parse(
                Files.readString(
                    golden().resolve("strategies/ema-crossover.yaml"), StandardCharsets.UTF_8))
            .config();
    when(versions.resolve(any(), any()))
        .thenReturn(new ResolvedVersion(STRATEGY_ID, "1.0.0", "checksum-ema", config));
    seedNiftyCandles();
  }

  @Test
  void trialOnTheTrialsStreamRunsAndEmitsResult() {
    Job sweep =
        jobs.insertQueued(
            JobKind.OPTIMIZATION, null, STRATEGY_ID,
            objectMapper.createObjectNode().put("strategyId", STRATEGY_ID.toString()),
            "corr-sweep");
    Job trial =
        jobs.insertQueued(
            JobKind.TRIAL,
            sweep.id(),
            STRATEGY_ID,
            objectMapper
                .createObjectNode()
                .put("strategyId", STRATEGY_ID.toString())
                .put("from", "2026-01-05")
                .put("to", "2026-01-10"),
            "corr-trial");

    runner.run(trial, pct -> {}, () -> false);

    // optimizations.results carries this trial's metrics, tagged with its sweep parent.
    List<MapRecord<String, Object, Object>> results =
        redis
            .opsForStream()
            .read(StreamOffset.create(Streams.OPT_RESULTS, ReadOffset.from("0")));
    assertThat(results).isNotEmpty();
    MapRecord<String, Object, Object> mine =
        results.stream()
            .filter(r -> trial.id().toString().equals(r.getValue().get("trialId")))
            .findFirst()
            .orElseThrow();
    assertThat(mine.getValue().get("sweepId")).isEqualTo(sweep.id().toString());
    assertThat(mine.getValue().get("runId")).isNotNull();
    JsonNode metrics = readJson(mine.getValue().get("metrics"));
    assertThat(metrics.has("sharpe")).isTrue();
  }

  private JsonNode readJson(Object raw) {
    try {
      return objectMapper.readTree(raw.toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
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
