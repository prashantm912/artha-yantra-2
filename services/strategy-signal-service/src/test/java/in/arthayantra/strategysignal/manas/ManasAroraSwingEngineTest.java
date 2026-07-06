package in.arthayantra.strategysignal.manas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.SignalEmitted;
import in.arthayantra.strategysignal.signals.SignalExited;
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
 * are entry-index-dependent, so an unevaluated exit here mis-manages a real live-paper stop), plus
 * the F2 §3.4 pyramiding logic (the +gain trigger, the ≤6% open-risk cap, and the exit grouping that
 * closes ALL lots of a pyramided symbol at once — §3.5.D).
 */
class ManasAroraSwingEngineTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  // ---- §3.4.1 pyramid trigger (pure) ----------------------------------------------------------

  @Test
  void movedUpTriggersOnlyAtOrAboveTheGainThreshold() {
    BigDecimal five = new BigDecimal("5.0");
    assertThat(ManasAroraSwingEngine.movedUp(bd(105), bd(100), five)).as("exactly +5%").isTrue();
    assertThat(ManasAroraSwingEngine.movedUp(new BigDecimal("104.99"), bd(100), five))
        .as("just under +5%")
        .isFalse();
    assertThat(ManasAroraSwingEngine.movedUp(bd(110), bd(100), five)).as("+10%").isTrue();
    // Guards: unknown/non-positive last-lot entry never triggers an add.
    assertThat(ManasAroraSwingEngine.movedUp(bd(150), null, five)).isFalse();
    assertThat(ManasAroraSwingEngine.movedUp(null, bd(100), five)).isFalse();
    assertThat(ManasAroraSwingEngine.movedUp(bd(150), BigDecimal.ZERO, five)).isFalse();
  }

  // ---- §3.4.3 open-risk cap (pure) ------------------------------------------------------------

  @Test
  void breachesRiskCapAddsTheNewLotRiskToTheExistingBookRisk() {
    BigDecimal equity = bd(1_000_000);
    BigDecimal cap = new BigDecimal("6.0");
    // existing 4% (₹40k) + new lot 1% (100 × ₹100 stop = ₹10k) = 5% ≤ 6% → allowed.
    assertThat(
            ManasAroraSwingEngine.breachesRiskCap(bd(40_000), bd(100), bd(100), equity, cap))
        .as("5% total is within the 6% cap")
        .isFalse();
    // existing 5.5% (₹55k) + new lot 1% = 6.5% > 6% → blocked.
    assertThat(
            ManasAroraSwingEngine.breachesRiskCap(bd(55_000), bd(100), bd(100), equity, cap))
        .as("6.5% total breaches the 6% cap")
        .isTrue();
    // Missing inputs never block (the add just is not auto-papered / cannot be risk-sized).
    assertThat(ManasAroraSwingEngine.breachesRiskCap(bd(55_000), null, bd(100), equity, cap))
        .isFalse();
    assertThat(ManasAroraSwingEngine.breachesRiskCap(bd(55_000), bd(100), bd(100), null, cap))
        .isFalse();
    assertThat(
            ManasAroraSwingEngine.breachesRiskCap(bd(55_000), bd(100), BigDecimal.ZERO, equity, cap))
        .isFalse();
  }

  // ---- exit-pass fetch-failure handling (audit P0-3) ------------------------------------------

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

  // ---- F2 pyramiding: exit grouping + the OFF-path skip ---------------------------------------

  @Test
  void aPyramidedSymbolExitsAllLotsAtOnceAndExpiresEverySibling() throws IOException {
    ExitHarness h = new ExitHarness();
    // TWO open lots for the SAME symbol (the pyramid): lot 42 (older) + lot 43 (a later add). They
    // share one averaged position; the decline trips the stop off the OLDEST lot (42). The anchors sit
    // deep enough in the series for the entry-pinned ATR(20) initial stop to be computable.
    h.stubAnchors(
        h.anchor(42L, h.series.get(24).bucketStart()),
        h.anchor(43L, h.series.get(25).bucketStart()));
    org.mockito.Mockito.when(h.candles.fetch(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(h.series);

    ManasAroraSwingEngine.ManasSwingRun run = h.engine().runDaily();

    assertThat(run.exits()).as("ONE symbol closed (not one-per-lot)").isEqualTo(1);
    // Both lots' anchors are expired (§3.5.D closes all lots at once — no phantom active add lingers).
    verify(h.signals).transition(42L, "EXPIRED");
    verify(h.signals).transition(43L, "EXPIRED");
    // A close fires for EACH lot's linked signal so the shared position closes regardless of which
    // lot's order opened it (closeForSignal is a CAS — the first wins, the rest no-op).
    verify(h.events)
        .publishEvent(argThat((Object e) -> e instanceof SignalExited s && s.anchorSignalId() == 42L));
    verify(h.events)
        .publishEvent(argThat((Object e) -> e instanceof SignalExited s && s.anchorSignalId() == 43L));
  }

  @Test
  void withPyramidingOffAHeldSymbolInTheFunnelIsNeverReEntered() throws IOException {
    ExitHarness h = new ExitHarness();
    // TESTCO is already held (the anchor) AND back in the funnel at a higher price — with pyramiding
    // OFF this must NOT emit a second entry (the pre-F2 held-skip).
    org.mockito.Mockito.when(h.funnel.buyableAndOnDeck())
        .thenReturn(
            List.of(
                new ManasFunnelClient.Candidate(
                    "TESTCO", bd(200), bd(150), "breakout", null, bd(150), null)));

    h.engine(false).runDaily();

    verify(h.events, never()).publishEvent(argThat((Object e) -> e instanceof SignalEmitted));
  }

  // ---- harness --------------------------------------------------------------------------------

  /** Shared wiring: one published Manas strategy (real breakout YAML) + a held anchor, empty funnel. */
  private final class ExitHarness {
    final StrategyRepository registry = mock(StrategyRepository.class);
    final in.arthayantra.strategysignal.signals.SignalRepository signals =
        mock(in.arthayantra.strategysignal.signals.SignalRepository.class);
    final ManasFunnelClient funnel = mock(ManasFunnelClient.class);
    final in.arthayantra.strategysignal.signals.MarketDataCandlesClient candles =
        mock(in.arthayantra.strategysignal.signals.MarketDataCandlesClient.class);
    final org.springframework.context.ApplicationEventPublisher events =
        mock(org.springframework.context.ApplicationEventPublisher.class);
    final List<EngineCandle> series = craftDecline();
    final java.util.UUID publishedVersion = java.util.UUID.randomUUID();

    ExitHarness() throws IOException {
      java.util.UUID strategyId = java.util.UUID.randomUUID();
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
      stubAnchors(anchor(42L, series.get(0).bucketStart()));
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

    /** Registers the given anchors as the active held entries (varargs so a pyramid has ≥2). */
    void stubAnchors(in.arthayantra.strategysignal.signals.SignalRepository.SignalRow... anchors) {
      org.mockito.Mockito.when(signals.activeEntries()).thenReturn(List.of(anchors));
    }

    /** A held TESTCO anchor generated at {@code at} (entry 152, TAKEN). */
    in.arthayantra.strategysignal.signals.SignalRepository.SignalRow anchor(long id, OffsetDateTime at) {
      return new in.arthayantra.strategysignal.signals.SignalRepository.SignalRow(
          id, publishedVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY", new BigDecimal("152"),
          null, null, BigDecimal.ONE,
          new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
          "TAKEN", at, at.plusDays(1), null, null, null, null, null, null);
    }

    ManasAroraSwingEngine engine() {
      return engine(false);
    }

    ManasAroraSwingEngine engine(boolean pyramidEnabled) {
      org.springframework.transaction.support.TransactionTemplate tx =
          mock(org.springframework.transaction.support.TransactionTemplate.class);
      org.mockito.Mockito.when(tx.execute(org.mockito.ArgumentMatchers.any()))
          .thenAnswer(inv ->
              inv.<org.springframework.transaction.support.TransactionCallback<Long>>getArgument(0)
                  .doInTransaction(null));
      return new ManasAroraSwingEngine(
          registry, funnel, candles, signals,
          mock(in.arthayantra.strategysignal.signals.SignalPublisher.class),
          events, Optional.empty(),
          tx, new com.fasterxml.jackson.databind.ObjectMapper(),
          java.time.Clock.systemUTC(), true, 520, 60, 1440,
          pyramidEnabled, new BigDecimal("5.0"), 3, new BigDecimal("6.0"));
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

  /**
   * 28 daily bars around ~150 (a small ±1 range so the entry-pinned ATR(20) is a positive number, not
   * a degenerate 0) then a slide to 120 on the last two bars — an underwater held position whose close
   * is well below the ~10%-capped 2×ATR initial stop.
   */
  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      bars.add(bar(d, 150.0));
    }
    bars.add(bar(26, 140.0));
    bars.add(bar(27, 120.0));
    return bars;
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal c = BigDecimal.valueOf(price);
    // a small high/low spread (±1) gives ATR(20) a positive value so the 2×ATR initial stop computes.
    BigDecimal high = BigDecimal.valueOf(price + 1);
    BigDecimal low = BigDecimal.valueOf(price - 1);
    return new EngineCandle(bucket, c, high, low, c, 1_000L, null);
  }

  private static BigDecimal bd(long v) {
    return BigDecimal.valueOf(v);
  }
}
