package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.fills.InstrumentClass;
import in.arthayantra.strategysignal.paper.InstrumentMetaClient.InstrumentMeta;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * §7.2.2 paper.events IT (mock profile, engine off): opening then closing a paper position fires the
 * existing {@link PaperPositionOpened}/{@link PaperPositionClosed} events AFTER_COMMIT, which {@link
 * PaperEventPublisher} serialises into an append-only {@code paper_events} row AND a {@code
 * paper.events} Redis frame. Asserts both the durable rows (the record of truth) and the live frames
 * (the shape the FE hook appends). The base is non-transactional (real commits) so the AFTER_COMMIT
 * listener has fired by the time each response returns.
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class PaperEventLifecycleIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class StubInstruments {
    @Bean
    @Primary
    InstrumentMetaClient stubInstrumentMetaClient() {
      return (exchange, tradingsymbol) ->
          new InstrumentMeta(InstrumentClass.OPTION, new BigDecimal("0.05"), 50);
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RedisConnectionFactory connectionFactory;

  @Test
  void openThenCloseWritesOpenedAndClosedRowsAndFrames() throws Exception {
    List<String> frames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) ->
            frames.add(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(PaperEventPublisher.CHANNEL));
    listener.afterPropertiesSet();
    listener.start();
    try {
      String sym = "EVTOPT-" + UUID.randomUUID();
      String openBody =
          mockMvc
              .perform(
                  post("/api/v1/paper/orders")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json(
                              Map.of(
                                  "exchange", "NFO", "tradingsymbol", sym, "side", "SELL", "qty", 50,
                                  "price", "120.00"))))
              .andExpect(status().isCreated())
              .andReturn()
              .getResponse()
              .getContentAsString();
      long positionId = objectMapper.readTree(openBody).get("id").asLong();

      // OPENED durable row.
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> eventCount(positionId, "OPENED") == 1);

      mockMvc
          .perform(
              post("/api/v1/paper/positions/" + positionId + "/close")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json(Map.of("price", "100.00"))))
          .andExpect(status().isOk());

      // CLOSED durable row (a manual close → kind CLOSED, reason MANUAL, realized > 0 on the short).
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> eventCount(positionId, "CLOSED") == 1);
      String reason =
          jdbc.queryForObject(
              "SELECT reason FROM paper_events WHERE position_id=? AND kind='CLOSED'",
              String.class,
              positionId);
      assertThat(reason).isEqualTo("MANUAL");

      // Both frames arrived and carry the right kind/positionId (frame shape).
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> frameFor(frames, positionId, "OPENED") != null);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> frameFor(frames, positionId, "CLOSED") != null);
      JsonNode opened = frameFor(frames, positionId, "OPENED");
      assertThat(opened.get("book").asText()).isEqualTo("manual");
      assertThat(opened.get("side").asText()).isEqualTo("SELL");
      assertThat(opened.get("qty").asLong()).isEqualTo(50L);
      assertThat(opened.get("realizedPnl").isNull()).isTrue();
      JsonNode closed = frameFor(frames, positionId, "CLOSED");
      assertThat(closed.get("reason").asText()).isEqualTo("MANUAL");
      assertThat(closed.get("realizedPnl").isNull()).isFalse();
    } finally {
      listener.stop();
    }
  }

  private long eventCount(long positionId, String kind) {
    Long n =
        jdbc.queryForObject(
            "SELECT count(*) FROM paper_events WHERE position_id=? AND kind=?",
            Long.class,
            positionId,
            kind);
    return n == null ? 0 : n;
  }

  private JsonNode frameFor(List<String> frames, long positionId, String kind) {
    for (String f : frames) {
      try {
        JsonNode node = objectMapper.readTree(f);
        if (node.get("positionId").asLong() == positionId && kind.equals(node.get("kind").asText())) {
          return node;
        }
      } catch (Exception ignored) {
        // not our frame
      }
    }
    return null;
  }

  private String json(Map<String, ?> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }
}
