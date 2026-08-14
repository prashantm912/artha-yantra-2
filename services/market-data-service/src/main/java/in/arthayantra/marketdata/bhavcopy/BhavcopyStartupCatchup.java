package in.arthayantra.marketdata.bhavcopy;

import in.arthayantra.marketdata.canary.BhavcopyCloseCanary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Startup catch-up: pull once on boot so a long downtime self-heals immediately and the fetch path
 * is exercised. Extracted from {@link BhavcopyBackfillService} so it can be switched OFF the same
 * way {@code SyncBootstrap} already is — see {@code artha.instruments.bootstrap-sync}.
 *
 * <p><b>Why this is a separate, disableable bean.</b> {@link BhavcopyBackfillService#runIfFree()} is
 * FIRE-AND-FORGET: it submits to the service's own executor and returns, so the write to
 * {@code nse_eod_bhavcopy} outlives {@code ApplicationReadyEvent} by an unbounded margin. In the
 * integration suite that raced {@code BhavcopyBackfillIntegrationTest}'s whole-table
 * {@code DELETE FROM nse_eod_bhavcopy} and deadlocked —
 * {@code PessimisticLockingFailureException: deadlock detected}, two transactions each holding what
 * the other wanted. It reproduced ONLY on the 2-core CI runner (locally the startup run finishes
 * long before the test reaches its DELETE), which is why it passed every PR shard while turning
 * {@code push:main} red from #1016 onward.
 *
 * <p>⚠️ The first mitigation — "tests that manipulate bhavcopy tables set
 * {@code artha.bhavcopy.startup-catchup=false}" — was the WRONG SCOPE and the deadlock came back
 * (task_06ad72b6, 2026-07-31: 3-of-3 surefire attempts on PR #1138's shard, with the failing test
 * carrying the property). Spring CACHES test contexts: every OTHER context in the module boots
 * with catch-up on, and this listener's fire-and-forget write can land while any test's DELETE
 * runs — the hazard is any cached-context writer vs any test, not this test vs itself. The
 * property is therefore defaulted OFF for the whole module on
 * {@code MarketDataIntegrationTestBase} (the substrate all 67 IT contexts extend), along with the
 * 19:30-IST eod-cron (14:00 UTC — prime CI hours, the same race through the scheduled door). There
 * is NO in-hierarchy opt-out — subclass {@code @DynamicPropertySource} runs BEFORE the base's, so
 * the base wins; a test that wants this path must not extend the shared substrate.
 *
 * <p>Production behaviour is unchanged: the property defaults to on via {@code matchIfMissing}.
 *
 * <p><b>⚠️ This listener also OWNS AN ORDERING, not just a trigger.</b> The bhavcopy projection
 * writes 1d {@code candles} rows, and {@code source} is not in that table's primary key: a
 * {@code source='BHAVCOPY'} row and a {@code source='KITE'} row collide on the same bucket, and
 * {@code upsertAuthoritativeAll} KEEPS the existing source when every OHLCV field matches. So if
 * this projection claims today's buckets first, the bars that agree PERFECTLY are exactly the ones
 * that stay BHAVCOPY and fall out of {@link BhavcopyCloseCanary}'s {@code source='KITE'}
 * comparison population — a sample biased against agreement, judging agreement.
 *
 * <p>{@code BhavcopyCloseCanary.prefetchPopulation} is scheduled at 16:05 IST to be ahead of any
 * same-day bhavcopy write, but it is CRON-ONLY: on a boot after 16:05 it never runs for that
 * session, and this listener fires immediately. That is the inversion. So the catch-up here is a
 * PAIR, in order: replay the canary's missed population pass, then pull the bhavcopy.
 *
 * <p><b>Why a direct call on a one-shot thread, and not two ordered listeners.</b> Three shapes
 * were considered:
 *
 * <ul>
 *   <li><b>{@code @Order} on the two {@code ApplicationReadyEvent} listeners</b> — does not work.
 *       An unannotated listener sits at {@code LOWEST_PRECEDENCE}, so ordering the canary before
 *       THIS bean necessarily orders it before every other one too, including
 *       {@code PinnedIndicesSubscriber} / {@code SubscriptionReplayer} / {@code FuturesPinner}.
 *       The population pass is ~71 s nominal and unbounded in a Kite brown-out (Retry ×4 over a
 *       60 s read timeout), so that trades a canary's coverage for the LIVE FEED's subscription.
 *   <li><b>A completion gate the canary publishes and this bean awaits</b> — correct, and it needs
 *       a future, a timeout, and a {@code finally} that completes the gate on every abnormal path.
 *       All of that exists only to re-derive an ordering that a direct call states outright.
 *   <li><b>A direct call, on one short-lived daemon thread</b> — chosen. The ordering is program
 *       order, so there is nothing to get wrong and nothing to wait on; the ready thread returns as
 *       immediately as it did before (both steps move off it, where previously only the second
 *       did); and it cannot deadlock, because this thread is the only thing running on itself and
 *       nothing else ever joins it.
 * </ul>
 *
 * <p><b>The coupling this creates, stated rather than hidden:</b> {@code
 * artha.bhavcopy.startup-catchup=false} now also disables the canary's population catch-up. That is
 * the right pairing for the ordering hazard (no startup projection, no startup inversion) but NOT
 * for coverage — the 18:45 cron still projects, so on a late boot with this bean off, the canary is
 * back to a borrowed population. It is off only in the integration suite, where the canary is
 * live-profile-gated and inert anyway.
 */
@Component
@ConditionalOnProperty(name = "artha.bhavcopy.startup-catchup", havingValue = "true", matchIfMissing = true)
public class BhavcopyStartupCatchup {

  private static final Logger log = LoggerFactory.getLogger(BhavcopyStartupCatchup.class);

  private final BhavcopyBackfillService backfill;
  private final BhavcopyCloseCanary closeCanary;

  /** Wires the backfill service and the close canary whose population must land first. */
  public BhavcopyStartupCatchup(BhavcopyBackfillService backfill, BhavcopyCloseCanary closeCanary) {
    this.backfill = backfill;
    this.closeCanary = closeCanary;
  }

  /**
   * Pull once on startup, behind the close canary's missed population pass. Both steps run off the
   * ready thread on one short-lived daemon thread; {@code runIfFree()} then hands off to the
   * service's own executor exactly as before, so the write is still fire-and-forget.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    Thread ordered = new Thread(this::catchUpInOrder, "bhavcopy-startup-catchup");
    ordered.setDaemon(true);
    ordered.start();
  }

  /** The ordered pair: canary population first, bhavcopy pull second. Package-private for tests. */
  void catchUpInOrder() {
    try {
      closeCanary.catchUpPopulation();
    } catch (RuntimeException e) {
      // The bhavcopy pull is the more important of the two — it is the self-heal for a long
      // downtime — so a canary failure must delay it, never cancel it. Without this catch the
      // exception kills the thread before runIfFree() is reached and the backfill silently never
      // starts, which is a strictly worse outcome than a biased canary sample.
      log.warn("bhavcopy-close population catch-up failed, running the bhavcopy pull anyway: {}",
          e.toString());
    }
    backfill.runIfFree();
  }
}
