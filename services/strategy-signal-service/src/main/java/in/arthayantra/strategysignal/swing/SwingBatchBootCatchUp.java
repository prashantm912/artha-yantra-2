package in.arthayantra.strategysignal.swing;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Boot-time trigger for the swing catch-up's missed-cron sweep (ledger H18). Spring never replays a
 * {@code @Scheduled} fire that ticked while the process was down, and this machine is off overnight
 * and boots ~08:00 on weekdays against an 08:35 IST cron — so a boot at 08:38 misses the sweep that,
 * since the 16:00/08:35 split, is the ONLY path that takes swing entries. Measured 2026-08-14: the
 * machine booted 08:38, {@code swing_catchup_runs} held no row at all for {@code 2026-08-13}, and
 * the entries were recoverable only by hand.
 *
 * <p><b>Deliberately dumb.</b> Every gate BELOW this door lives in
 * {@link SwingBatchCatchUp#catchUpIfMissed()} — the cron's own fire time, the pre-open wall-clock
 * cutoff, the {@code artha.swing.catchup-enabled} arming, the window seed and the atomic
 * per-{@code (batch, session)} claim. So a boot before the cron, a boot near or after the open, an
 * evening boot, a weekend, a disabled cron and a second boot the same morning are all no-ops, and
 * nothing here can emit anything the 08:35 cron would not have.
 *
 * <h2>What actually prevents a double entry, in order of who does the work</h2>
 *
 * <ol>
 *   <li><b>{@code swingCatchUpTaskScheduler} corePoolSize=1</b> — the boot one-shot and the 08:35
 *       {@code @Scheduled} share this pool, so they cannot overlap. Ratcheted by
 *       {@code MonitorSchedulingConfigTest}.
 *   <li><b>The atomic claim</b> in {@code swing_catchup_runs} — never re-claims a {@code RUNNING} or
 *       terminal row, so a SEQUENTIAL second sweep skips a session the first still owns.
 *   <li><b>The per-family {@link SwingRunMutex}</b> — ⚠️ <b>inert for the boot-vs-cron pair</b>
 *       (point 1 already forbids that overlap, so it is never contended by those two). It guards a
 *       manual {@code POST /run} arriving on a Tomcat request thread. Cited here as what it really
 *       does, because an earlier version of this note credited it with the boot-vs-cron guarantee.
 *   <li><b>The money backstop, and the one that would still hold if all three above failed:</b>
 *       {@code swing_paper_effects} UNIQUE {@code (batch, session_date, effect_key)} (V050:25),
 *       claimed by {@code expectEntry}'s {@code INSERT … ON CONFLICT DO NOTHING} <b>inside the same
 *       transaction as the signal insert</b> ({@code SwingBatchEngine:729-756}) — a lost claim
 *       returns {@code -1} and no signal row is written. Pool serialization alone would NOT suffice:
 *       two SEQUENTIAL sweeps of a still-PENDING session would otherwise re-run the recorder and
 *       re-enter, and {@code PaperService} AVERAGES a second open on the same key rather than
 *       rejecting it.
 * </ol>
 *
 * <h2>⚠️ Why this door needs its OWN flag, and why reusing the existing one would have been wrong</h2>
 *
 * <p>{@code artha.swing.catchup-enabled} looks like the natural arming switch — it already gates the
 * sweep, and its code AND compose defaults are both {@code false}. <b>But {@code .env} sets it
 * {@code true} in production</b>, because that flag is what arms the live 08:35 entry pass. Reusing
 * it would therefore have shipped this door ARMED on the very first deploy, and the only way to ship
 * it inert would have been to turn off the working swing entry path — the exact opposite of the
 * point. A default-{@code false} flag whose live value is {@code true} is not a safe default; it is
 * a safe-looking one.
 *
 * <p>So {@code artha.swing.boot-catchup-enabled} (default {@code false}) gates ONLY this listener.
 * <b>Both flags must be true for anything to happen</b>: this one opens the door,
 * {@code catchup-enabled} still gates the sweep itself inside {@code catchUp()}. Deliberately
 * checked HERE rather than inside {@code catchUpIfMissed()} so the cron path is provably untouched —
 * no line this flag can reach is on the 08:35 path.
 *
 * <p><b>Why a separate bean rather than an {@code @EventListener} on the catch-up itself.</b>
 * {@link SwingBatchCatchUp} carries two published back-compatible constructors that focused tests
 * build by hand; widening it further to inject a scheduler would churn a money-path class for a
 * boot-path concern. Same split as market-data's {@code MorningCanaryCatchUp} over
 * {@code IngestCoverageCanary.catchUpIfMissed()}.
 *
 * <p>⚠️ <b>{@code swingCatchUpTaskScheduler}, not an ad-hoc thread, and the choice is load-bearing
 * twice over.</b> That pool is {@code poolSize=1} and is the pool {@code SwingBatchCatchUp.catchUp}'s
 * {@code @Scheduled} already runs on, so a boot landing in the same second as the 08:35 tick cannot
 * run CONCURRENTLY with it — the second one queues behind the first and then finds every session
 * terminal or claimed. That is a serialization guarantee on top of the durable claim, not a
 * replacement for it. It also keeps the sweep visible to {@link SwingRunActivity}, whose whole
 * contract is "pool active ⇒ a sweep is running" — the pre-open reconciler's bounded wait reads it,
 * and a boot sweep run on some other thread would be invisible there. Running it INLINE on
 * {@code ApplicationReadyEvent} was the other option and is wrong for a third reason: the sweep was
 * measured at 81 s for both families, which would be 81 s of delayed startup.
 */
@Component
public class SwingBatchBootCatchUp {

  private static final Logger log = LoggerFactory.getLogger(SwingBatchBootCatchUp.class);

  private final SwingBatchCatchUp catchUp;
  private final ThreadPoolTaskScheduler catchUpScheduler;
  private final SwingCatchUpStateRepository state;
  private final boolean enabled;

  /**
   * Stamped at CONSTRUCTION, which is the point of it: this bean is built during startup and the
   * sweep only runs on {@code ApplicationReadyEvent}, so nothing this process claims can predate
   * this instant — which makes "claimed before it" a sound test for an orphan.
   */
  private final Instant startedAt;

  /** Wires the sweep, the dedicated catch-up pool the one-shot runs on, and this door's own flag. */
  public SwingBatchBootCatchUp(
      SwingBatchCatchUp catchUp,
      @Qualifier("swingCatchUpTaskScheduler") ThreadPoolTaskScheduler catchUpScheduler,
      SwingCatchUpStateRepository state,
      Clock clock,
      @Value("${artha.swing.boot-catchup-enabled:false}") boolean enabled) {
    this.catchUp = catchUp;
    this.catchUpScheduler = catchUpScheduler;
    this.state = state;
    this.startedAt = clock.instant();
    this.enabled = enabled;
  }

  /** Off-thread by construction so a sweep that runs for minutes never delays startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    catchUpScheduler.execute(this::runIfMissed);
  }

  /** Package-private seam so the gating can be driven without publishing a Spring event. */
  void runIfMissed() {
    // ONE gate, checked here and nowhere else. A second copy inside catchUpIfMissed() would make a
    // red-proof on either copy ambiguous — the failure mode that already bit the market-open
    // deadline check on this change, where removing one of two copies changed nothing.
    if (!enabled) {
      log.info(
          "swing boot catch-up: artha.swing.boot-catchup-enabled is off — the 08:35 cron path is"
              + " unaffected and still owns the sweep");
      return;
    }
    releaseOrphanedClaims();
    try {
      catchUp.catchUpIfMissed();
    } catch (RuntimeException failure) {
      // Fail-soft: the 08:35 cron and the next morning's sweep are both still live, and a boot that
      // dies here would take the whole service's startup path with it.
      log.error("swing boot catch-up failed: {}", failure.getMessage(), failure);
    }
  }

  /**
   * Frees claims a previous process died holding, so this boot's sweep can retake them.
   *
   * <p>Without this the Critical stands: a hard death mid-sweep leaves a {@code RUNNING} row that
   * {@code claim()} refuses for {@code STALE_LEASE_MINUTES} (30), and with the 08:35 cron and the
   * 09:05 boot cutoff <b>that lease cannot expire while there is still time to act</b> — restarting
   * the service does nothing and the session's entries are forfeited.
   *
   * <p>⚠️ In its OWN try/catch, and deliberately so: a failure here must not cost the sweep. The
   * sweep is the thing that takes entries; this only widens what the sweep is allowed to retake.
   */
  private void releaseOrphanedClaims() {
    try {
      int released = state.releaseClaimsFrom(startedAt);
      if (released > 0) {
        log.warn(
            "swing boot catch-up: released {} orphaned RUNNING claim(s) held by a process that died"
                + " before {} — they are PENDING again and this sweep may retake them",
            released,
            startedAt);
      }
    } catch (RuntimeException failure) {
      log.error(
          "swing boot catch-up: could not release orphaned claims ({}) — continuing to the sweep,"
              + " which simply skips any session still marked RUNNING",
          failure.getMessage(),
          failure);
    }
  }
}
