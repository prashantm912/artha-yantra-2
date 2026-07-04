package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Phase-9 swing backtest sim: over a synthetic Stage-2 uptrend (MAs stacked, near the 52-week
 * high) that breaks to a fresh high on expanding volume and then rolls over, the primary-base setup
 * takes one trade and closes it on the protective stop / 50-day-MA trail with a loss — proving the
 * entry gate, the exit doctrine, and the PnL math end-to-end. Pure (no DB, no Spring).
 */
class MinerviniSwingBacktestTest {

  private final VcpDetector detector = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40);

  @Test
  void primaryBaseTakesABreakoutTradeAndStopsOutOnTheRollover() {
    List<DailyBar> bars = new ArrayList<>();
    // 256 sessions of a steady Stage-2 uptrend 100 -> 180 (MAs stacked, above the rising 200-day).
    for (int i = 0; i < 256; i++) {
      bars.add(bar(i, 100.0 + 80.0 * i / 255.0, 1_000));
    }
    // an 8-session consolidation BELOW the 180 high — a monotonic rise never re-crosses its own high,
    // so the close must first dip under the prior 52-week high for a later push above it to be a
    // genuine crossover.
    double[] base = {175, 173, 176, 174, 175, 173, 176, 174};
    for (int i = 0; i < base.length; i++) {
      bars.add(bar(256 + i, base[i], 1_000));
    }
    // a fresh-52w-high breakout to 185 on 3x volume (bar 264, past the 260-bar floor), then a rollover.
    double[] tail = {185, 188, 182, 174, 165};
    long[] vol = {3_000, 1_000, 1_000, 1_000, 1_000};
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(264 + i, tail[i], vol[i]));
    }

    List<BtTrade> trades =
        MinerviniSwingBacktest.simulate("TESTCO", bars, detector, LocalDate.of(2020, 1, 1).plusDays(264));

    List<BtTrade> primaryBase = trades.stream().filter(t -> t.setup().equals("primary-base")).toList();
    assertThat(primaryBase).as("the fresh-high breakout takes a primary-base trade").isNotEmpty();
    BtTrade t = primaryBase.get(0);
    assertThat(t.entryPrice()).isEqualTo(185.0); // entered on the breakout bar
    assertThat(t.exitReason()).isIn("STOP_LOSS", "TRAILING_STOP");
    assertThat(t.pnlPct()).as("the rollover closes the trade at a loss").isNegative();
    assertThat(t.barsHeld()).isPositive();
  }

  private static DailyBar bar(int day, double price, long volume) {
    LocalDate d = LocalDate.of(2020, 1, 1).plusDays(day);
    return new DailyBar(d, price, price, price, price, volume);
  }
}
