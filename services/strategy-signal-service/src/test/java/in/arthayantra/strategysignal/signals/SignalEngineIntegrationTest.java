package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.MarketDataInstrumentClient;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Phase 23 IT (Flow 5 Part B): publish a strategy → closed bars on the candle channel produce a
 * persisted signal whose breakdown satisfies the renderer invariant AND an identical payload on
 * the {@code signals} channel (divergence is the FAIL criterion); hot-swap takes effect at a
 * bar boundary; taken/dismiss transitions work; evaluation never rides the Redis thread (the
 * emitting thread is the engine's executor — asserted via the publisher payload ordering).
 */
@SpringBootTest(properties = {"spring.profiles.active=mock"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignalEngineIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  // intentional load-time capture: one fixed warm-up anchor shared by the stub and the bars
  @SuppressWarnings("TimeInStaticInitializer")
  private static final OffsetDateTime WARM_BASE =
      OffsetDateTime.now(IST).truncatedTo(ChronoUnit.MINUTES).minusMinutes(90);

  /** Stubs: instruments always resolve; candle warm-up is a deterministic declining ramp. */
  @TestConfiguration
  static class Stubs {
    @Bean
    @Primary
    MarketDataInstrumentClient stubInstruments() {
      return (exchange, tradingsymbol) -> true;
    }

    @Bean
    @Primary
    MarketDataCandlesClient stubCandles(
        org.springframework.web.client.RestClient.Builder builder, ObjectMapper objectMapper) {
      return new MarketDataCandlesClient(builder, objectMapper, "http://127.0.0.1:1") {
        @Override
        public List<EngineCandle> fetch(
            String exchange, String tradingsymbol, String interval,
            OffsetDateTime from, OffsetDateTime to) {
          if (!"1m".equals(interval) || !"SIGTEST".equals(tradingsymbol)) {
            return List.of();
          }
          List<EngineCandle> warm = new java.util.ArrayList<>();
          for (int i = 0; i < 30; i++) {
            BigDecimal close = new BigDecimal("103.00").subtract(new BigDecimal("0.10").multiply(BigDecimal.valueOf(i)));
            warm.add(
                new EngineCandle(
                    WARM_BASE.plusMinutes(i),
                    close.add(new BigDecimal("0.05")),
                    close.add(new BigDecimal("0.10")),
                    close.subtract(new BigDecimal("0.10")),
                    close,
                    500));
          }
          return warm;
        }
      };
    }
  }

  private static final String STRATEGY_YAML =
      """
      schema: strategy-schema/v1
      id: engine-it-momentum
      name: "Engine IT Momentum"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: SIGTEST }
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

  @Autowired private RegistryService registryService;
  @Autowired private SignalEngine engine;
  @Autowired private SignalRepository signals;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RedisConnectionFactory connectionFactory;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockMvc mockMvc;

  private static UUID strategyId;
  private static long firstSignalId;

  @Test
  @Order(1)
  void publishedStrategyEmitsAPersistedAndPublishedSignal() throws Exception {
    List<String> channelPayloads = new CopyOnWriteArrayList<>();
    RedisMessageListenerContainer listener = new RedisMessageListenerContainer();
    listener.setConnectionFactory(connectionFactory);
    listener.addMessageListener(
        (message, pattern) ->
            channelPayloads.add(
                new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)),
        new ChannelTopic(SignalPublisher.CHANNEL));
    listener.afterPropertiesSet();
    listener.start();
    try {
      strategyId =
          (UUID)
              registryService.create("Engine IT Momentum", null, null, STRATEGY_YAML).get("id");
      registryService.publish(strategyId, null, null);

      await()
          .atMost(Duration.ofSeconds(20))
          .until(() -> engine.loadedSlugs().contains("engine-it-momentum"));

      // rising live bars after the declining warm-up: RSI climbs, the gate is trivially true
      OffsetDateTime liveBase = WARM_BASE.plusMinutes(30);
      BigDecimal price = new BigDecimal("100.10");
      for (int i = 0; i < 8; i++) {
        price = price.add(new BigDecimal("0.50"));
        publishBar("SIGTEST", liveBase.plusMinutes(i), price);
      }

      await().atMost(Duration.ofSeconds(20)).until(() -> !signals.active().isEmpty());

      SignalRepository.SignalRow row = signals.active().get(0);
      firstSignalId = row.id();
      assertThat(row.signalType()).isEqualTo("ENTRY");
      assertThat(row.side()).isEqualTo("BUY");
      assertThat(row.interval()).isEqualTo("1m");
      assertThat(row.stopLoss()).isNotNull();
      assertThat(row.target()).isNotNull();

      // renderer invariant on the persisted breakdown
      BigDecimal contributions = BigDecimal.ZERO;
      for (var entry : row.scoreBreakdown().path("indicators")) {
        if (entry.path("activated").asBoolean()) {
          contributions = contributions.add(new BigDecimal(entry.path("contribution").asText()));
        }
      }
      BigDecimal denominator = new BigDecimal(row.scoreBreakdown().path("weightDenominator").asText());
      BigDecimal reconstructed = contributions.divide(denominator, java.math.MathContext.DECIMAL64);
      assertThat(new BigDecimal(row.scoreBreakdown().path("composite").asText())
              .subtract(reconstructed).abs())
          .isLessThan(new BigDecimal("0.0000001"));

      // the channel payload carries the SAME breakdown (divergence = FAIL criterion)
      await().atMost(Duration.ofSeconds(10)).until(() -> !channelPayloads.isEmpty());
      var payload = objectMapper.readTree(channelPayloads.get(0));
      assertThat(payload.path("scoreBreakdown")).isEqualTo(row.scoreBreakdown());
      assertThat(payload.path("strategyId").asText()).isEqualTo("engine-it-momentum");
      assertThat(payload.path("version").asText()).isEqualTo("1.0.0");
      assertThat(payload.path("checksum").asText()).hasSize(64); // engine pinning triple
    } finally {
      listener.stop();
    }
  }

  @Test
  @Order(2)
  void hotSwapLandsAtTheNextBarBoundary() {
    String tightened = STRATEGY_YAML.replace("threshold: 0.2", "threshold: 0.95");
    registryService.update(strategyId, tightened, null, "tighten");
    registryService.publish(strategyId, null, null);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> "1.0.1".equals(engine.loadedVersions().get("engine-it-momentum")));
    assertThat(engine.loadedVersions().get("engine-it-momentum")).isEqualTo("1.0.1");
  }

  @Test
  @Order(3)
  void takenAndDismissTransitionsWork() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/signals/" + firstSignalId + "/taken"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("TAKEN"));

    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/signals/" + firstSignalId + "/dismiss"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("DISMISSED"));

    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/signals/active"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(
            MockMvcResultMatchers.jsonPath(
                    "$.items[?(@.id == " + firstSignalId + ")]")
                .isEmpty());

    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/signals?limit=10"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.items").isNotEmpty());

    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/signals/99999999"))
        .andExpect(MockMvcResultMatchers.status().isNotFound());
  }

  private void publishBar(String tradingsymbol, OffsetDateTime bucket, BigDecimal close)
      throws Exception {
    Map<String, Object> bar = new java.util.LinkedHashMap<>();
    bar.put("exchange", "NSE");
    bar.put("tradingsymbol", tradingsymbol);
    bar.put("interval", "1m");
    bar.put("bucket", bucket.toString());
    bar.put("open", close.subtract(new BigDecimal("0.10")).toPlainString());
    bar.put("high", close.add(new BigDecimal("0.10")).toPlainString());
    bar.put("low", close.subtract(new BigDecimal("0.20")).toPlainString());
    bar.put("close", close.toPlainString());
    bar.put("volume", 750);
    bar.put("oi", null);
    bar.put("source", "MOCK");
    redis.convertAndSend(
        "candles.1m.NSE." + tradingsymbol, objectMapper.writeValueAsString(bar));
  }
}
