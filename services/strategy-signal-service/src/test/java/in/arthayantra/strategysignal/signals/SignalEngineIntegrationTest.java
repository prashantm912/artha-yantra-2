package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategysignal.registry.MarketDataInstrumentClient;
import in.arthayantra.strategysignal.registry.RegistryService;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
  private static final String COLD_START_UNDERLYING = "ENGINE-COLDSTART";
  private static final String COLD_START_RETRY_UNDERLYING = "ENGINE-COLDSTART-RETRY";
  private static final String COLD_START_PARTIAL_A_UNDERLYING = "ENGINE-COLDSTART-PARTIAL-A";
  private static final String COLD_START_PARTIAL_B_UNDERLYING = "ENGINE-COLDSTART-PARTIAL-B";
  private static final String COLD_START_BOOT_KEY_UNDERLYING = "ENGINE-COLDSTART-BOOT-KEY";
  private static final AtomicBoolean FUTURES_UNIVERSE_AVAILABLE = new AtomicBoolean();

  // intentional load-time capture: one fixed warm-up anchor shared by the stub and the bars
  @SuppressWarnings("TimeInStaticInitializer")
  private static final OffsetDateTime WARM_BASE =
      OffsetDateTime.now(IST).truncatedTo(ChronoUnit.MINUTES).minusMinutes(90);
  private static final OffsetDateTime LIVE_NOW = WARM_BASE.plusMinutes(90);

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
    FuturesUniverseResolver stubFuturesResolver() {
      FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
      when(resolver.resolve(anyString(), anyString(), anyString(), anyInt()))
          .thenAnswer(
              invocation -> {
                boolean available = FUTURES_UNIVERSE_AVAILABLE.get();
                return available
                    ? List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"))
                    : List.of();
              });
      return resolver;
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(LIVE_NOW.toInstant(), ZoneOffset.UTC);
    }

    @Bean
    @Primary
    MarketDataCandlesClient stubCandles(
        org.springframework.web.client.RestClient.Builder builder, ObjectMapper objectMapper) {
      return new MarketDataCandlesClient(builder, objectMapper, "http://127.0.0.1:1", 10_000) {
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

  /** A SWING strategy the tick engine deliberately SKIPS at load (session.style=swing) — used to
   *  regression-guard the reconcile: published (incl. this) must equal the reload snapshot even though
   *  it is not in the LOADED set, so the 20s reconcile must NOT see drift (else it loops — #579). */
  private static final String SWING_YAML =
      """
      schema: strategy-schema/v1
      id: engine-it-swing
      name: "Engine IT Swing"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: SWINGTEST }
      timeframes: { primary: 1d }
      indicators:
        - { name: RSI, alias: rsi_1d, timeframe: 1d, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        gate:
          all:
            - "close > 1"
        scoring: { threshold: 0.2 }
      exit_rules:
        - { type: stop_loss, params: { basis: percent, value: 8 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: swing }
      """;

  private static final String COLD_START_YAML =
      STRATEGY_YAML
          .replace("id: engine-it-momentum", "id: engine-it-coldstart")
          .replace("name: \"Engine IT Momentum\"", "name: \"Engine IT Coldstart\"")
          .replace(
              "universe:\n  mode: explicit\n  instruments:\n    - { exchange: NSE, tradingsymbol: SIGTEST }\n",
              "universe:\n  mode: futures_of_underlying\n  underlying: { exchange: NSE, tradingsymbol: "
                  + COLD_START_UNDERLYING
                  + " }\n  futures: { contract: front_month, roll_days_before_expiry: 1 }\n");

  private static final String COLD_START_RETRY_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-coldstart-retry")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Coldstart Retry\"")
          .replace(COLD_START_UNDERLYING, COLD_START_RETRY_UNDERLYING);

  private static final String COLD_START_PARTIAL_A_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-coldstart-partial-a")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Coldstart Partial A\"")
          .replace(COLD_START_UNDERLYING, COLD_START_PARTIAL_A_UNDERLYING);

  private static final String COLD_START_PARTIAL_B_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-coldstart-partial-b")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Coldstart Partial B\"")
          .replace(COLD_START_UNDERLYING, COLD_START_PARTIAL_B_UNDERLYING);

  private static final String COLD_START_BOOT_KEY_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-coldstart-boot-key")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Coldstart Boot Key\"")
          .replace(COLD_START_UNDERLYING, COLD_START_BOOT_KEY_UNDERLYING);

  @Autowired private RegistryService registryService;
  @Autowired private StrategyRepository repository;
  @Autowired private SignalEngine engine;
  @Autowired private SignalRepository signals;
  @Autowired private StringRedisTemplate redis;
  @Autowired private RedisConnectionFactory connectionFactory;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meterRegistry;

  private static UUID strategyId;
  private static long firstSignalId;

  /**
   * The CONNECTED retry now converges on "no unresolved universes", so a test that publishes
   * CONNECTED while the stub universe is unavailable leaves a REAL retry chain running on the shared
   * autowired engine. Shorten it so those attempts cannot bleed reloads (each clearing bankCache and
   * rebuilding the listener container) into later @Order methods.
   */
  @BeforeEach
  void shortenKiteConnectedRetry() {
    engine.kiteConnectedReloadDelayMillis = 500L;
    // Every kite.status publish in this class also reaches the SHARED autowired engine, and a retry
    // chain now outlives its test while universes stay unresolved. Drain any leftover chain so its
    // reloads cannot land mid-assertion in the next @Order method.
    await().atMost(Duration.ofSeconds(20)).until(() -> !engine.kiteConnectedReloadInFlight());
  }

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

      // scope to THIS test's instrument — the IT DB is shared with no per-method cleanup, so other
      // methods/classes may leave their own active signals newer than ours (CLAUDE.md IT contract).
      await()
          .atMost(Duration.ofSeconds(20))
          .until(() -> signals.active().stream().anyMatch(s -> "SIGTEST".equals(s.tradingsymbol())));

      SignalRepository.SignalRow row =
          signals.active().stream()
              .filter(s -> "SIGTEST".equals(s.tradingsymbol()))
              .findFirst()
              .orElseThrow();
      firstSignalId = row.id();
      assertThat(row.signalType()).isEqualTo("ENTRY");
      assertThat(row.side()).isEqualTo("BUY");
      assertThat(row.interval()).isEqualTo("1m");
      assertThat(row.stopLoss()).isNotNull();
      assertThat(row.target()).isNotNull();

      Map<String, Object> latencyStamp =
          jdbc.queryForMap(
              "SELECT generated_at, emitted_at, emit_latency_ms FROM signals WHERE id = ?",
              row.id());
      assertThat(((java.sql.Timestamp) latencyStamp.get("generated_at")).toInstant())
          .as("latency instrumentation never rewrites the deterministic bar-bucket instant")
          .isEqualTo(row.generatedAt().toInstant());
      assertThat(((java.sql.Timestamp) latencyStamp.get("emitted_at")).toInstant())
          .isEqualTo(LIVE_NOW.toInstant());
      assertThat(latencyStamp.get("emit_latency_ms")).isEqualTo(0L);
      assertThat(meterRegistry.find("ay_signal_bar_to_emit_seconds").timer())
          .isNotNull()
          .extracting(timer -> timer.count())
          .isEqualTo(1L);

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
      // M17: the frame carries the paper book (Books.fromTags) so a book-filtered live view can drop
      // a frame for another book — no family tag on this strategy → OTHER.
      assertThat(payload.path("book").asText()).isEqualTo(Books.OTHER);

      // generated_at is the entry BAR's bucket instant — deterministic, in the live-bar window
      // (NOT wall-clock now(), which is ~60 min ahead) — and the row and channel payload agree.
      assertThat(row.generatedAt().toInstant())
          .as("generated_at is bar-aligned, not wall-clock")
          .isBetween(liveBase.toInstant(), liveBase.plusMinutes(8).toInstant());
      assertThat(OffsetDateTime.parse(payload.path("generatedAt").asText()).toInstant())
          .as("row and channel carry the identical generated_at instant")
          .isEqualTo(row.generatedAt().toInstant());
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

  @Test
  @Order(4)
  void reconcileConvergesWhenASkippedSwingStrategyIsPublished() {
    // Regression for #579: a published+enabled SWING strategy is deliberately SKIPPED by the tick
    // engine at load (session.style=swing → the daily batch owns it). It is therefore in the published
    // set but NOT the loaded set. The 20s reconcile must compare the published set against the
    // last-reload SNAPSHOT (which includes it), NOT the loaded subset — otherwise loaded < published
    // reads as perpetual "drift" and the engine reloads all strategies every 20s forever.
    UUID swingId = (UUID) registryService.create("Engine IT Swing", null, null, SWING_YAML).get("id");
    registryService.publish(swingId, null, null);
    engine.reload();

    assertThat(engine.loadedVersions()).doesNotContainKey("engine-it-swing"); // skipped, by design
    assertThat(engine.publishedSetDrifted()).isFalse(); // ...yet the reconcile sees NO drift → no loop
  }

  @Test
  @Order(5)
  void togglingEnabledArmsDisarmsTheEngineWritesAuditAndReconcileConverges() {
    // Phase-2 slice D (app-platform audit §2.7): the enabled-toggle is the master kill-switch the
    // reconcile filters on (enabled && publishedVersionId). Trace: publish → loaded → DISABLE →
    // audit row + unloaded + reconcile CONVERGES (must not regress the #579 loop) → ENABLE → reloaded.
    UUID id =
        (UUID)
            registryService
                .create(
                    "Engine IT Toggle", null, null,
                    STRATEGY_YAML
                        .replace("id: engine-it-momentum", "id: engine-it-toggle")
                        .replace("name: \"Engine IT Momentum\"", "name: \"Engine IT Toggle\""))
                .get("id");
    registryService.publish(id, null, null);
    engine.reload();
    assertThat(engine.loadedSlugs()).contains("engine-it-toggle"); // enabled + published → loaded

    // DISABLE via the real service path: writes a DISABLE audit row + emits strategy.changed.
    registryService.setEnabled(id, false);
    assertThat(repository.auditLog(id, 50, 0))
        .extracting(StrategyRepository.AuditRow::action)
        .contains("DISABLE");
    // Deterministic reconcile: reload reads the COMMITTED enabled=false, the filter drops it, and the
    // drift predicate converges against the fresh snapshot (loaded==published==without-it — no #579 loop).
    engine.reload();
    assertThat(engine.loadedSlugs()).doesNotContain("engine-it-toggle");
    assertThat(engine.publishedSetDrifted()).isFalse();

    // RE-ENABLE reloads it back and again converges.
    registryService.setEnabled(id, true);
    assertThat(repository.auditLog(id, 50, 0))
        .extracting(StrategyRepository.AuditRow::action)
        .contains("ENABLE");
    engine.reload();
    assertThat(engine.loadedSlugs()).contains("engine-it-toggle");
    assertThat(engine.publishedSetDrifted()).isFalse();
  }

  @Test
  @Order(6)
  void kiteConnectedRetriesUntilAColdStartUniverseBecomesAvailable() {
    FUTURES_UNIVERSE_AVAILABLE.set(false);
    UUID id =
        (UUID)
            registryService
                .create("Engine IT Coldstart Retry", null, null, COLD_START_RETRY_YAML)
                .get("id");
    registryService.publish(id, null, null);
    StrategyRepository.StrategyRow strategy = repository.findById(id).orElseThrow();
    StrategyRepository.VersionRow version =
        repository.findVersionById(strategy.publishedVersionId()).orElseThrow();
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(strategy));
    when(isolatedRegistry.findVersionById(version.id())).thenReturn(Optional.of(version));
    AtomicInteger isolatedResolutionCount = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              boolean available = FUTURES_UNIVERSE_AVAILABLE.get();
              isolatedResolutionCount.incrementAndGet();
              return available
                  ? List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"))
                  : List.of();
            });
    SignalEngine isolatedEngine =
        new SignalEngine(
            isolatedRegistry,
            mock(SignalRepository.class),
            mock(SignalPublisher.class),
            mock(org.springframework.context.ApplicationEventPublisher.class),
            mock(LiveSeriesStore.class),
            isolatedResolver,
            connectionFactory,
            objectMapper,
            Clock.fixed(LIVE_NOW.toInstant(), ZoneOffset.UTC),
            meterRegistry,
            Optional.empty(),
            Optional.empty(),
            mock(SignalRejectionRepository.class),
            mock(ShadowBookService.class),
            mock(org.springframework.transaction.PlatformTransactionManager.class),
            60);
    isolatedEngine.kiteConnectedReloadDelayMillis = 500L;

    try {
      isolatedEngine.start();
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
      int resolutionsBeforeConnected = isolatedResolutionCount.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      await()
          .atMost(Duration.ofSeconds(20))
          .until(() -> isolatedResolutionCount.get() > resolutionsBeforeConnected);
      assertThat(isolatedEngine.loadedSlugs()).doesNotContain("engine-it-coldstart-retry");

      int resolutionsAfterUnavailableAttempt = isolatedResolutionCount.get();
      FUTURES_UNIVERSE_AVAILABLE.set(true);

      await()
          .atMost(Duration.ofSeconds(20))
          .until(
              () ->
                  isolatedResolutionCount.get() > resolutionsAfterUnavailableAttempt
                      && isolatedEngine.loadedSlugs().contains("engine-it-coldstart-retry"));
    } finally {
      isolatedEngine.stop();
    }
  }

  @Test
  @Order(7)
  void kiteConnectedReloadsStrategiesSkippedDuringColdStartButTokenExpiredDoesNot() {
    FUTURES_UNIVERSE_AVAILABLE.set(false);
    UUID id =
        (UUID)
            registryService.create("Engine IT Coldstart", null, null, COLD_START_YAML).get("id");
    registryService.publish(id, null, null);
    engine.reload();

    assertThat(engine.loadedSlugs()).doesNotContain("engine-it-coldstart");
    await()
        .during(Duration.ofSeconds(1))
        .untilAsserted(
            () -> assertThat(engine.loadedSlugs()).doesNotContain("engine-it-coldstart"));

    FUTURES_UNIVERSE_AVAILABLE.set(true);
    redis.convertAndSend("kite.status", "CONNECTED");

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> engine.loadedSlugs().contains("engine-it-coldstart"));
    // Let the CONNECTED chain finish BEFORE flipping the universe away: an in-flight retry would
    // re-reload against the now-unavailable stub and drop the strategy, so the TOKEN_EXPIRED
    // assertion below would be racing this test's own retry rather than testing TOKEN_EXPIRED.
    await().atMost(Duration.ofSeconds(20)).until(() -> !engine.kiteConnectedReloadInFlight());

    FUTURES_UNIVERSE_AVAILABLE.set(false);
    redis.convertAndSend("kite.status", "TOKEN_EXPIRED");

    await()
        .during(Duration.ofSeconds(2))
        .untilAsserted(
            () -> assertThat(engine.loadedSlugs()).contains("engine-it-coldstart"));

    // The first CONNECTED rebuilt the listener container; a later CONNECTED must reach its
    // replacement as well, even when the universe is empty again.
    redis.convertAndSend("kite.status", "CONNECTED");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> !engine.loadedSlugs().contains("engine-it-coldstart"));
    await()
        .during(Duration.ofSeconds(1))
        .untilAsserted(
            () -> assertThat(engine.loadedSlugs()).doesNotContain("engine-it-coldstart"));
  }

  /**
   * Regression: the retry must converge on "every universe resolved", NOT on "something loaded". A
   * breaker that re-opens partway through a reload leaves a PARTIAL set loaded — a non-empty
   * {@code loaded} that is really a degraded session. Stopping there would strand the rest for the
   * whole session, turning a loud total outage into a silent partial one. Fails against a
   * {@code !loaded.isEmpty()} predicate: A loads on attempt 1 and the chain would stop with B dead.
   */
  @Test
  @Order(8)
  void kiteConnectedKeepsRetryingWhenOnlySomeUniversesResolved() {
    AtomicBoolean partialBAvailable = new AtomicBoolean(false);
    StrategyRepository.StrategyRow rowA = publishedRow(COLD_START_PARTIAL_A_YAML, "Partial A");
    StrategyRepository.StrategyRow rowB = publishedRow(COLD_START_PARTIAL_B_YAML, "Partial B");
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(rowA, rowB));
    when(isolatedRegistry.findVersionById(rowA.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowA.publishedVersionId()));
    when(isolatedRegistry.findVersionById(rowB.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowB.publishedVersionId()));

    // A always resolves; B only once the (simulated) breaker lets its call through.
    AtomicInteger bResolutionAttempts = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              String underlying = invocation.getArgument(1);
              if (COLD_START_PARTIAL_A_UNDERLYING.equals(underlying)) {
                return List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"));
              }
              bResolutionAttempts.incrementAndGet();
              return partialBAvailable.get()
                  ? List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"))
                  : List.of();
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry, isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 500L;
    try {
      isolatedEngine.start();
      // The partial state: A up, B dropped — `loaded` is NON-EMPTY but the session is degraded.
      assertThat(isolatedEngine.loadedSlugs()).containsExactly("engine-it-coldstart-partial-a");

      int bAttemptsAtBoot = bResolutionAttempts.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      // B must stay unavailable until CONNECTED's FIRST attempt has been and gone — otherwise that
      // attempt loads B itself and the test would pass without the retry ever running (it would then
      // pass against the old `!loaded.isEmpty()` predicate too, proving nothing).
      await().atMost(Duration.ofSeconds(20)).until(() -> bResolutionAttempts.get() > bAttemptsAtBoot);
      assertThat(isolatedEngine.loadedSlugs()).containsExactly("engine-it-coldstart-partial-a");

      partialBAvailable.set(true);

      // No second CONNECTED is ever published: only a RETRY can pick B up. The old predicate stopped
      // the chain at attempt 1 (A was loaded ⇒ "success"), so B would stay dead and this times out.
      await()
          .atMost(Duration.ofSeconds(20))
          .until(
              () ->
                  isolatedEngine
                      .loadedSlugs()
                      .containsAll(
                          List.of("engine-it-coldstart-partial-a", "engine-it-coldstart-partial-b")));
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * The CONNECTED channel is edge-only and Redis pub/sub is fire-and-forget, so a CONNECTED
   * published while this engine was still booting is lost forever — and none ever follows, because
   * the state is already CONNECTED. start() must therefore fall back to the level-triggered
   * {@code kite:session:status} KEY. Without this test that path is entirely unexercised: a typo in
   * the key literal would silently disable it and only a live cold boot would ever notice.
   */
  @Test
  @Order(9)
  void bootReadsTheKiteStatusKeyWhenTheConnectedPublishWasMissed() {
    FUTURES_UNIVERSE_AVAILABLE.set(false);
    StrategyRepository.StrategyRow row = publishedRow(COLD_START_BOOT_KEY_YAML, "Boot Key");
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(row));
    when(isolatedRegistry.findVersionById(row.publishedVersionId()))
        .thenReturn(repository.findVersionById(row.publishedVersionId()));
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                FUTURES_UNIVERSE_AVAILABLE.get()
                    ? List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"))
                    : List.of());

    // The session is ALREADY CONNECTED before this engine exists — the edge is long gone.
    redis.opsForValue().set("kite:session:status", "CONNECTED");
    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry, isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 500L;
    try {
      isolatedEngine.start();
      // Boot resolved nothing, so start() must have read the KEY and armed a retry off it alone.
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
      FUTURES_UNIVERSE_AVAILABLE.set(true);

      // No CONNECTED is ever published on the channel: only the key-triggered chain can load this.
      await()
          .atMost(Duration.ofSeconds(20))
          .until(() -> isolatedEngine.loadedSlugs().contains("engine-it-coldstart-boot-key"));
    } finally {
      isolatedEngine.stop();
      redis.delete("kite:session:status");
    }
  }

  private StrategyRepository.StrategyRow publishedRow(String yaml, String name) {
    UUID id = (UUID) registryService.create("Engine IT Coldstart " + name, null, null, yaml).get("id");
    registryService.publish(id, null, null);
    return repository.findById(id).orElseThrow();
  }

  private SignalEngine isolatedEngine(
      StrategyRepository isolatedRegistry, FuturesUniverseResolver isolatedResolver) {
    return new SignalEngine(
        isolatedRegistry,
        mock(SignalRepository.class),
        mock(SignalPublisher.class),
        mock(org.springframework.context.ApplicationEventPublisher.class),
        mock(LiveSeriesStore.class),
        isolatedResolver,
        connectionFactory,
        objectMapper,
        Clock.fixed(LIVE_NOW.toInstant(), ZoneOffset.UTC),
        meterRegistry,
        Optional.empty(),
        Optional.empty(),
        mock(SignalRejectionRepository.class),
        mock(ShadowBookService.class),
        mock(org.springframework.transaction.PlatformTransactionManager.class),
        60);
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
