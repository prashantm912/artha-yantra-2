package in.arthayantra.strategyengine;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Bar-for-bar recreation of the synthetic series behind the committed reference vectors —
 * the SAME integer-cents formulas as tools/indicator-vectors/generate_vectors.py. Two IST
 * sessions (2026-02-03 / 2026-02-04), 40 one-minute bars each; integer cents make every price
 * exact. The CSVs hold only EXPECTED outputs; inputs are reproduced here.
 */
final class VectorFixtures {

  static final int BARS_PER_DAY = 40;
  static final int TOTAL_BARS = 80;
  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private VectorFixtures() {}

  static EngineSeries primarySeries() {
    List<EngineCandle> candles = new ArrayList<>(TOTAL_BARS);
    for (int i = 0; i < TOTAL_BARS; i++) {
      long closeC = 10000 + 7L * i + (((long) i * i) % 13) * 3;
      long openC = closeC - 5 - (i % 4) * 3L;
      long highC = Math.max(openC, closeC) + 5 + (i % 3) * 5L;
      long lowC = Math.min(openC, closeC) - 5 - ((i + 1) % 3) * 5L;
      long volume = 1000 + (i * 37L) % 250;
      long oi = 100000 + i * 50L - (i % 7) * 120L;
      candles.add(
          new EngineCandle(
              bucket(i), cents(openC), cents(highC), cents(lowC), cents(closeC), volume,
              BigDecimal.valueOf(oi)));
    }
    return EngineSeries.of(new SeriesKey("NSE", "VECTOR", "1m"), candles);
  }

  static EngineSeries contextSeries() {
    List<EngineCandle> candles = new ArrayList<>(TOTAL_BARS);
    for (int i = 0; i < TOTAL_BARS; i++) {
      long closeC = 20000 + 3L * i + (i % 5) * 4L;
      BigDecimal close = cents(closeC);
      candles.add(new EngineCandle(bucket(i), close, close, close, close, 0));
    }
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY 50", "1m"), candles);
  }

  private static OffsetDateTime bucket(int i) {
    int day = i / BARS_PER_DAY;
    int minute = i % BARS_PER_DAY;
    return OffsetDateTime.of(2026, 2, 3 + day, 9, 15, 0, 0, IST).plusMinutes(minute);
  }

  private static BigDecimal cents(long c) {
    return BigDecimal.valueOf(c, 2);
  }
}
