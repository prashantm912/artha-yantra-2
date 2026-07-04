package in.arthayantra.strategyengine;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.indicators.EngineIndicator;
import in.arthayantra.strategyengine.indicators.IndicatorRegistry;
import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MV-6.6 indicator: {@code WEEK52_HIGH}/{@code WEEK52_LOW} are the trailing high/low over the prior
 * {@code period} bars — EXCLUDING the current bar (so {@code close > WEEK52_HIGH} is a fresh high).
 * Direct, independent check on a known series.
 */
class Week52BehaviorTest {

  // highs 10,12,11,15,13,14  /  lows 9,11,10,14,12,13
  private static final double[] HIGHS = {10, 12, 11, 15, 13, 14};

  @Test
  void trailingHighExcludesTheCurrentBar() {
    EngineIndicator w = IndicatorRegistry.create("WEEK52_HIGH", series(), null, java.util.Map.of("period", 3));
    assertThat(w.valueAt(2)).as("warm-up: fewer than 3 prior bars").isNull();
    assertThat(w.valueAt(3)).isEqualByComparingTo("12"); // max(10,12,11)
    assertThat(w.valueAt(4)).isEqualByComparingTo("15"); // max(12,11,15)
    assertThat(w.valueAt(5)).isEqualByComparingTo("15"); // max(11,15,13)
  }

  @Test
  void trailingLowExcludesTheCurrentBar() {
    EngineIndicator w = IndicatorRegistry.create("WEEK52_LOW", series(), null, java.util.Map.of("period", 3));
    assertThat(w.valueAt(2)).isNull();
    assertThat(w.valueAt(3)).isEqualByComparingTo("9"); // min(9,11,10)
    assertThat(w.valueAt(4)).isEqualByComparingTo("10"); // min(11,10,14)
    assertThat(w.valueAt(5)).isEqualByComparingTo("10"); // min(10,14,12)
  }

  private static EngineSeries series() {
    List<EngineCandle> candles = new ArrayList<>();
    LocalDate day = LocalDate.of(2026, 6, 1);
    for (int i = 0; i < HIGHS.length; i++) {
      BigDecimal high = BigDecimal.valueOf(HIGHS[i]);
      BigDecimal low = BigDecimal.valueOf(HIGHS[i] - 1);
      candles.add(
          new EngineCandle(
              OffsetDateTime.of(day.plusDays(i), LocalTime.of(9, 15), ZoneOffset.ofHoursMinutes(5, 30)),
              low, high, low, low, 1_000L));
    }
    return EngineSeries.of(new SeriesKey("NSE", "TEST", "1d"), candles);
  }
}
