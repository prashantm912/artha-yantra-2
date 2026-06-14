package in.arthayantra.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.backtest.client.StrategyVersionClient;
import in.arthayantra.backtest.client.StrategyVersionClient.ResolvedVersion;
import in.arthayantra.backtest.testsupport.BacktestIntegrationTestBase;
import in.arthayantra.common.web.error.ErrorCodes;
import in.arthayantra.common.web.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The headline Phase-28 acceptance: the full D12 state machine over REST + the per-job progress
 * channel, on a real Timescale + Redis stack. The replay is the no-op stub — only the spine is under
 * test here.
 */
@SpringBootTest(
    properties = {
      "spring.profiles.active=mock",
      "artha.backtest.workers=1",
      "artha.backtest.stub-step-millis=120",
      "artha.backtest.summary-refresh-ms=1000"
    })
@AutoConfigureMockMvc
class JobLifecycleIntegrationTest extends BacktestIntegrationTestBase {

  private static final String KNOWN = "11111111-1111-1111-1111-111111111111";
  private static final String UNKNOWN = "22222222-2222-2222-2222-222222222222";

  @MockitoBean private StrategyVersionClient versions;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RedisConnectionFactory connectionFactory;
  @Autowired private StringRedisTemplate redis;

  @BeforeEach
  void stubVersions() {
    when(versions.resolve(eq(KNOWN), any()))
        .thenReturn(
            new ResolvedVersion(
                UUID.fromString(KNOWN), null, "1.0.0", "checksum-abc", objectMapper.createObjectNode()));
    when(versions.resolve(eq(UNKNOWN), any()))
        .thenThrow(new NotFoundException(ErrorCodes.NOT_FOUND_RESOURCE, "no such strategy"));
  }

  @Test
  void submitRunsThroughTheStateMachineToCompletion() throws Exception {
    String jobId = submit(KNOWN);

    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              JsonNode job = getJob(jobId);
              assertThat(job.path("status").asText()).isEqualTo("completed");
              assertThat(job.path("progress").asInt()).isEqualTo(100);
              assertThat(job.path("finishedAt").isNull()).isFalse();
            });
  }

  @Test
  void unknownStrategyIs404WithEnvelope() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/backtests/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content(runBody(UNKNOWN)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND_RESOURCE));
  }

  @Test
  void cancelDrivesTheJobToCancelled() throws Exception {
    String jobId = submit(KNOWN);

    int cancelStatus =
        mockMvc
            .perform(delete("/api/v1/backtests/jobs/" + jobId))
            .andReturn()
            .getResponse()
            .getStatus();
    assertThat(cancelStatus).isIn(202, 204); // 202 cancelling (running) or 204 (still queued)

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(getJob(jobId).path("status").asText()).isEqualTo("cancelled"));
  }

  @Test
  void deletingATerminalJobIs409() throws Exception {
    String jobId = submit(KNOWN);
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> assertThat(getJob(jobId).path("status").asText()).isEqualTo("completed"));

    mockMvc
        .perform(delete("/api/v1/backtests/jobs/" + jobId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(ErrorCodes.CONFLICT_JOB_TERMINAL));
  }

  @Test
  void progressFramesArePublishedToThePerJobChannel() throws Exception {
    List<String> frames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.afterPropertiesSet();
    container.start();
    container.addMessageListener(
        (message, pattern) ->
            frames.add(new String(message.getBody(), StandardCharsets.UTF_8)),
        new PatternTopic("jobs.*"));
    try {
      String jobId = submit(KNOWN);
      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () -> {
                assertThat(frames).isNotEmpty();
                assertThat(frames)
                    .anyMatch(f -> f.contains(jobId) && f.contains("\"status\":\"completed\""));
                assertThat(frames).anyMatch(f -> f.contains(jobId) && f.contains("\"progress\""));
              });
    } finally {
      container.stop();
    }
  }

  @Test
  void jobsSummaryKeyIsMaintained() throws Exception {
    submit(KNOWN);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              String summary = redis.opsForValue().get("jobs:summary");
              assertThat(summary).isNotNull();
              JsonNode parsed = objectMapper.readTree(summary);
              assertThat(parsed.has("queued")).isTrue();
              assertThat(parsed.has("running")).isTrue();
            });
  }

  private String submit(String strategyId) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/backtests/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(runBody(strategyId)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("queued"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("jobId").asText();
  }

  private JsonNode getJob(String jobId) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/backtests/jobs/" + jobId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private static String runBody(String strategyId) {
    return "{\"strategyId\":\"" + strategyId + "\",\"from\":\"2026-01-05\",\"to\":\"2026-01-09\"}";
  }
}
