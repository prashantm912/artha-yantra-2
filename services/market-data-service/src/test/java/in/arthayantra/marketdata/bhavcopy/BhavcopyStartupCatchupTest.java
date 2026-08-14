package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.canary.BhavcopyCloseCanary;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The boot catch-up must replay the close canary's missed population pass — and must never let that
 * replay stand between the boot and the bhavcopy pull.
 *
 * <p><b>Why the replay is load-bearing.</b> {@code BhavcopyCloseCanary.prefetchPopulation} is
 * CRON-ONLY at 16:05 IST, so a boot after that hour skips the pass for the whole session and the
 * canary is left comparing whatever {@code source='KITE'} bars other jobs happened to leave behind.
 * Measured: 202 seeded against 14 borrowed (2026-08-11 / 08-12), with the canary reporting GREEN on
 * those 14. A late boot is ordinary here — the machine is off overnight, and the live stack has
 * spent an entire afternoon down (2026-08-10, no batch 08:29→18:47 IST). {@code
 * BhavcopyClosePopulationPrefetchTest} pins the canary's half (what gets replayed, and when); this
 * pins the listener's half (that it happens at all, and that it stays bounded).
 *
 * <p><b>What this deliberately does NOT claim.</b> Running the replay FIRST is not a correctness
 * requirement. The bhavcopy projection could safely claim these 1d buckets first: {@code
 * upsertAuthoritativeAll}'s keep-the-existing-source branch also requires {@code oi} to match, and
 * {@code BhavcopyCandles} writes {@code oi = null} where Kite historical returns {@code 0} for cash
 * equities, so a later Kite bar always takes {@code source='KITE'}. That is pinned in {@code
 * CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero},
 * not here. The order asserted below is the intended composition on a healthy night, and it is
 * bounded precisely BECAUSE it is not load-bearing.
 */
class BhavcopyStartupCatchupTest {

  private final BhavcopyBackfillService backfill = mock(BhavcopyBackfillService.class);
  private final BhavcopyCloseCanary closeCanary = mock(BhavcopyCloseCanary.class);
  private final BhavcopyStartupCatchup catchup =
      new BhavcopyStartupCatchup(backfill, closeCanary);

  @Test
  @DisplayName("the ready event replays the canary population before pulling the bhavcopy")
  void thePopulationReplayRunsBeforeTheBhavcopyPull() {
    catchup.onStartup();

    // Both steps run off the ready thread, so wait for the second one rather than assuming the
    // listener ran them inline. A timeout, never a sleep: on a slow box a fixed sleep is a flake,
    // and here it would be a flake that reports the ORDERING as broken.
    verify(backfill, timeout(5_000)).runIfFree();

    InOrder order = inOrder(closeCanary, backfill);
    order
        .verify(closeCanary)
        .catchUpPopulation();
    order.verify(backfill).runIfFree();
  }

  @Test
  @DisplayName("a failing population replay never cancels the bhavcopy pull")
  void aFailingPopulationReplayStillLetsTheBhavcopyPullRun() {
    // Pins the OUTCOME, not one mechanism. Two independent things deliver it today — the replay
    // runs on its own thread, and the call itself is wrapped — and either alone would suffice.
    // That redundancy is deliberate: the bhavcopy pull is the self-heal for a long downtime, and a
    // silently-skipped pull looks exactly like the startup catch-up having been disabled.
    when(closeCanary.catchUpPopulation()).thenThrow(new IllegalStateException("kite circuit open"));

    catchup.catchUpInOrder();

    verify(backfill).runIfFree();
  }

  @Test
  @DisplayName("a population replay that overruns its deadline hands off to the bhavcopy pull")
  void aSlowPopulationReplayHandsOffAtTheDeadline() throws InterruptedException {
    // ⚠️ The failure this closes, and no other test here can see it: the catch above handles a
    // canary that THROWS, never one that is merely SLOW. The pass is ~202 sequential Kite fetches,
    // each retried up to 4 times over a 60 s read timeout, so a brown-out stretches it without
    // bound — and an 18:57 boot into one would hold the pull past the 19:00 machine-off and cost
    // the night's bhavcopy entirely. A mock that never returns is exactly that shape.
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean replayFinished = new AtomicBoolean();
    when(closeCanary.catchUpPopulation())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await(30, TimeUnit.SECONDS);
              replayFinished.set(true);
              return 202;
            });

    long startedAt = System.nanoTime();
    try {
      catchup.catchUpInOrder(Duration.ofMillis(100));
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(entered.await(5, TimeUnit.SECONDS))
          .as("the replay must actually have been started, or the hand-off proves nothing")
          .isTrue();
      verify(backfill).runIfFree();
      assertThat(elapsedMs)
          .as(
              "the pull must start at the deadline (100 ms), not when the replay finally returns"
                  + " (blocked up to 30 s here)")
          .isLessThan(5_000);
      assertThat(replayFinished)
          .as("and it must still be IN FLIGHT — the hand-off is the point, not an early finish")
          .isFalse();
    } finally {
      release.countDown();
    }

    // Handed off, NOT abandoned: the replay keeps running and its bars still land, which is why
    // the deadline can afford to be short. BhavcopyCloseCanary.sweep() does not read the
    // population until 18:58, so a pass that completes after the pull started still counts.
    await().atMost(Duration.ofSeconds(5)).untilTrue(replayFinished);
  }
}
