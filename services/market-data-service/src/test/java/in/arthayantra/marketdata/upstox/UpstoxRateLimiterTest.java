package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the token-scoped {@link UpstoxRateLimiter} (EXT-02): the live and the batch path draw
 * from ONE shared budget, and a saturated backfill can never consume the headroom reserved for the
 * live capture / quote / margin path.
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

  @Test
  void liveAndBatchDrawFromOneBudget() {
    UpstoxRateLimiter limiter = new UpstoxRateLimiter(); // production 45/s · 450/min · 1800/30min

    limiter.acquire();
    limiter.acquire();
    limiter.acquire();
    limiter.acquireForBatch();
    limiter.acquireForBatch();

    // Every call — live OR batch — records into the SAME windows: one honest per-token count.
    assertThat(used(limiter, "30m")).isEqualTo(5);
    assertThat(used(limiter, "1m")).isEqualTo(5);
    assertThat(used(limiter, "1s")).isEqualTo(5);
  }

  @Test
  void saturatedBatchDoesNotStarveLive() throws InterruptedException {
    // One long window (nothing expires during the test), max 3, 25% reserved → batch ceiling 2, so
    // the batch may fill only 2 of the 3 slots; the 3rd is reserved for the live path.
    UpstoxRateLimiter limiter =
        new UpstoxRateLimiter(
            0.25, new String[] {"w"}, new long[] {3_600_000L}, new int[] {3});

    limiter.acquireForBatch();
    limiter.acquireForBatch();
    assertThat(used(limiter, "w")).isEqualTo(2); // batch saturated at its ceiling

    // A 3rd batch call parks — it may not touch the live-reserved slot (window never frees here).
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

    // The live path must still proceed immediately using the reserved slot. Run it on its own thread
    // with a join timeout so a REGRESSION (live wrongly blocked) fails fast instead of hanging forever.
    Thread live = new Thread(limiter::acquire);
    live.setDaemon(true);
    live.start();
    live.join(2_000);

    assertThat(live.isAlive())
        .as("live must not be starved by a saturated backfill")
        .isFalse();
    assertThat(used(limiter, "w")).isEqualTo(3);
    assertThat(parkedBatch.isAlive())
        .as("batch must not consume the live-reserved slot")
        .isTrue();

    parkedBatch.interrupt();
    parkedBatch.join(1_000);
  }

  @Test
  void batchCeilingReservesTheDefaultLiveFraction() {
    // The production default reserve (20%) on a fast single window: max 10 → batch ceiling
    // floor(10 * 0.8) = 8, so 2 slots (20%) stay reserved for the live path. Long window so nothing
    // expires mid-test; small counts so it runs instantly.
    UpstoxRateLimiter limiter =
        new UpstoxRateLimiter(
            UpstoxRateLimiter.DEFAULT_LIVE_RESERVE_FRACTION,
            new String[] {"w"},
            new long[] {3_600_000L},
            new int[] {10});
    for (int i = 0; i < 8; i++) {
      limiter.acquireForBatch();
    }
    assertThat(used(limiter, "w")).isEqualTo(8); // batch saturated at 80% of the window
    limiter.acquire();
    limiter.acquire();
    assertThat(used(limiter, "w")).isEqualTo(10); // live still consumed the reserved 20%
  }
}
