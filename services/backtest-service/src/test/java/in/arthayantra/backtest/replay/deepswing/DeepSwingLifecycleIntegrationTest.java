package in.arthayantra.backtest.replay.deepswing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobKind;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.BacktestRunner;
import in.arthayantra.backtest.replay.RunRepository;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end DEEP_SWING lineage against the real Timescale + Redis stack (research-fidelity audit
 * P0-3): submit through {@code POST /api/v1/backtests/deep-swing}, run the worker (market-data's
 * deep-sim boundary mocked with a fixed reply), and assert the SHIPPED backtest lineage holds the
 * outcome — a {@code backtest_runs} row carrying the market-data engine SHA (V008) + actor (V009) +
 * the FULL deep-sim report intact in the metrics JSONB, per-trade {@code backtest_trades} rows with
 * the deep-sim's REAL exit attribution, the standard {@code /results} + {@code /trades} endpoints
 * serving it, and the {@code experiment_runs} view (V010) surfacing it with {@code kind=DEEP_SWING}.
 * Also pins submission validation (bad family / missing from / future from → 422) and that an
 * upstream failure leaves NO run row.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class DeepSwingLifecycleIntegrationTest extends BacktestIntegrationTestBase {

  private static final String MD_SHA = "mdsha0000000000000000000000000000000feed";
  private static final String MD_IMAGE = "market-data-service:it-test";
  private static final LocalDate FROM = LocalDate.of(2015, 7, 1);

  @MockitoBean private StrategyVersionClient versions; // never called on the deep-swing path
  @MockitoBean private MarketDataDeepSwingClient client; // the market-data HTTP boundary

  @Autowired private MockMvc mockMvc;
  @Autowired private JobRepository jobs;
  @Autowired private BacktestRunner runner;
  @Autowired private RunRepository runs;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  /** A fixed deep-sim reply: 2 closed lots + a full report block, stamped with market-data's SHA. */
  private DeepSwingReply reply(String family, String variant) {
    ObjectNode report = objectMapper.createObjectNode();
    report.put("status", "completed");
    report.put("variant", variant);
    report.put("totalTrades", 2);
    report.putObject("portfolioRsPriorityNet").put("cagrPct", "12.34");
    List<DeepSwingReply.Trade> trades =
        List.of(
            new DeepSwingReply.Trade(
                "RELIANCE", "breakout", LocalDate.of(2016, 2, 1), new BigDecimal("500.10"),
                LocalDate.of(2016, 5, 2), new BigDecimal("600.50"), new BigDecimal("20.08"),
                62, "TRAILING_STOP", new BigDecimal("81.5")),
            new DeepSwingReply.Trade(
                "TCS", "vcp", LocalDate.of(2017, 3, 6), new BigDecimal("1200.00"),
                LocalDate.of(2017, 3, 20), new BigDecimal("1104.00"), new BigDecimal("-8.00"),
                10, "STOP_LOSS", null));
    return new DeepSwingReply(
        MD_SHA,
        MD_IMAGE,
        new DeepSwingReply.Result(
            family,
            variant,
            FROM,
            "2026-07-12T10:00:00+05:30",
            42,
            new BigDecimal("1000000"),
            new BigDecimal("250.50"),
            new BigDecimal("12.34"),
            new BigDecimal("30.20"),
            new BigDecimal("0.96"),
            100,
            40,
            new BigDecimal("48.1000"),
            new BigDecimal("1.8000"),
            report,
            trades));
  }

  private UUID submit(String body) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/backtests/deep-swing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andExpect(jsonPath("$.status").value("queued"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(response).path("jobId").asText());
  }

  @Test
  void deepSwingJobPersistsFullLineageAndServesStandardReads() throws Exception {
    when(client.run(eq("manas"), eq(FROM), any())).thenReturn(reply("manas", "rs-turnover-nopyramid"));

    UUID jobId = submit("{\"family\":\"manas\",\"from\":\"2015-07-01\"}");

    // the job row: DEEP_SWING kind, owner actor, pinned {family, from} request.
    Job job = jobs.find(jobId).orElseThrow();
    assertThat(job.kind()).isEqualTo(JobKind.DEEP_SWING);
    assertThat(job.createdBy()).isEqualTo("owner");
    assertThat(job.request().path("family").asText()).isEqualTo("manas");
    assertThat(job.request().path("from").asText()).isEqualTo("2015-07-01");

    // worker execution (the pool is disabled; drive the runner directly, the counterfactual pattern).
    runner.run(job, pct -> {}, () -> false);
    UUID runId = runs.findRunIdByJobId(jobId).orElseThrow();

    // job status payload resolves the resultRef from backtest_runs (the standard read path).
    mockMvc
        .perform(get("/api/v1/backtests/jobs/" + jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("DEEP_SWING"))
        .andExpect(jsonPath("$.resultRef").value(runId.toString()));

    // the run row: headline metrics + MARKET-DATA's engine identity + actor + report intact.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT exchange, tradingsymbol, \"interval\", total_return, sharpe, max_drawdown,"
                + " win_rate, profit_factor, trade_count, seed, data_hash, engine_version,"
                + " engine_sha, engine_image, created_by, premium_source, initial_equity,"
                + " final_equity, metrics FROM backtest_runs WHERE id = ?",
            runId);
    assertThat(row.get("exchange")).isEqualTo("SWING");
    assertThat(row.get("tradingsymbol")).isEqualTo("MANAS:rs-turnover-nopyramid");
    assertThat(row.get("interval")).isEqualTo("1d");
    assertThat((BigDecimal) row.get("total_return")).isEqualByComparingTo("250.50");
    assertThat((BigDecimal) row.get("sharpe")).isEqualByComparingTo("0.96");
    assertThat((BigDecimal) row.get("max_drawdown")).isEqualByComparingTo("30.20");
    assertThat((BigDecimal) row.get("win_rate")).isEqualByComparingTo("48.1");
    assertThat((BigDecimal) row.get("profit_factor")).isEqualByComparingTo("1.8");
    // review MED: trade_count = the PORTFOLIO's tradesTaken (the same statistical basis as
    // sharpe/total_return), NOT the per-trade row count — that rides metrics.signalLotCount.
    assertThat(row.get("trade_count")).isEqualTo(100);
    assertThat(row.get("engine_sha")).isEqualTo(MD_SHA); // the jar that COMPUTED the numbers
    assertThat(row.get("engine_image")).isEqualTo(MD_IMAGE);
    assertThat(row.get("engine_version")).isEqualTo("deep-swing/manas");
    assertThat(row.get("created_by")).isEqualTo("owner");
    assertThat(row.get("premium_source")).isEqualTo("NA");
    assertThat(row.get("data_hash")).isEqualTo("deep-swing:manas:rs-turnover-nopyramid");
    // final equity derives from capital × (1 + totalReturn%): 1,000,000 × 3.505.
    assertThat((BigDecimal) row.get("initial_equity")).isEqualByComparingTo("1000000");
    assertThat((BigDecimal) row.get("final_equity")).isEqualByComparingTo("3505000.00");
    // the FULL deep-sim report rides the metrics JSONB verbatim — the run row alone reproduces it.
    JsonNode metrics = objectMapper.readTree(row.get("metrics").toString());
    assertThat(metrics.path("cagr").asText()).isEqualTo("12.34");
    assertThat(metrics.path("family").asText()).isEqualTo("manas");
    assertThat(metrics.path("signalLotCount").asInt()).isEqualTo(2); // full signal-lot set count
    assertThat(metrics.path("tradesTaken").asInt()).isEqualTo(100); // the trade_count basis
    assertThat(metrics.path("report").path("status").asText()).isEqualTo("completed");
    assertThat(metrics.path("report").path("totalTrades").asInt()).isEqualTo(2);
    assertThat(metrics.path("caveats").isArray()).isTrue();

    // per-trade rows carry the deep-sim's REAL exit attribution + real stock symbols.
    List<Map<String, Object>> tradeRows =
        jdbc.queryForList(
            "SELECT seq, side, qty, tradingsymbol, exchange, exit_reason, pnl_pct, bars_held"
                + " FROM backtest_trades WHERE run_id = ? ORDER BY seq",
            runId);
    assertThat(tradeRows).hasSize(2);
    assertThat(tradeRows.get(0).get("tradingsymbol")).isEqualTo("RELIANCE");
    assertThat(tradeRows.get(0).get("exchange")).isEqualTo("NSE");
    assertThat(tradeRows.get(0).get("exit_reason")).isEqualTo("TRAILING_STOP");
    assertThat((BigDecimal) tradeRows.get(0).get("pnl_pct")).isEqualByComparingTo("20.08");
    assertThat(tradeRows.get(1).get("tradingsymbol")).isEqualTo("TCS");
    assertThat(tradeRows.get(1).get("exit_reason")).isEqualTo("STOP_LOSS");
    assertThat(tradeRows.get(1).get("bars_held")).isEqualTo(10);

    // the STANDARD results + trades endpoints serve the run (no bespoke read surface).
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/results"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.engineSha").value(MD_SHA))
        .andExpect(jsonPath("$.tradingsymbol").value("MANAS:rs-turnover-nopyramid"))
        .andExpect(jsonPath("$.metrics.report.status").value("completed"));
    mockMvc
        .perform(get("/api/v1/backtests/" + runId + "/trades"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].tradingsymbol").value("RELIANCE"))
        .andExpect(jsonPath("$.items[1].exitReason").value("STOP_LOSS"));

    // the experiment read layer (V010) surfaces the run — EVO consumes DEEP_SWING for free.
    Map<String, Object> exp =
        jdbc.queryForMap(
            "SELECT kind, total_return, engine_sha, created_by FROM experiment_runs WHERE run_id = ?",
            runId);
    assertThat(exp.get("kind")).isEqualTo("DEEP_SWING");
    assertThat((BigDecimal) exp.get("total_return")).isEqualByComparingTo("250.50");
    assertThat(exp.get("engine_sha")).isEqualTo(MD_SHA);
    assertThat(exp.get("created_by")).isEqualTo("owner");
  }

  @Test
  void minerviniFamilyRoutesWithVariantPin() throws Exception {
    when(client.run(eq("minervini"), eq(FROM), eq("rs-only")))
        .thenReturn(reply("minervini", "rs-only"));

    UUID jobId = submit("{\"family\":\"minervini\",\"from\":\"2015-07-01\",\"variant\":\"rs-only\"}");
    Job job = jobs.find(jobId).orElseThrow();
    assertThat(job.request().path("variant").asText()).isEqualTo("rs-only");

    runner.run(job, pct -> {}, () -> false);
    UUID runId = runs.findRunIdByJobId(jobId).orElseThrow();
    assertThat(
            jdbc.queryForObject(
                "SELECT tradingsymbol FROM backtest_runs WHERE id = ?", String.class, runId))
        .isEqualTo("MINERVINI:rs-only");
  }

  @Test
  void submissionValidationRejectsBadInput() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/backtests/deep-swing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"weinstein\",\"from\":\"2015-07-01\"}"))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    mockMvc
        .perform(
            post("/api/v1/backtests/deep-swing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"manas\"}"))
        .andExpect(status().is(422));
    mockMvc
        .perform(
            post("/api/v1/backtests/deep-swing")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"family\":\"manas\",\"from\":\"2099-01-01\"}"))
        .andExpect(status().is(422));
  }

  @Test
  void upstreamFailureLeavesNoRunRow() throws Exception {
    when(client.run(eq("manas"), eq(LocalDate.of(2016, 1, 4)), any()))
        .thenThrow(new RuntimeException("deep-sim busy (409)"));

    UUID jobId = submit("{\"family\":\"manas\",\"from\":\"2016-01-04\"}");
    Job job = jobs.find(jobId).orElseThrow();
    assertThatThrownBy(() -> runner.run(job, pct -> {}, () -> false))
        .isInstanceOf(RuntimeException.class);
    assertThat(runs.findRunIdByJobId(jobId)).isEmpty(); // failed jobs persist NOTHING
  }
}
