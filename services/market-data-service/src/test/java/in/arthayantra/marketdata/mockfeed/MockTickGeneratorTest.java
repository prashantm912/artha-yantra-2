package in.arthayantra.marketdata.mockfeed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CD-10 determinism + scenario shapes. */
class MockTickGeneratorTest {

  private static List<BigDecimal> prices(long seed, String scenario, long token, int n) {
    MockTickGenerator generator = new MockTickGenerator(seed, scenario);
    List<BigDecimal> prices = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      prices.add(generator.next(token).lastPrice());
    }
    return prices;
  }

  @Test
  void sameSeedProducesIdenticalFirstHundredTicks() {
    assertThat(prices(42, "trend-up", 256265, 100))
        .isEqualTo(prices(42, "trend-up", 256265, 100));
  }

  @Test
  void differentSeedsDiverge() {
    assertThat(prices(42, "trend-up", 256265, 100))
        .isNotEqualTo(prices(43, "trend-up", 256265, 100));
  }

  @Test
  void pricesSnapToNseTickAndStayPositive() {
    for (BigDecimal price : prices(7, "chop", 100001, 500)) {
      assertThat(price.remainder(new BigDecimal("0.05"))).isEqualByComparingTo(BigDecimal.ZERO);
      assertThat(price).isPositive();
    }
  }

  @Test
  void trendScenariosDriftAsNamed() {
    List<BigDecimal> up = prices(42, "trend-up", 1, 2000);
    List<BigDecimal> down = prices(42, "trend-down", 1, 2000);

    assertThat(up.get(1999)).isGreaterThan(up.get(0));
    assertThat(down.get(1999)).isLessThan(down.get(0));
  }

  @Test
  void gapOpenJumpsOnFirstTick() {
    BigDecimal base = MockTickGenerator.basePrice(1);
    BigDecimal firstGap = prices(42, "gap-open", 1, 1).get(0);

    // ~+2% gap before the first random step
    assertThat(firstGap)
        .isGreaterThan(base.multiply(new BigDecimal("1.01")));
  }

  @Test
  void cumulativeVolumeIsMonotonic() {
    MockTickGenerator generator = new MockTickGenerator(42, "chop");
    long previous = 0;
    for (int i = 0; i < 100; i++) {
      long volume = generator.next(5).cumulativeDayVolume();
      assertThat(volume).isGreaterThan(previous);
      previous = volume;
    }
  }
}
