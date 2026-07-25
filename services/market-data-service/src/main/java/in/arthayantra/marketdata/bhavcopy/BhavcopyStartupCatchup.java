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
 * {@code push:main} red from #1016 onward. Tests that manipulate bhavcopy tables set
 * {@code artha.bhavcopy.startup-catchup=false} and the race cannot occur.
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
