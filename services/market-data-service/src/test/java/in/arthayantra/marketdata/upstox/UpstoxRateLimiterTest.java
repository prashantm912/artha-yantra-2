package in.arthayantra.marketdata.upstox;

import static org.assertj.core.api.Assertions.assertThat;

import in.arthayantra.marketdata.upstox.UpstoxRateLimiter.WindowStat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the token-scoped {@link UpstoxRateLimiter} (EXT-02): the live and the batch path draw
 * from ONE shared budget; the batch path PAUSES through the batch-quiet guard window (from before open
 * through close) so the live capture path owns the token during the session, and it will NOT record a
 * hit admitted before the window inside it (item 1); a saturated budget makes the live {@code tryAcquire}
 * fail FAST rather than parking (item 2); off-hours a saturated backfill still cannot consume the reserve.
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

  /** A metered single window, market closed, nothing reserved for live. */
  private static UpstoxRateLimiter metered(int max, long windowMs, SimpleMeterRegistry meters) {
    return new UpstoxRateLimiter(
        0.0, () -> false, new String[] {"w"}, new long[] {windowMs}, new int[] {max}, meters);
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
  void batchParksInTheGuardWindowSoLiveOwnsTheBudget() throws InterruptedException {
    // A batch call at, say, 09:00 IST (inside the [open−30min, close] guard window) must PARK — it
    // records nothing, so the whole budget stays available to the live capture path through the session.
    AtomicBoolean inGuardWindow = new AtomicBoolean(true);
    UpstoxRateLimiter limiter =
        new UpstoxRateLimiter(
            0.0, inGuardWindow::get, new String[] {"w"}, new long[] {3_600_000L}, new int[] {5});

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
    assertThat(batch.isAlive()).as("batch stays paused in the guard window").isTrue();

    batch.interrupt();
    batch.join(1_000);
  }

  @Test
  void aBatchHitAdmittedBeforeTheGuardWindowIsNotRecordedInsideIt() throws InterruptedException {
    // gate = false on the FIRST check (the call passes the pre-record pause, i.e. admitted before the
    // window) then true (the guard window opened by the time a slot is about to be recorded). The batch
    // hit must NOT record across that boundary — the call re-pauses instead. The window has a free slot,
    // so the ONLY thing that can stop the record is the record-time gate re-check.
    AtomicInteger checks = new AtomicInteger();
    UpstoxRateLimiter limiter =
        new UpstoxRateLimiter(
            0.0,
            () -> checks.getAndIncrement() >= 1,
            new String[] {"w"},
            new long[] {3_600_000L},
            new int[] {5});

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

    // Wait until the call has passed the pause (check 0) AND done the record-time re-check (check 1).
    Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> checks.get() >= 2);
    assertThat(used(limiter, "w")).as("no batch hit recorded across the guard boundary").isEqualTo(0);
    assertThat(batch.isAlive()).as("the batch re-parked instead of recording").isTrue();

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
  void budgetTelemetryCountsLiveAndBatchCallsSeparately() {
    // H26 step 1: the two priorities have different irreducibility (only the live half cannot be
    // deferred), so a session total that cannot be split is not the number the owner needs.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    UpstoxRateLimiter limiter = metered(10, 3_600_000L, meters);

    assertThat(limiter.tryAcquire(50)).isTrue();
    assertThat(limiter.tryAcquire(50)).isTrue();
    assertThat(limiter.tryAcquire(50)).isTrue();
    limiter.acquireForBatch();
    limiter.acquireForBatch();

    assertThat(meters.get("ay_upstox_calls_total").tag("path", "live").counter().count())
        .isEqualTo(3.0);
    assertThat(meters.get("ay_upstox_calls_total").tag("path", "batch").counter().count())
        .isEqualTo(2.0);
    assertThat(used(limiter, "w"))
        .as("telemetry is observational: the budget still debits exactly as before")
        .isEqualTo(5);
  }

  @Test
  void budgetTelemetryCountsRefusedLiveAcquisitions() {
    // The single most important series: a refused live slot is DIRECT evidence of exhaustion, and it
    // is silent by construction today because tryAcquire fails soft and records nothing.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    UpstoxRateLimiter limiter = metered(1, 3_600_000L, meters);

    assertThat(limiter.tryAcquire(50)).isTrue(); // fills the only slot
    assertThat(limiter.tryAcquire(50)).isFalse();
    assertThat(limiter.tryAcquire(50)).isFalse();

    assertThat(meters.get("ay_upstox_live_refused_total").counter().count()).isEqualTo(2.0);
    assertThat(meters.get("ay_upstox_calls_total").tag("path", "live").counter().count())
        .as("a refused acquisition is not a call")
        .isEqualTo(1.0);
  }

  @Test
  void peakUsedRetainsABurstTheSnapshotHasAlreadyLost() {
    // The crux of H26 step 1. getUsageStats() is an instantaneous SNAPSHOT, so a burst that happens
    // between two 15 s scrapes is invisible to it — which is exactly the question "did we ever come
    // close to the cap" needs answered. Fill 4 of 10 slots, let the window drain, and the snapshot
    // reads 0 while the high-water mark still reports the 4 that were actually reached.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    UpstoxRateLimiter limiter = metered(10, 2_000L, meters);
    for (int i = 0; i < 4; i++) {
      assertThat(limiter.tryAcquire(50)).isTrue();
    }
    assertThat(used(limiter, "w")).isEqualTo(4);

    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> used(limiter, "w") == 0);

    assertThat(meters.get("ay_upstox_rate_window_peak_used").tag("window", "w").gauge().value())
        .as("the burst is gone from the snapshot; the high-water mark must still carry it")
        .isEqualTo(4.0);
    assertThat(meters.get("ay_upstox_rate_window_max").tag("window", "w").gauge().value())
        .as("a peak is uninterpretable without its cap on the same scrape")
        .isEqualTo(10.0);
  }

  @Test
  void peakUsedKeepsTheMaximumNotTheLastOccupancy() {
    // The property the sibling test above CANNOT observe: it only pins that the value survives a
    // drain, and a last-value implementation survives a drain identically (the last record() IS the
    // burst). Under last-value a 30m window that peaked at 1700 in-session and then took one quiet
    // evening call would report 1/1800 — "vast headroom", the exact number H26 step 1 exists to
    // produce, and an architecture would be committed to it.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    UpstoxRateLimiter limiter = metered(10, 2_000L, meters);
    for (int i = 0; i < 6; i++) {
      assertThat(limiter.tryAcquire(50)).isTrue();
    }
    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> used(limiter, "w") == 0);
    assertThat(limiter.tryAcquire(50)).isTrue(); // one later call: occupancy is now 1

    assertThat(meters.get("ay_upstox_rate_window_peak_used").tag("window", "w").gauge().value())
        .as("a later quiet call must not lower the session high-water mark")
        .isEqualTo(6.0);
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
