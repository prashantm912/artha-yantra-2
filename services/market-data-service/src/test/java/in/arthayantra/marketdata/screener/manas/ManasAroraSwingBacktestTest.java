package in.arthayantra.marketdata.screener.manas;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.manas.ManasAroraSwingBacktest.Variant;
import in.arthayantra.marketdata.screener.minervini.geometry.DailyBar;
import in.arthayantra.marketdata.screener.minervini.geometry.VcpDetector;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Manas swing backtest sim smoke test: over a synthetic Stage-2 uptrend that at least doubles off
 * its 52-week low (so the §4.1 ≥100%-above-low gate passes), consolidates, then breaks to a fresh
 * high on expanding volume and rolls over, the breakout setup takes a trade and closes it on the ATR
 * stop / ATR trail — proving the entry gate, the exit doctrine, and the PnL math end-to-end. Separate
 * cases prove the RS-rank / turnover filters block a trade and that pyramiding adds a distinct lot.
 * Pure (no DB, no Spring).
 */
class ManasAroraSwingBacktestTest {

  private final VcpDetector vcp = new VcpDetector(2.5, 2, 6, 0.2, 0.9, 0.5, 0.5, 100, 40, 60, 3, 65);
  private final ConsolidationBreakout breakout = new ConsolidationBreakout(2.5, 10, 40, 25);
  private final ManasAroraSwingBacktest sim = new ManasAroraSwingBacktest();
  private static final LocalDate ENTRY_FROM = LocalDate.of(2020, 1, 1).plusDays(264);

  @Test
  void breakoutTakesATradeAndExitsOnTheRollover() {
    List<DailyBar> bars = breakoutSeries();

    List<BtTrade> trades = sim.simulate("TESTCO", bars, vcp, breakout, ENTRY_FROM);

    List<BtTrade> breakoutTrades = trades.stream().filter(t -> t.setup().equals("breakout")).toList();
    assertThat(breakoutTrades).as("the fresh-high breakout takes a breakout trade").isNotEmpty();
    BtTrade t = breakoutTrades.get(0);
    assertThat(t.entryPrice()).isEqualTo(195.0); // entered on the breakout bar
    assertThat(t.exitReason()).isIn("STOP_LOSS", "TRAILING_STOP", "FAST_MOVE", "PARABOLIC");
    assertThat(t.barsHeld()).isPositive();
    assertThat(t.pyramidLevel()).isEqualTo(1);
  }

  @Test
  void rsGateBlocksTheTradeWhenTheCrossSectionalRankIsBelowTheFloor() {
    List<DailyBar> bars = breakoutSeries();
    double[] rsRank = new double[bars.size()];
    Arrays.fill(rsRank, 50.0); // below the 70 floor everywhere

    Variant v1 = ManasAroraSwingBacktest.V1;
    Variant v2 = new Variant("v2", true, 70, 0, false); // RS gate on, no turnover floor, no pyramid
    List<BtTrade> trades = sim.simulate("TESTCO", bars, vcp, breakout, ENTRY_FROM, rsRank, List.of(v1, v2));

    assertThat(trades.stream().filter(t -> t.variant().equals("v1-technical")))
        .as("v1 (RS relaxed) still takes the breakout").isNotEmpty();
    assertThat(trades.stream().filter(t -> t.variant().equals("v2")))
        .as("v2 skips it — the RS-rank (50) is below the 70 gate").isEmpty();
  }

  @Test
  void turnoverFloorBlocksAnIlliquidName() {
    List<DailyBar> bars = breakoutSeries(); // price ~195, volume ~1-3k → turnover well under ₹37.5L
    double[] rsRank = new double[bars.size()];
    Arrays.fill(rsRank, 100.0); // RS passes — isolate the turnover gate

    Variant v2 = new Variant("v2", true, 0, 3_750_000, false); // ₹37.5L/day floor, RS min 0
    List<BtTrade> trades = sim.simulate("TESTCO", bars, vcp, breakout, ENTRY_FROM, rsRank, List.of(v2));

    assertThat(trades).as("the tiny turnover is far below the floor → no trade").isEmpty();
  }

  @Test
  void pyramidingAddsASecondLotOnAFreshBreakoutWhileTheFirstIsUp() {
    List<DailyBar> bars = pyramidSeries();
    double[] rsRank = new double[bars.size()];
    Arrays.fill(rsRank, 100.0);

    Variant pyramid = new Variant("pyr", false, 0, 0, true);
    List<BtTrade> trades = sim.simulate("TESTCO", bars, vcp, breakout, ENTRY_FROM, rsRank, List.of(pyramid));

    List<BtTrade> breakoutTrades = trades.stream().filter(t -> t.setup().equals("breakout")).toList();
    assertThat(breakoutTrades.stream().map(BtTrade::pyramidLevel))
        .as("a second lot is added while the first is in the money").contains(1, 2);
  }

