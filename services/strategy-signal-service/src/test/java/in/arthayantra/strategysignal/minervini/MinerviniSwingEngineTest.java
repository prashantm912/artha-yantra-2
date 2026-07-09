package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.Books;
import in.arthayantra.strategysignal.signals.EmissionGuard;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalPublisher;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.SwingBatchEngine;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Minervini doctrine driving the shared {@link SwingBatchEngine}: {@code buildBank} seeds the VCP
 * pivot context and the FROZEN {@link EntryEvaluator}/{@link ExitEvaluator} decide entry/exit on the
 * just-closed daily bar (parity — the batch never re-implements scoring). Pins entry on a
 * volume-expansion breakout, no entry on a flat-volume breakout, the 8% protective stop, the audit-H2
 * superseded-version adoption (both halves), the P0-3 fetch-retry, and the H3 per-emit gate re-check.
 */
class MinerviniSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal PIVOT = new BigDecimal("150");

  /** The Minervini candidate context seeds, matching the doctrine's unconditional 3-context form. */
  private static Map<String, BigDecimal> seeds(BigDecimal pivot, BigDecimal cheat, boolean thrust) {
    return Map.of(
        "MINERVINI_PIVOT", pivot == null ? BigDecimal.ZERO : pivot,
        "MINERVINI_CHEAT", cheat == null ? BigDecimal.ZERO : cheat,
        "MINERVINI_THRUST", thrust ? BigDecimal.ONE : BigDecimal.ZERO);
  }

  @Test
  void runDailyIsAnInertNoOpWhenDisabled() {
    StrategyRepository registry = mock(StrategyRepository.class);
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    SignalRepository signals = mock(SignalRepository.class);
    SignalPublisher publisher = mock(SignalPublisher.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, publisher, events, Optional.empty(), tx,
            new ObjectMapper(), Clock.systemUTC());
    MinerviniDoctrine doctrine =
        new MinerviniDoctrine(funnel, signals, new ObjectMapper(), false, 520, 60, 1440);

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine);

    assertThat(run.strategies()).isZero();
    assertThat(run.entries()).isZero();
    assertThat(run.exits()).isZero();
    verifyNoInteractions(registry, funnel, candles, signals, publisher, events, tx);
  }

  @Test
  void entryFiresOnPivotBreakoutWithExpandingVolume() throws IOException {
    List<EngineCandle> series = craft(3_000L);
    IndicatorBank bank =
        SwingBatchEngine.buildBank(vcp(), "TESTCO", series, seeds(PIVOT, BigDecimal.ZERO, false));
    Optional<EntryEvaluator.Evaluation> eval = EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);
    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("the last bar breaks out above the 150 pivot on 3x volume").isTrue();
  }

  @Test
  void entryBlockedWhenTheBreakoutLacksVolume() throws IOException {
    List<EngineCandle> series = craft(1_000L);
    IndicatorBank bank =
        SwingBatchEngine.buildBank(vcp(), "TESTCO", series, seeds(PIVOT, BigDecimal.ZERO, false));
    Optional<EntryEvaluator.Evaluation> eval = EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);
    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("crossover fires but the vol>1.2 gate blocks it").isFalse();
  }

  @Test
  void protectiveStopExitsAnEightPercentUnderwaterPosition() throws IOException {
    List<EngineCandle> series = craftDecline();
    IndicatorBank bank =
        SwingBatchEngine.buildBank(
            vcp(), "TESTCO", series, seeds(BigDecimal.ZERO, BigDecimal.ZERO, false));
    Optional<ExitEvaluator.ExitDecision> exit =
        ExitEvaluator.evaluate(
            vcp(), bank,
            new ExitEvaluator.Position(ExitEvaluator.Direction.LONG, new BigDecimal("152"), 0),
            series.size() - 1);
    assertThat(exit).isPresent();
    assertThat(exit.get().type()).isEqualTo("stop_loss");
  }

  @Test
  void exitPassAdoptsAnchorsOfASupersededVersion() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    UUID supersededVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow = strategyRow(strategyId, publishedVersion);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    List<EngineCandle> series = craftDecline();
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(42L, supersededVersion, new BigDecimal("152"), series)));
    stubInsert(signals, 43L);

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    SwingBatchEngine.SwingRun run = engine(registry, candles, signals, funnel).runDaily(doctrine(funnel, signals, true, 60));

    assertThat(run.exits()).as("the superseded-version anchor is adopted and its stop fires").isEqualTo(1);
    verify(signals).transition(42L, "EXPIRED");
  }

  @Test
  void heldSymbolsBlocksReEntryForSupersededVersionAnchors() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    UUID supersededVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow = strategyRow(strategyId, publishedVersion);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(List.of(anchor(42L, supersededVersion, new BigDecimal("100"), series)));

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(new MinerviniFunnelClient.Candidate("TESTCO", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T")));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    SwingBatchEngine.SwingRun run = engine(registry, candles, signals, funnel).runDaily(doctrine(funnel, signals, true, 60));

    assertThat(run.entries()).as("the superseded-version anchor's symbol blocks re-entry").isZero();
  }

  @Test
  void exitPassRetriesAFailedFetchOnceThenEvaluatesTheStop() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any()))
        .thenReturn(List.of())
        .thenReturn(h.series);

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine());

    assertThat(run.exits()).as("the retry recovers the series and the stop fires").isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
    verify(h.candles, times(2)).fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any());
  }

  @Test
  void exitPassCountsAndScreamsWhenTheSeriesIsUnavailableAfterRetry() throws IOException {
    ExitHarness h = new ExitHarness();
    when(h.candles.fetch(any(), any(), any(), any(), any())).thenReturn(List.of());

    SwingBatchEngine.SwingRun run = h.engine().runDaily(h.doctrine());

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("the unevaluated stop is surfaced, not swallowed").isEqualTo(1);
  }

  @Test
  void entryGateIsReCheckedPerEntryAndStopsTheRunWhenTheBookTrips() throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));

    List<EngineCandle> series = craft(3_000L);
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new MinerviniFunnelClient.Candidate("AAA", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T"),
                new MinerviniFunnelClient.Candidate("BBB", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T")));
    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), any(), eq("1d"), any(), any())).thenReturn(series);
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries()).thenReturn(List.of());
    stubInsert(signals, 1L);

    EmissionGuard guard = mock(EmissionGuard.class);
    when(guard.entryAllowed(Books.MINERVINI)).thenReturn(true, true, false);
    when(guard.suggestedQty(any(), any(), any(), any(), any(), any())).thenReturn(new BigDecimal("10"));

    SwingBatchEngine engine =
        new SwingBatchEngine(
            registry, candles, signals, mock(SignalPublisher.class),
            mock(ApplicationEventPublisher.class), Optional.of(guard), passthroughTx(),
            new ObjectMapper(), Clock.systemUTC());

    SwingBatchEngine.SwingRun run = engine.runDaily(doctrine(funnel, signals, true, 10));

    assertThat(run.entries()).as("the mid-run book trip halts entries after the first").isEqualTo(1);
  }

  // ---- harness --------------------------------------------------------------------------------

  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final SignalRepository signals = mock(SignalRepository.class);
    final MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    final MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    final List<EngineCandle> series = craftDecline();

    ExitHarness() throws IOException {
      UUID strategyId = UUID.randomUUID();
      UUID publishedVersion = UUID.randomUUID();
      JsonNode config = vcpConfig();
      when(registry.listAll()).thenReturn(List.of(strategyRow(strategyId, publishedVersion)));
      when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));
      when(signals.activeEntries())
          .thenReturn(List.of(anchor(42L, publishedVersion, new BigDecimal("152"), series)));
      stubInsert(signals, 43L);
      when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    }

    SwingBatchEngine engine() {
      return MinerviniSwingEngineTest.this.engine(registry, candles, signals, funnel);
    }

    MinerviniDoctrine doctrine() {
      return MinerviniSwingEngineTest.this.doctrine(funnel, signals, true, 60);
    }
  }

  private SwingBatchEngine engine(
      StrategyRepository registry, MarketDataCandlesClient candles, SignalRepository signals,
      MinerviniFunnelClient funnel) {
    return new SwingBatchEngine(
        registry, candles, signals, mock(SignalPublisher.class),
        mock(ApplicationEventPublisher.class), Optional.empty(), passthroughTx(),
        new ObjectMapper(), Clock.systemUTC());
  }

  private MinerviniDoctrine doctrine(
      MinerviniFunnelClient funnel, SignalRepository signals, boolean enabled, int minBars) {
    return new MinerviniDoctrine(funnel, signals, new ObjectMapper(), enabled, 520, minBars, 1440);
  }

  private static TransactionTemplate passthroughTx() {
    TransactionTemplate tx = mock(TransactionTemplate.class);
    when(tx.execute(any()))
        .thenAnswer(inv -> inv.<TransactionCallback<Long>>getArgument(0).doInTransaction(null));
    return tx;
  }

  private static void stubInsert(SignalRepository signals, long id) {
    when(signals.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(id);
  }

  private static StrategyRepository.StrategyRow strategyRow(UUID strategyId, UUID publishedVersion) {
    return new StrategyRepository.StrategyRow(
        strategyId, "minervini-vcp", "Minervini VCP", null, null, List.of("minervini"), true,
        publishedVersion, null, null, false, null);
  }

  private static SignalRepository.SignalRow anchor(
      long id, UUID versionId, BigDecimal entryPrice, List<EngineCandle> series) {
    return new SignalRepository.SignalRow(
        id, versionId, "NSE", "TESTCO", "1d", "ENTRY", "BUY", entryPrice, null, null, BigDecimal.ONE,
        new ObjectMapper().createObjectNode(), "TAKEN", series.get(0).bucketStart(),
        series.get(0).bucketStart().plusDays(1), null, null, null, null, null, null);
  }

  private static StrategyDefinition vcp() throws IOException {
    return StrategyCompiler.compile(vcpConfig());
  }

  private static JsonNode vcpConfig() throws IOException {
    try (InputStream in =
        MinerviniSwingEngineTest.class.getResourceAsStream("/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    }
  }

  private static StrategyRepository.VersionRow version(
      UUID id, UUID strategyId, String version, JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, version, null, config, "1", "chk-" + version, "published", null, null, null, null);
  }

  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147};
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(bar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(19 + i, tail[i], 1_000L));
    }
    bars.add(bar(24, 152.0, breakoutVolume));
    return bars;
  }

  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 23; d++) {
      bars.add(bar(d, 150.0, 1_000L));
    }
    bars.add(bar(24, 135.0, 1_000L));
    return bars;
  }

  private static EngineCandle bar(int day, double price, long volume) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, volume, null);
  }
}
