package in.arthayantra.marketdata.bhavcopy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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

/**
 * The boot catch-up must replay the close canary's missed population pass — and that replay must
 * never be able to stand between the boot and the bhavcopy pull.
 *
 * <p><b>Why the replay is load-bearing.</b> {@code BhavcopyCloseCanary.prefetchPopulation} is
 * CRON-ONLY at 16:05 IST, so a boot after that hour skips the pass for the whole session and the
 * canary is left comparing whatever non-bhavcopy bars other jobs happened to leave behind. Measured:
 * 202 seeded against 14 borrowed (2026-08-11 / 08-12), with the canary reporting GREEN on those 14.
 * A late boot is ordinary here — the machine is off overnight, and the live stack has spent an
 * entire afternoon down (2026-08-10, no batch 08:29→18:47 IST). {@code
 * BhavcopyClosePopulationPrefetchTest} pins the canary's half (what gets replayed, and when); this
 * pins the listener's half (that it happens at all, and that it never delays the feed).
 *
 * <p><b>What this deliberately does NOT claim: an ORDER.</b> The two steps are started
 * independently and race. Running the replay first is not a correctness requirement — {@code
 * upsertAuthoritativeAll}'s keep-the-existing-source branch also requires {@code oi} to match, and
 * {@code BhavcopyCandles} writes {@code oi = null} where Kite historical returns {@code 0} for cash
 * equities, so a later Kite bar always takes the Kite source. That is pinned in {@code
 * CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero}
 * and {@code
 * LiveHistoricalCandleGatewayTest#cashEquityDailyBarKeepsAZeroOpenInterestRatherThanNulling}, not
 * here. An earlier round of this change joined the replay for 90 s to state the order in program
 * order; {@link #aSlowPopulationReplayNeverDelaysTheBhavcopyPull()} below is what stops that from
 * coming back.
 */
class BhavcopyStartupCatchupTest {

  private final BhavcopyBackfillService backfill = mock(BhavcopyBackfillService.class);
  private final BhavcopyCloseCanary closeCanary = mock(BhavcopyCloseCanary.class);
  private final BhavcopyStartupCatchup catchup =
      new BhavcopyStartupCatchup(backfill, closeCanary);

  @Test
  @DisplayName("the ready event both replays the canary population and pulls the bhavcopy")
  void theReadyEventDoesBothSteps() {
    catchup.onStartup();

    // The pull is synchronous on the ready thread (runIfFree only SUBMITS); the replay is not, so
    // it gets a timeout rather than a sleep — on a slow box a fixed sleep would be a flake that
    // reports the replay as missing.
    verify(backfill).runIfFree();
    verify(closeCanary, timeout(5_000)).catchUpPopulation();
  }

  @Test
  @DisplayName("a failing population replay never cancels the bhavcopy pull")
  void aFailingPopulationReplayStillLetsTheBhavcopyPullRun() {
    // Pins the OUTCOME, not one mechanism. Three independent things deliver it today — the replay
    // runs on its own thread, the call itself is wrapped, and the pull sits in a finally — and any
    // one alone would suffice. That redundancy is deliberate: the bhavcopy pull is the self-heal
    // for a long downtime, and a silently-skipped pull looks exactly like the startup catch-up
    // having been disabled.
    when(closeCanary.catchUpPopulation()).thenThrow(new IllegalStateException("kite circuit open"));

    catchup.onStartup();

    verify(backfill).runIfFree();
    verify(closeCanary, timeout(5_000)).catchUpPopulation();
  }

  @Test
  @DisplayName("a population replay that never returns still does not hold up the bhavcopy pull")
  void aSlowPopulationReplayNeverDelaysTheBhavcopyPull() throws InterruptedException {
    // ⚠️ The regression this closes, and no other test here can see it: the catch above handles a
    // canary that THROWS, never one that is merely SLOW. The pass is ~202 sequential Kite fetches,
    // each retried up to 4 times over a 60 s read timeout, so a brown-out stretches it without
    // bound — and any wait in front of the pull spends that budget against a 19:00 machine-off. A
    // mock that never returns is exactly that shape.
    //
    // ⚠️ What this test actually detects, stated precisely because an earlier version of this
    // comment claimed "re-add a join of any length and this reds" — which is FALSE. The only timing
    // assertion is `elapsedMs < 5_000`, and the `replayFinished` isFalse check also survives a short
    // join because the mock blocks 30 s. A `replay.join(4_000)` passes every assertion here. So this
    // catches an UNBOUNDED or long wait, not any wait at all. The 5 s budget is deliberate — it has
    // to clear a 2-core CI runner — and tightening it to catch a 4 s join would trade a real
    // regression guard for flakiness. A comment that overstates its own test is the failure mode
    // this PR guards against elsewhere (see LiveHistoricalCandleGatewayTest:139-140).
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
      catchup.onStartup();
      long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(elapsedMs)
          .as(
              "the pull must be submitted immediately, not when the replay finally returns (blocked"
                  + " up to 30 s here) and not after any deadline in between")
          .isLessThan(5_000);
      assertThat(entered.await(5, TimeUnit.SECONDS))
          .as("the replay must actually have been started, or the independence proves nothing")
          .isTrue();
      verify(backfill).runIfFree();
      assertThat(replayFinished)
          .as("and it must still be IN FLIGHT — running alongside the pull is the point")
          .isFalse();
    } finally {
      release.countDown();
    }

    // Started, NOT abandoned: the replay keeps running and its bars still land. {@code
    // BhavcopyCloseCanary.sweep()} does not read the population until 18:58, so a pass that
    // completes well after the pull started still counts.
    await().atMost(Duration.ofSeconds(5)).untilTrue(replayFinished);
  }
}
