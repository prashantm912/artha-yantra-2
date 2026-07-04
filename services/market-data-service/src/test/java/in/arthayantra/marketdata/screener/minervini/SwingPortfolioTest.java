package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the slot-limited compounding portfolio model ({@link SwingPortfolio}). */
class SwingPortfolioTest {

  @Test
  void singleWinnerCompoundsTheWholeBook() {
    SwingPortfolio.Result r =
        SwingPortfolio.simulate(List.of(t("2020-01-01", "2020-02-01", 10)), 1, false);
    assertThat(r.totalReturnPct()).isCloseTo(10.0, within(1e-6));
    assertThat(r.tradesTaken()).isEqualTo(1);
    assertThat(r.tradesSkipped()).isZero();
  }

  @Test
  void allSlotsFullSkipsTheOverlappingEntry() {
    SwingPortfolio.Result r =
        SwingPortfolio.simulate(
            List.of(t("2020-01-01", "2020-06-01", 10), t("2020-02-01", "2020-07-01", 99)), 1, false);
    assertThat(r.tradesTaken()).isEqualTo(1);
    assertThat(r.tradesSkipped()).isEqualTo(1);
    assertThat(r.totalReturnPct()).as("only the first trade was taken").isCloseTo(10.0, within(1e-6));
    assertThat(r.annual())
        .as("the skipped trade must NOT inflate the 2020 trade count")
        .anyMatch(y -> y.year() == 2020 && y.trades() == 1);
  }

  @Test
  void sequentialTradesCompoundOnOneSlot() {
    SwingPortfolio.Result r =
        SwingPortfolio.simulate(
            List.of(t("2020-01-01", "2020-03-01", 10), t("2020-04-01", "2020-06-01", 10)), 1, false);
    assertThat(r.tradesTaken()).isEqualTo(2);
    assertThat(r.totalReturnPct()).as("1.1 × 1.1 = 1.21").isCloseTo(21.0, within(1e-6));
  }

  @Test
  void twoSlotsSplitCapitalEqually() {
    SwingPortfolio.Result r =
        SwingPortfolio.simulate(
            List.of(t("2020-01-01", "2020-03-01", 10), t("2020-01-15", "2020-03-15", 20)), 2, false);
    assertThat(r.tradesTaken()).isEqualTo(2);
    // 0.5·1.10 + 0.5·1.20 = 1.15
    assertThat(r.totalReturnPct()).isCloseTo(15.0, within(1e-6));
  }

  @Test
  void drawdownIsPeakToTroughOnTheRealisedCurve() {
    SwingPortfolio.Result r =
        SwingPortfolio.simulate(
            List.of(t("2020-01-01", "2020-02-01", 20), t("2020-03-01", "2020-04-01", -10)), 1, false);
    assertThat(r.totalReturnPct()).as("1.2 × 0.9 = 1.08").isCloseTo(8.0, within(1e-6));
    assertThat(r.maxDrawdownPct()).as("peak 1.2 → trough 1.08").isCloseTo(10.0, within(1e-6));
  }

  @Test
  void rsPriorityGivesTheScarceSlotToTheStrongerName() {
    // Two entries the SAME day, one slot. Returns are inverted vs RS so the test proves selection is
    // by RS-rank, not by outcome: the weak-RS name has the big return, the strong-RS name the small one.
    BtTrade weakBigReturn = t("2020-01-01", "2020-03-01", 50, 30); // rs 30
    BtTrade strongSmallReturn = t("2020-01-01", "2020-03-01", 5, 90); // rs 90
    List<BtTrade> trades = List.of(weakBigReturn, strongSmallReturn);

    SwingPortfolio.Result fifo = SwingPortfolio.simulate(trades, 1, false);
    SwingPortfolio.Result rsp = SwingPortfolio.simulate(trades, 1, true);

    assertThat(fifo.totalReturnPct()).as("FIFO takes the first-inserted name").isCloseTo(50.0, within(1e-6));
    assertThat(rsp.totalReturnPct()).as("RS-priority takes the stronger-RS name").isCloseTo(5.0, within(1e-6));
  }

  private static BtTrade t(String entry, String exit, double pnlPct) {
    return t(entry, exit, pnlPct, 50.0);
  }

  private static BtTrade t(String entry, String exit, double pnlPct, double rsRank) {
    return new BtTrade(
        "technical", "vcp", "X", LocalDate.parse(entry), 100.0, LocalDate.parse(exit),
        100.0 * (1 + pnlPct / 100.0), pnlPct, 20, pnlPct > 0 ? "TRAILING_STOP" : "STOP_LOSS", rsRank);
  }
}
