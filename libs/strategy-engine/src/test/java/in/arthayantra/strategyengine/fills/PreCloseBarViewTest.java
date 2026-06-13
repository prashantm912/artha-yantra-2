package in.arthayantra.strategyengine.fills;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BTST pre-close bar view (A9 [FP-6]): deterministic OHLC aggregation of the 1m slice. */
class PreCloseBarViewTest {

  private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

  private static EngineCandle bar(int hour, int minute, String o, String h, String l, String c) {
    return new EngineCandle(
        OffsetDateTime.of(2026, 1, 5, hour, minute, 0, 0, IST),
        new BigDecimal(o), new BigDecimal(h), new BigDecimal(l), new BigDecimal(c), 10L);
  }

  @Test
  void aggregatesOpenHighLowCloseAndVolume() {
    List<EngineCandle> bars =
        List.of(
            bar(9, 15, "100", "101", "99", "100.5"),
            bar(9, 16, "100.5", "103", "100", "102"),
            bar(9, 17, "102", "102.5", "101", "101.5"));

    EngineCandle view = PreCloseBarView.assemble(bars);

    assertThat(view.open()).isEqualByComparingTo("100"); // first open
    assertThat(view.high()).isEqualByComparingTo("103"); // max high
    assertThat(view.low()).isEqualByComparingTo("99"); // min low
    assertThat(view.close()).isEqualByComparingTo("101.5"); // last close
    assertThat(view.volume()).isEqualTo(30L); // sum
  }

  @Test
  void assemblyIsByteIdenticalAcrossTwoRuns() {
    List<EngineCandle> bars =
        List.of(bar(9, 15, "100", "101", "99", "100.5"), bar(9, 16, "100.5", "103", "100", "102"));
    assertThat(PreCloseBarView.assemble(bars)).isEqualTo(PreCloseBarView.assemble(bars));
  }

  @Test
  void assembleUpToExcludesBarsAtOrAfterThePreCloseClock() {
    List<EngineCandle> day =
        List.of(
            bar(15, 18, "100", "101", "99", "100.5"),
            bar(15, 19, "100.5", "104", "100", "103"), // last bar before 15:20
            bar(15, 20, "103", "110", "102", "108")); // excluded (closes after 15:20)

    EngineCandle view = PreCloseBarView.assembleUpTo(day, LocalTime.of(15, 20));

    assertThat(view.high()).isEqualByComparingTo("104"); // 15:20 bar's 110 excluded
    assertThat(view.close()).isEqualByComparingTo("103"); // 15:19 close
  }
}
