package in.arthayantra.marketdata.kite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** The pure rate-budget fraction the {@link KiteRateBudgetPublisher} snapshots into Redis (F6). */
class KiteRateBudgetPublisherTest {

  @Test
  void budgetFractionIsAvailableOverPeriodClampedToUnitInterval() {
    assertThat(KiteRateBudgetPublisher.budgetFraction(3, 3)).isEqualTo(1.0); // idle → full headroom
    assertThat(KiteRateBudgetPublisher.budgetFraction(0, 3)).isEqualTo(0.0); // saturated
    assertThat(KiteRateBudgetPublisher.budgetFraction(1, 3)).isCloseTo(1.0 / 3, within(1e-9));
  }

  @Test
  void budgetFractionFloorsANegativeAvailableAndTreatsAnUnconfiguredBudgetAsFull() {
    // resilience4j can report a transient negative available when threads queue past the budget.
    assertThat(KiteRateBudgetPublisher.budgetFraction(-2, 3)).isEqualTo(0.0);
    // period 0 (no configured limiter) reads as fully available rather than dividing by zero.
    assertThat(KiteRateBudgetPublisher.budgetFraction(5, 0)).isEqualTo(1.0);
  }
}
