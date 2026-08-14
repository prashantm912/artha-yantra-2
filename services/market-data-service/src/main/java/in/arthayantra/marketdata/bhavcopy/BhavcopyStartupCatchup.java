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
 * <p><b>⚠️ This listener also REPLAYS the close canary's missed population pass.</b> {@link
 * BhavcopyCloseCanary#prefetchPopulation()} — the 16:05 IST pass that fetches that canary's OWN
 * 202-symbol comparison population — is CRON-ONLY, so on a boot after 16:05 it never runs for that
 * session and the evening's population falls back to whatever other jobs happened to leave behind.
 * That is not a theoretical shrink: measured 202 seeded against <b>14</b> borrowed on 2026-08-11 /
 * 08-12, and the canary reported GREEN on those 14. Nor is a late boot a corner case — this machine
 * is off overnight and the live stack has already spent a whole afternoon down (2026-08-10, no
 * batch 08:29→18:47 IST). Replaying the missed pass here is the ONLY door that covers it, and it is
 * the whole reason this listener knows about the canary at all.
 *
 * <p><b>The two are started INDEPENDENTLY and nothing orders them, because nothing needs to.</b>
 * It once read as though an order were required: the bhavcopy projection writes 1d {@code candles}
 * rows, {@code source} is not in that table's primary key, and {@code upsertAuthoritativeAll} keeps
 * the EXISTING source when the incoming bar matches — from which it looked as though a
 * bhavcopy-first write would strand the PERFECTLY AGREEING bars at {@code source='BHAVCOPY'},
 * outside the canary's comparison population. It does not. That {@code CASE} compares {@code oi} as
 * well, and the two producers encode "no open interest" DIFFERENTLY — {@link BhavcopyCandles} emits
 * {@code oi = null}, while the Kite historical gateway asks for {@code oi=1} and cash equities
 * answer with a literal {@code 0}. {@code NULL IS NOT DISTINCT FROM 0} is FALSE, so a later Kite
 * write ALWAYS takes the Kite source. Bhavcopy-first is safe. Pinned on both halves, because the
 * safety is ACCIDENTAL — it turns on an encoding difference neither producer promises: {@code
 * CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero}
 * pins the write path, and {@code
 * LiveHistoricalCandleGatewayTest#cashEquityDailyBarKeepsAZeroOpenInterestRatherThanNulling} pins
 * that the gateway carries a literal {@code 0} through as {@code 0} rather than normalising it
 * away. ⚠️ What NEITHER can pin is Kite's own choice to SEND the seventh element at all — see that
 * gateway test for the residual gap and why the daily {@code ContractCanary} does not close it.
 *
 * <p><b>There is deliberately no wait, no deadline and no hand-off WARN.</b> An earlier round of
 * this change joined the replay for 90 s before pulling. It was removed rather than retuned:
 *
 * <ul>
 *   <li>The provenance argument above is the only correctness reason sequencing could have had, and
 *       it is dead. The one justification left for the join — "keep two batch writers off the same
 *       1d buckets" — was DISCARDED BY THE BOUND ITSELF: when the replay is slow the pull starts
 *       anyway and the writers overlap, i.e. exactly the case where overlap could matter is the
 *       case the deadline permits. Bound and justification were mutually exclusive.
 *   <li>The overlap is not a hazard here in any case. {@link
 *       BhavcopyCloseCanary#prefetchNow(java.time.LocalDate)} fetches a ONE-DAY window per symbol,
 *       so each replay write touches a single 1d row in its own transaction and can never hold two
 *       row locks — there is no cycle to form against the projection's bulk insert.
 *   <li>The deadline's own load-bearing sentence — "an 18:58 boot still pulls by 18:59:30" — was
 *       true and INERT. {@link BhavcopyBackfillService#runIfFree()} only SUBMITS; the real work is
 *       an NSE + BSE download, parse, ~3 300-row raw upsert and candle projection against a 19:00
 *       machine-off. Starting at 18:59:30 does not save that night's bhavcopy, so the 90 s bought
 *       nothing while costing the feed 90 s and emitting a WARN that reads as a fault on any slow
 *       evening deploy.
 * </ul>
 *
 * <p>What remains is the minimum that delivers the replay without ever standing in front of the
 * feed: the replay goes on one short-lived daemon thread, and {@code runIfFree()} is called from a
 * {@code finally} so the pull is unconditional. On a healthy night the replay's first bars still
 * land first — it starts fetching immediately while the pull is still downloading two exchange
 * files — but that is an accident of timing now, claimed by nothing.
 *
 * <p><b>The coupling this creates, stated rather than hidden:</b> {@code
 * artha.bhavcopy.startup-catchup=false} now also disables the canary's population catch-up. That
 * pairing is a coincidence of wiring rather than a design — a late boot with this bean off leaves
 * the canary back on a borrowed population, since the 16:05 cron has already been missed and
 * nothing else replays it. It is off only in the integration suite, where the canary is
 * live-profile-gated and inert anyway.
 */
@Component
@ConditionalOnProperty(name = "artha.bhavcopy.startup-catchup", havingValue = "true", matchIfMissing = true)
public class BhavcopyStartupCatchup {

  private static final Logger log = LoggerFactory.getLogger(BhavcopyStartupCatchup.class);

  private final BhavcopyBackfillService backfill;
  private final BhavcopyCloseCanary closeCanary;

  /** Wires the backfill service and the close canary whose missed population pass is replayed. */
  public BhavcopyStartupCatchup(BhavcopyBackfillService backfill, BhavcopyCloseCanary closeCanary) {
    this.backfill = backfill;
    this.closeCanary = closeCanary;
  }

  /**
   * Starts the close canary's missed population replay on its own thread, then pulls. The replay
   * cannot delay the pull (different threads, no join) and cannot skip it (the {@code finally});
   * {@code runIfFree()} hands off to the service's own executor exactly as it did before this
   * listener knew about the canary, so the write is still fire-and-forget off the ready thread.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    try {
      Thread replay = new Thread(this::replayPopulation, "bhavcopy-close-population-replay");
      replay.setDaemon(true);
      replay.start();
    } finally {
      // Not decoration. The one traced path that would otherwise skip the pull is an
      // OutOfMemoryError out of Thread creation or start(), and a silently-skipped pull looks
      // exactly like the startup catch-up having been switched off — the feed must never depend on
      // a canary's sample being startable.
      backfill.runIfFree();
    }
  }

  /** One population replay, on its own thread so a slow canary cannot hold the pull. */
  private void replayPopulation() {
    try {
      closeCanary.catchUpPopulation();
    } catch (RuntimeException e) {
      // Legibility, not control flow. The pull's independence from this call is STRUCTURAL — this
      // body runs on its own thread and the caller never joins it — so an uncaught exception here
      // could not stop the pull anyway; it would just land as a bare stderr dump from the default
      // handler instead of a WARN an operator can grep.
      log.warn("bhavcopy-close population replay failed: {}", e.toString());
    }
  }
}
