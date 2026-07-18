package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the token-scoped {@link UpstoxRateLimiter} (EXT-02): the live and the batch path draw
 * from ONE shared budget; the batch path PAUSES while the market is open so the live capture path owns
 * the token during the session (item 1); a saturated budget makes the live {@code tryAcquire} fail FAST
 * rather than parking (item 2); and off-hours a saturated backfill still cannot consume the live reserve.
 */
class UpstoxRateLimiterTest {

  private static int used(UpstoxRateLimiter limiter, String window) {
    for (WindowStat s : limiter.getUsageStats()) {
      if (s.window().equals(window)) {
        return s.used();
      }
    }
    throw new IllegalArgumentException("no window " + window);
  }

  /** A long single window (nothing expires mid-test) with the given max and reserve fraction, market closed. */
  private static UpstoxRateLimiter window(double reserveFraction, int max) {
    return new UpstoxRateLimiter(
        reserveFraction, () -> false, new String[] {"w"}, new long[] {3_600_000L}, new int[] {max});
  }

  @Test
  void liveAndBatchDrawFromOneBudget() {
    UpstoxRateLimiter limiter = new UpstoxRateLimiter(); // production 45/s · 450/min · 1800/30min

    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    assertThat(limiter.tryAcquire()).isTrue();
    limiter.acquireForBatch();
    limiter.acquireForBatch();

    // Every call — live OR batch — records into the SAME windows: one honest per-token count.
    assertThat(used(limiter, "30m")).isEqualTo(5);
    assertThat(used(limiter, "1m")).isEqualTo(5);
    assertThat(used(limiter, "1s")).isEqualTo(5);
  }

  @Test
  void batchPausesWhileMarketOpenSoLiveOwnsTheBudget() throws InterruptedException {
    AtomicBoolean marketOpen = new AtomicBoolean(true);
    UpstoxRateLimiter limiter =
        new UpstoxRateLimiter(
            0.0, marketOpen::get, new String[] {"w"}, new long[] {3_600_000L}, new int[] {5});

    // A batch call started while the market is open must PARK in the pause loop — it records nothing,
    // so the whole budget stays available to the live capture path during the session.
    Thread batch =
        new Thread(
            () -> {
              try {
                limiter.acquireForBatch();
              } catch (RuntimeException ignored) {
                // interrupted at teardown — expected
              }
            });
    batch.setDaemon(true);
    batch.start();

    // The live path proceeds immediately even though the batch is trying to run.
    assertThat(limiter.tryAcquire(200)).isTrue();
    assertThat(used(limiter, "w")).as("only the live hit is recorded; batch is paused").isEqualTo(1);
    assertThat(batch.isAlive()).as("batch stays paused while the market is open").isTrue();

    batch.interrupt();
    batch.join(1_000);
  }

  @Test
  void liveTryAcquireFailsFastWhenSaturated() {
    UpstoxRateLimiter limiter = window(0.0, 1); // max 1, no reserve
    assertThat(limiter.tryAcquire(50)).isTrue(); // fills the only slot

    long start = System.nanoTime();
    boolean acquired = limiter.tryAcquire(50);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(acquired).as("saturated budget → live caller must not acquire").isFalse();
    assertThat(elapsedMs).as("returns within ~the bound, never parks ~30 min").isLessThan(500);
  }

  @Test
  void saturatedBatchDoesNotStarveLiveOffHours() throws InterruptedException {
    // Market closed, max 3, 25% reserved → batch ceiling 2 (the 3rd slot is reserved for live).
    UpstoxRateLimiter limiter = window(0.25, 3);

    limiter.acquireForBatch();
    limiter.acquireForBatch();
    assertThat(used(limiter, "w")).isEqualTo(2); // batch saturated at its ceiling

    // A 3rd batch call parks (window never frees here); a live call still gets the reserved slot.
    Thread parkedBatch =
        new Thread(
            () -> {
              try {
                limiter.acquireForBatch();
              } catch (RuntimeException ignored) {
                // interrupted at teardown — expected
              }
            });
    parkedBatch.setDaemon(true);
    parkedBatch.start();

    assertThat(limiter.tryAcquire(200)).as("live uses the reserved off-hours headroom").isTrue();
    assertThat(used(limiter, "w")).isEqualTo(3);
    assertThat(parkedBatch.isAlive()).as("batch may not consume the live-reserved slot").isTrue();

    parkedBatch.interrupt();
    parkedBatch.join(1_000);
  }

  @Test
  void batchCeilingReservesTheDefaultLiveFraction() {
    // The production default reserve (20%) on a fast single window: max 10 → batch ceiling
    // floor(10 * 0.8) = 8, so 2 slots (20%) stay reserved for the live path.
    UpstoxRateLimiter limiter = window(UpstoxRateLimiter.DEFAULT_LIVE_RESERVE_FRACTION, 10);
    for (int i = 0; i < 8; i++) {
      limiter.acquireForBatch();
    }
    assertThat(used(limiter, "w")).isEqualTo(8); // batch saturated at 80% of the window
    assertThat(limiter.tryAcquire(50)).isTrue();
    assertThat(limiter.tryAcquire(50)).isTrue();
    assertThat(used(limiter, "w")).isEqualTo(10); // live still consumed the reserved 20%
  }
}
