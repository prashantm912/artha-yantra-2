package in.arthayantra.strategyengine;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.Test;

/**
 * PSAR is a stop-and-reverse PRICE LEVEL. Vector-pinning is deliberately left to the Phase 23
 * end-to-end goldens — the acceleration-factor ratchet is ta4j-internal (same character as
 * SUPERTREND), so it is behavior-tested here instead: a sustained rise keeps the SAR BELOW close,
 * a sustained fall keeps it ABOVE close, a V-shape flips side, and warm-up returns null. The
 * "close &gt; PSAR = long bias" decision is an S12 strategy-YAML gate, never in the indicator.
 */
class PsarBehaviorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final Map<String, Object> DEFAULTS = Map.of(); // step 0.02 / max 0.2

  private static long risingClose(int i) {
    return 10000 + 40L * i;
  }

  private static long fallingClose(int i) {
    return 14000 - 40L * i;
  }

  private static EngineSeries series(String symbol, IntToLongFunction closeCents, int bars) {
    List<EngineCandle> candles = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      candles.add(candle(i, closeCents.applyAsLong(i)));
    }
    return EngineSeries.of(new SeriesKey("NSE", symbol, "1m"), candles);
  }

  private static EngineCandle candle(int i, long closeCents) {
    BigDecimal close = BigDecimal.valueOf(closeCents, 2);
    return new EngineCandle(
        OffsetDateTime.of(2026, 2, 3, 9, 15, 0, 0, IST).plusMinutes(i),
        close,
        close.add(new BigDecimal("0.40")),
        close.subtract(new BigDecimal("0.40")),
        close,
        1000);
  }

  @Test
  void sustainedRiseKeepsSarBelowClose() {
    EngineIndicator psar =
        IndicatorRegistry.create(
            "PSAR", series("RISE", PsarBehaviorTest::risingClose, 60), null, DEFAULTS);
    for (int i = 30; i < 60; i++) {
      assertThat(psar.valueAt(i))
          .as("PSAR[%d] stays below close on a sustained rise", i)
          .isLessThan(BigDecimal.valueOf(risingClose(i), 2));
    }
  }

  @Test
  void sustainedFallKeepsSarAboveClose() {
    EngineIndicator psar =
        IndicatorRegistry.create(
            "PSAR", series("FALL", PsarBehaviorTest::fallingClose, 60), null, DEFAULTS);
    for (int i = 30; i < 60; i++) {
      assertThat(psar.valueAt(i))
          .as("PSAR[%d] stays above close on a sustained fall", i)
          .isGreaterThan(BigDecimal.valueOf(fallingClose(i), 2));
    }
  }

  @Test
  void vShapeFlipsAndWarmsUpNull() {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      candles.add(candle(i, 14000 - 60L * i)); // sharp fall
    }
    for (int i = 40; i < 100; i++) {
      candles.add(candle(i, 14000 - 60L * 39 + 60L * (i - 39))); // sharp recovery
    }
    EngineSeries series = EngineSeries.of(new SeriesKey("NSE", "VSHAPE", "1m"), candles);
    EngineIndicator psar = IndicatorRegistry.create("PSAR", series, null, DEFAULTS);

    assertThat(psar.valueAt(35))
        .as("downtrend: SAR above close")
        .isGreaterThan(BigDecimal.valueOf(14000 - 60L * 35, 2));
    assertThat(psar.valueAt(95))
        .as("uptrend after recovery: SAR below close")
        .isLessThan(BigDecimal.valueOf(14000 - 60L * 39 + 60L * (95 - 39), 2));
    assertThat(psar.valueAt(0)).as("warm-up returns null").isNull();
  }
}
