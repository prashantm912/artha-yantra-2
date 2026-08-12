package in.arthayantra.strategysignal.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyschema.StrategyDocuments;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import in.arthayantra.strategysignal.signals.MarketDataCandlesClient;
import in.arthayantra.strategysignal.signals.SignalRepository;
import in.arthayantra.strategysignal.swing.SellDecisionRepository;
import in.arthayantra.strategysignal.swing.SwingSellDecisionService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The shared {@link SwingSellDecisionService} driven by the MINERVINI doctrine.
 *
 * <p><b>Why this class exists.</b> {@code SwingSellDecisionService} is shared and dispatches through
 * {@code SwingDoctrine}, but until 2026-07-30 the ONLY test of it was
 * {@code ManasAroraSellDecisionServiceTest} — so every claim about the service was really a claim
 * about one doctrine. The two are ASYMMETRIC in exactly the place that matters, and the gap let a
 * proposed change look safe while it silently broke the untested side:
 *
 * <ul>
 *   <li>MANAS — stop is {@code basis: atr_multiple}, so {@code ExitEvaluator.entryLevels} resolves
 *       {@code ATR.valueAt(0)}, which is null for want of warmup; and {@code ManasDoctrine.trailLevel}
 *       carries its own {@code entryIndex >= 0} guard. Both advisory levels come out NULL when the
 *       entry bar predates the fetched window.
 *   <li>MINERVINI — stop is {@code basis: percent, value: 8}, which reads only
 *       {@code position.entryPrice()} and NEVER the series; and {@link MinerviniDoctrine#trailLevel}
 *       ignores {@code position}/{@code entryIndex} entirely, returning sma50 at {@code lastIndex}.
 *       Both advisory levels are VALID NUMBERS in the same state.
 * </ul>
 *
 * <p>A change that forced both levels to null when {@code entryIndex < 0} passed the Manas test AND
 * its red-proof, and would have suppressed Minervini's 8% stop and 50-day trail on the sell-decisions
 * page. It was caught only by cross-vendor review and reverted. {@link
 * #keepsBothAdvisoryLevelsWhenTheEntryBarPredatesTheWindow()} is the guard that was missing.
 */
class MinerviniSellDecisionServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  /** MV-7.2: the protective stop sits 8% below entry, so the level is entry × 0.92. */
  private static final BigDecimal STOP_FACTOR = new BigDecimal("0.92");

  private static final BigDecimal ENTRY_PRICE = new BigDecimal("150");

  @Test
  void reportsTheEightPercentStopAndTheFiftyDayTrailForAnOpenHolding() throws IOException {
    List<EngineCandle> series = craftRising();
    SwingSellDecisionService.SwingSellReport out = report(series, series.get(60).bucketStart());

    assertThat(out.items()).hasSize(1);
    SwingSellDecisionService.SwingSellDecision d = out.items().get(0);
    assertThat(d.symbol()).isEqualTo("TESTCO");

    // MV-7.2 — percent basis reads only the entry price, never the series
    assertThat(d.stopLevel())
        .as("8%% protective stop = entry × 0.92")
        .isEqualByComparingTo(ENTRY_PRICE.multiply(STOP_FACTOR));
    // MV-7.4 — the trail is sma50 at the LAST bar, independent of the entry index
    assertThat(d.trailLevel())
        .as("50-day-MA trail at the last bar")
        .isEqualByComparingTo(sma50At(series, series.size() - 1));

    assertThat(d.sellingNow()).as("close stays above both the 8%% stop and sma50").isFalse();
    assertThat(d.verdict()).isEqualTo("HOLD");
  }

  /**
   * THE REGRESSION GUARD. When the entry bar predates the fetched window,
   * {@code bank.primarySeries().indexAtOrBefore(...)} returns -1 and the service clamps it to 0 for
   * the two advisory levels while the sell VERDICT abstains via its own {@code entryIndex >= 0}
   * guard. Under Minervini neither level reads the entry index at all, so both must survive that
   * state UNCHANGED — identical to the in-window case above.
   *
   * <p>This is the assertion that had no coverage. Guarding the levels on {@code entryIndex >= 0}
   * looks harmless against Manas (where they are already null) and silently blanks two real numbers
   * here.
   */
  @Test
  void keepsBothAdvisoryLevelsWhenTheEntryBarPredatesTheWindow() throws IOException {
    List<EngineCandle> series = craftRising();
    // one day BEFORE bar 0 → indexAtOrBefore == -1
    OffsetDateTime beforeTheWindow = series.get(0).bucketStart().minusDays(1);

    SwingSellDecisionService.SwingSellReport out = report(series, beforeTheWindow);

    assertThat(out.items()).hasSize(1);
    SwingSellDecisionService.SwingSellDecision d = out.items().get(0);

    assertThat(d.stopLevel())
        .as("percent basis never reads the series, so a missing entry bar cannot blank it")
        .isEqualByComparingTo(ENTRY_PRICE.multiply(STOP_FACTOR));
    assertThat(d.trailLevel())
        .as("MinerviniDoctrine.trailLevel ignores entryIndex — sma50 at lastIndex stands")
        .isEqualByComparingTo(sma50At(series, series.size() - 1));

    // the verdict still abstains, exactly as it does under Manas — only the LEVELS differ
    assertThat(d.sellingNow()).isFalse();
    assertThat(d.verdict()).isEqualTo("HOLD");
  }

  @Test
  void sellsWhenThePriceBreaksTheEightPercentStop() throws IOException {
    List<EngineCandle> series = craftStopBreach();
    SwingSellDecisionService.SwingSellReport out = report(series, series.get(60).bucketStart());

    assertThat(out.items()).hasSize(1);
    SwingSellDecisionService.SwingSellDecision d = out.items().get(0);
    assertThat(d.sellingNow()).as("close 136.8 < entry × 0.92 = 138, while sma50 stays below the close").isTrue();
    assertThat(d.verdict()).startsWith("SELL");
    // the stop level is still reported alongside the SELL, not blanked
    assertThat(d.stopLevel()).isEqualByComparingTo(ENTRY_PRICE.multiply(STOP_FACTOR));
  }

  private SwingSellDecisionService.SwingSellReport report(
      List<EngineCandle> series, OffsetDateTime entryAt) throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = vcpConfig();
    ObjectMapper om = new ObjectMapper();

    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow =
        new StrategyRepository.StrategyRow(
            strategyId, "minervini-vcp", "Minervini VCP Breakout", null, null,
            List.of("minervini", "swing"), true, publishedVersion, null, null, false, null);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(
            Optional.of(
                new StrategyRepository.VersionRow(
                    publishedVersion, strategyId, "1", null, config, "1", "chk", "published",
                    null, null, null, null)));

    // the minervini_detail side-channel MinerviniDoctrine.detailOf reads off the anchor row
    JsonNode detail = om.readTree("{\"pivot\":\"149\",\"cheatPivot\":\"148\",\"thrust\":false}");
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(
            List.of(
                new SignalRepository.SignalRow(
                    77L, publishedVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY", ENTRY_PRICE,
                    null, null, BigDecimal.ONE, om.createObjectNode(), "TAKEN", entryAt,
                    entryAt.plusDays(1), null, null, null, null, detail, null, null)));

    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    MinerviniDoctrine doctrine =
        new MinerviniDoctrine(
            mock(MinerviniFunnelClient.class), signals, om, true, 520, 60, 1440);

    return new SwingSellDecisionService(
            registry, candles, signals, mock(SellDecisionRepository.class), Clock.systemUTC())
        .report(doctrine);
  }

  private static JsonNode vcpConfig() throws IOException {
    try (InputStream in =
        MinerviniSellDecisionServiceTest.class.getResourceAsStream(
            "/minervini-strategies/minervini-vcp.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    }
  }

  /** The simple mean of the last 50 closes — what SMA(50) resolves to at {@code index}. */
  private static BigDecimal sma50At(List<EngineCandle> series, int index) {
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = index - 49; i <= index; i++) {
      sum = sum.add(series.get(i).close());
    }
    return sum.divide(BigDecimal.valueOf(50), 10, RoundingMode.HALF_UP);
  }

  /**
   * 80 bars rising 150 → 165.8. Deliberately RISING, not flat: on a dead-flat series the close
   * equals sma50 and the MV-7.4 close-below trail fires, so a "flat" fixture would exercise the
   * trail rather than the HOLD path it claims to. Rising keeps close (165.8) above both sma50
   * (≈160.9) and the 8% stop (138), so neither exit rule fires.
   */
  private static List<EngineCandle> craftRising() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d < 80; d++) {
      bars.add(bar(d, 150.0 + d * 0.2));
    }
    return bars;
  }

  /**
   * 85 bars rising 120 → 136.8, against an entry of 150. Isolates the 8% STOP: the close (136.8) is
   * below entry × 0.92 = 138, while sma50 (≈131.9) stays BELOW the close, so the 50-day trail is
   * provably not breached and the SELL can only come from MV-7.2. A falling series cannot isolate
   * it — sma50 sits above the close throughout, so both rules would fire and the reason would be
   * ambiguous.
   */
  private static List<EngineCandle> craftStopBreach() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d < 85; d++) {
      bars.add(bar(d, 120.0 + d * 0.2));
    }
    return bars;
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal c = BigDecimal.valueOf(price);
    return new EngineCandle(
        bucket, c, BigDecimal.valueOf(price + 1), BigDecimal.valueOf(price - 1), c, 1_000L, null);
  }
}