  @Test
  void nextOpenUsesTheFollowingBarsOpenForEntryAndExit() {
    List<DailyBar> bars = withDistinctOpens(breakoutSeries());
    Variant nextOpen = nextOpenVariant();

    List<BtTrade> trades =
        sim.simulate(
            "TESTCO", bars, vcp, breakout, ENTRY_FROM, null,
            List.of(ManasAroraSwingBacktest.V1, nextOpen));

    BtTrade atClose = firstBreakout(trades, "v1-technical");
    BtTrade atNextOpen = firstBreakout(trades, nextOpen.name());
    assertThat(atNextOpen.entryDate()).isEqualTo(atClose.entryDate());
    assertThat(atNextOpen.exitDate()).isEqualTo(atClose.exitDate());
    int entrySignal = indexOfDate(bars, atNextOpen.entryDate());
    int exitSignal = indexOfDate(bars, atNextOpen.exitDate());
    assertThat(atClose.entryPrice()).isEqualTo(bars.get(entrySignal).close());
    assertThat(atClose.exitPrice()).isEqualTo(bars.get(exitSignal).close());
    assertThat(atNextOpen.entryPrice()).isEqualTo(bars.get(entrySignal + 1).open());
    assertThat(atNextOpen.exitPrice()).isEqualTo(bars.get(exitSignal + 1).open());
  }

  @Test
  void nextOpenPricesPyramidAddsAndWholePositionExitsAtFollowingOpens() {
    List<DailyBar> bars = withModestDistinctOpens(pyramidSeries());
    Variant atClosePyramid = new Variant("close-pyramid-test", false, 0, 0, true);
    Variant nextOpenPyramid = new Variant("next-open-pyramid-test", false, 0, 0, true, "next_open");

    List<BtTrade> trades =
        sim.simulate(
            "TESTCO", bars, vcp, breakout, ENTRY_FROM, null,
            List.of(atClosePyramid, nextOpenPyramid));

    BtTrade atCloseSecondLot = breakoutLot(trades, atClosePyramid.name(), 2);
    BtTrade nextOpenFirstLot = breakoutLot(trades, nextOpenPyramid.name(), 1);
    BtTrade nextOpenSecondLot = breakoutLot(trades, nextOpenPyramid.name(), 2);
    assertThat(nextOpenSecondLot.entryDate()).isEqualTo(atCloseSecondLot.entryDate());
    int addSignal = indexOfDate(bars, nextOpenSecondLot.entryDate());
    assertThat(atCloseSecondLot.entryPrice()).isEqualTo(bars.get(addSignal).close());
    assertThat(nextOpenSecondLot.entryPrice()).isEqualTo(bars.get(addSignal + 1).open());

    assertThat(nextOpenFirstLot.exitDate()).isEqualTo(nextOpenSecondLot.exitDate());
    assertThat(nextOpenFirstLot.exitReason())
        .as("the pyramid fixture closes both lots through the whole-position exit path")
        .isNotEqualTo("STOP_LOSS");
    assertThat(nextOpenSecondLot.exitReason()).isEqualTo(nextOpenFirstLot.exitReason());
    int exitSignal = indexOfDate(bars, nextOpenFirstLot.exitDate());
    assertThat(nextOpenFirstLot.exitPrice()).isEqualTo(bars.get(exitSignal + 1).open());
    assertThat(nextOpenSecondLot.exitPrice()).isEqualTo(bars.get(exitSignal + 1).open());
  }

  @Test
  void nextOpenDropsAnExitSignalOnTheFinalBar() {
    List<DailyBar> full = breakoutSeries();
    List<BtTrade> control = sim.simulate("TESTCO", full, vcp, breakout, ENTRY_FROM);
    int exitSignal = indexOfDate(full, firstBreakout(control, "v1-technical").exitDate());
    List<DailyBar> endingOnExitSignal = new ArrayList<>(full.subList(0, exitSignal + 1));
    Variant nextOpen = nextOpenVariant();

    List<BtTrade> trades =
        sim.simulate(
            "TESTCO", endingOnExitSignal, vcp, breakout, ENTRY_FROM, null,
            List.of(ManasAroraSwingBacktest.V1, nextOpen));

    assertThat(trades.stream().filter(t -> t.variant().equals("v1-technical")))
        .as("at_close can still fill on the final signal bar")
        .isNotEmpty();
    assertThat(trades.stream().filter(t -> t.variant().equals(nextOpen.name())))
        .as("next_open cannot fill without a following bar, so the open lot is dropped")
        .isEmpty();
  }

