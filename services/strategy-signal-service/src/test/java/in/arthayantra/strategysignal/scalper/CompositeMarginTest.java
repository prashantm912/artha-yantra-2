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
}
