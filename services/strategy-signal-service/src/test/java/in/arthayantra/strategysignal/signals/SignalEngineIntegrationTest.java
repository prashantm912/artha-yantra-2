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
import io.micrometer.core.instrument.Counter;
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
import org.springframework.test.util.ReflectionTestUtils;

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
  private static final String COLD_START_EMPTY_UNDERLYING = "ENGINE-COLDSTART-EMPTY";
  private static final String COLD_START_RETRY_UNDERLYING = "ENGINE-COLDSTART-RETRY";
  private static final String COLD_START_PARTIAL_A_UNDERLYING = "ENGINE-COLDSTART-PARTIAL-A";
  private static final String COLD_START_PARTIAL_B_UNDERLYING = "ENGINE-COLDSTART-PARTIAL-B";
  private static final String COLD_START_BOOT_KEY_UNDERLYING = "ENGINE-COLDSTART-BOOT-KEY";
  private static final String KEEP_BEST_A_UNDERLYING = "ENGINE-KEEPBEST-A";
  private static final String KEEP_BEST_B_UNDERLYING = "ENGINE-KEEPBEST-B";
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
                    ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                    : Optional.empty();
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
  /** chip task_37ee83e0 Σ-invariant fixture: (id, name, tradingsymbol, gate rule). */
  private static final String SUM_YAML =
      """
      schema: strategy-schema/v1
      id: %s
      name: "%s"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: %s }
      timeframes: { primary: 1m }
      indicators:
        - { name: RSI, alias: rsi_1m, timeframe: 1m, params: { period: 14 }, weight: 1.0,
            normalize: { type: rsi_momentum } }
      entry_rules:
        direction: long
        gate:
          all:
            - "%s"
        scoring: { threshold: 0.2 }
      exit_rules:
        - { type: stop_loss, params: { basis: premium_pct, value: 20 } }
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

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

  private static final String COLD_START_EMPTY_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-coldstart-empty")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Coldstart Empty\"")
          .replace(COLD_START_UNDERLYING, COLD_START_EMPTY_UNDERLYING);

  private static final String UNSUPPORTED_LIVE_UNIVERSE_YAML =
      STRATEGY_YAML
          .replace("id: engine-it-momentum", "id: engine-it-index-constituents")
          .replace("name: \"Engine IT Momentum\"", "name: \"Engine IT Index Constituents\"")
          .replace(
              "universe:\n  mode: explicit\n  instruments:\n    - { exchange: NSE, tradingsymbol: SIGTEST }\n",
              "universe:\n  mode: index_constituents\n  index: NIFTY 50\n");

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

  private static final String KEEP_BEST_A_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-keepbest-a")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Keepbest A\"")
          .replace(COLD_START_UNDERLYING, KEEP_BEST_A_UNDERLYING);

  private static final String KEEP_BEST_B_YAML =
      COLD_START_YAML
          .replace("id: engine-it-coldstart", "id: engine-it-keepbest-b")
          .replace("name: \"Engine IT Coldstart\"", "name: \"Engine IT Keepbest B\"")
          .replace(COLD_START_UNDERLYING, KEEP_BEST_B_UNDERLYING);

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

      // chip task_37ee83e0: the per-outcome counters are wired on the LIVE eval path (not just the
      // classifier), and EVERY tag is pre-registered at boot — a not-yet-happened outcome must
      // scrape as 0, because a MISSING "fired" series would again be indistinguishable from an
      // engine that never evaluated (the four-states-one-signature ambiguity this chip closes).
      assertThat(meterRegistry.find("ay_signal_eval_outcome_total").counters())
          .as("every outcome tag exists from boot, even at zero")
          .hasSize(SignalEngine.Outcome.values().length);
      Counter fired =
          meterRegistry.find("ay_signal_eval_outcome_total").tag("outcome", "fired").counter();
      assertThat(fired).isNotNull();
      assertThat(fired.count())
          .as("the bar that emitted the signal above counted as an outcome")
          .isGreaterThanOrEqualTo(1.0);

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
  void takenTransitionWorksAndDismissingATakenAnchorIsRefused() throws Exception {
    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/signals/" + firstSignalId + "/taken"))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("TAKEN"));

    // task_6f1372da: this method used to assert TAKEN→dismiss returned 200 DISMISSED — it PINNED the
    // defect. A TAKEN anchor holds an open paper position, and the engine resolves that position's exit
    // ONLY through an ACTIVE/TAKEN entry (SignalRepository.activeEntry:166-178), so discarding the
    // anchor strands the position un-exitable forever. Dismiss is legal from ACTIVE only; the row keeps
    // its status. The happy-path dismiss (from ACTIVE) is covered by
    // PaperManualTicketAnchorIntegrationTest.dismissingAnActiveSignalStillWorks.
    mockMvc
        .perform(MockMvcRequestBuilders.post("/api/v1/signals/" + firstSignalId + "/dismiss"))
        .andExpect(MockMvcResultMatchers.status().isUnprocessableEntity())
        .andExpect(MockMvcResultMatchers.jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(MockMvcResultMatchers.jsonPath("$.details.signalStatus").value("TAKEN"));

    mockMvc
        .perform(MockMvcRequestBuilders.get("/api/v1/signals/" + firstSignalId))
        .andExpect(MockMvcResultMatchers.status().isOk())
        .andExpect(MockMvcResultMatchers.jsonPath("$.status").value("TAKEN"));

    // Unchanged intent: the signal is no longer a live call. /active lists status='ACTIVE' only, so a
    // TAKEN signal drops out of it exactly as a DISMISSED one did — this assertion never depended on
    // the dismiss.
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
                  ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                  : Optional.empty();
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
    // replacement as well, even when the universe is empty again — evidenced by the retry chain
    // ARMING. The strategy being DROPPED can no longer be that evidence: keep-best now refuses to
    // install a reload that leaves the engine worse off, and a transient market-data failure
    // disarming a working engine is precisely the regression the guard exists to prevent.
    redis.convertAndSend("kite.status", "CONNECTED");
    await().atMost(Duration.ofSeconds(20)).until(() -> engine.kiteConnectedReloadInFlight());
    await().atMost(Duration.ofSeconds(30)).until(() -> !engine.kiteConnectedReloadInFlight());
    assertThat(engine.loadedSlugs()).contains("engine-it-coldstart");
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
                return Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")));
              }
              bResolutionAttempts.incrementAndGet();
              return partialBAvailable.get()
                  ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                  : Optional.empty();
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
                    ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                    : Optional.empty());

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

  @Test
  @Order(10)
  void failedUniverseResolutionIncrementsTheUnresolvedDropCounter() {
    StrategyRepository.StrategyRow failed =
        uniquePublishedRow(COLD_START_YAML, "engine-it-unresolved", "Unresolved");
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(invocation -> Optional.empty());

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(failed), isolatedResolver);
    try {
      isolatedEngine.reload();

      assertThat(ReflectionTestUtils.getField(isolatedEngine, "lastReloadUnresolvedDrops"))
          .isEqualTo(1);
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
    } finally {
      isolatedEngine.stop();
    }
  }

  @Test
  @Order(11)
  void genuineEmptyUniverseDoesNotIncrementTheUnresolvedDropCounter() {
    StrategyRepository.StrategyRow genuinelyEmpty =
        uniquePublishedRow(COLD_START_EMPTY_YAML, "engine-it-genuine-empty", "Genuine Empty");
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(invocation -> Optional.of(List.of()));

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(genuinelyEmpty), isolatedResolver);
    try {
      isolatedEngine.reload();

      assertThat(ReflectionTestUtils.getField(isolatedEngine, "lastReloadUnresolvedDrops"))
          .isEqualTo(0);
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
    } finally {
      isolatedEngine.stop();
    }
  }

  @Test
  @Order(12)
  void unsupportedLiveUniverseModeDoesNotIncrementTheUnresolvedDropCounter() {
    StrategyRepository.StrategyRow unsupported =
        uniquePublishedRow(
            UNSUPPORTED_LIVE_UNIVERSE_YAML, "engine-it-unsupported-universe", "Unsupported Universe");

    SignalEngine isolatedEngine =
        isolatedEngine(isolatedRegistry(unsupported), mock(FuturesUniverseResolver.class));
    try {
      isolatedEngine.reload();

      assertThat(ReflectionTestUtils.getField(isolatedEngine, "lastReloadUnresolvedDrops"))
          .isEqualTo(0);
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * The retry BOUND must outlive a real cold-start fault, not merely the kite-rest breaker's 30s.
   * Two live drills (2026-07-16 ~73s, 2026-07-17 ~81s for market-data to serve term-structure) both
   * recovered only on the last of 3 attempts, and the TRUE fault (an expired token — market-data
   * cannot warm its lastGood cache at all until the owner logs in) is slower still. Fails against
   * the old 3-attempt bound: this universe only comes back on the 5th chain attempt, so the chain
   * exhausts and the strategy stays dead for the session.
   */
  @Test
  @Order(13)
  void kiteConnectedRetriesOutliveAFaultLongerThanTheOldThreeAttemptBound() {
    StrategyRepository.StrategyRow row =
        uniquePublishedRow(COLD_START_YAML, "engine-it-long-fault", "Long Fault");
    AtomicInteger resolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                // boot is resolution 1, so chain attempt N is resolution N+1: the old 3-attempt
                // chain gives up at resolution 4, the widened one reaches 6 on chain attempt 5.
                resolutions.incrementAndGet() >= 6
                    ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                    : Optional.empty());

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(row), isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();

      redis.convertAndSend("kite.status", "CONNECTED");

      await().atMost(Duration.ofSeconds(30)).until(() -> isolatedEngine.loadedSlugs().size() == 1);
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * The chain must never end WORSE than not retrying at all. The terminal state used to be
   * unconditionally the LAST attempt's result, so a Kite/market-data regression across the retry
   * window could take a live engine down to 0 for the session where NOT retrying would have kept
   * its boot set (observed in vitro during the 2026-07-16 drill: boot 32 loaded, CONNECTED attempt
   * 1 = 0). Here A is loaded at boot and then regresses for the whole chain; keep-best must retain
   * it. Fails against the unguarded install: `loaded` goes empty on attempt 1 and never comes back.
   */
  @Test
  @Order(14)
  void kiteConnectedRetryNeverInstallsAReloadThatLeavesTheEngineWorseOff() {
    StrategyRepository.StrategyRow rowA =
        uniquePublishedRow(KEEP_BEST_A_YAML, "engine-it-keepbest-a", "Keepbest A");
    StrategyRepository.StrategyRow rowB =
        uniquePublishedRow(KEEP_BEST_B_YAML, "engine-it-keepbest-b", "Keepbest B");
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(rowA, rowB));
    when(isolatedRegistry.findVersionById(rowA.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowA.publishedVersionId()));
    when(isolatedRegistry.findVersionById(rowB.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowB.publishedVersionId()));

    // A resolves at boot then regresses; B never resolves, which keeps the chain armed throughout.
    AtomicBoolean aResolvable = new AtomicBoolean(true);
    AtomicInteger aResolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              if (!KEEP_BEST_A_UNDERLYING.equals(invocation.<String>getArgument(1))) {
                return Optional.empty();
              }
              aResolutions.incrementAndGet();
              return aResolvable.get()
                  ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                  : Optional.empty();
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry, isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      // The pre-retry state the engine must never fall below: A up, B dropped (degraded, not dead).
      assertThat(isolatedEngine.loadedSlugs()).hasSize(1);
      int aAtBoot = aResolutions.get();

      // Everything regresses BEFORE the chain runs, so every attempt computes a strictly worse set.
      aResolvable.set(false);
      redis.convertAndSend("kite.status", "CONNECTED");

      await().atMost(Duration.ofSeconds(30)).until(() -> aResolutions.get() > aAtBoot);
      await().atMost(Duration.ofSeconds(30)).until(() -> !isolatedEngine.kiteConnectedReloadInFlight());

      // The chain exhausted having only ever computed WORSE results — A must still be loaded.
      assertThat(isolatedEngine.loadedSlugs()).hasSize(1);
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * The chain must NEVER report success while every strategy is failing to load. A drop is counted
   * only for a resolution FAILURE, so a reload where every strategy THROWS reports "0 loaded, 0
   * unresolved" — and an `unresolvedDrops == 0` success predicate reads that as "resolved every
   * universe — retry complete" over a completely dead engine. That is the 2026-07-15/16 outage's
   * exact signature, and the shape a previous fix on this path silently re-issued while logging
   * success. Fails against that predicate: the chain stops after ONE attempt, so the strategy
   * becoming loadable later is never picked up.
   */
  @Test
  @Order(15)
  void kiteConnectedRetryNeverReportsSuccessWhileEveryStrategyFailsToLoad() {
    StrategyRepository.StrategyRow row =
        uniquePublishedRow(COLD_START_EMPTY_YAML, "engine-it-zero-success", "Zero Success");
    AtomicBoolean loadFails = new AtomicBoolean(true);
    AtomicInteger resolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              resolutions.incrementAndGet();
              if (loadFails.get()) {
                // A load failure: counted by NEITHER loadedCount NOR unresolvedDrops.
                throw new IllegalStateException("simulated load failure");
              }
              return Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")));
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(row), isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      // The trap's exact state: nothing loaded, yet nothing counted as a drop either.
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
      assertThat(ReflectionTestUtils.getField(isolatedEngine, "lastReloadUnresolvedDrops"))
          .isEqualTo(0);

      int atBoot = resolutions.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      // Despite 0 drops, the chain must NOT declare success — every strategy is failing.
      await().atMost(Duration.ofSeconds(30)).until(() -> resolutions.get() >= atBoot + 2);

      loadFails.set(false);
      await().atMost(Duration.ofSeconds(30)).until(() -> isolatedEngine.loadedSlugs().size() == 1);
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * CRITICAL-1 regression: keep-best must judge IDENTITY, not counts. A reload that swaps which
   * strategy loaded is "1 loaded / 1 unresolved" either way, so a count-based guard installs it —
   * removing A from `loaded`, which is the only thing exit evaluation walks, so A's open position
   * loses its exits for the session.
   *
   * <p>Both halves matter, and per-strategy reconciliation is the only thing that gets both: A
   * FAILED, so it keeps its last-good entry; B SUCCEEDED, so its load installs on its own merit.
   * Fails against a count comparison (A is silently replaced by B) and equally against
   * whole-reload suppression (A survives, but B's legitimate load is vetoed by A's fault).
   *
   * <p>Asserts exact ORDER, not membership: `loaded` order is evaluation order (onClosedBar
   * iterates it) and strategies sharing a book compete for one RiskService MAX_OPEN slot, so
   * whichever evaluates first takes it. The reconciled order must therefore be indistinguishable
   * from a clean reload of the same registry — registry order, A before B. Appending retained
   * entries after the successes yields B,A and changes which strategy trades.
   */
  @Test
  @Order(16)
  void keepBestRetainsAFailedStrategyWhileAnotherStrategySucceeds() {
    StrategyRepository.StrategyRow rowA =
        uniquePublishedRow(KEEP_BEST_A_YAML, "engine-it-swap-a", "Swap A");
    StrategyRepository.StrategyRow rowB =
        uniquePublishedRow(KEEP_BEST_B_YAML, "engine-it-swap-b", "Swap B");
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(rowA, rowB));
    when(isolatedRegistry.findVersionById(rowA.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowA.publishedVersionId()));
    when(isolatedRegistry.findVersionById(rowB.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowB.publishedVersionId()));

    // Phase 1 (boot): A resolves, B fails. Phase 2: exactly inverted — same counts, swapped identity.
    AtomicBoolean swapped = new AtomicBoolean(false);
    AtomicInteger resolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              resolutions.incrementAndGet();
              boolean isA = KEEP_BEST_A_UNDERLYING.equals(invocation.<String>getArgument(1));
              return isA != swapped.get()
                  ? Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")))
                  : Optional.empty();
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry, isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      assertThat(isolatedEngine.loadedSlugs()).containsExactly(rowA.slug());

      swapped.set(true);
      int atBoot = resolutions.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      await().atMost(Duration.ofSeconds(30)).until(() -> resolutions.get() > atBoot);
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> !isolatedEngine.kiteConnectedReloadInFlight());

      // Same count, different identity: A must NOT have been swapped out for B (its own load
      // failed ⇒ last-good retained), and B must NOT have been vetoed by A's fault (it succeeded).
      // Exact order — registry order, as a clean reload would produce. A retained entry appended
      // after the successes would give [B, A] and hand B the shared per-book MAX_OPEN slot.
      assertThat(isolatedEngine.loadedSlugs()).containsExactly(rowA.slug(), rowB.slug());
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * CRITICAL-2 regression: a universe that RESOLVES to empty is a legitimate stand-aside (a
   * futures_screener whose movers screen picked nobody), NOT a fault. It must INSTALL — loading
   * nothing is the correct outcome. Treating it as unhealthy makes keep-best suppress it, and the
   * engine then keeps trading YESTERDAY's universe, which is worse than the bug keep-best fixes.
   * Fails against a `liveCandidates > 0 && loaded == 0 ⇒ unhealthy` predicate: the stale set stays.
   */
  @Test
  @Order(17)
  void aResolvedEmptyUniverseInstallsAsAStandAsideAndIsNeverSuppressed() {
    StrategyRepository.StrategyRow row =
        uniquePublishedRow(COLD_START_EMPTY_YAML, "engine-it-stand-aside", "Stand Aside");
    AtomicBoolean standAside = new AtomicBoolean(false);
    AtomicInteger resolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              resolutions.incrementAndGet();
              // Present-but-empty = "resolved, nothing to trade today" (resolveScreener's contract).
              return standAside.get()
                  ? Optional.of(List.of())
                  : Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")));
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(row), isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      // The engine is holding a universe that must be RELINQUISHED when the screen empties.
      assertThat(isolatedEngine.loadedSlugs()).containsExactly(row.slug());

      standAside.set(true);
      int atBoot = resolutions.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      await().atMost(Duration.ofSeconds(30)).until(() -> resolutions.get() > atBoot);
      // Standing aside is HEALTHY, so the chain installs it and completes rather than retrying.
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> !isolatedEngine.kiteConnectedReloadInFlight());

      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * The MIXED case: keep-best must reconcile PER STRATEGY, because a fault is per strategy.
   *
   * <p>A screener resolving to empty is a legitimate stand-aside and must install even while an
   * UNRELATED strategy has a genuine fault. Suppressing the whole reload (the round-2 shape) let
   * one broken strategy veto every other strategy's update: the aggregate outcome read unhealthy,
   * the reload was dropped entirely, and the standing-aside screener kept its stale universe in
   * {@code loaded} — where {@code onClosedBar} would go on trading yesterday's movers until the
   * unrelated fault cleared.
   *
   * <p>Asserts BOTH halves. A alone would pass under a plain "install everything" that reintroduces
   * the original chip-2 bug; B alone would pass under whole-reload suppression. Only per-strategy
   * reconciliation satisfies both at once.
   */
  @Test
  @Order(20)
  void keepBestReconcilesPerStrategySoAStandAsideLandsDespiteAnUnrelatedFault() {
    StrategyRepository.StrategyRow rowA =
        uniquePublishedRow(KEEP_BEST_A_YAML, "engine-it-mixed-a", "Mixed A");
    StrategyRepository.StrategyRow rowB =
        uniquePublishedRow(KEEP_BEST_B_YAML, "engine-it-mixed-b", "Mixed B");
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(rowA, rowB));
    when(isolatedRegistry.findVersionById(rowA.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowA.publishedVersionId()));
    when(isolatedRegistry.findVersionById(rowB.publishedVersionId()))
        .thenReturn(repository.findVersionById(rowB.publishedVersionId()));

    // Boot: both load. Then A stands aside (RESOLVED_EMPTY, legitimate) while B genuinely FAILS.
    AtomicBoolean mixedPhase = new AtomicBoolean(false);
    AtomicInteger resolutions = new AtomicInteger();
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              resolutions.incrementAndGet();
              boolean isA = KEEP_BEST_A_UNDERLYING.equals(invocation.<String>getArgument(1));
              if (!mixedPhase.get()) {
                return Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")));
              }
              return isA ? Optional.of(List.of()) : Optional.empty();
            });

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry, isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      assertThat(isolatedEngine.loadedSlugs()).containsExactlyInAnyOrder(rowA.slug(), rowB.slug());

      mixedPhase.set(true);
      int atBoot = resolutions.get();
      redis.convertAndSend("kite.status", "CONNECTED");

      await().atMost(Duration.ofSeconds(30)).until(() -> resolutions.get() > atBoot);
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> !isolatedEngine.kiteConnectedReloadInFlight());

      // A stood aside on its OWN merit — its stale universe must be GONE, not vetoed by B's fault.
      // B genuinely failed — its last-good entry must be RETAINED, or this is the chip-2 bug again.
      assertThat(isolatedEngine.loadedSlugs()).containsExactly(rowB.slug());
    } finally {
      isolatedEngine.stop();
    }
  }

  /**
   * CRITICAL-3 regression: the boot gate must arm from the load's retryable-failure state, not from
   * `drops > 0`. A boot where every strategy THROWS yields 0 loaded / 0 unresolved, so a drops-only
   * gate never arms the chain, the reconcile sees no registry drift, and the engine stays dead all
   * session — the 2026-07-16 shape. Deliberately drives the boot-KEY path ONLY: the session is
   * already CONNECTED before the engine exists and no channel edge is ever published, because the
   * boot-KEY path is the one that broke. Fails against the `drops > 0` gate: nothing ever arms.
   */
  @Test
  @Order(18)
  void bootArmsTheRetryFromTheKiteStatusKeyWhenEveryStrategyFailsToLoad() {
    StrategyRepository.StrategyRow row =
        uniquePublishedRow(COLD_START_BOOT_KEY_YAML, "engine-it-boot-loaderr", "Boot Load Error");
    AtomicBoolean loadFails = new AtomicBoolean(true);
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation -> {
              if (loadFails.get()) {
                throw new IllegalStateException("simulated boot load failure");
              }
              return Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST")));
            });

    // Already CONNECTED before this engine exists — the channel edge is long gone.
    redis.opsForValue().set("kite:session:status", "CONNECTED");
    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(row), isolatedResolver);
    isolatedEngine.kiteConnectedReloadDelayMillis = 200L;
    try {
      isolatedEngine.start();
      // 0 loaded AND 0 unresolved — the state a drops-only gate reads as "nothing to retry".
      assertThat(isolatedEngine.loadedSlugs()).isEmpty();
      assertThat(ReflectionTestUtils.getField(isolatedEngine, "lastReloadUnresolvedDrops"))
          .isEqualTo(0);

      loadFails.set(false);

      // No CONNECTED is ever published on the channel: only a chain armed from the KEY can load it.
      await().atMost(Duration.ofSeconds(30)).until(() -> isolatedEngine.loadedSlugs().size() == 1);
    } finally {
      isolatedEngine.stop();
      redis.delete("kite:session:status");
    }
  }

  /**
   * MAJOR-4: a structurally identical reload must not cold-start every indicator bank. Equal
   * unhealthy outcomes still install, and installing clears bankCache — recomputing recursive ta4j
   * indicators from bar 0 in BigDecimal math on the single eval thread. Widening the chain to 8
   * attempts would drive that up to 8 times per CONNECTED on a persistently degraded engine.
   */
  @Test
  @Order(19)
  @SuppressWarnings("unchecked")
  void anIdenticalReloadRetainsTheWarmIndicatorBanks() {
    StrategyRepository.StrategyRow row =
        uniquePublishedRow(COLD_START_YAML, "engine-it-bank-retain", "Bank Retain");
    FuturesUniverseResolver isolatedResolver = mock(FuturesUniverseResolver.class);
    when(isolatedResolver.resolve(anyString(), anyString(), anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                Optional.of(List.of(new StrategyDefinition.InstrumentRef("NSE", "SIGTEST"))));

    SignalEngine isolatedEngine = isolatedEngine(isolatedRegistry(row), isolatedResolver);
    try {
      isolatedEngine.start();
      assertThat(isolatedEngine.loadedSlugs()).containsExactly(row.slug());

      Map<String, Object> banks =
          (Map<String, Object>) ReflectionTestUtils.getField(isolatedEngine, "bankCache");
      banks.put("warm-sentinel", mock(in.arthayantra.strategyengine.eval.IndicatorBank.class));

      // Nothing changed in the registry or the universe — the banks must survive.
      isolatedEngine.reload();

      assertThat(banks).containsKey("warm-sentinel");
    } finally {
      isolatedEngine.stop();
    }
  }

  private StrategyRepository.StrategyRow publishedRow(String yaml, String name) {
    UUID id = (UUID) registryService.create("Engine IT Coldstart " + name, null, null, yaml).get("id");
    registryService.publish(id, null, null);
    return repository.findById(id).orElseThrow();
  }

  /**
   * chip task_37ee83e0 — THE INVARIANT: Σ(outcome counters) == entry evaluations. If the sum could
   * undercount, "the engine performed no evaluations" would again be indistinguishable from "the
   * engine evaluated and fell through an uncounted path" — the exact ambiguity this chip closes,
   * one level up. A double-count is equally fatal, so the sum is asserted EXACT, not >=.
   *
   * <p>The instrument is unique per run: the IT DB has no cleanup and published strategies persist
   * across surefire reruns, so a fixed symbol would accumulate watchers and drift the expected sum
   * on every rerun. The stub candle client warms only SIGTEST, so this symbol starts COLD — which
   * is what walks the bars through the warming path first and then the scoreable ones.
   */
  @Test
  @Order(21)
  void everyEvaluationIsCountedExactlyOnceSoTheOutcomeSumIsTheEvaluationCount() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    String symbol = "SIGSUM" + suffix.toUpperCase(java.util.Locale.ROOT);
    String slugA = "engine-it-sum-a-" + suffix;
    String slugB = "engine-it-sum-b-" + suffix;
    // A: gate wide open, strictly declining bars ⇒ RSI→0 ⇒ composite 0 < 0.2 ⇒ never fires.
    // B: gate can never pass ⇒ chart-gate-failed. Neither fires, so no signal ever becomes active
    // and EVERY bar keeps taking the entry branch — that is what makes evaluations == bars×2.
    String yamlA = SUM_YAML.formatted(slugA, "Engine IT Sum A " + suffix, symbol, "close > 1");
    String yamlB = SUM_YAML.formatted(slugB, "Engine IT Sum B " + suffix, symbol, "close > 100000");
    UUID idA = (UUID) registryService.create("Engine IT Sum A " + suffix, null, null, yamlA).get("id");
    registryService.publish(idA, null, null);
    UUID idB = (UUID) registryService.create("Engine IT Sum B " + suffix, null, null, yamlB).get("id");
    registryService.publish(idB, null, null);

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> engine.loadedSlugs().containsAll(List.of(slugA, slugB)));

    double sumBefore = outcomeSum();
    double unscoreableBefore = outcomeCount("unscoreable-indicators-warming");
    double belowBefore = outcomeCount("composite-below-threshold");
    double gateFailedBefore = outcomeCount("chart-gate-failed");
    double firedBefore = outcomeCount("fired");

    int bars = 20;
    OffsetDateTime base = WARM_BASE.plusMinutes(30);
    BigDecimal price = new BigDecimal("120.00");
    for (int i = 0; i < bars; i++) {
      price = price.subtract(new BigDecimal("0.25"));
      publishBar(symbol, base.plusMinutes(i), price);
    }
    double expected = sumBefore + 2.0 * bars;

    // Reach the expected sum, then let any EXTRA (double-counted) increment land before asserting
    // equality — a >= assertion would go green mid-flight on an engine that counts twice.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(outcomeSum())
                    .as("Σ(outcomes) must reach %s (%d bars × 2 strategies) — a silent, uncounted"
                        + " branch shows up here as an undercount", expected, bars)
                    .isGreaterThanOrEqualTo(expected));
    await()
        .pollDelay(Duration.ofMillis(500))
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(outcomeSum())
                    .as("Σ(outcomes) == evaluations: %d bars × 2 strategies, counted exactly once", bars)
                    .isEqualTo(expected));

    // The test's own premise: nothing fired, so no active entry ever diverted a bar to the exit
    // branch and every one of the 40 evaluations really did go through the entry path.
    assertThat(outcomeCount("fired") - firedBefore).isZero();
    // ...and the run spanned several paths rather than trivially counting one.
    assertThat(outcomeCount("unscoreable-indicators-warming") - unscoreableBefore)
        .as("the cold series warms first")
        .isPositive();
    assertThat(outcomeCount("composite-below-threshold") - belowBefore)
        .as("A: gate open, composite under threshold")
        .isPositive();
    assertThat(outcomeCount("chart-gate-failed") - gateFailedBefore)
        .as("B: gate shut")
        .isPositive();
  }

  private double outcomeSum() {
    return meterRegistry.find("ay_signal_eval_outcome_total").counters().stream()
        .mapToDouble(Counter::count)
        .sum();
  }

  private double outcomeCount(String tag) {
    Counter counter =
        meterRegistry.find("ay_signal_eval_outcome_total").tag("outcome", tag).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private StrategyRepository.StrategyRow uniquePublishedRow(
      String template, String idPrefix, String namePrefix) {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String yaml =
        template
            .replaceFirst("id: [^\\r\\n]+", "id: " + idPrefix + "-" + suffix)
            .replaceFirst("name: \"[^\"]+\"", "name: \"" + namePrefix + " " + suffix + "\"");
    return publishedRow(yaml, namePrefix + " " + suffix);
  }

  private StrategyRepository isolatedRegistry(StrategyRepository.StrategyRow row) {
    StrategyRepository isolatedRegistry = mock(StrategyRepository.class);
    when(isolatedRegistry.listAll()).thenReturn(List.of(row));
    when(isolatedRegistry.findVersionById(row.publishedVersionId()))
        .thenReturn(repository.findVersionById(row.publishedVersionId()));
    return isolatedRegistry;
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
