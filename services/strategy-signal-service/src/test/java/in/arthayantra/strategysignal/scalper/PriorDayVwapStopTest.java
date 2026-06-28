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

/**
 * E8 §3.9 Morning Trade prior-day-VWAP stop: {@link ScalperConfluenceGate#priorSessionVwap} computes
 * the VWAP over the PRIOR completed session's warmed bars, and {@link ScalperGates#priorDayVwapStop}
 * accepts it as the morning stop only when it sits on the protective side of the entry.
 */
class PriorDayVwapStopTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static EngineCandle bar(OffsetDateTime ts, String hlc, long vol) {
    BigDecimal v = bd(hlc);
    return new EngineCandle(ts, v, v, v, v, vol); // flat OHLC: typical == hlc, so VWAP is a clean mean
  }

  private static EngineSeries series(EngineCandle... candles) {
    return EngineSeries.of(new SeriesKey("NSE", "NIFTY-FUT", "1m"), List.of(candles));
  }

  // prior session (2026-06-19): 100@vol10 + 110@vol10 → VWAP = (300·10 + 330·10)/(20·3) = 6300/60 = 105.
  private static EngineSeries twoSessionSeries() {
    OffsetDateTime d19 = OffsetDateTime.of(2026, 6, 19, 9, 15, 0, 0, IST);
    OffsetDateTime d20 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, IST);
    return series(
        bar(d19, "100", 10),
        bar(d19.plusMinutes(1), "110", 10),
        bar(d20, "112", 5), // today bar 0 (session start, index 2)
        bar(d20.plusMinutes(1), "113", 5));
  }

  @Test
  void priorSessionVwapAveragesThePriorSessionsBars() {
    assertThat(ScalperConfluenceGate.priorSessionVwap(twoSessionSeries(), 3))
        .isEqualByComparingTo("105");
  }

  @Test
  void priorSessionVwapIsNullWithoutAPriorSession() {
    OffsetDateTime d20 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, IST);
    EngineSeries todayOnly = series(bar(d20, "112", 5), bar(d20.plusMinutes(1), "113", 5));
    assertThat(ScalperConfluenceGate.priorSessionVwap(todayOnly, 1)).isNull();
  }

  @Test
  void priorSessionVwapIsNullWhenThePriorSessionHadNoVolume() {
    OffsetDateTime d19 = OffsetDateTime.of(2026, 6, 19, 9, 15, 0, 0, IST);
    OffsetDateTime d20 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, IST);
    EngineSeries s = series(bar(d19, "100", 0), bar(d19.plusMinutes(1), "110", 0), bar(d20, "112", 5));
    assertThat(ScalperConfluenceGate.priorSessionVwap(s, 2)).isNull();
  }

  @Test
  void priorDayVwapStopUsesTheVwapOnlyOnTheProtectiveSide() {
    // CE long: a VWAP BELOW the entry is a valid support stop; ABOVE the entry it is not (→ null).
    assertThat(ScalperGates.priorDayVwapStop(bd("105"), bd("110"), CE)).isEqualByComparingTo("105");
    assertThat(ScalperGates.priorDayVwapStop(bd("105"), bd("100"), CE)).isNull();
    // PE short: a VWAP ABOVE the entry is a valid resistance stop; BELOW it is not.
    assertThat(ScalperGates.priorDayVwapStop(bd("105"), bd("100"), PE)).isEqualByComparingTo("105");
    assertThat(ScalperGates.priorDayVwapStop(bd("105"), bd("110"), PE)).isNull();
    // null operands degrade to null (the default structural stop stands).
    assertThat(ScalperGates.priorDayVwapStop(null, bd("110"), CE)).isNull();
    assertThat(ScalperGates.priorDayVwapStop(bd("105"), null, CE)).isNull();
  }

  /** n consecutive 10-wide bars (high = base+5, low = base−5) for the E8 ATR(14) read. */
  private static EngineSeries volatileSeries(int n) {
    OffsetDateTime t0 = OffsetDateTime.of(2026, 6, 20, 9, 15, 0, 0, IST);
    EngineCandle[] bars = new EngineCandle[n];
    for (int i = 0; i < n; i++) {
      BigDecimal base = bd(String.valueOf(100 + i));
      bars[i] =
          new EngineCandle(
              t0.plusMinutes(i), base, base.add(bd("5")), base.subtract(bd("5")), base, 100L);
    }
    return series(bars);
  }

  @Test
  void atrAtEntryIsNullWithoutEnoughBars() {
    assertThat(ScalperConfluenceGate.atrAtEntry(volatileSeries(10), 9)).isNull(); // index 9 < period 14
  }

  @Test
  void atrAtEntryComputesAPositiveAtrOnAVolatileSeries() {
    assertThat(ScalperConfluenceGate.atrAtEntry(volatileSeries(20), 19)).isPositive();
  }
}
