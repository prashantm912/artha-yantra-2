package in.arthayantra.strategysignal.scalper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * T14 (bug queue B5): the confluence-composite margin is a SCALAR-shortfall diagnostic. A
 * decisive-leg block (VWAP side / 60m bias / stand-aside failing while the aggregate clears the
 * threshold) must carry NO scalar margin — the old unconditional {@code aggregate − threshold}
 * logged blocked rows with a positive margin, a self-contradiction that mis-attributed every
 * §3.5 would-have-fired query (3 rows on 2026-07-24, 1 on 07-23).
 */
class CompositeMarginTest {

  private static final BigDecimal THRESHOLD = new BigDecimal("0.600");

  @Test
  void scalarShortfallBlockKeepsTheNegativeMargin() {
    assertThat(ScalperConfluenceGate.compositeMargin(false, new BigDecimal("0.452"), THRESHOLD))
        .isEqualByComparingTo("-0.148");
  }

  @Test
  void decisiveLegBlockCarriesNoScalarMargin() {
    // the 2026-07-24 shape: composite 0.6373 >= 0.600 yet blocked (VWAP/bias/stand-aside leg)
    assertThat(ScalperConfluenceGate.compositeMargin(false, new BigDecimal("0.6373"), THRESHOLD))
        .isNull();
  }

  @Test
  void passingRowKeepsThePositiveSlack() {
    assertThat(ScalperConfluenceGate.compositeMargin(true, new BigDecimal("0.688"), THRESHOLD))
        .isEqualByComparingTo("0.088");
  }

  @Test
  void nullOperandsYieldNoMargin() {
    assertThat(ScalperConfluenceGate.compositeMargin(false, null, THRESHOLD)).isNull();
    assertThat(ScalperConfluenceGate.compositeMargin(false, new BigDecimal("0.5"), null)).isNull();
  }

  @Test
  void softVwapMissIsNeverReportedAsDecisive() {
    // codex round 2: on the #9 opening-tick path vwapHardGate=false degrades VWAP to a soft dot —
    // a soft VWAP miss overlapping a decisive bias failure must name the BIAS, not VWAP; naming
    // VWAP "decisive" when it did not gate is the same class of contradiction T14 removes.
    ConnectTheDotsScorer.Confluence conf =
        new ConnectTheDotsScorer.Confluence(
            new BigDecimal("0.65"), in.arthayantra.black76.Black76.OptionType.CE,
            false, false, false, false, false, java.util.List.of());
    assertThat(ScalperConfluenceGate.compositeReason(conf, THRESHOLD, false, null))
        .contains("60m bias")
        .doesNotContain("VWAP");
    // with the hard gate held, the same shape correctly names VWAP as decisive
    assertThat(ScalperConfluenceGate.compositeReason(conf, THRESHOLD, true, null)).contains("VWAP");
  }

  // ------------------------------------------- F5 U4b §5.3: the coverage-floor block's own reason

  private static final BigDecimal FLOOR = new BigDecimal("0.90");

  /** A confluence with all three original legs HELD, parameterised on aggregate + coverage. */
  private static ConnectTheDotsScorer.Confluence legsHeld(String aggregate, String coverage) {
    BigDecimal cov = new BigDecimal(coverage);
    return new ConnectTheDotsScorer.Confluence(
        new BigDecimal(aggregate), in.arthayantra.black76.Black76.OptionType.CE,
        false, false, true, true, false, java.util.List.of(), new BigDecimal(aggregate), false,
        cov, cov.compareTo(FLOOR) >= 0);
  }

  @Test
  void aCoverageFloorBlockIsNotReportedAsAScalarShortfall() {
    // Codex review Major 2, and the same T14 class as the margin fix above: the coverage floor makes
    // `valid` false while the aggregate CLEARS its threshold, so the scalar sentence fell through and
    // persisted "aggregate 0.9202 below threshold 0.600" — arithmetically false on its own face, with
    // the real reason recorded nowhere. Every later §3 rejection query believes that string.
    //
    // THE DISCRIMINATING PAIR — both rows are blocked with all three original legs held, and the two
    // causes must not produce the same sentence:
    //   coverage 0.8138 (below 0.90), aggregate 0.9202 CLEARS 0.600 -> a COVERAGE block
    //   coverage 1.0000 (intact),     aggregate 0.4520 MISSES 0.600 -> a genuine SCALAR shortfall
    String coverageBlock =
        ScalperConfluenceGate.compositeReason(legsHeld("0.9202", "0.8138"), THRESHOLD, true, FLOOR);
    String scalarBlock =
        ScalperConfluenceGate.compositeReason(legsHeld("0.4520", "1.0000"), THRESHOLD, true, FLOOR);

    assertThat(coverageBlock)
        .as("names coverage, and never the arithmetically false scalar claim")
        .contains("coverage")
        .contains("0.8138")
        .contains("0.90")
        .doesNotContain("below threshold");
    assertThat(scalarBlock)
        .as("a real shortfall still reads exactly as it always did")
        .isEqualTo("aggregate 0.4520 below threshold 0.600");
    assertThat(coverageBlock).isNotEqualTo(scalarBlock);
  }

  @Test
  void anUnarmedStrategyNeverBlamesCoverage() {
    // DEFAULT-OFF parity. The SAME low-coverage bar on a strategy that has not armed the floor was
    // not blocked by coverage at all — its confluence is whatever the scalar said — so the reason
    // must stay byte-identical to the pre-§5.3 string. `coverageFloor == null` is the arming signal.
    assertThat(
            ScalperConfluenceGate.compositeReason(legsHeld("0.4520", "0.8138"), THRESHOLD, true, null))
        .isEqualTo("aggregate 0.4520 below threshold 0.600");
    // …and an ARMED strategy whose coverage HELD is byte-identical too — the branch fires only when
    // the floor is both armed and failed.
    assertThat(
            ScalperConfluenceGate.compositeReason(legsHeld("0.4520", "1.0000"), THRESHOLD, true, FLOOR))
        .isEqualTo("aggregate 0.4520 below threshold 0.600");
  }

  @Test
  void anEarlierDecisiveLegStillWinsThePrecedence() {
    // The coverage branch sits LAST among the legs, so the three original ones keep their exact
    // precedence: a bar that fails BOTH the stand-aside and the coverage floor still names the
    // stand-aside, as it did before §5.3 existed.
    ConnectTheDotsScorer.Confluence standAsideAndLowCoverage =
        new ConnectTheDotsScorer.Confluence(
            new BigDecimal("0.9202"), in.arthayantra.black76.Black76.OptionType.CE,
            false, false, true, true, true, java.util.List.of(), new BigDecimal("0.9202"), false,
            new BigDecimal("0.8138"), false);
    assertThat(
            ScalperConfluenceGate.compositeReason(standAsideAndLowCoverage, THRESHOLD, true, FLOOR))
        .isEqualTo("stand-aside (both-IV-high 40/40 suppression)");
  }
}
