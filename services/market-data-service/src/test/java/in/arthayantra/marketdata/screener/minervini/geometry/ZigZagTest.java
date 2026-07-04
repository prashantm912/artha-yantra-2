package in.arthayantra.marketdata.screener.minervini.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MV-5.1: the zig-zag swing extractor on synthetic monotonic-leg series. */
class ZigZagTest {

  /** A clean up → down → up series yields alternating low/high/low/high pivots at the turns. */
  @Test
  void extractsAlternatingSwingsAtTurns() {
    // 10 → 20 (up), 20 → 14 (down 30%), 14 → 22 (up). One bar per step, high=low=close.
    List<DailyBar> bars = new ArrayList<>();
    for (double p : new double[] {10, 13, 16, 20}) {
      bars.add(bar(bars.size(), p));
    }
    for (double p : new double[] {18, 16, 14}) {
      bars.add(bar(bars.size(), p));
    }
    for (double p : new double[] {17, 20, 22}) {
      bars.add(bar(bars.size(), p));
    }

    List<Pivot> pivots = new ZigZag(0.05).extract(bars);

    assertThat(pivots).extracting(Pivot::high).containsExactly(false, true, false, true);
    assertThat(pivots).extracting(Pivot::price).containsExactly(10.0, 20.0, 14.0, 22.0);
    // The final HIGH is the trailing tip at the last bar.
    assertThat(pivots.get(3).index()).isEqualTo(bars.size() - 1);
  }

  /** A move smaller than the threshold registers no reversal pivot. */
  @Test
  void ignoresSubThresholdWiggle() {
    List<DailyBar> bars = new ArrayList<>();
    for (double p : new double[] {10, 12, 14, 16, 18, 20}) {
      bars.add(bar(bars.size(), p));
    }
    // 1% dip — below the 5% threshold.
    bars.add(bar(bars.size(), 19.8));
    for (double p : new double[] {21, 23, 25}) {
      bars.add(bar(bars.size(), p));
    }

    List<Pivot> pivots = new ZigZag(0.05).extract(bars);

    // Only the initial low and the trailing high — the 1% dip is noise.
    assertThat(pivots).extracting(Pivot::high).containsExactly(false, true);
    assertThat(pivots).extracting(Pivot::price).containsExactly(10.0, 25.0);
  }

  private static DailyBar bar(int i, double price) {
    return new DailyBar(LocalDate.of(2025, 1, 1).plusDays(i), price, price, price, price, 1_000);
  }
}
