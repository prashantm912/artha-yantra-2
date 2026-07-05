package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import in.arthayantra.marketdata.screener.minervini.SwingRotationPortfolio.WeeklySeries;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins the RS-rotation logic ({@link SwingRotationPortfolio}) — evict the weakest, buy the leader. */
class SwingRotationPortfolioTest {

  // Holding A: entered @100 on 2020-01-01, would naturally exit @130 (+30%) on 2020-06-01, entry RS 50.
  // Its RS decays: by 2020-03-01 it is 40, price 105 (+5% so far).
  private final BtTrade a =
      new BtTrade(
          "rs-only", "vcp", "A", LocalDate.parse("2020-01-01"), 100.0, LocalDate.parse("2020-06-01"),
          130.0, 30.0, 100, "TRAILING_STOP", 50.0, 1e12);
  // Newcomer B: enters 2020-03-01 (mid-hold of A) with a strong RS 90; natural +10%.
  private final BtTrade b =
      new BtTrade(
          "rs-only", "vcp", "B", LocalDate.parse("2020-03-01"), 200.0, LocalDate.parse("2020-08-01"),
          220.0, 10.0, 100, "TRAILING_STOP", 90.0, 1e12);
  private final Map<String, WeeklySeries> weekly =
      Map.of(
          "A",
          new WeeklySeries(
              new long[] {
                LocalDate.parse("2020-01-01").toEpochDay(),
                LocalDate.parse("2020-02-15").toEpochDay(),
                LocalDate.parse("2020-03-01").toEpochDay()
              },
              new double[] {50.0, 45.0, 40.0},
              new double[] {100.0, 103.0, 105.0}));

  @Test
  void aStrongerNewcomerEvictsTheWeakestHoldingEarly() {
    SwingRotationPortfolio.Outcome o =
        SwingRotationPortfolio.simulate(List.of(a, b), weekly, 1, null, 5.0);

    assertThat(o.rotations()).as("B (RS 90) evicts A (current RS 40) on 2020-03-01").isEqualTo(1);
    assertThat(o.result().tradesTaken()).isEqualTo(2);
    assertThat(o.result().tradesSkipped()).isZero();
    // A exits early at 105 (+5%), then B compounds +10%: 1.05 × 1.10 = 1.155
    assertThat(o.result().totalReturnPct()).isCloseTo(15.5, within(0.01));
  }

  @Test
  void aHoldingWithUnassessableRsIsTreatedAsWeakestAndCanBeEvicted() {
    // A's current RS is NaN on the rotation date (thin cross-section) but its close is known (105).
    Map<String, WeeklySeries> nanRs =
        Map.of(
            "A",
            new WeeklySeries(
                new long[] {
                  LocalDate.parse("2020-01-01").toEpochDay(),
                  LocalDate.parse("2020-03-01").toEpochDay()
                },
                new double[] {50.0, Double.NaN},
                new double[] {100.0, 105.0}));

    SwingRotationPortfolio.Outcome o =
        SwingRotationPortfolio.simulate(List.of(a, b), nanRs, 1, null, 5.0);

    assertThat(o.rotations()).as("NaN-RS holding is evictable, not protected").isEqualTo(1);
    assertThat(o.result().tradesTaken()).isEqualTo(2);
    assertThat(o.result().totalReturnPct()).isCloseTo(15.5, within(0.01));
  }

  @Test
  void tooLargeAMarginBlocksRotationSoTheLaggardIsHeldToItsNaturalExit() {
    SwingRotationPortfolio.Outcome o =
        SwingRotationPortfolio.simulate(List.of(a, b), weekly, 1, null, 100.0);

    assertThat(o.rotations()).as("90 is not > 40 + 100, so no rotation").isZero();
    assertThat(o.result().tradesTaken()).isEqualTo(1);
    assertThat(o.result().tradesSkipped()).isEqualTo(1);
    // A held to its natural +30%
    assertThat(o.result().totalReturnPct()).isCloseTo(30.0, within(0.01));
  }
}
