package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit coverage for the pure F9 {@link PaperService#advisedLots} risk-based sizing. */
class PaperServiceAdvisedLotsTest {

  private static final BigDecimal EQUITY = new BigDecimal("150000");
  private static final BigDecimal RISK_1PCT = new BigDecimal("1.0");

  @Test
  void floorsRiskBudgetOverStopDistance() {
    // 1% of 150000 = 1500 budget; stop distance 8 → floor(187.5) = 187
    assertThat(PaperService.advisedLots(EQUITY, RISK_1PCT, new BigDecimal("100"), new BigDecimal("92")))
        .isEqualTo(187L);
  }

  @Test
  void usesAbsoluteStopDistanceForShorts() {
    // stop above fill (a short) → |100 − 108| = 8 → same 187
    assertThat(PaperService.advisedLots(EQUITY, RISK_1PCT, new BigDecimal("100"), new BigDecimal("108")))
        .isEqualTo(187L);
  }

  @Test
  void nullWhenNoStop() {
    assertThat(PaperService.advisedLots(EQUITY, RISK_1PCT, new BigDecimal("100"), null)).isNull();
  }

  @Test
  void nullWhenStopEqualsFill() {
    assertThat(
            PaperService.advisedLots(EQUITY, RISK_1PCT, new BigDecimal("100"), new BigDecimal("100")))
        .isNull();
  }

  @Test
  void nullWhenEquityMissing() {
    assertThat(PaperService.advisedLots(null, RISK_1PCT, new BigDecimal("100"), new BigDecimal("92")))
        .isNull();
  }
}
