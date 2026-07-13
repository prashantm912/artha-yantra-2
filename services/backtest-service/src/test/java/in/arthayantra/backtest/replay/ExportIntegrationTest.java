package in.arthayantra.backtest.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** D4 P2-3: per-run CSV/JSON exports over the real backtest Flyway lineage. */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class ExportIntegrationTest extends BacktestIntegrationTestBase {

  private static final String CSV = "text/csv";
  private static final String JSON = "application/json";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JobRepository jobs;

  @Test
  void exportsTradesFoldsEquityAndCompareAsCsvAndJsonDownloads() throws Exception {
    Fixture fixture = insertFixture();

    MvcResult tradesCsv = csv(fixture, "trades");
    assertThat(body(tradesCsv)).startsWith(
        "seq,side,qty,entryTs,entryPrice,exitTs,exitPrice,pnl,pnlPct,exitReason,");
    assertThat(body(tradesCsv)).contains("1,BUY,50,2026-07-13T09:15:00+05:30,100.2500");
    assertThat(body(tradesCsv).split("\\r?\\n")).hasSize(3);
    assertThat(tradesCsv.getResponse().getHeader("X-Result-Rows")).isEqualTo("2");
    JsonNode tradesJson = json(fixture, "trades", null);
    assertThat(tradesJson.path("items")).hasSize(2);
    assertThat(tradesJson.path("items").get(0).path("entryTs").asText())
        .isEqualTo("2026-07-13T09:15:00+05:30");
    assertSameJson(
        tradesJson,
        getJson("/api/v1/backtests/" + fixture.runId() + "/trades?limit=1000&offset=0"));

    MvcResult foldsCsv = csv(fixture, "folds");
    assertThat(body(foldsCsv)).startsWith(
        "fold,train,test,trainMetrics,oosMetrics,regimeMix,regimeOos,tradeRegimes");
    assertThat(body(foldsCsv)).contains("0,");
    JsonNode foldsJson = json(fixture, "folds", null);
    assertThat(foldsJson).hasSize(1);
    assertThat(foldsJson.get(0).path("oosMetrics").path("sharpe").asText()).isEqualTo("0.80");
    assertSameJson(foldsJson, getJson("/api/v1/backtests/" + fixture.runId() + "/folds"));

    MvcResult equityCsv = csv(fixture, "equity");
    assertThat(body(equityCsv)).startsWith("ts,value\r\n");
    assertThat(body(equityCsv)).contains("2026-07-13T09:15:00+05:30,100000.00");
    JsonNode equityJson = json(fixture, "equity", null);
    assertThat(equityJson).hasSize(2);
    JsonNode results = getJson("/api/v1/backtests/" + fixture.runId() + "/results");
    assertSameJson(equityJson, results.path("equityCurve"));

    String compareParams = "strategyVersionIds=" + fixture.strategyVersionId();
    MvcResult compareCsv = csv(fixture, "compare", compareParams);
    assertThat(body(compareCsv)).startsWith(
        "strategyVersionId,runId,sharpe,totalReturn,maxDrawdown,completedAt,equity");
    assertThat(body(compareCsv)).contains(fixture.strategyVersionId().toString());
    JsonNode compareJson = json(fixture, "compare", compareParams);
    assertThat(compareJson.path("items")).hasSize(1);
    assertThat(compareJson.path("items").get(0).path("runId").asText())
        .isEqualTo(fixture.runId().toString());
    assertSameJson(compareJson, getJson("/api/v1/backtests/summary?" + compareParams));
  }

  @Test
  void defaultsToCsvRejectsUnknownFormatAndReturns404ForUnknownRun() throws Exception {
    Fixture fixture = insertFixture();

    mockMvc
        .perform(get(exportPath(fixture.runId(), "trades")))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(CSV));

    mockMvc
        .perform(get(exportPath(fixture.runId(), "trades")).param("format", "xml"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    UUID unknown = UUID.randomUUID();
    for (String artifact : new String[] {"trades", "folds", "equity", "compare"}) {
      mockMvc
          .perform(get(exportPath(unknown, artifact)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND_RESOURCE"));
    }
  }

  private MvcResult csv(Fixture fixture, String artifact) throws Exception {
    return csv(fixture, artifact, null);
  }

  private MvcResult csv(Fixture fixture, String artifact, String query) throws Exception {
    String path = exportPath(fixture.runId(), artifact) + (query == null ? "" : "?" + query);
    return mockMvc
        .perform(get(path).param("format", "csv"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(CSV))
        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
        .andExpect(header().string("X-Result-Truncated", "false"))
        .andReturn();
  }

  private JsonNode json(Fixture fixture, String artifact, String query) throws Exception {
    String path = exportPath(fixture.runId(), artifact) + (query == null ? "" : "?" + query);
    MvcResult result =
        mockMvc
            .perform(get(path).param("format", "json"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(JSON))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(header().string("X-Result-Truncated", "false"))
            .andReturn();
    return objectMapper.readTree(body(result));
  }

  private JsonNode getJson(String path) throws Exception {
    return objectMapper.readTree(
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8));
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  private static String exportPath(UUID runId, String artifact) {
    return "/api/v1/backtests/" + runId + "/export/" + artifact;
  }

  private static void assertSameJson(JsonNode actual, JsonNode expected) {
    assertThat(actual).isEqualTo(expected);
  }

  private Fixture insertFixture() {
    UUID strategyVersionId = UUID.randomUUID();
    Job job =
        jobs.insertQueued(
            JobKind.BACKTEST,
            null,
            strategyVersionId,
            objectMapper.createObjectNode().put("strategyId", UUID.randomUUID().toString()),
            "corr-export-" + UUID.randomUUID(),
            "owner");
    UUID runId =
        jdbc.queryForObject(
            """
            INSERT INTO backtest_runs (
              job_id, strategy_version_id, exchange, tradingsymbol, "interval", start_ts, end_ts,
              initial_equity, final_equity, seed, data_hash, total_return, sharpe, sortino,
              max_drawdown, win_rate, profit_factor, trade_count, metrics, fold_metrics,
              equity_curve, drawdown_curve, engine_version, premium_source)
            VALUES (?, ?, 'NSE', 'NIFTY 50', '1m', ?, ?, 100000.00, 100250.50, 17,
                    'export-fixture-hash', 0.250500, 1.250000, 1.100000, 0.500000,
                    50.000000, 2.000000, 2, '{}'::jsonb, ?::jsonb, ?::jsonb, '[]'::jsonb,
                    'strategy-engine/export-it', 'NA')
            RETURNING id
            """,
            (rs, n) -> UUID.fromString(rs.getString("id")),
            job.id(),
            strategyVersionId,
            OffsetDateTime.parse("2026-07-13T09:15:00+05:30"),
            OffsetDateTime.parse("2026-07-13T15:30:00+05:30"),
            foldMetrics(),
            equityCurve());
    insertTrade(
        runId,
        1,
        "BUY",
        OffsetDateTime.parse("2026-07-13T09:15:00+05:30"),
        "100.2500",
        OffsetDateTime.parse("2026-07-13T09:45:00+05:30"),
        "102.7500",
        "125.00",
        "2.493766",
        "TAKE_PROFIT");
    insertTrade(
        runId,
        2,
        "SELL",
        OffsetDateTime.parse("2026-07-13T10:00:00+05:30"),
        "103.0000",
        OffsetDateTime.parse("2026-07-13T10:30:00+05:30"),
        "100.5000",
        "125.00",
        "2.427184",
        "STOP_LOSS");
    return new Fixture(runId, strategyVersionId);
  }

  private void insertTrade(
      UUID runId,
      int seq,
      String side,
      OffsetDateTime entryTs,
      String entryPrice,
      OffsetDateTime exitTs,
      String exitPrice,
      String pnl,
      String pnlPct,
      String exitReason) {
    jdbc.update(
        """
        INSERT INTO backtest_trades (
          run_id, seq, side, qty, entry_ts, entry_price, exit_ts, exit_price, pnl, pnl_pct,
          exit_reason, bars_held, touch_basis, contributions, exchange, tradingsymbol,
          stop_loss, take_profit)
        VALUES (?, ?, ?, 50, ?, ?, ?, ?, ?, ?, ?, 30, 'CLOSE_EVAL',
                '{"ema":"1.25"}'::jsonb, 'NSE', 'NIFTY 50', 99.0000, 105.0000)
        """,
        runId,
        seq,
        side,
        entryTs,
        new BigDecimal(entryPrice),
        exitTs,
        new BigDecimal(exitPrice),
        new BigDecimal(pnl),
        new BigDecimal(pnlPct),
        exitReason);
  }

  private static String foldMetrics() {
    return """
        [{
          "fold": 0,
          "train": {"from":"2026-07-01T09:15:00+05:30","to":"2026-07-08T15:30:00+05:30"},
          "test": {"from":"2026-07-09T09:15:00+05:30","to":"2026-07-13T15:30:00+05:30"},
          "trainMetrics": {"sharpe":"1.20"},
          "oosMetrics": {"sharpe":"0.80"},
          "regimeMix": {},
          "regimeOos": {},
          "tradeRegimes": []
        }]
        """;
  }

  private static String equityCurve() {
    return """
        [
          {"ts":"2026-07-13T09:15:00+05:30","value":"100000.00"},
          {"ts":"2026-07-13T15:30:00+05:30","value":"100250.50"}
        ]
        """;
  }

  private record Fixture(UUID runId, UUID strategyVersionId) {}
}
