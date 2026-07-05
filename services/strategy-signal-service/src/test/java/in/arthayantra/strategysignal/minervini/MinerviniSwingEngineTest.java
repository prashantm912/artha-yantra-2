package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import in.arthayantra.strategyengine.config.StrategyCompiler;
import in.arthayantra.strategyengine.config.StrategyDefinition;
import in.arthayantra.strategyengine.eval.EntryEvaluator;
import in.arthayantra.strategyengine.eval.ExitEvaluator;
import in.arthayantra.strategyengine.eval.IndicatorBank;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The Phase-9 swing engine's core: {@link MinerviniSwingEngine#buildBank} seeds the per-symbol VCP
 * pivot as a flat context series, and the FROZEN {@link EntryEvaluator}/{@link ExitEvaluator} then
 * decide entry/exit on the just-closed daily bar — the batch never re-implements scoring. Pins that a
 * pivot breakout ON THE LAST BAR with expanding volume fires an entry, a flat-volume breakout does
 * not, and an 8%-underwater position exits on the protective stop.
 */
class MinerviniSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal PIVOT = new BigDecimal("150");

  @Test
  void runDailyIsAnInertNoOpWhenDisabled() {
    // The arming gate (audit P9): with artha.minervini.swing.enabled=false, runDaily() must never
    // touch the registry / funnel / signals / publisher — so an on-demand POST /run cannot fire a
    // signal or open a paper position before the owner arms the batch.
    StrategyRepository registry = mock(StrategyRepository.class);
    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    in.arthayantra.strategysignal.signals.SignalPublisher publisher =
        mock(in.arthayantra.strategysignal.signals.SignalPublisher.class);
    org.springframework.context.ApplicationEventPublisher events =
        mock(org.springframework.context.ApplicationEventPublisher.class);
    org.springframework.transaction.support.TransactionTemplate tx =
        mock(org.springframework.transaction.support.TransactionTemplate.class);

    MinerviniSwingEngine engine =
        new MinerviniSwingEngine(
            registry, funnel, candles, signals, publisher, events, Optional.empty(), tx,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            java.time.Clock.systemUTC(), false, 520, 60, 1440);

    MinerviniSwingEngine.SwingRun run = engine.runDaily();

    assertThat(run.strategies()).isZero();
    assertThat(run.entries()).isZero();
    assertThat(run.exits()).isZero();
    verifyNoInteractions(registry, funnel, candles, signals, publisher, events, tx);
  }

  private static StrategyDefinition vcp() throws IOException {
    try (InputStream in =
        MinerviniSwingEngineTest.class.getResourceAsStream("/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyCompiler.compile(StrategyDocuments.parse(yaml).config());
    }
  }

  @Test
  void entryFiresOnPivotBreakoutWithExpandingVolume() throws IOException {
    List<EngineCandle> series = craft(3_000L); // 3x volume on the final (breakout) bar
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, PIVOT, BigDecimal.ZERO, false);

    Optional<EntryEvaluator.Evaluation> eval =
        EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);

    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("the last bar breaks out above the 150 pivot on 3x volume").isTrue();
  }

  @Test
  void entryBlockedWhenTheBreakoutLacksVolume() throws IOException {
    List<EngineCandle> series = craft(1_000L); // flat volume on the breakout bar
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, PIVOT, BigDecimal.ZERO, false);

    Optional<EntryEvaluator.Evaluation> eval =
        EntryEvaluator.evaluate(vcp(), bank, series.size() - 1);

    assertThat(eval).isPresent();
    assertThat(eval.get().entry()).as("crossover fires but the vol>1.2 gate blocks it").isFalse();
  }

  @Test
  void protectiveStopExitsAnEightPercentUnderwaterPosition() throws IOException {
    // A held position entered at 152; the series then closes at 135 (11% underwater, below the 8% stop).
    List<EngineCandle> series = craftDecline();
    IndicatorBank bank =
        MinerviniSwingEngine.buildBank(vcp(), "TESTCO", series, BigDecimal.ZERO, BigDecimal.ZERO, false);

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
    // Audit H2 regression: an open anchor whose version is no longer the strategy's published one
    // (routine after a seeder resync + republish) must still be exit-managed — with the anchor's
    // OWN frozen config — and its EXIT must fire when the doctrine says so. On the old code the
    // exit pass silently skipped it (byVersion keyed on the CURRENT published version only).
    java.util.UUID strategyId = java.util.UUID.randomUUID();
    java.util.UUID publishedVersion = java.util.UUID.randomUUID();
    java.util.UUID supersededVersion = java.util.UUID.randomUUID();

    com.fasterxml.jackson.databind.JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow =
        new StrategyRepository.StrategyRow(
            strategyId, "minervini-vcp", "Minervini VCP", null, null, List.of("minervini"), true,
            publishedVersion, null, null, false, null);
    org.mockito.Mockito.when(registry.listAll()).thenReturn(List.of(strategyRow));
    org.mockito.Mockito.when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    org.mockito.Mockito.when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    org.mockito.Mockito.when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    // An 11%-underwater anchor carried by the SUPERSEDED version: entry 152, last close 135.
    List<EngineCandle> series = craftDecline();
    in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    org.mockito.Mockito.when(signals.activeEntries())
        .thenReturn(
            List.of(
                new in.arthayantra.strategysignal.signals.SignalRepository.SignalRow(
                    42L, supersededVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY",
                    new BigDecimal("152"), null, null, BigDecimal.ONE,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "TAKEN",
                    series.get(0).bucketStart(), series.get(0).bucketStart().plusDays(1), null,
                    null, null, null, null, null)));
    org.mockito.Mockito.when(signals.insert(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(43L);

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    org.mockito.Mockito.when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    org.mockito.Mockito.when(candles.fetch(
            org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(series);
    org.springframework.transaction.support.TransactionTemplate tx =
        mock(org.springframework.transaction.support.TransactionTemplate.class);
    org.mockito.Mockito.when(tx.execute(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv ->
            inv.<org.springframework.transaction.support.TransactionCallback<Long>>getArgument(0)
                .doInTransaction(null));

    MinerviniSwingEngine engine =
        new MinerviniSwingEngine(
            registry, funnel, candles, signals,
            mock(in.arthayantra.strategysignal.signals.SignalPublisher.class),
            mock(org.springframework.context.ApplicationEventPublisher.class), Optional.empty(),
            tx, new com.fasterxml.jackson.databind.ObjectMapper(),
            java.time.Clock.systemUTC(), true, 520, 60, 1440);

    MinerviniSwingEngine.SwingRun run = engine.runDaily();

    assertThat(run.exits()).as("the superseded-version anchor is adopted and its stop fires").isEqualTo(1);
    org.mockito.Mockito.verify(signals).transition(42L, "EXPIRED");
  }

  @Test
  void heldSymbolsBlocksReEntryForSupersededVersionAnchors() throws IOException {
    // The second half of audit H2 (double exposure): a symbol held by a SUPERSEDED version's anchor
    // must still block a new-version entry. Pre-fix, heldSymbols counted only current published
    // versions, so this breakout candidate would fire a fresh ENTRY on the already-held symbol.
    java.util.UUID strategyId = java.util.UUID.randomUUID();
    java.util.UUID publishedVersion = java.util.UUID.randomUUID();
    java.util.UUID supersededVersion = java.util.UUID.randomUUID();

    com.fasterxml.jackson.databind.JsonNode config = vcpConfig();
    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow =
        new StrategyRepository.StrategyRow(
            strategyId, "minervini-vcp", "Minervini VCP", null, null, List.of("minervini"), true,
            publishedVersion, null, null, false, null);
    org.mockito.Mockito.when(registry.listAll()).thenReturn(List.of(strategyRow));
    org.mockito.Mockito.when(registry.findById(strategyId)).thenReturn(Optional.of(strategyRow));
    org.mockito.Mockito.when(registry.findVersionById(publishedVersion))
        .thenReturn(Optional.of(version(publishedVersion, strategyId, "2", config)));
    org.mockito.Mockito.when(registry.findVersionById(supersededVersion))
        .thenReturn(Optional.of(version(supersededVersion, strategyId, "1", config)));

    // A breakout series that WOULD fire the entry gate (pivot crossover on 3x volume) — the only
    // thing standing between the candidate and a duplicate position is the held-symbol block.
    List<EngineCandle> series = craft(3_000L);
    in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    org.mockito.Mockito.when(signals.activeEntries())
        .thenReturn(
            List.of(
                new in.arthayantra.strategysignal.signals.SignalRepository.SignalRow(
                    42L, supersededVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY",
                    new BigDecimal("100"), null, null, BigDecimal.ONE,
                    new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "TAKEN",
                    series.get(0).bucketStart(), series.get(0).bucketStart().plusDays(1), null,
                    null, null, null, null, null)));

    MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    org.mockito.Mockito.when(funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new MinerviniFunnelClient.Candidate(
                    "TESTCO", new BigDecimal("152"), PIVOT, null, false, 2, "40W 31/3 4T")));
    in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    org.mockito.Mockito.when(candles.fetch(
            org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(series);
    org.springframework.transaction.support.TransactionTemplate tx =
        mock(org.springframework.transaction.support.TransactionTemplate.class);
    org.mockito.Mockito.when(tx.execute(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv ->
            inv.<org.springframework.transaction.support.TransactionCallback<Long>>getArgument(0)
                .doInTransaction(null));

    MinerviniSwingEngine engine =
        new MinerviniSwingEngine(
            registry, funnel, candles, signals,
            mock(in.arthayantra.strategysignal.signals.SignalPublisher.class),
            mock(org.springframework.context.ApplicationEventPublisher.class), Optional.empty(),
            tx, new com.fasterxml.jackson.databind.ObjectMapper(),
            java.time.Clock.systemUTC(), true, 520, 60, 1440);

    MinerviniSwingEngine.SwingRun run = engine.runDaily();

    assertThat(run.entries())
        .as("the superseded-version anchor's symbol blocks the new-version re-entry")
        .isZero();
  }

  @Test
  void exitPassRetriesAFailedFetchOnceThenEvaluatesTheStop() throws IOException {
    // Audit P0-3: MarketDataCandlesClient fail-softs to an empty list and the per-run cache pins
    // it — pre-fix the held anchor's ONLY daily stop evaluation was silently skipped. The fix
    // retries once outside the cache; here the retry succeeds and the 8% stop fires.
    ExitHarness h = new ExitHarness();
    org.mockito.Mockito.when(h.candles.fetch(
            org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of())
        .thenReturn(h.series);

    MinerviniSwingEngine.SwingRun run = h.engine().runDaily();

    assertThat(run.exits()).as("the retry recovers the series and the stop fires").isEqualTo(1);
    assertThat(run.exitSkipped()).isZero();
    org.mockito.Mockito.verify(h.candles, org.mockito.Mockito.times(2))
        .fetch(org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void exitPassCountsAndScreamsWhenTheSeriesIsUnavailableAfterRetry() throws IOException {
    // Both fetches fail → the anchor's stop is NOT evaluated today; the run must say so loudly
    // (exitSkipped > 0) instead of the pre-fix silent skip.
    ExitHarness h = new ExitHarness();
    org.mockito.Mockito.when(h.candles.fetch(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());

    MinerviniSwingEngine.SwingRun run = h.engine().runDaily();

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("the unevaluated stop is surfaced, not swallowed").isEqualTo(1);
  }

  /** Shared wiring for the exit-pass fetch-failure tests: one published anchor, empty funnel. */
  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    final MinerviniFunnelClient funnel = mock(MinerviniFunnelClient.class);
    final in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    final List<EngineCandle> series = craftDecline();

    ExitHarness() throws IOException {
      java.util.UUID strategyId = java.util.UUID.randomUUID();
      java.util.UUID publishedVersion = java.util.UUID.randomUUID();
      com.fasterxml.jackson.databind.JsonNode config = vcpConfig();
      StrategyRepository.StrategyRow strategyRow =
          new StrategyRepository.StrategyRow(
              strategyId, "minervini-vcp", "Minervini VCP", null, null, List.of("minervini"),
              true, publishedVersion, null, null, false, null);
      org.mockito.Mockito.when(registry.listAll()).thenReturn(List.of(strategyRow));
      org.mockito.Mockito.when(registry.findVersionById(publishedVersion))
          .thenReturn(Optional.of(version(publishedVersion, strategyId, "1", config)));
      org.mockito.Mockito.when(signals.activeEntries())
          .thenReturn(
              List.of(
                  new in.arthayantra.strategysignal.signals.SignalRepository.SignalRow(
                      42L, publishedVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY",
                      new BigDecimal("152"), null, null, BigDecimal.ONE,
                      new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                      "TAKEN", series.get(0).bucketStart(), series.get(0).bucketStart().plusDays(1),
                      null, null, null, null, null, null)));
      org.mockito.Mockito.when(signals.insert(
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()))
          .thenReturn(43L);
      org.mockito.Mockito.when(funnel.buyableAndOnDeck()).thenReturn(List.of());
    }

    MinerviniSwingEngine engine() {
      org.springframework.transaction.support.TransactionTemplate tx =
          mock(org.springframework.transaction.support.TransactionTemplate.class);
      org.mockito.Mockito.when(tx.execute(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(inv ->
              inv.<org.springframework.transaction.support.TransactionCallback<Long>>getArgument(0)
                  .doInTransaction(null));
      return new MinerviniSwingEngine(
          registry, funnel, candles, signals,
          mock(in.arthayantra.strategysignal.signals.SignalPublisher.class),
          mock(org.springframework.context.ApplicationEventPublisher.class), Optional.empty(),
          tx, new com.fasterxml.jackson.databind.ObjectMapper(),
          java.time.Clock.systemUTC(), true, 520, 60, 1440);
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode vcpConfig() throws IOException {
    try (InputStream in =
        MinerviniSwingEngineTest.class.getResourceAsStream("/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyDocuments.parse(yaml).config();
    }
  }

  private static StrategyRepository.VersionRow version(
      java.util.UUID id, java.util.UUID strategyId, String version,
      com.fasterxml.jackson.databind.JsonNode config) {
    return new StrategyRepository.VersionRow(
        id, strategyId, version, null, config, "1", "chk-" + version, "published", null, null,
        null, null);
  }

  /** 25 daily bars: a rise 100→149, a consolidation 146–148 below the 150 pivot, then a breakout to 152 on the LAST bar. */
  private static List<EngineCandle> craft(long breakoutVolume) {
    double[] tail = {146, 148, 146, 148, 147}; // consolidation below the pivot
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 18; d++) {
      bars.add(bar(d, 100.0 + (149.0 - 100.0) * d / 18.0, 1_000L));
    }
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(19 + i, tail[i], 1_000L));
    }
    bars.add(bar(24, 152.0, breakoutVolume)); // the breakout bar (crossover above 150) — LAST bar
    return bars;
  }

  /** 25 daily bars flat at ~150 then a slide to 135 on the last bar (an underwater held position). */
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
