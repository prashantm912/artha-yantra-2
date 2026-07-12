package in.arthayantra.backtest.replay.counterfactual;

import static org.assertj.core.api.Assertions.assertThat;
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
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
 * End-to-end counterfactual replay against the real Timescale + Redis stack (EVO E3 item 9): seed
 * BACKFILL option candles + an expired-contract lot size, submit through {@code POST
 * /api/v1/backtests/counterfactual}, run the worker, and assert the per-variant metrics persist and the
 * results endpoint serves them. Also pins the captured-data guard (MOCK / absent premium → 422), the
 * lot-size refusal, and determinism (identical numbers across two runs of the same request).
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class CounterfactualIntegrationTest extends BacktestIntegrationTestBase {

  @MockitoBean private StrategyVersionClient versions; // never called on the counterfactual path

  @Autowired private MockMvc mockMvc;
  @Autowired private JobRepository jobs;
  @Autowired private BacktestRunner runner;
  @Autowired private CounterfactualRunRepository cfRuns;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  /** Seeds three 1m BACKFILL premium bars (100 → 112 → 90) for a leg. */
  private void seedCandles(String symbol, String source) {
    seedBar(symbol, "2026-06-16T09:15:00+05:30", "100", source);
    seedBar(symbol, "2026-06-16T09:16:00+05:30", "112", source);
    seedBar(symbol, "2026-06-16T09:17:00+05:30", "90", source);
  }

  private void seedBar(String symbol, String bucket, String close, String source) {
    BigDecimal c = new BigDecimal(close);
    jdbc.update(
        "INSERT INTO marketdata.candles (exchange, tradingsymbol, \"interval\", bucket, open, high,"
            + " low, close, volume, source) VALUES ('NFO', ?, '1m', ?, ?, ?, ?, ?, 0, ?)"
            + " ON CONFLICT DO NOTHING",
        symbol, OffsetDateTime.parse(bucket), c, c, c, c, source);
  }

  private void seedExpiredContract(String symbol, int lotSize) {
    jdbc.update(
        "INSERT INTO marketdata.expired_contracts (exchange, tradingsymbol, instrument_type,"
            + " underlying_symbol, expiry, strike, lot_size, weekly, upstox_key, underlying_key)"
            + " VALUES ('NFO', ?, 'CE', 'NIFTY', DATE '2026-06-16', 25000, ?, true, ?,"
            + " 'NSE_INDEX|Nifty 50') ON CONFLICT DO NOTHING",
        symbol, lotSize, "key-" + symbol);
  }

  private String requestBody(String symbol) {
    String template =
        """
        {
          "from": "2026-06-16",
          "to": "2026-06-17",
          "entries": [
            {"exchange":"NFO","tradingsymbol":"%s","entryTime":"2026-06-16T09:15:00+05:30",
             "entryPremium":100,"qty":150}
          ],
          "variants": [
            {"name":"tp10","exitKnobs":{"takeProfitPct":10}},
            {"name":"sl5","exitKnobs":{"stopLossPct":5}},
            {"name":"hold","exitKnobs":{}}
          ]
        }
        """;
    return template.formatted(symbol);
  }

  private UUID submit(String symbol) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/backtests/counterfactual")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(symbol)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(objectMapper.readTree(body).path("jobId").asText());
  }

  private UUID runToRun(UUID jobId) {
    Job job = jobs.find(jobId).orElseThrow();
    runner.run(job, pct -> {}, () -> false);
    return cfRuns.findRunIdByJobId(jobId).orElseThrow();
  }

  @Test
  void replaysCapturedPremiumUnderVariantsAndServesResults() throws Exception {
    String symbol = "CFHAPPY26JUN25000CE";
    seedCandles(symbol, "BACKFILL");
    seedExpiredContract(symbol, 75);

    UUID jobId = submit(symbol);

    // the COUNTERFACTUAL job's resultRef is resolved from counterfactual_runs on the status payload.
    UUID runId = runToRun(jobId);
    mockMvc
        .perform(get("/api/v1/backtests/jobs/" + jobId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("COUNTERFACTUAL"))
        .andExpect(jsonPath("$.resultRef").value(runId.toString()));

    String results =
        mockMvc
            .perform(get("/api/v1/backtests/counterfactual/" + runId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entryCount").value(1))
            .andExpect(jsonPath("$.variantCount").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = objectMapper.readTree(results);
    JsonNode tp = variant(root, "tp10");
    JsonNode sl = variant(root, "sl5");
    JsonNode hold = variant(root, "hold");

    // TP@110 exits on the 112 bar (profit); SL@95 and the knob-less hold fall to 90 (loss).
    assertThat(tp.path("exitReasonCounts").path("TAKE_PROFIT").asInt()).isEqualTo(1);
    assertThat(sl.path("exitReasonCounts").path("STOP_LOSS").asInt()).isEqualTo(1);
    assertThat(hold.path("exitReasonCounts").path("END_OF_DATA").asInt()).isEqualTo(1);
    assertThat(new BigDecimal(tp.path("netPnlInr").asText())).isPositive();
    assertThat(new BigDecimal(hold.path("netPnlInr").asText())).isNegative();
    assertThat(new BigDecimal(tp.path("grossPremiumPoints").asText())).isEqualByComparingTo("12");
  }

  @Test
  void sameRequestReplaysToIdenticalNumbers() throws Exception {
    String symbol = "CFDET26JUN25000CE";
    seedCandles(symbol, "BACKFILL");
    seedExpiredContract(symbol, 75);

    UUID run1 = runToRun(submit(symbol));
    UUID run2 = runToRun(submit(symbol));

    assertThat(netPnl(run1, "tp10")).isEqualByComparingTo(netPnl(run2, "tp10"));
    assertThat(netPnl(run1, "hold")).isEqualByComparingTo(netPnl(run2, "hold"));
  }

  @Test
  void mockSourcePremiumIsRefused() throws Exception {
    String symbol = "CFMOCK26JUN25000CE";
    seedCandles(symbol, "MOCK"); // synthetic — not captured real data
    seedExpiredContract(symbol, 75);

    mockMvc
        .perform(
            post("/api/v1/backtests/counterfactual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(symbol)))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("DATA_STALE"));
  }

  @Test
  void absentPremiumIsRefused() throws Exception {
    String symbol = "CFEMPTY26JUN25000CE"; // no candles seeded
    seedExpiredContract(symbol, 75);

    mockMvc
        .perform(
            post("/api/v1/backtests/counterfactual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(symbol)))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("DATA_GAP"));
  }

  @Test
  void unresolvableLotSizeIsRefused() throws Exception {
    String symbol = "CFNOLOT26JUN25000CE";
    seedCandles(symbol, "BACKFILL"); // real premium, but no registry row → no lot size
    // no expired_contracts / instruments row

    mockMvc
        .perform(
            post("/api/v1/backtests/counterfactual")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(symbol)))
        .andExpect(status().is(422))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // findRunIdByJobId must resolve THIS job's run under multi-row shared-DB state (the singleton IT DB
  // accumulates runs from every method) and must return the CURRENT run after a crash-recovery re-run
  // replaces it — guards the resolver contract the ORDER BY completed_at DESC, id DESC + the
  // uq_counterfactual_runs_job DELETE-by-job replace both underwrite (never an arbitrary row).
  @Test
  void findRunIdByJobIdResolvesTheJobsOwnCurrentRun() {
    ObjectNode emptyRequest = objectMapper.createObjectNode();
    UUID jobA =
        jobs.insertQueued(JobKind.COUNTERFACTUAL, null, null, emptyRequest, "cf-order-A", "owner").id();
    UUID jobB =
        jobs.insertQueued(JobKind.COUNTERFACTUAL, null, null, emptyRequest, "cf-order-B", "owner").id();
    OffsetDateTime from = OffsetDateTime.parse("2026-06-16T00:00:00+05:30");
    OffsetDateTime to = OffsetDateTime.parse("2026-06-17T00:00:00+05:30");

    UUID runA = cfRuns.insert(jobA, from, to, 1, List.of(), "owner");
    UUID runB = cfRuns.insert(jobB, from, to, 1, List.of(), "owner");
    assertThat(cfRuns.findRunIdByJobId(jobA)).contains(runA);
    assertThat(cfRuns.findRunIdByJobId(jobB)).contains(runB);

    // Crash-recovery re-run: insert() DELETEs-by-job then INSERTs a fresh id. The resolver must return
    // the CURRENT run, the superseded id must no longer resolve, and jobB stays untouched.
    UUID runA2 = cfRuns.insert(jobA, from, to, 1, List.of(), "owner");
    assertThat(runA2).isNotEqualTo(runA);
    assertThat(cfRuns.findRunIdByJobId(jobA)).contains(runA2);
    assertThat(cfRuns.findResult(runA)).isEmpty();
    assertThat(cfRuns.findRunIdByJobId(jobB)).contains(runB);
  }

  private JsonNode variant(JsonNode result, String name) {
    for (JsonNode v : result.path("variants")) {
      if (name.equals(v.path("name").asText())) {
        return v;
      }
    }
    throw new AssertionError("no variant " + name);
  }

  private BigDecimal netPnl(UUID runId, String variant) {
    return cfRuns.findResult(runId).orElseThrow().variants().stream()
        .filter(v -> v.name().equals(variant))
        .findFirst()
        .orElseThrow()
        .netPnlInr();
  }
}
