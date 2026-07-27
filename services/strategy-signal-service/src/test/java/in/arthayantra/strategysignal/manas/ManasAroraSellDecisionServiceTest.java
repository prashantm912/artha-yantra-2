package in.arthayantra.strategysignal.manas;

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
 * The shared {@link SwingSellDecisionService} driven by the Manas doctrine: for an open holding it
 * re-scores today's daily bar off the persisted {@code manas_arora_detail} pivot and reports the
 * HOLD / SELL verdict via the FROZEN {@link in.arthayantra.strategyengine.eval.ExitEvaluator}.
 */
class ManasAroraSellDecisionServiceTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  @Test
  void reportsSellWhenTheAtrStopIsHitAndHoldWhenItIsNot() throws IOException {
    // Declining series → the 2×ATR stop fires → SELL.
    SwingSellDecisionService.SwingSellReport sell = report(craftDecline());
    assertThat(sell.items()).hasSize(1);
    SwingSellDecisionService.SwingSellDecision d = sell.items().get(0);
    assertThat(d.symbol()).isEqualTo("TESTCO");
    assertThat(d.setup()).isEqualTo("manas-arora-breakout");
    assertThat(d.sellingNow()).as("the ATR stop fires on the declining series").isTrue();
    assertThat(d.verdict()).startsWith("SELL");

    // Flat series (no decline) → the stop does not fire → HOLD.
    SwingSellDecisionService.SwingSellReport hold = report(craftFlat());
    assertThat(hold.items()).hasSize(1);
    assertThat(hold.items().get(0).sellingNow()).isFalse();
    assertThat(hold.items().get(0).verdict()).isEqualTo("HOLD");
  }

  private SwingSellDecisionService.SwingSellReport report(List<EngineCandle> series) throws IOException {
    UUID strategyId = UUID.randomUUID();
    UUID publishedVersion = UUID.randomUUID();
    JsonNode config = breakoutConfig();
    ObjectMapper om = new ObjectMapper();

    StrategyRepository registry = mock(StrategyRepository.class);
    StrategyRepository.StrategyRow strategyRow =
        new StrategyRepository.StrategyRow(
            strategyId, "manas-arora-breakout", "Manas Breakout", null, null, List.of("manas-arora"),
            true, publishedVersion, null, null, false, null);
    when(registry.listAll()).thenReturn(List.of(strategyRow));
    when(registry.findVersionById(publishedVersion))
        .thenReturn(
            Optional.of(
                new StrategyRepository.VersionRow(
                    publishedVersion, strategyId, "1", null, config, "1", "chk", "published",
                    null, null, null, null)));

    JsonNode detail = om.readTree("{\"setup\":\"manas-arora-breakout\",\"pivot\":\"150\"}");
    // Anchor deep enough for the entry-pinned ATR(20) to have warmup (bar 0 is degenerate → no stop).
    OffsetDateTime entryAt = series.get(24).bucketStart();
    SignalRepository signals = mock(SignalRepository.class);
    when(signals.activeEntries())
        .thenReturn(
            List.of(
                new SignalRepository.SignalRow(
                    42L, publishedVersion, "NSE", "TESTCO", "1d", "ENTRY", "BUY", new BigDecimal("152"),
                    null, null, BigDecimal.ONE, om.createObjectNode(), "TAKEN", entryAt,
                    entryAt.plusDays(1), null, null, null, null, null, detail, null)));

    MarketDataCandlesClient candles = mock(MarketDataCandlesClient.class);
    when(candles.fetch(eq("NSE"), eq("TESTCO"), eq("1d"), any(), any())).thenReturn(series);

    ManasDoctrine doctrine =
        new ManasDoctrine(
            mock(ManasFunnelClient.class), signals,
            new ManasPyramidPolicy(false, new BigDecimal("5.0"), 3, new BigDecimal("6.0")),
            om, true, 520, 60, 1440);

    return new SwingSellDecisionService(
            registry, candles, signals, mock(SellDecisionRepository.class), Clock.systemUTC())
        .report(doctrine);
  }

  private static JsonNode breakoutConfig() throws IOException {
    try (InputStream in =
        ManasAroraSellDecisionServiceTest.class.getResourceAsStream(
            "/manas-arora-strategies/manas-arora-breakout.yaml")) {
      assertThat(in).isNotNull();
      return StrategyDocuments.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).config();
    }
  }

  /** 28 bars ~150 then a slide to 120 — trips the entry-pinned 2×ATR stop. */
  private static List<EngineCandle> craftDecline() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 25; d++) {
      bars.add(bar(d, 150.0));
    }
    bars.add(bar(26, 140.0));
    bars.add(bar(27, 120.0));
    return bars;
  }

  /** 28 flat bars at ~150 — no stop breach → HOLD. */
  private static List<EngineCandle> craftFlat() {
    List<EngineCandle> bars = new ArrayList<>();
    for (int d = 0; d <= 27; d++) {
      bars.add(bar(d, 150.0));
    }
    return bars;
  }

  private static EngineCandle bar(int day, double price) {
    OffsetDateTime bucket = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, IST).plusDays(day);
    BigDecimal c = BigDecimal.valueOf(price);
    return new EngineCandle(bucket, c, BigDecimal.valueOf(price + 1), BigDecimal.valueOf(price - 1), c, 1_000L, null);
  }
}
