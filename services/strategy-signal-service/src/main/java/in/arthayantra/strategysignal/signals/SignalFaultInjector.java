package in.arthayantra.strategysignal.signals;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Deliberate, guarded fault injection for {@link SubscriberHealthCanary}'s receive-side fire path —
 * the one branch of the live watchdog that has NEVER run outside a unit test.
 *
 * <p><b>Why this exists.</b> A live drill on 2026-08-03 killed the engine's Redis pub/sub connection
 * mid-session ({@code CLIENT KILL}); Lettuce's {@code ConnectionWatchdog} healed it in ~22 ms, no bar
 * was missed and {@code subscriber_health_events} never moved. The transport-drop class self-heals ~4
 * orders of magnitude below the 180 s {@code bar-gap-ms} threshold and so cannot reach the detector.
 *
 * <p><b>What this reproduces — and what it does not.</b> Stopping the listener container produces a
 * <b>detector-equivalent receive stall</b>: the canary-visible state (candle receipt stops while the
 * producer heartbeat stays fresh) matches the 2026-07-07 episode, so the real detection, recovery,
 * alert and telemetry branch all execute. It is <b>not</b> a reproduction of the 2026-07-07
 * <i>mechanism</i> — {@code stop()} intentionally closes the pub/sub subscription, making this a THIRD
 * mechanism, distinct from both a server-side socket kill and the hypothesised socket-up listener
 * loss. This validates the detector, not the diagnosis; nothing here is evidence about what actually
 * happened on 07-07.
 *
 * <p><b>Safety posture</b> (this is a deliberate way to break a live money-adjacent service):
 *
 * <ul>
 *   <li><b>Default OFF, and never wireable into a broken context.</b> Requires an explicit
 *       {@code artha.signals.fault-injection.enabled=true} AND the engine to be enabled — the same
 *       {@code artha.signals.engine-enabled} gate {@link SignalEngine} itself carries. Without the
 *       second half, enabling injection on an engine-disabled instance would fail startup on an
 *       unsatisfied dependency rather than simply staying absent.
 *   <li><b>Not reachable through the edge gateway.</b> {@link SignalFaultInjectionController} serves
 *       {@code /api/v1/signal-fault-injection/**}, absent from edge-gateway's {@code Path=} prefix
 *       allowlist. That omission is the control, not an oversight — do not "fix" it.
 *   <li><b>Detector-capable by default.</b> The default window is derived from the canary's OWN
 *       {@code bar-gap-ms} plus its sweep cadence, so a parameterless drill is guaranteed long enough
 *       for the watchdog to actually fire. A shorter window is allowed but is loudly labelled as
 *       NOT detector-capable, so an absent alert is never misread as a broken watchdog.
 *   <li><b>Restoration is confirmed, not merely requested.</b> The bounded restore retries until
 *       {@link SignalEngine#candleSubscriptionActive()} verifies the subscription is back, and logs a
 *       terminal ERROR if it never is.
 *   <li><b>One drill at a time.</b> A second injection while a restore is pending is REFUSED — it
 *       would cancel the pending deadline and extend the outage.
 *   <li><b>Loudly logged.</b> Every inject/restore logs at ERROR carrying {@link #MARKER}.
 *   <li><b>No accidental trigger.</b> No scheduling, no {@code @EventListener}, no GET side effect.
 * </ul>
 */
@Component
@ConditionalOnExpression(
    "${artha.signals.fault-injection.enabled:false} and ${artha.signals.engine-enabled:true}")
public class SignalFaultInjector {

  private static final Logger log = LoggerFactory.getLogger(SignalFaultInjector.class);

  /** Unmistakable log marker — an injected stall must never read as a real one. */
  static final String MARKER = "*** INJECTED FAULT (DRILL) ***";

  static final long MIN_AUTO_RESTORE_MS = 1_000L;
  /** ~10 min: far under a 6 h 15 m session, with headroom above the detection floor. */
  static final long MAX_AUTO_RESTORE_MS = 600_000L;

  private static final long RESTORE_CONFIRM_POLL_MS = 200L;
  private static final long RESTORE_CONFIRM_TIMEOUT_MS = 5_000L;
  private static final int MAX_RESTORE_ATTEMPTS = 12; // ~60s of confirmed-retry before escalating

  /**
   * Outcome of one injection attempt (typed record — never {@code Map<String,Object>}).
   *
   * @param injected whether the candle subscription was actually suspended
   * @param autoRestoreMs the clamped delay after which the bounded restore fires; on a REFUSED
   *     injection, the time remaining on the drill already in flight
   * @param detectorCapable whether this window is long enough for the watchdog to actually fire
   * @param detail operator-readable description of what happened
   */
  public record SubscriptionStallInjection(
      boolean injected, long autoRestoreMs, boolean detectorCapable, String detail) {}

  private final SignalEngine engine;
  private final SubscriberHealthCanary canary;
  private final ScheduledExecutorService restoreScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "fault-injection-restore");
            t.setDaemon(true);
            return t;
          });

  // Both guarded by `this`. The generation keys timer ownership so a slow, finishing restore can
  // never clear a NEWER drill's handle.
  private ScheduledFuture<?> pendingRestore;
  private long activeGeneration;
  private long generationSequence;

  /** Wires the engine + the canary it drills, and announces at ERROR that this instance is armed. */
  public SignalFaultInjector(SignalEngine engine, SubscriberHealthCanary canary) {
    this.engine = engine;
    this.canary = canary;
    log.error(
        "{} SignalFaultInjector is ENABLED on this instance (artha.signals.fault-injection.enabled"
            + "=true). The subscriber watchdog's fire path can be triggered deliberately here, which"
            + " stops live candle evaluation for up to {} ms per drill. This must NEVER be enabled on"
            + " an instance whose alerts are trusted as real.",
        MARKER,
        MAX_AUTO_RESTORE_MS);
  }

  /**
   * The shortest drill the watchdog can actually detect: the receive gap must first exceed
   * {@code bar-gap-ms}, and only the NEXT sweep can observe that — so worst case a full sweep
   * interval later. Derived from the canary's own values so the two cannot drift apart.
   */
  long detectionFloorMs() {
    return canary.barGapMs() + SubscriberHealthCanary.SWEEP_INTERVAL_MS;
  }

  /** The floor plus one sweep of margin — what a parameterless drill gets. */
  long defaultAutoRestoreMs() {
    return Math.min(
        MAX_AUTO_RESTORE_MS, detectionFloorMs() + SubscriberHealthCanary.SWEEP_INTERVAL_MS);
  }

  /** Clamps to a bounded window so a forgotten or fat-fingered drill cannot outlive a session. */
  long clampAutoRestore(Long requestedMs) {
    if (requestedMs == null) {
      return defaultAutoRestoreMs();
    }
    return Math.min(MAX_AUTO_RESTORE_MS, Math.max(MIN_AUTO_RESTORE_MS, requestedMs));
  }

  /**
   * Suspends the engine's candle subscription so {@code lastBarReceivedAtMs} goes stale while the
   * producer's {@code ticks:last-at} heartbeat stays fresh — precisely the pair
   * {@link SubscriberHealthCanary} keys its receive-stall branch on.
   *
   * @param requestedAutoRestoreMs desired window; null takes {@link #defaultAutoRestoreMs()}, which
   *     is always detector-capable. Clamped to [{@value #MIN_AUTO_RESTORE_MS},
   *     {@value #MAX_AUTO_RESTORE_MS}] ms; a value below the detection floor is honoured but
   *     reported and logged as NOT detector-capable.
   */
  public synchronized SubscriptionStallInjection injectSubscriptionStall(
      Long requestedAutoRestoreMs) {
    if (pendingRestore != null && !pendingRestore.isDone()) {
      long remainingMs = Math.max(0, pendingRestore.getDelay(TimeUnit.MILLISECONDS));
      log.error(
          "{} injection REFUSED — a drill is already in flight ({} s until its bounded restore)."
              + " Re-injecting would cancel that deadline and EXTEND the outage.",
          MARKER,
          remainingMs / 1000);
      return new SubscriptionStallInjection(
          false,
          remainingMs,
          false,
          "a drill is already in flight; its bounded restore fires in ~" + remainingMs / 1000 + "s");
    }
    long autoRestoreMs = clampAutoRestore(requestedAutoRestoreMs);
    long detectionFloorMs = detectionFloorMs();
    boolean detectorCapable = autoRestoreMs >= detectionFloorMs;
    if (!engine.suspendCandleSubscriptionForFaultDrill()) {
      log.error(
          "{} subscription stall NOT injected — the engine has no active listener container.",
          MARKER);
      return new SubscriptionStallInjection(
          false, autoRestoreMs, detectorCapable, "no active listener container to suspend");
    }
    if (detectorCapable) {
      log.error(
          "{} candle subscription DELIBERATELY suspended for {} ms. SubscriberHealthCanary is"
              + " EXPECTED to detect a receive stall within ~{} ms, re-subscribe, page, and write a"
              + " subscriber_health_events row. Any stall alert from this instance in that window is"
              + " INJECTED, not real.",
          MARKER,
          autoRestoreMs,
          detectionFloorMs);
    } else {
      log.error(
          "{} candle subscription DELIBERATELY suspended for {} ms, which is BELOW the {} ms"
              + " detection floor (bar-gap {} ms + one {} ms sweep). The watchdog will NOT have time"
              + " to fire: this drill exercises the injector and the bounded restore ONLY. Do NOT"
              + " read the absence of an alert as evidence the watchdog is broken.",
          MARKER,
          autoRestoreMs,
          detectionFloorMs,
          canary.barGapMs(),
          SubscriberHealthCanary.SWEEP_INTERVAL_MS);
    }
    long generation = ++generationSequence;
    activeGeneration = generation;
    pendingRestore =
        restoreScheduler.schedule(
            () -> autoRestore(generation), autoRestoreMs, TimeUnit.MILLISECONDS);
    return new SubscriptionStallInjection(
        true,
        autoRestoreMs,
        detectorCapable,
        "candle subscription suspended; bounded restore in " + autoRestoreMs + " ms");
  }

  /**
   * The bounded backstop, made a GUARANTEE rather than a request: {@code forceResubscribe} only
   * ENQUEUES a rebuild and swallows its exception on another thread, so a single fire-and-forget call
   * could leave the engine stopped indefinitely with nothing logged. This retries until
   * {@link SignalEngine#candleSubscriptionActive()} confirms the subscription is back, and escalates
   * loudly if it never is. Never throws — it runs on a scheduler thread.
   */
  private void autoRestore(long generation) {
    boolean confirmed = false;
    try {
      for (int attempt = 1; attempt <= MAX_RESTORE_ATTEMPTS && !confirmed; attempt++) {
        try {
          engine.forceResubscribe(MARKER + " bounded auto-restore (attempt " + attempt + ")");
        } catch (RuntimeException requestFailed) {
          log.error(
              "{} auto-restore attempt {} could not even be requested: {}",
              MARKER,
              attempt,
              requestFailed.toString());
        }
        confirmed = awaitSubscriptionActive();
        if (!confirmed) {
          log.error(
              "{} auto-restore attempt {} of {} NOT confirmed — the candle subscription is still"
                  + " down; retrying.",
              MARKER,
              attempt,
              MAX_RESTORE_ATTEMPTS);
        }
      }
    } catch (RuntimeException unexpected) {
      log.error("{} auto-restore aborted on an unexpected error: {}", MARKER, unexpected.toString());
    } finally {
      if (confirmed) {
        log.error(
            "{} auto-restore CONFIRMED — the candle subscription is active again. The drill is over.",
            MARKER);
      } else {
        log.error(
            "{} auto-restore FAILED after {} attempts — THE CANDLE SUBSCRIPTION IS STILL DOWN and"
                + " this instance will not evaluate bars until a reload or a restart. ESCALATE.",
            MARKER,
            MAX_RESTORE_ATTEMPTS);
      }
      clearIfStillOwned(generation);
    }
  }

  /** Clears the pending handle only if a NEWER drill has not already taken ownership. */
  private synchronized void clearIfStillOwned(long generation) {
    if (activeGeneration == generation) {
      pendingRestore = null;
      activeGeneration = 0;
    }
  }

  /** Polls the engine's own subscription state — the only honest confirmation available here. */
  private boolean awaitSubscriptionActive() {
    long deadlineNanos = System.nanoTime() + RESTORE_CONFIRM_TIMEOUT_MS * 1_000_000L;
    while (System.nanoTime() < deadlineNanos) {
      if (engine.candleSubscriptionActive()) {
        return true;
      }
      try {
        Thread.sleep(RESTORE_CONFIRM_POLL_MS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return engine.candleSubscriptionActive();
      }
    }
    return engine.candleSubscriptionActive();
  }

  /** Stops the restore thread with the context; a pending drill restore dies with the JVM anyway. */
  @PreDestroy
  void shutdown() {
    restoreScheduler.shutdownNow();
  }
}
