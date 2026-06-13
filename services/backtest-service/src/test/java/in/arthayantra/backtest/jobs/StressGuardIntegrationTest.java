package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
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
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase-32 S1C stress-test contamination guard (§D.6) end-to-end over REST: a {@code purpose:
 * stress_test} window overlapping a prior MANUAL quick-backtest (not only sweeps) is refused with
 * 422 {@code WINDOW_CONTAMINATED} naming the offending job ids; the clean-window suggestion is the
 * day after the latest tested {@code to}; and the holdout-reuse counter increments per repeat.
 */
@SpringBootTest(
    properties = {"spring.profiles.active=mock", "artha.backtest.worker-pool-enabled=false"})
@AutoConfigureMockMvc
class StressGuardIntegrationTest extends BacktestIntegrationTestBase {

  private static final UUID STRATEGY_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final UUID OTHER_STRATEGY = UUID.fromString("77777777-7777-7777-7777-777777777777");

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

  /** The strategy lineage each test's submits land on (the request-body strategyId is the key). */
  private UUID currentStrategyId = STRATEGY_ID;

  /** An empty config (no universe) so neither the primary nor the benchmark pre-flight fires — the
   * stress guard is what is under test, not coverage. */
  private void stubKnown(UUID strategyId) {
    this.currentStrategyId = strategyId;
    when(versions.resolve(any(), any()))
        .thenReturn(
            new ResolvedVersion(strategyId, "1.0.0", "checksum-stress", objectMapper.createObjectNode()));
  }

  @Test
  void stressWindowOverlappingPriorManualBacktestIsRefused() throws Exception {
    stubKnown(STRATEGY_ID);
    // a prior MANUAL quick-backtest over Jan -> the holdout for that window is now contaminated.
    String priorJobId = submit("backtest", "2026-01-01", "2026-02-01");

    MvcResult res =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("stress_test", "2026-01-15", "2026-03-01"))) // overlaps Jan15..Feb01
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("WINDOW_CONTAMINATED"))
            .andReturn();

    JsonNode err = objectMapper.readTree(res.getResponse().getContentAsString());
    JsonNode jobIds = err.path("details").path("jobIds");
    assertThat(jobIds.isArray()).isTrue();
    boolean named = false;
    for (JsonNode id : jobIds) {
      if (id.asText().equals(priorJobId)) {
        named = true;
      }
    }
    assertThat(named).as("the offending manual-backtest jobId is listed").isTrue();
  }

  @Test
  void nonOverlappingStressWindowSucceedsAndRecordsReuseCounter() throws Exception {
    stubKnown(OTHER_STRATEGY);
    submit("backtest", "2026-01-01", "2026-02-01"); // prior tested window: Jan

    // a CLEAN window (Mar) — accepted; the reuse counter is recorded on the job request echo.
    String firstStress = submit("stress_test", "2026-03-01", "2026-04-01");
    assertThat(reuseCount(firstStress)).isEqualTo(1);

    // a SECOND stress against the SAME (strategy, window) increments the counter (holdout reuse).
    String secondStress = submit("stress_test", "2026-03-01", "2026-04-01");
    assertThat(reuseCount(secondStress)).isEqualTo(2);
  }

  @Test
  void stressWindowEndpointSuggestsTheDayAfterTheLatestTestedTo() throws Exception {
    stubKnown(STRATEGY_ID);
    submit("backtest", "2026-01-01", "2026-02-01");
    submit("backtest", "2026-02-15", "2026-03-10"); // latest tested `to` == 2026-03-10

    mockMvc
        .perform(get("/api/v1/backtests/stress-window").param("strategyId", STRATEGY_ID.toString()))
        .andExpect(status().isOk())
        // `to` is exclusive, so the first clean day IS 2026-03-10.
        .andExpect(jsonPath("$.from").value("2026-03-10"));
  }

  @Test
  void stressWindowEndpointIs404ForAStrategyWithNoLineage() throws Exception {
    stubKnown(UUID.fromString("88888888-8888-8888-8888-888888888888"));
    mockMvc
        .perform(
            get("/api/v1/backtests/stress-window")
                .param("strategyId", "88888888-8888-8888-8888-888888888888"))
        .andExpect(status().isNotFound());
  }

  // ----- helpers -----

  private String submit(String purpose, String from, String to) throws Exception {
    MvcResult res =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(purpose, from, to)))
            .andExpect(status().isAccepted())
            .andReturn();
    return objectMapper.readTree(res.getResponse().getContentAsString()).path("jobId").asText();
  }

  private String body(String purpose, String from, String to) {
    return objectMapper
        .createObjectNode()
        .put("strategyId", currentStrategyId.toString())
        .put("from", from)
        .put("to", to)
        .put("purpose", purpose)
        .toString();
  }

  private long reuseCount(String jobId) {
    Long c =
        jdbc.queryForObject(
            "SELECT (request->>'holdoutReuseCount')::bigint FROM jobs WHERE id=?::uuid",
            Long.class,
            jobId);
    return c == null ? 0L : c;
  }
}
