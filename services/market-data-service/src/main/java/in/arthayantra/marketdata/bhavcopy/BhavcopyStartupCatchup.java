package in.arthayantra.marketdata.bhavcopy;

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
 */
@Component
@ConditionalOnProperty(name = "artha.bhavcopy.startup-catchup", havingValue = "true", matchIfMissing = true)
public class BhavcopyStartupCatchup {

  private final BhavcopyBackfillService backfill;

  /** Wires the backfill service. */
  public BhavcopyStartupCatchup(BhavcopyBackfillService backfill) {
    this.backfill = backfill;
  }

  /** Pull once on startup; async by construction (the service owns its executor). */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    backfill.runIfFree();
  }
}
