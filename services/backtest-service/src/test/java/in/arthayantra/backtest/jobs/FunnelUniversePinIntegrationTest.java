package in.arthayantra.backtest.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ErrorCodes;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B9 (EVO §13 row 19): the swing FUNNEL universe modes ({@code manas_arora_funnel} /
 * {@code minervini_funnel}) are pinned by copy into the job request at submission — the same
 * {@code futures_screener} pin-by-copy precedent (JobsService), extended so Manas/Minervini SIM_FIRST
 * trials become runnable through the job pipeline instead of throwing "needs an explicit
 * single-instrument universe". Verifies (1) a populated funnel materialises a concrete pinned
 * universe, (2) an empty funnel fails CLEAN at submission with a 4xx (not a mid-replay crash), and
 * (3) the {@code futures_screener} empty-pin behaviour is unchanged (the 4xx guard is funnel-scoped).
 * The mocked resolver stands in for strategy-signal's REST universe resolution (this module holds no
 * marketdata grant); the config carries no {@code indicators} so the worker runs the no-op stub.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.workers=1",
      "artha.backtest.stub-step-millis=120",
      "artha.backtest.summary-refresh-ms=1000"
    })
@AutoConfigureMockMvc
class FunnelUniversePinIntegrationTest extends BacktestIntegrationTestBase {

  private static final String FUNNEL = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SCREENER = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JobRepository jobs;

  @BeforeEach
  void stubVersions() throws Exception {
    when(versions.resolve(eq(FUNNEL), any()))
        .thenReturn(
            new ResolvedVersion(
                UUID.fromString(FUNNEL),
                null,
                "1.0.0",
                "cksum-funnel",
                objectMapper.readTree("{\"universe\":{\"mode\":\"manas_arora_funnel\"}}")));
    when(versions.resolve(eq(SCREENER), any()))
        .thenReturn(
            new ResolvedVersion(
                UUID.fromString(SCREENER),
                null,
                "1.0.0",
                "cksum-screener",
                objectMapper.readTree("{\"universe\":{\"mode\":\"futures_screener\"}}")));
  }

  @Test
  void populatedFunnelPinsConcreteUniverseIntoTheRequest() throws Exception {
    when(versions.resolveUniverse(any(), any()))
        .thenReturn(
            Optional.of(
                objectMapper.readTree(
                    "{\"mode\":\"manas_arora_funnel\",\"asOf\":\"2026-07-10\",\"checksum\":\"pin-abc\","
                        + "\"items\":[{\"exchange\":\"NSE\",\"tradingsymbol\":\"RELIANCE\"},"
                        + "{\"exchange\":\"NSE\",\"tradingsymbol\":\"TCS\"}]}")));

    String jobId = submit(FUNNEL);

    JsonNode request =
        jobs.find(UUID.fromString(jobId)).orElseThrow().request();
    JsonNode pinned = request.path("universe");
    assertThat(pinned.isArray()).isTrue();
    assertThat(pinned).hasSize(2);
    assertThat(pinned.get(0).path("exchange").asText()).isEqualTo("NSE");
    assertThat(pinned.get(0).path("tradingsymbol").asText()).isEqualTo("RELIANCE");
    assertThat(request.path("universeChecksum").asText()).isEqualTo("pin-abc");
    // task_03b9f52d / task_9062b5f1: the funnel-chosen screen date is stamped into run provenance so
    // a windowed funnel submission records WHICH persisted screen fed the pinned universe.
    assertThat(request.path("universeAsOf").asText()).isEqualTo("2026-07-10");
  }

  @Test
  void emptyFunnelIsRejectedWithA4xxAtSubmission() throws Exception {
    when(versions.resolveUniverse(any(), any()))
        .thenReturn(
            Optional.of(
                objectMapper.readTree(
                    "{\"mode\":\"manas_arora_funnel\",\"checksum\":\"empty\",\"items\":[]}")));

    mockMvc
        .perform(
            post("/api/v1/backtests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategyId\":\"" + FUNNEL + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value(ErrorCodes.STRATEGY_UNIVERSE_UNSUPPORTED));
  }

  @Test
  void emptyFuturesScreenerStillPinsEmptyAndQueues() throws Exception {
    // Regression: the empty-universe 4xx guard is FUNNEL-scoped — futures_screener keeps its
    // byte-identical "pin empty, let the worker decide" behaviour (it must NOT 422 at submission).
    when(versions.resolveUniverse(any(), any()))
        .thenReturn(
            Optional.of(
                objectMapper.readTree(
                    "{\"mode\":\"futures_screener\",\"checksum\":\"empty\",\"items\":[]}")));

    String jobId = submit(SCREENER);

    JsonNode pinned =
        jobs.find(UUID.fromString(jobId)).orElseThrow().request().path("universe");
    assertThat(pinned.isArray()).isTrue();
    assertThat(pinned).isEmpty();
  }

  private String submit(String strategyId) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"strategyId\":\"" + strategyId + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("queued"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("jobId").asText();
  }
}
