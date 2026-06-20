package in.arthayantra.strategysignal.scalper;

import static in.arthayantra.black76.Black76.OptionType.CE;
import static in.arthayantra.black76.Black76.OptionType.PE;
import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import in.arthayantra.strategyengine.series.EngineSeries;
import in.arthayantra.strategyengine.series.SeriesKey;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §3.1 Two-Candle detector: two same-colour candles above the floor + a strong 2nd, deploy on 3rd. */
class TwoCandleGateTest {

  private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 6, 20, 10, 0, 0, 0, ZoneOffset.UTC);
  private static final long FLOOR = 130_000; // ≥ NIFTY 125k
  private static final long BELOW = 100_000; // < NIFTY 125k

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  // open, high, low, close, volume — a STRONG green candle: body 10, combined shadow 2 (< 2×body).
  private static EngineCandle green(int i, long volume) {
    return new EngineCandle(T0.plusMinutes(i), bd("100"), bd("111"), bd("99"), bd("110"), volume);
  }

  private static EngineCandle red(int i, long volume) {
    return new EngineCandle(T0.plusMinutes(i), bd("110"), bd("111"), bd("99"), bd("100"), volume);
  }

  // a WEAK candle: body 1, combined shadow 14 (≥ 2×body) → rejection, fails the strength test.
  private static EngineCandle weakGreen(int i, long volume) {
    return new EngineCandle(T0.plusMinutes(i), bd("100"), bd("110"), bd("95"), bd("101"), volume);
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "3m"), List.of(candles));
  }

  @Test
  void detectsTwoGreenCandlesAndAnchorsStopOnTheFirstCandleLow() {
    EngineSeries s = series(green(0, FLOOR), green(1, FLOOR), green(2, FLOOR));
    TwoCandleGate.Formation f = TwoCandleGate.detect(s, 2, CE, "NIFTY 50");
    assertThat(f.present()).isTrue();
    assertThat(f.stopLevel()).isEqualByComparingTo("99"); // 1st candle (index 0) low
  }

  @Test
  void detectsTwoRedCandlesAndAnchorsStopOnTheFirstCandleHigh() {
    EngineSeries s = series(red(0, FLOOR), red(1, FLOOR), red(2, FLOOR));
    TwoCandleGate.Formation f = TwoCandleGate.detect(s, 2, PE, "NIFTY 50");
    assertThat(f.present()).isTrue();
    assertThat(f.stopLevel()).isEqualByComparingTo("111"); // 1st candle high
  }

  @Test
  void rejectsWhenColoursDoNotMatchTheSide() {
    // CE wants two green; a red 1st candle is not the bullish formation
    EngineSeries s = series(red(0, FLOOR), green(1, FLOOR), green(2, FLOOR));
    assertThat(TwoCandleGate.detect(s, 2, CE, "NIFTY 50").present()).isFalse();
  }

  @Test
  void rejectsWhenEitherCandleMissesTheVolumeFloor() {
    EngineSeries s = series(green(0, FLOOR), green(1, BELOW), green(2, FLOOR));
    assertThat(TwoCandleGate.detect(s, 2, CE, "NIFTY 50").present()).isFalse();
  }

  @Test
  void rejectsWhenTheSecondCandleIsWeak() {
    // 2nd candle (index 1) has a wick ≥ 2× its body → rejection, not a momentum candle
    EngineSeries s = series(green(0, FLOOR), weakGreen(1, FLOOR), green(2, FLOOR));
    assertThat(TwoCandleGate.detect(s, 2, CE, "NIFTY 50").present()).isFalse();
  }

  @Test
  void rejectsAtTheSeriesEdgeWithoutTwoPriorCandles() {
    EngineSeries s = series(green(0, FLOOR), green(1, FLOOR));
    assertThat(TwoCandleGate.detect(s, 1, CE, "NIFTY 50").present()).isFalse();
    assertThat(TwoCandleGate.detect(null, 5, CE, "NIFTY 50").present()).isFalse();
  }
}
