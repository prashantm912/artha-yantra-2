package in.arthayantra.marketdata.bhavcopy;

import in.arthayantra.marketdata.canary.BhavcopyCloseCanary;
import java.time.Duration;
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
 * <p><b>The ORDER of the pair is not load-bearing, and that was measured.</b> It once read as
 * though it were: the bhavcopy projection writes 1d {@code candles} rows, {@code source} is not in
 * that table's primary key, and {@code upsertAuthoritativeAll} keeps the EXISTING source when the
 * incoming bar matches — from which it looked as though a bhavcopy-first write would strand the
 * PERFECTLY AGREEING bars at {@code source='BHAVCOPY'}, outside the canary's {@code source='KITE'}
 * population. It does not. That {@code CASE} compares {@code oi} as well, and the two producers
 * encode "no open interest" DIFFERENTLY — {@link BhavcopyCandles} emits {@code oi = null}, while
 * the Kite historical gateway asks for {@code oi=1} and cash equities answer with a literal
 * {@code 0}. {@code NULL IS NOT DISTINCT FROM 0} is FALSE, so a later Kite write ALWAYS takes
 * {@code source='KITE'}. Bhavcopy-first is safe. Pinned by {@code
 * CandleCaggIntegrationTest#kiteOverBhavcopyTakesTheKiteSourceBecauseOnlyOneSideEncodesNoOiAsZero},
 * because the safety is ACCIDENTAL: it turns on an encoding difference neither producer promises.
 *
 * <p><b>So why keep the pair sequential, and why the wait is BOUNDED.</b> Sequential composition
 * costs nothing on a normal night (the pass is ~71 s), keeps two batch writers off the same 1d
 * buckets, and states the order in program order rather than re-deriving it from a completion gate.
 * But it is now a NICETY, and {@code runIfFree()} is the DATA FEED — so the wait carries a hard
 * deadline and hands off when it expires. The try/catch below cannot supply that: it covers a
 * canary that THROWS, never one that is merely SLOW. And slow is the realistic failure — the pass
 * is ~202 sequential Kite fetches, each retried up to 4 times over a 60 s read timeout, so a Kite
 * brown-out stretches it arbitrarily. Unbounded, a boot at 18:57 into a brown-out would hold the
 * pull past the 19:00 machine-off and cost the night's bhavcopy ENTIRELY — the canary's sample
 * starving the feed it exists to check.
 *
 * <p><b>Why a direct call on a one-shot thread, and not two ordered listeners.</b> Three shapes
 * were considered:
 *
 * <ul>
 *   <li><b>{@code @Order} on the two {@code ApplicationReadyEvent} listeners</b> — does not work.
 *       An unannotated listener sits at {@code LOWEST_PRECEDENCE}, so ordering the canary before
 *       THIS bean necessarily orders it before every other one too, including
 *       {@code PinnedIndicesSubscriber} / {@code SubscriptionReplayer} / {@code FuturesPinner}.
 *       The population pass is ~71 s nominal and unbounded in a Kite brown-out, so that trades a
 *       canary's coverage for the LIVE FEED's subscription.
 *   <li><b>A completion gate the canary publishes and this bean awaits</b> — correct, and it needs
 *       a future, a timeout, and a {@code finally} that completes the gate on every abnormal path.
 *       All of that exists only to re-derive an ordering that a direct call states outright.
 *   <li><b>A direct call, on short-lived daemon threads</b> — chosen. The ordering is program
 *       order, so there is nothing to get wrong; the ready thread returns as immediately as it did
 *       before (both steps move off it, where previously only the second did); and it cannot
 *       deadlock, because nothing else ever joins these threads.
 * </ul>
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

  /**
   * How long the bhavcopy pull will wait behind the population replay before starting anyway.
   *
   * <p><b>Sized against the machine's real operating window.</b> The replay only ever runs on a
   * TRADING-DAY boot AFTER 16:05 IST ({@link BhavcopyCloseCanary#catchUpPopulation()} returns 0
   * otherwise), and this machine is off at 19:00 — so every case this deadline governs falls inside
   * 16:05–19:00, whose tail is the evening chain (18:20–18:58) with the bhavcopy landing ~18:45
   * (earliest observed publish 17:52, once as late as 19:31).
   *
   * <p><b>Why 90 s.</b> It is the smallest value that still covers the measured nominal pass —
   * ~71 s for 202 symbols at a measured 2.84 calls/s on the 3 req/s {@code kite-historical} limiter
   * — with headroom, so a HEALTHY night is untouched and the intended order actually happens. Small
   * on purpose, because the cost is ASYMMETRIC: too long starves the bhavcopy pull, which is the
   * failure this deadline exists to remove, while too short costs only an ordering the class
   * javadoc above shows is not load-bearing. Even the latest boot that can trigger a replay starts
   * the pull inside the window — an 18:58 boot pulls by 18:59:30.
   *
   * <p><b>After it expires the replay CONTINUES; it is not abandoned.</b> It cannot delay the pull,
   * because the pull has already started — the deadline is a hand-off, not a cancellation.
   * Abandoning would need a cancellation flag threaded through the canary's per-symbol loop, and it
   * would throw away work that is still useful: {@link BhavcopyCloseCanary#sweep()} does not read
   * the population until 18:58, so bars landing at 18:52 still count.
   *
   * <p><b>A partially-completed replay leaves only CORRECT bars behind.</b> Each symbol is an
   * independent authoritative upsert of that symbol's real Kite 1d bar, so stopping (or being
   * outrun) between symbols yields a SHORTER population, never a wrong one — cost, never
   * corruption. And a short population is exactly what {@code artha.bhavcopy-close.min-compared}
   * reports rather than certifies.
   */
  static final Duration POPULATION_REPLAY_DEADLINE = Duration.ofSeconds(90);

  private final BhavcopyBackfillService backfill;
  private final BhavcopyCloseCanary closeCanary;

  /** Wires the backfill service and the close canary whose missed population pass is replayed. */
  public BhavcopyStartupCatchup(BhavcopyBackfillService backfill, BhavcopyCloseCanary closeCanary) {
    this.backfill = backfill;
    this.closeCanary = closeCanary;
  }

  /**
   * Pull once on startup, behind a DEADLINED wait for the close canary's missed population pass.
   * Everything runs off the ready thread; {@code runIfFree()} then hands off to the service's own
   * executor exactly as before, so the write is still fire-and-forget.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    Thread ordered = new Thread(this::catchUpInOrder, "bhavcopy-startup-catchup");
    ordered.setDaemon(true);
    ordered.start();
  }

  /** The pair at the production deadline: population replay first, bhavcopy pull second. */
  void catchUpInOrder() {
    catchUpInOrder(POPULATION_REPLAY_DEADLINE);
  }

  /**
   * The pair, with the deadline injected so a test can prove the hand-off without waiting out the
   * real 90 s. Package-private: the production deadline is a constant precisely so there is no
   * knob whose only settings are "no change" and "starve the feed for longer".
   */
  void catchUpInOrder(Duration populationDeadline) {
    awaitPopulationReplay(populationDeadline);
    backfill.runIfFree();
  }

  /**
   * Starts the population replay and waits up to {@code deadline} for it. Returns either way — the
   * caller's next statement is the bhavcopy pull, and nothing this method does may prevent it.
   */
  private void awaitPopulationReplay(Duration deadline) {
    Thread replay = new Thread(this::replayPopulation, "bhavcopy-close-population-replay");
    replay.setDaemon(true);
    replay.start();
    try {
      replay.join(deadline.toMillis());
    } catch (InterruptedException e) {
      // Shutdown, almost certainly. Restore the flag and fall through: runIfFree() only SUBMITS,
      // and a service that is going down will drop the work on its own terms rather than ours.
      Thread.currentThread().interrupt();
      log.warn("interrupted while waiting for the bhavcopy-close population replay");
      return;
    }
    if (replay.isAlive()) {
      log.warn(
          "bhavcopy-close population replay has not finished within {} — starting the bhavcopy pull"
              + " NOW and letting the replay run on. The pull is the data feed; the population is a"
              + " canary's sample, and it must never be able to starve the feed.",
          deadline);
    }
  }

  /** One population replay, on its own thread so a slow canary cannot hold the pull. */
  private void replayPopulation() {
    try {
      closeCanary.catchUpPopulation();
    } catch (RuntimeException e) {
      // Legibility, not control flow. The pull's independence from this call is STRUCTURAL now —
      // it runs on the caller's thread, which only ever joins with a timeout — so an uncaught
      // exception here would no longer stop the pull; it would just land as a bare stderr dump
      // from the default handler instead of a WARN an operator can grep.
      log.warn("bhavcopy-close population replay failed: {}", e.toString());
    }
  }
}
