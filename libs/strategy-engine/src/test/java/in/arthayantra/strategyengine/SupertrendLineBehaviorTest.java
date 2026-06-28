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
import org.junit.jupiter.api.Test;

/**
 * SUPERTREND_LINE price-level behavior (vector-pinning left to the goldens, like SUPERTREND/PSAR — the
 * band ratchet is ta4j-internal): the trailing-stop line sits BELOW the close in a sustained uptrend
 * and ABOVE it in a sustained downtrend, and is null during warm-up. This is the level the
 * direction-only SUPERTREND dot could not expose (the E6 ST-distance / E9 ST-level stop seam).
 */
class SupertrendLineBehaviorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static EngineSeries trending(int bars, boolean rising) {
    List<EngineCandle> candles = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      candles.add(candle(i, closeCents(i, rising)));
    }
    return EngineSeries.of(new SeriesKey("NSE", "TREND", "1m"), candles);
  }

  private static long closeCents(int i, boolean rising) {
    return rising ? 10000 + 40L * i : 14000 - 40L * i;
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
  void upTrendLineSitsBelowClose() {
    EngineIndicator line =
        IndicatorRegistry.create(
            "SUPERTREND_LINE", trending(60, true), null, Map.of("period", 10, "multiplier", 2.0));

    for (int i = 30; i < 60; i++) {
      BigDecimal close = BigDecimal.valueOf(closeCents(i, true), 2);
      assertThat(line.valueAt(i)).as("index %d", i).isNotNull();
      assertThat(line.valueAt(i)).as("index %d below close", i).isLessThan(close);
    }
  }

  @Test
  void downTrendLineSitsAboveClose() {
    EngineIndicator line =
        IndicatorRegistry.create(
            "SUPERTREND_LINE", trending(60, false), null, Map.of("period", 10, "multiplier", 2.0));

    for (int i = 30; i < 60; i++) {
      BigDecimal close = BigDecimal.valueOf(closeCents(i, false), 2);
      assertThat(line.valueAt(i)).as("index %d", i).isNotNull();
      assertThat(line.valueAt(i)).as("index %d above close", i).isGreaterThan(close);
    }
  }

  @Test
  void warmUpReturnsNull() {
    EngineIndicator line =
        IndicatorRegistry.create(
            "SUPERTREND_LINE", trending(60, true), null, Map.of("period", 10, "multiplier", 2.0));
    assertThat(line.valueAt(5)).as("warm-up returns null").isNull();
  }
}
