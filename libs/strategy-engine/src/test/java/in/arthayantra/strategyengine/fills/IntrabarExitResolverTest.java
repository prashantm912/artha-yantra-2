package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.fills.IntrabarExitResolver.Resolution;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The A9 [FP-5] intra-bar exit-touch rule: 1m drill (INTRABAR_1M), worst-of primary fallback with
 * gap-through at open (BAR_HL_WORSTOF), and close evaluation (CLOSE_EVAL). The parity floor is the
 * closed 1m bar — never tick-level. A long stop is a downward breach ({@code upward=false}).
 */
class IntrabarExitResolverTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
  private static final BigDecimal STOP = new BigDecimal("99.00"); // long stop below

  private static EngineCandle bar(int minute, String o, String h, String l, String c) {
    return new EngineCandle(
        OffsetDateTime.of(2026, 1, 5, 9, minute, 0, 0, IST),
        new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), 10L);
  }

  @Test
  void intrabar1mTouchFillsAtTheLevelWhenNoGap() {
    EngineCandle primary = bar(15, "100", "101", "98.5", "100");
    List<EngineCandle> oneMin =
        List.of(
            bar(15, "100", "100.5", "99.5", "100"), // no touch
            bar(16, "99.8", "100", "98.8", "99.2")); // low 98.8 <= 99.00 -> touched, open 99.8 > stop

    Resolution res = IntrabarExitResolver.resolve(false, STOP, primary, oneMin, true);

    assertThat(res.touched()).isTrue();
    assertThat(res.basis()).isEqualTo(TouchBasis.INTRABAR_1M);
    assertThat(res.exitPrice()).isEqualByComparingTo("99.00"); // filled at the stop, no gap
  }

  @Test
  void intrabar1mGapThroughFillsAtTheOpen() {
    EngineCandle primary = bar(15, "100", "101", "97", "98");
    List<EngineCandle> oneMin =
        List.of(bar(15, "98.5", "99", "97", "97.5")); // opens 98.5 BELOW the 99.00 stop -> gap

    Resolution res = IntrabarExitResolver.resolve(false, STOP, primary, oneMin, true);

    assertThat(res.touched()).isTrue();
    assertThat(res.basis()).isEqualTo(TouchBasis.INTRABAR_1M);
    assertThat(res.exitPrice()).isEqualByComparingTo("98.5"); // gap-through at the open
  }

  @Test
  void worstOfFallbackWhenOneMinuteCoverageMissing() {
    EngineCandle primary = bar(15, "100", "101", "98.5", "100"); // low 98.5 <= stop, open 100 > stop

    Resolution res = IntrabarExitResolver.resolve(false, STOP, primary, null, true);

    assertThat(res.touched()).isTrue();
    assertThat(res.basis()).isEqualTo(TouchBasis.BAR_HL_WORSTOF);
    assertThat(res.exitPrice()).isEqualByComparingTo("99.00"); // filled at stop (no gap at open)
  }

  @Test
  void worstOfFallbackGapsThroughAtOpen() {
    EngineCandle primary = bar(15, "98", "99", "97", "97.5"); // opens 98 below the 99.00 stop

    Resolution res = IntrabarExitResolver.resolve(false, STOP, primary, List.of(), true);

    assertThat(res.basis()).isEqualTo(TouchBasis.BAR_HL_WORSTOF);
    assertThat(res.exitPrice()).isEqualByComparingTo("98"); // gap-through at bar open
  }

  @Test
  void closeEvaluationWhenIntrabarDisabled() {
    EngineCandle dips = bar(15, "100", "101", "98", "98.5"); // wicks below but closes 98.5 < stop
    Resolution res = IntrabarExitResolver.resolve(false, STOP, dips, null, false);
    assertThat(res.basis()).isEqualTo(TouchBasis.CLOSE_EVAL);
    assertThat(res.touched()).isTrue();
    assertThat(res.exitPrice()).isEqualByComparingTo("98.5"); // filled at the close

    EngineCandle wickOnly = bar(15, "100", "101", "98", "100"); // dips to 98 but closes 100 > stop
    assertThat(IntrabarExitResolver.resolve(false, STOP, wickOnly, null, false).touched()).isFalse();
  }

  @Test
  void noTouchReturnsUntouched() {
    EngineCandle primary = bar(15, "100", "101", "99.5", "100"); // never reaches the 99.00 stop
    Resolution res = IntrabarExitResolver.resolve(false, STOP, primary, null, true);
    assertThat(res.touched()).isFalse();
    assertThat(res.exitPrice()).isNull();
  }
}
