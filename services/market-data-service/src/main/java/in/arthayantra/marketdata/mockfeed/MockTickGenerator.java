package in.arthayantra.marketdata.mockfeed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Seeded deterministic random-walk price generator (A.7a / CD-10): same seed ⇒ identical tick
 * sequence per instrument. Scenario shapes: {@code trend-up}, {@code trend-down}, {@code chop},
 * {@code gap-open} (one +2% jump on the first tick, then chop). Prices snap to the 0.05 NSE tick
 * and stay exact decimals throughout — floats never touch the output.
 */
public class MockTickGenerator {

  private static final BigDecimal TICK_SIZE = new BigDecimal("0.05");

  private final String scenario;
  private final long seed;
  private final Map<Long, InstrumentState> states = new HashMap<>();

  private static final class InstrumentState {
    final Random random;
    BigDecimal price;
    long cumulativeVolume;
    long openInterest = 100_000;
    boolean first = true;

    InstrumentState(Random random, BigDecimal price) {
      this.random = random;
      this.price = price;
    }
  }

  public MockTickGenerator(long seed, String scenario) {
    this.seed = seed;
    this.scenario = scenario;
  }

  /** The next price/volume step for {@code instrumentToken}; deterministic in call order. */
  public Step next(long instrumentToken) {
    InstrumentState state =
        states.computeIfAbsent(
            instrumentToken,
            token -> new InstrumentState(new Random(seed * 31 + token), basePrice(token)));

    double drift;
    double volatility;
    switch (scenario) {
      case "trend-down" -> {
        drift = -0.00015;
        volatility = 0.0006;
      }
      case "chop" -> {
        drift = 0.0;
        volatility = 0.0012;
      }
      case "gap-open" -> {
        if (state.first) {
          state.price =
              snap(state.price.multiply(new BigDecimal("1.02")));
        }
        drift = 0.0;
        volatility = 0.0012;
      }
      default -> { // trend-up
        drift = 0.00015;
        volatility = 0.0006;
      }
    }
    state.first = false;

    double step = drift + volatility * state.random.nextGaussian();
    BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(step));
    state.price = snap(state.price.multiply(factor)).max(TICK_SIZE);
    state.cumulativeVolume += 1 + state.random.nextInt(1000);
    // deterministic OI random walk for F&O consumers (callers decide applicability)
    state.openInterest = Math.max(0, state.openInterest + state.random.nextInt(2001) - 1000);
    return new Step(state.price, state.cumulativeVolume, state.openInterest);
  }

  /** One generated step. */
  public record Step(BigDecimal lastPrice, long cumulativeDayVolume, long openInterest) {}

  /** Deterministic per-token base price in a plausible range. */
  static BigDecimal basePrice(long token) {
    long base = 100L + Math.floorMod(token * 2654435761L, 24_000L);
    return new BigDecimal(base).setScale(2, RoundingMode.UNNECESSARY);
  }

  private static BigDecimal snap(BigDecimal price) {
    return price
        .divide(TICK_SIZE, 0, RoundingMode.HALF_UP)
        .multiply(TICK_SIZE)
        .setScale(2, RoundingMode.HALF_UP);
  }
}
