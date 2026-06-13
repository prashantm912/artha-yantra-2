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
 * SUPERTREND direction behavior (vector-pinning is deliberately left to the Phase 23 end-to-end
 * goldens — the band ratchet is ta4j-internal): a sustained rise reads +1, a sustained fall
 * reads -1, and a V-shape flips down then back up.
 */
class SupertrendBehaviorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static EngineSeries trending(int bars, boolean rising) {
    List<EngineCandle> candles = new ArrayList<>(bars);
    for (int i = 0; i < bars; i++) {
      long c = rising ? 10000 + 40L * i : 14000 - 40L * i;
      candles.add(candle(i, c));
    }
    return EngineSeries.of(new SeriesKey("NSE", "TREND", "1m"), candles);
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
  void sustainedRiseReadsUpTrend() {
    EngineIndicator st =
        IndicatorRegistry.create(
            "SUPERTREND", trending(60, true), null, Map.of("period", 10, "multiplier", 2.0));

    for (int i = 30; i < 60; i++) {
      assertThat(st.valueAt(i)).as("index %d", i).isEqualByComparingTo(BigDecimal.ONE);
    }
  }

  @Test
  void sustainedFallReadsDownTrend() {
    EngineIndicator st =
        IndicatorRegistry.create(
            "SUPERTREND", trending(60, false), null, Map.of("period", 10, "multiplier", 2.0));

    for (int i = 30; i < 60; i++) {
      assertThat(st.valueAt(i)).as("index %d", i).isEqualByComparingTo(BigDecimal.ONE.negate());
    }
  }

  @Test
  void vShapeFlipsDownThenUp() {
    List<EngineCandle> candles = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      candles.add(candle(i, 14000 - 60L * i)); // sharp fall
    }
    for (int i = 40; i < 100; i++) {
      candles.add(candle(i, 14000 - 60L * 39 + 60L * (i - 39))); // sharp recovery
    }
    EngineSeries series = EngineSeries.of(new SeriesKey("NSE", "VSHAPE", "1m"), candles);
    EngineIndicator st =
        IndicatorRegistry.create(
            "SUPERTREND", series, null, Map.of("period", 10, "multiplier", 2.0));

    assertThat(st.valueAt(35)).isEqualByComparingTo(BigDecimal.ONE.negate());
    assertThat(st.valueAt(95)).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(st.valueAt(5)).as("warm-up returns null").isNull();
  }
}
