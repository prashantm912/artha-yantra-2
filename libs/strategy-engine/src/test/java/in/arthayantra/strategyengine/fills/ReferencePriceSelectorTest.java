package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Reference-price timing (A9 [FP-6]): next-open vs signal-bar close + the A7 btst default. */
class ReferencePriceSelectorTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static EngineCandle bar(String open, String high, String low, String close) {
    return new EngineCandle(
        OffsetDateTime.of(2026, 1, 5, 9, 15, 0, 0, IST),
        new BigDecimal(open),
        new BigDecimal(high),
        new BigDecimal(low),
        new BigDecimal(close),
        100L);
  }

  @Test
  void atCloseUsesTheSignalBarClose() {
    EngineCandle signal = bar("100", "105", "99", "103");
    EngineCandle next = bar("104", "106", "103", "105");
    assertThat(ReferencePriceSelector.select(FillTiming.AT_CLOSE, signal, next))
        .isEqualByComparingTo("103");
  }

  @Test
  void nextOpenUsesTheNextBarOpen() {
    EngineCandle signal = bar("100", "105", "99", "103");
    EngineCandle next = bar("104", "106", "103", "105");
    assertThat(ReferencePriceSelector.select(FillTiming.NEXT_OPEN, signal, next))
        .isEqualByComparingTo("104");
  }

  @Test
  void defaultTimingIsAtCloseForBtstNextOpenOtherwise() {
    assertThat(ReferencePriceSelector.defaultFor("btst", null)).isEqualTo(FillTiming.AT_CLOSE);
    assertThat(ReferencePriceSelector.defaultFor("intraday", null)).isEqualTo(FillTiming.NEXT_OPEN);
    // explicit configuration wins over the style default
    assertThat(ReferencePriceSelector.defaultFor("btst", "next_open"))
        .isEqualTo(FillTiming.NEXT_OPEN);
  }
}