  /** Stage-2 uptrend (doubles off the low) → consolidation → fresh-high breakout on 3x vol → rollover. */
  private static List<DailyBar> breakoutSeries() {
    List<DailyBar> bars = new ArrayList<>();
    // 256 sessions rising 80 -> 185 (so the 52-week low is ~80 and the breakout ~2.3x the low).
    for (int i = 0; i < 256; i++) {
      bars.add(bar(i, 80.0 + 105.0 * i / 255.0, 1_000));
    }
    // a 12-session consolidation BELOW the ~185 high (a range the breakout detector will bracket).
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      bars.add(bar(256 + i, base[i], 1_000));
    }
    // a fresh-high breakout to 195 on 3x volume (bar 268, past the 260-bar floor), then a rollover.
    double[] tail = {195, 197, 190, 178, 170};
    long[] vol = {3_000, 1_000, 1_000, 1_000, 1_000};
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(268 + i, tail[i], vol[i]));
    }
    return bars;
  }

  /** Like {@link #breakoutSeries} but with a SECOND fresh-high breakout while the first lot is up. */
  private static List<DailyBar> pyramidSeries() {
    List<DailyBar> bars = new ArrayList<>();
    for (int i = 0; i < 256; i++) {
      bars.add(bar(i, 80.0 + 105.0 * i / 255.0, 1_000));
    }
    double[] base = {182, 179, 183, 180, 182, 178, 183, 181, 182, 179, 183, 180};
    for (int i = 0; i < base.length; i++) {
      bars.add(bar(256 + i, base[i], 1_000));
    }
    // first breakout to 195, then a NEW tight consolidation ~205-210 (which forms a higher pivot at
    // the weekly geometry recompute), then a SECOND breakout to 225 while lot-1 is up >6% — the add
    // fires — then a rollover that trailing-stops out BOTH lots together.
    double[] tail = {
      195, 205, 208, 206, 209, 207, 208, 206, 209, 207, 208, 206, 207, 225, 228, 215, 200, 190
    };
    long[] vol = {
      3_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000, 1_000,
      3_000, 1_000, 1_000, 1_000, 1_000
    };
    for (int i = 0; i < tail.length; i++) {
      bars.add(bar(268 + i, tail[i], vol[i]));
    }
    return bars;
  }

  private static DailyBar bar(int day, double price, long volume) {
    LocalDate d = LocalDate.of(2020, 1, 1).plusDays(day);
    return new DailyBar(d, price, price, price, price, volume);
  }

  private static Variant nextOpenVariant() {
    assertThat(Arrays.stream(Variant.class.getRecordComponents()).map(c -> c.getName()))
        .as("Variant carries the per-variant fill timing")
        .contains("fillTiming");
    return new Variant("next-open-test", false, 0, 0, false, "next_open");
  }

  private static List<DailyBar> withDistinctOpens(List<DailyBar> source) {
    List<DailyBar> bars = new ArrayList<>(source.size());
    for (int i = 0; i < source.size(); i++) {
      DailyBar b = source.get(i);
      bars.add(new DailyBar(b.date(), 10_000 + i, b.high(), b.low(), b.close(), b.volume()));
    }
    return bars;
  }

  private static List<DailyBar> withModestDistinctOpens(List<DailyBar> source) {
    List<DailyBar> bars = new ArrayList<>(source.size());
    for (int i = 0; i < source.size(); i++) {
      DailyBar b = source.get(i);
      bars.add(new DailyBar(b.date(), b.close() + 0.125, b.high(), b.low(), b.close(), b.volume()));
    }
    return bars;
  }

  private static BtTrade firstBreakout(List<BtTrade> trades, String variant) {
    return trades.stream()
        .filter(t -> t.variant().equals(variant) && t.setup().equals("breakout"))
        .findFirst()
        .orElseThrow();
  }

  private static BtTrade breakoutLot(List<BtTrade> trades, String variant, int pyramidLevel) {
    return trades.stream()
        .filter(
            t ->
                t.variant().equals(variant)
                    && t.setup().equals("breakout")
                    && t.pyramidLevel() == pyramidLevel)
        .findFirst()
        .orElseThrow();
  }

  private static int indexOfDate(List<DailyBar> bars, LocalDate date) {
    for (int i = 0; i < bars.size(); i++) {
      if (bars.get(i).date().equals(date)) {
        return i;
      }
    }
    throw new IllegalArgumentException("date not found: " + date);
  }
}
