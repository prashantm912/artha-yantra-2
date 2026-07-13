package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.regime.RegimePreflight;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.strategyengine.golden.GoldenCandleCsv;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end Phase-30 replay against a real Timescale + Redis stack: seed the golden NIFTY candles
 * into {@code marketdata}, run a backtest through {@link BacktestRunner}, and assert the run + trades
 * persist and the results/trades endpoints serve them. Also pins the §D.6 coverage pre-flight (422
 * DATA_GAP) and the D10 read-only marketdata grant.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class BacktestReplayIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

  @MockitoBean private StrategyVersionClient versions;
  @MockitoBean private RegimePreflight regimePreflight;
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
        .thenReturn(new ResolvedVersion(STRATEGY_ID, STRATEGY_ID, "1.0.0", "checksum-ema", config));
    seedNiftyCandles();
  }

  @Test
  void replayPersistsRunAndTradesAndServesResults() throws Exception {
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
            "corr-e2e",
            "owner");

    runner.run(job, pct -> {}, () -> false);

    UUID runId = runs.findRunIdByJobId(job.id()).orElseThrow();
    String results =
        mockMvc
            .perform(get("/api/v1/backtests/" + runId + "/results"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dataHash").exists())
            .andExpect(jsonPath("$.metrics.tradeCount").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(results).path("metrics").has("sharpe")).isTrue();

    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/trades"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());

    // the reproducibility triple: a second run over the same data hashes identically
    Job job2 =
        jobs.insertQueued(
            JobKind.BACKTEST, null, STRATEGY_ID,
            objectMapper.createObjectNode().put("strategyId", STRATEGY_ID.toString())
                .put("from", "2026-01-05").put("to", "2026-01-10"),
            "corr-e2e-2",
            "owner");
    runner.run(job2, pct -> {}, () -> false);
    UUID runId2 = runs.findRunIdByJobId(job2.id()).orElseThrow();
    assertThat(dataHash(runId2)).isEqualTo(dataHash(runId));
  }

  @Test
  void tracedReplayServesTypedDecisionDaysAndUnknownRunIs404() throws Exception {
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
                .put("traceDecisions", true),
            "corr-decision-traces",
            "owner");

    runner.run(job, pct -> {}, () -> false);

    UUID runId = runs.findRunIdByJobId(job.id()).orElseThrow();
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/decision-traces"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].sessionDate").exists())
        .andExpect(jsonPath("$.items[0].reason").exists())
        .andExpect(jsonPath("$.items[0].bars").isNumber())
        .andExpect(jsonPath("$.items[0].sampleBucket").exists());

    mockMvc
        .perform(get("/api/v1/backtests/" + UUID.randomUUID() + "/decision-traces"))
        .andExpect(status().isNotFound());
  }

  @Test
  void submissionPinsTraceDecisionsOnlyWhenEnabled() throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"strategyId\":\""
                            + STRATEGY_ID
                            + "\",\"from\":\"2026-01-05\",\"to\":\"2026-01-10\","
                            + "\"traceDecisions\":true}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID jobId = UUID.fromString(objectMapper.readTree(response).path("jobId").asText());
    assertThat(jobs.find(jobId).orElseThrow().request().path("traceDecisions").asBoolean()).isTrue();
  }

  @Test
  void tradesCarrySymbolAndLevelsAndHonorFiltersAndSummary() throws Exception {
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
            "corr-symbol",
            "owner");
    runner.run(job, pct -> {}, () -> false);
    UUID runId = runs.findRunIdByJobId(job.id()).orElseThrow();

    // every trade row denormalizes the run's instrument (and exposes the SL/TP level keys)
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/trades"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value("NIFTY 50"))
        .andExpect(jsonPath("$.items[0].exchange").value("NSE"))
        .andExpect(jsonPath("$.items[0].seq").exists());

    // symbol filter: the run's symbol returns rows, a foreign symbol returns none
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/trades").param("symbol", "NIFTY 50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value("NIFTY 50"));
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/trades").param("symbol", "NOSUCH"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());

    // from/to entry-window filter: a window past every trade returns none
    mockMvc
        .perform(
            get("/api/v1/backtests/" + runId + "/trades").param("from", "2030-01-01T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());

    // the latest-backtest summary join surface: one item for the run's strategy version
    String summary =
        mockMvc
            .perform(
                get("/api/v1/backtests/summary").param("strategyVersionIds", STRATEGY_ID.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].strategyVersionId").value(STRATEGY_ID.toString()))
            .andExpect(jsonPath("$.items[0].sharpe").exists())
            .andExpect(jsonPath("$.items[0].equity").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(summary).path("items").get(0).path("runId").asText())
        .isEqualTo(runId.toString());

    // an unknown version id yields an empty summary list (no run)
    mockMvc
        .perform(
            get("/api/v1/backtests/summary")
                .param("strategyVersionIds", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty());
  }

  @Test
  void everyRunCarriesANonNullPremiumSource() throws Exception {
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
            "corr-premium",
            "owner");
    runner.run(job, pct -> {}, () -> false);
    UUID runId = runs.findRunIdByJobId(job.id()).orElseThrow();

    // §D.15: provenance is written before results persist and is NEVER null.
    String premiumSource =
        jdbc.queryForObject(
            "SELECT premium_source FROM backtest_runs WHERE id=?", String.class, runId);
    assertThat(premiumSource).isNotNull();
    // a non-options (ema-crossover) strategy on the candle path is NA.
    assertThat(premiumSource).isEqualTo("NA");

    // and the results endpoint echoes it (with the caveats array) at the top level.
    String results =
        mockMvc
            .perform(get("/api/v1/backtests/" + runId + "/results"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.premiumSource").value("NA"))
            .andExpect(jsonPath("$.caveats").isArray())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(results).path("caveats").isArray()).isTrue();

    // the global no-NULL invariant across the table.
    Long nullSources =
        jdbc.queryForObject(
            "SELECT count(*) FROM backtest_runs WHERE premium_source IS NULL", Long.class);
    assertThat(nullSources).isZero();
  }

  @Test
  void coverageGapSubmissionIs422DataGap() throws Exception {
    // a window with no seeded candles → DATA_GAP at submission
    mockMvc
        .perform(
            post("/api/v1/backtests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"strategyId\":\""
                        + STRATEGY_ID
                        + "\",\"from\":\"2026-02-02\",\"to\":\"2026-02-06\"}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("DATA_GAP"))
        .andExpect(jsonPath("$.details.missingBars").exists());
  }

  @Test
  void backtestRoleCanReadButNotWriteMarketdata() throws Exception {
    try (Connection conn = DriverManager.getConnection(jdbcUrl(), dbUser(), dbPassword());
        Statement st = conn.createStatement()) {
      st.execute("SET ROLE ay_backtest");
      // SELECT is granted (CD-1)
      st.executeQuery("SELECT count(*) FROM marketdata.candles").close();
      // INSERT is NOT — single-writer rule (D10)
      assertThatThrownBy(
              () ->
                  st.executeUpdate(
                      "INSERT INTO marketdata.candles "
                          + "(exchange, tradingsymbol, \"interval\", bucket, open, high, low, close, volume, source) "
                          + "VALUES ('NSE','X','1m', now(), 1,1,1,1,0,'MOCK')"))
          .hasMessageContaining("permission denied");
    }
  }

  private String dataHash(UUID runId) {
    return jdbc.queryForObject(
        "SELECT data_hash FROM backtest_runs WHERE id=?", String.class, runId);
  }

  private void seedNiftyCandles() throws Exception {
    Long existing =
        jdbc.queryForObject(
            "SELECT count(*) FROM marketdata.candles WHERE tradingsymbol='NIFTY 50' AND \"interval\"='1m'",
            Long.class);
    if (existing != null && existing > 0) {
      return; // singleton DB — seed once across the IT class
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
