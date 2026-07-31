package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategysignal.registry.MarketDataInstrumentClient;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
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
 * §7.2.1 signals.status IT (mock profile, engine off): taking a signal via {@code
 * POST /signals/{id}/taken} flips ACTIVE→TAKEN in {@link SignalRepository}, which emits a {@link
 * SignalStatusChanged} the {@link SignalStatusListener} serialises AFTER_COMMIT (fallbackExecution —
 * the controller mutates outside any transaction) into a {@code signals.status} frame. Proves the
 * end-to-end path AND the book resolution (the originating strategy carries a {@code scalper} tag →
 * the frame's book is {@code scalper}).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock", "artha.signals.engine-enabled=false"})
@AutoConfigureMockMvc
class SignalStatusFrameIntegrationTest extends StrategySignalIntegrationTestBase {

  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    MarketDataInstrumentClient stubClient() {
      return (exchange, tradingsymbol) -> true;
    }
  }

  private static final String YAML =
      """
      schema: strategy-schema/v1
      id: SLUG
      name: "NAME"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: SYM }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        gate:
          all:
            - "close > 1"
        scoring: { threshold: 0.2 }
      exit_rules:
        - { type: stop_loss, params: { basis: premium_pct, value: 20 } }
        - { type: take_profit, params: { basis: premium_pct, value: 40 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private RegistryService registry;
  @Autowired private SignalRepository signals;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RedisConnectionFactory connectionFactory;

  @Test
  void takingASignalPublishesAStatusFrameWithTheResolvedBook() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String yaml =
        YAML.replace("SLUG", "status-it-" + suffix)
            .replace("NAME", "Status IT " + suffix)
            .replace("SYM", "STAT" + suffix);
    UUID strategyId =
        registry.create("Status IT " + suffix, null, List.of("scalper"), yaml).id();
    UUID versionId =
        jdbc.queryForObject(
            "SELECT id FROM strategy_versions WHERE strategy_id=? ORDER BY created_at DESC LIMIT 1",
            UUID.class,
            strategyId);
    long signalId =
        signals.insert(
            versionId, "NFO", "STATOPT" + suffix, "3m", "ENTRY", "BUY",
            new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("140"),
            new BigDecimal("0.5"), "{}", OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), null);

    List<String> frames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) -> frames.add(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(SignalPublisher.STATUS_CHANNEL));
    listener.afterPropertiesSet();
    listener.start();
    try {
      mockMvc
          .perform(
              post("/api/v1/signals/" + signalId + "/taken")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isOk());

      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> frameFor(frames, signalId) != null);
      JsonNode frame = frameFor(frames, signalId);
      assertThat(frame.get("status").asText()).isEqualTo("TAKEN");
      assertThat(frame.get("prevStatus").asText()).isEqualTo("ACTIVE");
      assertThat(frame.get("book").asText()).isEqualTo("scalper");
    } finally {
      listener.stop();
    }
  }

  /**
   * Proves the {@code expireAllActive} UPDATE...RETURNING path against REAL Postgres (the unit test
   * mocks {@code queryForList}): the 15:45 sweep flips an intraday ACTIVE signal to EXPIRED and emits
   * its EXPIRED status frame. Sweeping other classes' leftover ACTIVE signals is harmless — surefire
   * runs test classes serially (no interleaving), and this filters the frames to its own id.
   */
  @Test
  void expireSweepFlipsIntradayActiveAndEmitsExpiredFrame() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String yaml =
        YAML.replace("SLUG", "expire-it-" + suffix)
            .replace("NAME", "Expire IT " + suffix)
            .replace("SYM", "EXP" + suffix);
    UUID strategyId =
        registry.create("Expire IT " + suffix, null, List.of("scalper"), yaml).id();
    UUID versionId =
        jdbc.queryForObject(
            "SELECT id FROM strategy_versions WHERE strategy_id=? ORDER BY created_at DESC LIMIT 1",
            UUID.class,
            strategyId);
    long signalId =
        signals.insert(
            versionId, "NFO", "EXPOPT" + suffix, "3m", "ENTRY", "BUY",
            new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("140"),
            new BigDecimal("0.5"), "{}", OffsetDateTime.now(), OffsetDateTime.now().plusHours(1), null);

    List<String> frames = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) -> frames.add(new String(message.getBody(), StandardCharsets.UTF_8)),
        new ChannelTopic(SignalPublisher.STATUS_CHANNEL));
    listener.afterPropertiesSet();
    listener.start();
    try {
      assertThat(signals.expireAllActive()).isGreaterThanOrEqualTo(1);

      await().atMost(Duration.ofSeconds(10)).until(() -> frameFor(frames, signalId) != null);
      assertThat(frameFor(frames, signalId).get("status").asText()).isEqualTo("EXPIRED");
      String dbStatus =
          jdbc.queryForObject("SELECT status FROM signals WHERE id=?", String.class, signalId);
      assertThat(dbStatus).isEqualTo("EXPIRED");
    } finally {
      listener.stop();
    }
  }

  private JsonNode frameFor(List<String> frames, long signalId) {
    for (String f : frames) {
      try {
        JsonNode node = objectMapper.readTree(f);
        if (node.get("id").asLong() == signalId) {
          return node;
        }
      } catch (Exception ignored) {
        // not our frame
      }
    }
    return null;
  }
}
