package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.testsupport.StrategySignalIntegrationTestBase;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Drives the real {@link SignalEngine#reload()} path with a controlled registry and Redis
 * subscriber. The fixture intentionally includes every enabled+published load branch so a
 * classification cannot be made total by only covering the universe resolver.
 */
class StrategyCoverageSignalEngineIntegrationTest extends StrategySignalIntegrationTestBase {

  private static final String NORMAL_YAML =
      """
      schema: strategy-schema/v1
      id: coverage-resolved
      name: "Coverage Resolved"
      version: 1.0.0
      universe:
        mode: explicit
        instruments:
          - { exchange: NSE, tradingsymbol: COVERAGE-FUT }
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
      risk:
        position_sizing: { method: fixed_quantity, params: { quantity: 1 } }
        max_positions: 1
        session: { style: intraday }
      """;

  @Test
  void partialReloadWithBarsFlowingPagesExactlyOnceWithSlugAndClassification() throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode normal = StrategyDocuments.parse(NORMAL_YAML).config();

    List<StrategyRepository.StrategyRow> rows = new ArrayList<>();
    Map<UUID, StrategyRepository.VersionRow> versions = new LinkedHashMap<>();
    add(rows, versions, new LinkedHashMap<>(), "coverage-bar-resolved", normal, RESOLVED);
    add(
        rows,
        versions,
        new LinkedHashMap<>(),
        "coverage-bar-unresolved",
        futuresOfUnderlying(normal),
        UNRESOLVED);

    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(rows);
    when(registry.countEnabledPublished()).thenReturn(2L);
    when(registry.findVersionById(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(invocation ->
        Optional.ofNullable(versions.get(invocation.getArgument(0))));

    FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
    when(resolver.resolve(anyString(), anyString(), anyString(), anyInt())).thenReturn(Optional.empty());
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    ApplicationEventPublisher events = event -> alerts.add((StrategyCoverageAlert) event);

    try (EngineFixture fixture = engine(registry, resolver, objectMapper)) {
      fixture.engine().reload();
      long barAt = Instant.parse("2026-07-24T10:00:00Z").toEpochMilli();
      fixture.engine()
          .onCandleMessage(
              """
              {"bucket":"2026-07-24T10:00:00Z","open":"100","high":"101","low":"99","close":"100","volume":1,"exchange":"NSE","tradingsymbol":"COVERAGE-FUT"}
              """);
      assertThat(fixture.engine().lastBarReceivedAtMs()).isEqualTo(barAt);

      StrategyCoverageWatchdog watchdog =
          new StrategyCoverageWatchdog(
              fixture.engine(),
              registry,
              events,
              Clock.fixed(Instant.parse("2026-07-24T04:30:00Z"), ZoneOffset.UTC),
              "ARMED",
              1);
      watchdog.sweep();
      watchdog.sweep();

      assertThat(alerts).singleElement().satisfies(alert -> {
        assertThat(alert.kind()).isEqualTo(StrategyCoverageAlert.Kind.STRATEGY_DARK);
        assertThat(alert.slug()).isEqualTo("coverage-bar-unresolved");
        assertThat(alert.classification()).isEqualTo(UNRESOLVED);
      });
    }
  }

  @Test
  void notLiveResolvableIsHealthyToReloadOutcomeButStillPages() throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode notLive = withUniverseMode(StrategyDocuments.parse(NORMAL_YAML).config(), "future_chain");
    List<StrategyRepository.StrategyRow> rows = new ArrayList<>();
    Map<UUID, StrategyRepository.VersionRow> versions = new LinkedHashMap<>();
    add(rows, versions, new LinkedHashMap<>(), "coverage-not-live-healthy", notLive, NOT_LIVE);

    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(rows);
    when(registry.countEnabledPublished()).thenReturn(1L);
    when(registry.findVersionById(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(invocation ->
        Optional.ofNullable(versions.get(invocation.getArgument(0))));
    FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
    List<StrategyCoverageAlert> alerts = new ArrayList<>();
    ApplicationEventPublisher events = event -> alerts.add((StrategyCoverageAlert) event);

    try (EngineFixture fixture = engine(registry, resolver, objectMapper)) {
      Object outcome = ReflectionTestUtils.invokeMethod(fixture.engine(), "reload", false);
      boolean healthy = (Boolean) ReflectionTestUtils.invokeMethod(outcome, "healthy");
      assertThat(healthy).isTrue();

      new StrategyCoverageWatchdog(
              fixture.engine(),
              registry,
              events,
              Clock.fixed(Instant.parse("2026-07-24T04:31:00Z"), ZoneOffset.UTC),
              "ARMED",
              1)
          .sweep();

      assertThat(alerts).singleElement().satisfies(alert -> {
        assertThat(alert.slug()).isEqualTo("coverage-not-live-healthy");
        assertThat(alert.classification()).isEqualTo(NOT_LIVE);
      });
    }
  }

  @Test
  void totalReloadClassifiesEveryEnabledPublishedSlugExactlyOnce() throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode normal = StrategyDocuments.parse(NORMAL_YAML).config();
    JsonNode scalperWithoutBounding = scalperWithoutBoundingExit();

    List<StrategyRepository.StrategyRow> rows = new ArrayList<>();
    Map<UUID, StrategyRepository.VersionRow> versions = new LinkedHashMap<>();
    Map<String, StrategyCoverageSnapshot.Classification> expected = new LinkedHashMap<>();
    add(rows, versions, expected, "coverage-resolved", normal, RESOLVED);
    add(rows, versions, expected, "coverage-unresolved", futuresOfUnderlying(normal), UNRESOLVED);
    add(rows, versions, expected, "coverage-empty", futuresScreener(normal), RESOLVED_EMPTY);
    add(rows, versions, expected, "coverage-not-live", withUniverseMode(normal, "future_chain"), NOT_LIVE);
    add(rows, versions, expected, "coverage-swing", swing(normal), NOT_APPLICABLE_SWING);
    add(rows, versions, expected, "coverage-not-rollable", notRollable(normal), NOT_ROLLABLE);
    add(rows, versions, expected, "coverage-no-exit", scalperWithoutBounding, NO_BOUNDING_EXIT);
    add(rows, versions, expected, "coverage-load-error", null, LOAD_ERROR);

    UUID missingStrategyId = UUID.randomUUID();
    UUID missingVersionId = UUID.randomUUID();
    rows.add(strategy(missingStrategyId, "coverage-missing-version", missingVersionId, true));
    expected.put("coverage-missing-version", MISSING_VERSION_ROW);

    rows.add(strategy(UUID.randomUUID(), "coverage-disabled", UUID.randomUUID(), false));
    rows.add(strategy(UUID.randomUUID(), "coverage-unpublished", null, true));

    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(rows);
    when(registry.findVersionById(org.mockito.ArgumentMatchers.any(UUID.class))).thenAnswer(invocation ->
        Optional.ofNullable(versions.get(invocation.getArgument(0))));
    when(registry.findVersionById(missingVersionId)).thenReturn(Optional.empty());

    FuturesUniverseResolver resolver = mock(FuturesUniverseResolver.class);
    when(resolver.resolve(anyString(), anyString(), anyString(), anyInt())).thenReturn(Optional.empty());
    when(resolver.resolveScreener(anyString(), anyInt(), anyString())).thenReturn(Optional.of(List.of()));

    try (EngineFixture fixture = engine(registry, resolver, objectMapper)) {
      fixture.engine().reload();

      StrategyCoverageSnapshot snapshot = fixture.engine().strategyCoverageSnapshot();
      assertThat(snapshot).isNotNull();
      assertThat(snapshot.terminalState())
          .isEqualTo(StrategyCoverageSnapshot.TerminalState.DEGRADED_TERMINAL);
      assertThat(snapshot.completedCurrentGeneration()).isTrue();
      assertThat(snapshot.classifications()).containsExactlyEntriesOf(expected);
      assertThat(fixture.engine().loadedSlugs()).contains("coverage-resolved");
      assertThat(fixture.engine().loadedSlugs()).doesNotContain("coverage-empty", "coverage-no-exit");
    }
  }

  @Test
  void firstReloadAbortLeavesAnInFlightGenerationForSnapshotMissing() throws IOException {
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenThrow(new IllegalStateException("registry unavailable"));

    try (EngineFixture fixture = engine(registry, mock(FuturesUniverseResolver.class), new ObjectMapper())) {
      assertThatThrownBy(() -> fixture.engine().reload())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("registry unavailable");

      StrategyCoverageSnapshot snapshot = fixture.engine().strategyCoverageSnapshot();
      assertThat(snapshot.terminalState()).isEqualTo(StrategyCoverageSnapshot.TerminalState.IN_FLIGHT);
      assertThat(snapshot.requestedGeneration()).isEqualTo(1L);
      assertThat(snapshot.completedGeneration()).isZero();
    }
  }

  @Test
  void newerReloadAbortCannotBeMaskedByAHealthyOlderSnapshot() throws IOException {
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of()).thenThrow(new IllegalStateException("hot-swap aborted"));

    try (EngineFixture fixture = engine(registry, mock(FuturesUniverseResolver.class), new ObjectMapper())) {
      fixture.engine().reload();
      assertThat(fixture.engine().strategyCoverageSnapshot().terminalState())
          .isEqualTo(StrategyCoverageSnapshot.TerminalState.HEALTHY);

      assertThatThrownBy(() -> fixture.engine().reload())
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("hot-swap aborted");

      StrategyCoverageSnapshot snapshot = fixture.engine().strategyCoverageSnapshot();
      assertThat(snapshot.terminalState()).isEqualTo(StrategyCoverageSnapshot.TerminalState.IN_FLIGHT);
      assertThat(snapshot.requestedGeneration()).isEqualTo(2L);
      assertThat(snapshot.completedGeneration()).isEqualTo(1L);
    }
  }

  private static final StrategyCoverageSnapshot.Classification RESOLVED =
      StrategyCoverageSnapshot.Classification.RESOLVED;
  private static final StrategyCoverageSnapshot.Classification RESOLVED_EMPTY =
      StrategyCoverageSnapshot.Classification.RESOLVED_EMPTY;
  private static final StrategyCoverageSnapshot.Classification UNRESOLVED =
      StrategyCoverageSnapshot.Classification.UNRESOLVED;
  private static final StrategyCoverageSnapshot.Classification NOT_LIVE =
      StrategyCoverageSnapshot.Classification.NOT_LIVE_RESOLVABLE;
  private static final StrategyCoverageSnapshot.Classification NOT_APPLICABLE_SWING =
      StrategyCoverageSnapshot.Classification.NOT_APPLICABLE_SWING;
  private static final StrategyCoverageSnapshot.Classification NOT_ROLLABLE =
      StrategyCoverageSnapshot.Classification.NOT_ROLLABLE_PRIMARY;
  private static final StrategyCoverageSnapshot.Classification NO_BOUNDING_EXIT =
      StrategyCoverageSnapshot.Classification.NO_BOUNDING_EXIT;
  private static final StrategyCoverageSnapshot.Classification LOAD_ERROR =
      StrategyCoverageSnapshot.Classification.LOAD_ERROR;
  private static final StrategyCoverageSnapshot.Classification MISSING_VERSION_ROW =
      StrategyCoverageSnapshot.Classification.MISSING_VERSION_ROW;

  private static void add(
      List<StrategyRepository.StrategyRow> rows,
      Map<UUID, StrategyRepository.VersionRow> versions,
      Map<String, StrategyCoverageSnapshot.Classification> expected,
      String slug,
      JsonNode config,
      StrategyCoverageSnapshot.Classification classification) {
    UUID strategyId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    rows.add(strategy(strategyId, slug, versionId, true));
    versions.put(versionId, version(versionId, strategyId, slug, config));
    expected.put(slug, classification);
  }

  private static StrategyRepository.StrategyRow strategy(
      UUID id, String slug, UUID publishedVersionId, boolean enabled) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new StrategyRepository.StrategyRow(
        id,
        slug,
        "Coverage " + slug + " " + UUID.randomUUID(),
        null,
        "coverage-test",
        List.of(),
        enabled,
        publishedVersionId,
        now,
        now,
        false,
        null);
  }

  private static StrategyRepository.VersionRow version(
      UUID id, UUID strategyId, String slug, JsonNode config) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return new StrategyRepository.VersionRow(
        id,
        strategyId,
        "1.0.0",
        "coverage-test-" + slug,
        config,
        "strategy-schema/v1",
        "coverage-checksum-" + slug,
        "published",
        null,
        "coverage-test",
        now,
        now);
  }

  private static JsonNode futuresOfUnderlying(JsonNode source) {
    ObjectNode config = source.deepCopy();
    ObjectNode universe = config.with("universe");
    universe.put("mode", "futures_of_underlying");
    universe.set("underlying", object("exchange", "NSE", "tradingsymbol", "COVERAGE-UNDERLYING"));
    universe.set("futures", object("contract", "front_month", "roll_days_before_expiry", 1));
    return config;
  }

  private static JsonNode futuresScreener(JsonNode source) {
    ObjectNode config = source.deepCopy();
    ObjectNode universe = config.with("universe");
    universe.put("mode", "futures_screener");
    universe.put("side", "long");
    universe.put("max_picks", 5);
    universe.put("source", "captured");
    return config;
  }

  private static JsonNode withUniverseMode(JsonNode source, String mode) {
    ObjectNode config = source.deepCopy();
    ((ObjectNode) config.with("universe")).put("mode", mode);
    return config;
  }

  private static JsonNode swing(JsonNode source) {
    ObjectNode config = source.deepCopy();
    ((ObjectNode) config.with("timeframes")).put("primary", "1d");
    ((ObjectNode) config.with("risk").with("session")).put("style", "swing");
    ((ObjectNode) config.withArray("indicators").get(0)).put("timeframe", "1d");
    return config;
  }

  private static JsonNode notRollable(JsonNode source) {
    ObjectNode config = source.deepCopy();
    ((ObjectNode) config.with("timeframes")).put("primary", "1d");
    ((ObjectNode) config.with("risk").with("session")).put("style", "intraday");
    return config;
  }

  private static JsonNode scalperWithoutBoundingExit() throws IOException {
    try (InputStream stream = StrategyCoverageSignalEngineIntegrationTest.class
        .getResourceAsStream("/scalper-strategies/scalp-connect-the-dots-nifty.yaml")) {
      if (stream == null) {
        throw new IOException("scalper fixture is missing");
      }
      String yaml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      ObjectNode config = StrategyDocuments.parse(yaml).config().deepCopy();
      ArrayNode exits = config.putArray("exit_rules");
      ObjectNode stop = exits.addObject();
      stop.put("type", "stop_loss");
      stop.putObject("params").put("basis", "premium_pct").put("value", 20);
      return config;
    }
  }

  private static ObjectNode object(String firstKey, String firstValue, String secondKey, Object secondValue) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode node = mapper.createObjectNode();
    node.put(firstKey, firstValue);
    if (secondValue instanceof Integer integer) {
      node.put(secondKey, integer);
    } else {
      node.put(secondKey, (String) secondValue);
    }
    return node;
  }

  private static EngineFixture engine(
      StrategyRepository registry, FuturesUniverseResolver resolver, ObjectMapper objectMapper) {
    LettuceConnectionFactory redis = new LettuceConnectionFactory(redisHost(), redisPort());
    redis.afterPropertiesSet();
    PrometheusMeterRegistry meters = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    SignalEngine engine =
        new SignalEngine(
            registry,
            mock(SignalRepository.class),
            mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class),
            mock(LiveSeriesStore.class),
            resolver,
            redis,
            objectMapper,
            Clock.fixed(OffsetDateTime.of(2026, 7, 24, 10, 0, 0, 0, ZoneOffset.UTC).toInstant(), ZoneOffset.UTC),
            meters,
            Optional.empty(),
            Optional.empty(),
            mock(RejectionWriter.class),
            mock(RiskSuppressionWriter.class),
            mock(CompositeRejectionWriter.class),
            Optional.empty(),
            mock(PlatformTransactionManager.class),
            60,
            true);
    return new EngineFixture(engine, redis, meters);
  }

  private record EngineFixture(
      SignalEngine engine, RedisConnectionFactory redis, PrometheusMeterRegistry meters)
      implements AutoCloseable {
    @Override
    public void close() {
      engine.stop();
      if (redis instanceof LettuceConnectionFactory lettuce) {
        lettuce.destroy();
      }
      meters.close();
    }
  }
}
