package in.arthayantra.marketdata.bhavcopy;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.canary.BhavcopyCloseCanary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * The boot catch-up must seed the close canary's comparison population BEFORE it projects the
 * bhavcopy into {@code candles}.
 *
 * <p>⚠️ Why this ordering is load-bearing, and why no other test can see it. {@code source} is not
 * in the {@code candles} primary key, so a {@code source='BHAVCOPY'} 1d row and a {@code
 * source='KITE'} 1d row collide on the same bucket; the bhavcopy projection uses {@code
 * insertIgnoreAll} (DO NOTHING) so a Kite bar already there is safe forever, while a later Kite
 * fetch goes through {@code upsertAuthoritativeAll}, which KEEPS the existing source when every
 * OHLCV field matches. Bhavcopy-first therefore drops precisely the PERFECTLY AGREEING bars out of
 * the canary's {@code source='KITE'} population — a sample biased against agreement, used to judge
 * agreement.
 *
 * <p>{@code BhavcopyCloseCanary.prefetchPopulation} is scheduled at 16:05 IST to be ahead of every
 * same-day bhavcopy write (NSE's earliest measured publish is 17:52), and {@code
 * BhavcopyClosePopulationPrefetchTest} pins that compose ordering. But that cron only fires while
 * the service is UP at 16:05, and this listener fires on EVERY boot — so a boot after 16:05 (the
 * machine is off overnight; the live stack has spent a whole afternoon down, 2026-08-10) inverts
 * the order the cron was chosen to guarantee. A compose-cron assertion cannot see that: both
 * schedules are still correct, and the projection simply arrives through a different door.
 */
class BhavcopyStartupCatchupOrderingTest {

  private final BhavcopyBackfillService backfill = mock(BhavcopyBackfillService.class);
  private final BhavcopyCloseCanary closeCanary = mock(BhavcopyCloseCanary.class);
  private final BhavcopyStartupCatchup catchup =
      new BhavcopyStartupCatchup(backfill, closeCanary);

  @Test
  @DisplayName("the ready event seeds the canary population before pulling the bhavcopy")
  void thePopulationPassRunsBeforeTheBhavcopyPull() {
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
  @DisplayName("a failing population catch-up delays the bhavcopy pull, never cancels it")
  void aCanaryFailureStillLetsTheBhavcopyPullRun() {
    // The bhavcopy pull is the self-heal for a long downtime and outranks the canary's sample
    // quality. Unguarded, the exception kills the catch-up thread before runIfFree() is reached and
    // the backfill silently never starts — a worse outcome than a biased canary population, and one
    // that would look exactly like the startup catch-up having been disabled.
    when(closeCanary.catchUpPopulation()).thenThrow(new IllegalStateException("kite circuit open"));

    catchup.catchUpInOrder();

    verify(backfill).runIfFree();
  }
}
