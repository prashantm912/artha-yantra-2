package in.arthayantra.marketdata.screener.minervini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import in.arthayantra.marketdata.screener.minervini.MinerviniSwingBacktest.BtTrade;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M10 (#128 batch scoping): "Deep-sim cost model never compounds order size." Confirmed still true
 * in both {@code SwingPortfolio.java:82} and {@code SwingRotationPortfolio.java:74}:
 * {@code orderValue = costs.capital() / slots} is computed ONCE, outside the per-trade loop, from
 * the STATIC configured capital — never from the sleeve's actual (compounded) value at the time of
 * each trade. So a sleeve that has already 10x'd by year 8 still prices its market-impact cost as if
 * every order were sized off the original capital, understating cost on later, larger-in-reality
 * trades (which inflates the reported net CAGR).
 *
 * <p>This is a CHARACTERIZATION, not a fix. Making {@code orderValue} compound is a backtest-
 * methodology change — it would move every reported net-of-cost CAGR/drawdown/Sharpe number — and is
 * therefore HOLD-tier, the same class as M36/M37; it is deliberately left OUT of this slice.
 *
 * <p>Fingerprint used below: the SAME set of trades (one big winner + two small winners, one slot,
 * non-overlapping so no capacity effects confound the comparison) produces an IDENTICAL total return
 * whether the big winner lands FIRST or LAST. Under a genuinely compounding cost model this would
 * NOT hold — a big winner occurring first would inflate {@code orderValue} (and therefore impact
 * cost) for every trade that follows it, while the same winner occurring last would leave the
 * earlier small trades' cost untouched by it, so the two orderings would realise different total
 * returns. Because {@code orderValue} never depends on the sleeve today, the realised total return
 * is order-invariant — this invariance is the observable proof of the never-compounds gap; a future
 * compounding change would break these exact assertions.
 */
class SwingCostModelOrderValueCharacterizationTest {

  // ₹1L capital / 1 slot -> orderValue = ₹10L. avgTurnoverAtEntry = ₹5 Cr/day keeps participation
  // (orderValue/adv = 0.02) well BELOW where impactCoeff·participation would hit the 100% cap — a
  // first draft of this test used a turnover low enough that impact saturated the cap on EVERY
  // trade regardless of orderValue, which made the whole comparison degenerate (every trade wiped
  // to -100% either way, "proving" order-invariance for a trivial reason unrelated to M10). Caught
  // by the red-proof below staying green on the first attempt; recalibrated so cost genuinely
  // scales with orderValue, which is the only way this test can actually discriminate.
  private static final SwingPortfolio.Costs COSTS =
      new SwingPortfolio.Costs(1_000_000, 0.0, 0.0, 1.0, 100.0);
  private static final double ADV = 50_000_000;

  @Test
  void swingPortfolioTotalReturnIsInvariantToWhenTheBigWinnerLandsInTheSequence() {
    List<BtTrade> bigWinnerFirst =
        List.of(
            t("X", "2020-01-01", "2020-02-01", 100),
            t("X", "2020-03-01", "2020-04-01", 5),
            t("X", "2020-05-01", "2020-06-01", 5));
    List<BtTrade> bigWinnerLast =
        List.of(
            t("X", "2020-01-01", "2020-02-01", 5),
            t("X", "2020-03-01", "2020-04-01", 5),
            t("X", "2020-05-01", "2020-06-01", 100));

    // rsPriority=true matches production's reported portfolioRsPriorityNet
    // (ManasAroraBacktestService.java:576 / MinerviniBacktestService.java:559) — inert here since the
    // 3 trades are non-overlapping (never compete for the single slot), but matching it removes any
    // doubt this exercises a variant nobody actually reports (the M27 golden-variant lesson).
    SwingPortfolio.Result winnerFirst = SwingPortfolio.simulate(bigWinnerFirst, 1, true, COSTS);
    SwingPortfolio.Result winnerLast = SwingPortfolio.simulate(bigWinnerLast, 1, true, COSTS);

    assertThat(winnerFirst.tradesTaken()).isEqualTo(3);
    assertThat(winnerLast.tradesTaken()).isEqualTo(3);
    assertThat(winnerFirst.totalReturnPct())
        .as("order-invariant total return is the fingerprint of a cost model blind to the sleeve's"
            + " actual compounded size (SwingPortfolio.java:82)")
        .isCloseTo(winnerLast.totalReturnPct(), within(1e-9));
    // Non-degenerate sanity: costs bite (not ~105% gross) but do not wipe the book (not ~-100%) —
    // proves the impact model is genuinely engaged, not saturated at its cap either way.
    assertThat(winnerFirst.totalReturnPct()).isBetween(90.0, 105.0);
  }

  @Test
  void swingRotationPortfolioSharesTheIdenticalStaticOrderValueFormulaAndTheSameInvariance() {
    // Distinct symbols per trade + strictly non-overlapping dates: each ENTRY always finds its slot
    // freed by the prior trade's natural EXIT, so no rotation (RS-eviction) ever fires here — this
    // isolates the cost model's order-value formula from the rotation logic entirely.
    List<BtTrade> bigWinnerFirst =
        List.of(
            t("A", "2020-01-01", "2020-02-01", 100),
            t("B", "2020-03-01", "2020-04-01", 5),
            t("C", "2020-05-01", "2020-06-01", 5));
    List<BtTrade> bigWinnerLast =
        List.of(
            t("A", "2020-01-01", "2020-02-01", 5),
            t("B", "2020-03-01", "2020-04-01", 5),
            t("C", "2020-05-01", "2020-06-01", 100));

    SwingRotationPortfolio.Outcome winnerFirst =
        SwingRotationPortfolio.simulate(bigWinnerFirst, Map.of(), 1, COSTS, 5.0);
    SwingRotationPortfolio.Outcome winnerLast =
        SwingRotationPortfolio.simulate(bigWinnerLast, Map.of(), 1, COSTS, 5.0);

    assertThat(winnerFirst.rotations()).as("no rotation should fire in this non-overlapping setup").isZero();
    assertThat(winnerLast.rotations()).isZero();
    assertThat(winnerFirst.result().tradesTaken()).isEqualTo(3);
    assertThat(winnerLast.result().tradesTaken()).isEqualTo(3);
    assertThat(winnerFirst.result().totalReturnPct())
        .as("same order-invariance fingerprint, proven independently for SwingRotationPortfolio.java:74")
        .isCloseTo(winnerLast.result().totalReturnPct(), within(1e-9));
    assertThat(winnerFirst.result().totalReturnPct()).isBetween(90.0, 105.0);
  }

  private static BtTrade t(String symbol, String entry, String exit, double pnlPct) {
    return new BtTrade(
        "technical", "vcp", symbol, LocalDate.parse(entry), 100.0, LocalDate.parse(exit),
        100.0 * (1 + pnlPct / 100.0), pnlPct, 20, pnlPct > 0 ? "TRAILING_STOP" : "STOP_LOSS", 50.0,
        ADV);
  }
}
