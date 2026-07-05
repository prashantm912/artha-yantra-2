package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
 * Pins the audit-P0-3 exit-pass fetch-failure handling on the MANAS clone (the Minervini twin has
 * the fuller suite; this clone is where the skip paths are MOST load-bearing — the #573 ATR exits
 * are entry-index-dependent, so an unevaluated exit here mis-manages a real live-paper stop).
 */
class ManasAroraSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Test
  void exitPassRetriesAFailedFetchOnceThenEvaluates() throws IOException {
    ExitHarness h = new ExitHarness();
    org.mockito.Mockito.when(h.candles.fetch(
            org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of())
        .thenReturn(h.series);

    ManasAroraSwingEngine.ManasSwingRun run = h.engine().runDaily();

    assertThat(run.exitSkipped()).as("the retry recovers the series — nothing skipped").isZero();
    org.mockito.Mockito.verify(h.candles, org.mockito.Mockito.times(2))
        .fetch(org.mockito.ArgumentMatchers.eq("NSE"), org.mockito.ArgumentMatchers.eq("TESTCO"),
            org.mockito.ArgumentMatchers.eq("1d"), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void exitPassCountsAndScreamsWhenTheSeriesIsUnavailableAfterRetry() throws IOException {
    ExitHarness h = new ExitHarness();
    org.mockito.Mockito.when(h.candles.fetch(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());

    ManasAroraSwingEngine.ManasSwingRun run = h.engine().runDaily();

    assertThat(run.exits()).isZero();
    assertThat(run.exitSkipped()).as("the unevaluated ATR stop is surfaced, not swallowed").isEqualTo(1);
  }

  /** Shared wiring: one published Manas anchor (real breakout YAML), empty funnel. */
  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    final ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    final in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    final List<EngineCandle> series = craftDecline();

    ExitHarness() throws IOException {
      java.util.UUID strategyId = java.util.UUID.randomUUID();
      java.util.UUID publishedVersion = java.util.UUID.randomUUID();
      com.fasterxml.jackson.databind.JsonNode config = breakoutConfig();
      StrategyRepository.StrategyRow strategyRow =
          new StrategyRepository.StrategyRow(
              strategyId, "manas-arora-breakout", "Manas Breakout", null, null,
              List.of("manas-arora"), true, publishedVersion, null, null, false, null);
      org.mockito.Mockito.when(registry.listAll()).thenReturn(List.of(strategyRow));
      org.mockito.Mockito.when(registry.findVersionById(publishedVersion))
          .thenReturn(
              Optional.of(
                  new StrategyRepository.VersionRow(
                      publishedVersion, strategyId, "1", null, config, "1", "chk", "published",
                      null, null, null, null)));
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

    ManasAroraSwingEngine engine() {
      org.springframework.transaction.support.TransactionTemplate tx =
          mock(org.springframework.transaction.support.TransactionTemplate.class);
      org.mockito.Mockito.when(tx.execute(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(inv ->
              inv.<org.springframework.transaction.support.TransactionCallback<Long>>getArgument(0)
                  .doInTransaction(null));
      return new ManasAroraSwingEngine(
          registry, funnel, candles, signals,
          mock(in.arthayantra.strategysignal.signals.SignalPublisher.class),
          mock(org.springframework.context.ApplicationEventPublisher.class), Optional.empty(),
          tx, new com.fasterxml.jackson.databind.ObjectMapper(),
          java.time.Clock.systemUTC(), true, 520, 60, 1440);
    }
  }

  private static com.fasterxml.jackson.databind.JsonNode breakoutConfig() throws IOException {
    try (InputStream in =
        ManasAroraSwingEngineTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return StrategyDocuments.parse(yaml).config();
    }
  }

  /** 25 daily bars flat at ~150 then a slide to 135 on the last bar (an underwater held position). */
  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 23; d++) {
      bars.add(bar(d, 150.0));
    }
    bars.add(bar(24, 135.0));
    return bars;
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal p = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, p, p, p, p, 1_000L, null);
  }
}
