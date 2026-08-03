package in.arthayantra.strategysignal.signals;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deliberate, guarded fault injection for {@link SubscriberHealthCanary}'s receive-side fire path —
 * the one branch of the live watchdog that has NEVER run outside a unit test.
 *
 * <p><b>Why this exists.</b> A live drill on 2026-08-03 killed the engine's Redis pub/sub connection
 * mid-session ({@code CLIENT KILL}); Lettuce's own {@code ConnectionWatchdog} reconnected in ~22 ms,
 * no bar was missed and {@code subscriber_health_events} never moved. The transport-drop class
 * self-heals ~4 orders of magnitude below the 180 s {@code bar-gap-ms} threshold and so CANNOT reach
 * the detector. The inference is that 2026-07-07 — a multi-minute silence, which a TCP drop cannot
 * produce — was a SILENT subscription loss with the socket still up: the listener container stopped
 * dispatching, so Lettuce saw nothing to repair. That shape cannot be injected from outside Redis,
 * which is why the fire path was untestable on a running stack until now.
 *
 * <p><b>Safety posture</b> (this is a deliberate way to break a live money-adjacent service):
 *
 * <ul>
 *   <li><b>Default OFF.</b> Gated on its OWN property, {@code artha.signals.fault-injection.enabled},
 *       with NO {@code matchIfMissing} — absent property means no bean, no endpoint, no code path.
 *       It deliberately does not reuse any broader existing flag.
 *   <li><b>Not reachable through the edge gateway.</b> {@link SignalFaultInjectionController} serves
 *       {@code /api/v1/signal-fault-injection/**}, which is absent from edge-gateway's {@code Path=}
 *       prefix allowlist. That omission is the control, not an oversight — do not "fix" it.
 *   <li><b>Self-limiting.</b> The watchdog's own {@code forceResubscribe} is expected to recover the
 *       engine (that is the path under test); this additionally schedules a BOUNDED auto-restore, so
 *       a forgotten injection cannot outlive a session even if the watchdog is disabled.
 *   <li><b>Loudly logged.</b> Every inject/restore logs at ERROR carrying {@link #MARKER}, so an
 *       operator reading logs can never mistake an injected stall for a real one.
 *   <li><b>No accidental trigger.</b> No scheduling, no {@code @EventListener}, no GET side effect —
 *       the only entry point is an explicit POST.
 * </ul>
 */
@Component
@ConditionalOnProperty(value = "artha.signals.fault-injection.enabled", havingValue = "true")
public class SignalFaultInjector {

  private static final Logger log = LoggerFactory.getLogger(SignalFaultInjector.class);

  /** Unmistakable log marker — an injected stall must never read as a real one. */
  static final String MARKER = "*** INJECTED FAULT (DRILL) ***";

  static final long MIN_AUTO_RESTORE_MS = 1_000L;
  static final long MAX_AUTO_RESTORE_MS = 300_000L;
  static final long DEFAULT_AUTO_RESTORE_MS = 120_000L;

  /**
   * Outcome of one injection attempt (typed record — never {@code Map<String,Object>}, per the
   * Map-return ratchet).
   *
   * @param injected whether the candle subscription was actually suspended
   * @param autoRestoreMs the clamped delay after which the bounded auto-restore fires
   * @param detail operator-readable description of what happened
   */
  public record SubscriptionStallInjection(boolean injected, long autoRestoreMs, String detail) {}

  private final SignalEngine engine;
  private final ScheduledExecutorService restoreScheduler =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "fault-injection-restore");
            t.setDaemon(true);
            return t;
          });

  // Guarded by `this` (only ever touched inside the synchronized methods below).
  private ScheduledFuture<?> pendingRestore;

  /** Wires the engine and announces — at ERROR — that this instance can be broken on purpose. */
  public SignalFaultInjector(SignalEngine engine) {
    this.engine = engine;
    log.error(
        "{} SignalFaultInjector is ENABLED on this instance (artha.signals.fault-injection.enabled"
            + "=true). The subscriber watchdog's fire path can be triggered deliberately here. This"
            + " must NEVER be enabled on an instance whose alerts are trusted as real.",
        MARKER);
  }

  /**
   * Suspends the engine's candle subscription so {@code lastBarReceivedAtMs} goes stale while the
   * producer's {@code ticks:last-at} heartbeat stays fresh — precisely the pair
   * {@link SubscriberHealthCanary} keys its receive-stall branch on.
   *
   * <p>Recovery is expected from the watchdog itself; the bounded auto-restore scheduled here is the
   * backstop for the case where it does not come. Re-injecting cancels any still-pending restore so
   * a second drill cannot be cut short by the first one's timer.
   *
   * @param requestedAutoRestoreMs desired auto-restore delay; null uses {@value
   *     #DEFAULT_AUTO_RESTORE_MS} ms, and any value is clamped to [{@value #MIN_AUTO_RESTORE_MS},
   *     {@value #MAX_AUTO_RESTORE_MS}] ms so a typo cannot park the engine indefinitely
   */
  public synchronized SubscriptionStallInjection injectSubscriptionStall(
      Long requestedAutoRestoreMs) {
    long autoRestoreMs = clampAutoRestore(requestedAutoRestoreMs);
    if (pendingRestore != null) {
      pendingRestore.cancel(false);
      pendingRestore = null;
    }
    if (!engine.suspendCandleSubscriptionForFaultDrill()) {
      log.error(
          "{} subscription stall NOT injected — the engine has no active listener container.",
          MARKER);
      return new SubscriptionStallInjection(
          false, autoRestoreMs, "no active listener container to suspend");
    }
    log.error(
        "{} candle subscription DELIBERATELY suspended. The engine will now stop receiving bars and"
            + " SubscriberHealthCanary is EXPECTED to detect a receive stall, re-subscribe, page, and"
            + " write a subscriber_health_events row. Bounded auto-restore in {} ms. Any stall alert"
            + " from this instance in that window is INJECTED, not real.",
        MARKER,
        autoRestoreMs);
    pendingRestore =
        restoreScheduler.schedule(this::autoRestore, autoRestoreMs, TimeUnit.MILLISECONDS);
    return new SubscriptionStallInjection(
        true,
        autoRestoreMs,
        "candle subscription suspended; auto-restore in " + autoRestoreMs + " ms");
  }

  /** Clamps to a bounded window so a forgotten or fat-fingered injection cannot outlive a session. */
  static long clampAutoRestore(Long requestedMs) {
    if (requestedMs == null) {
      return DEFAULT_AUTO_RESTORE_MS;
    }
    return Math.min(MAX_AUTO_RESTORE_MS, Math.max(MIN_AUTO_RESTORE_MS, requestedMs));
  }

  /**
   * The bounded backstop: force a re-subscription regardless of whether the watchdog already did.
   * {@code forceResubscribe} is overlap-safe, so a redundant restore is harmless. Never throws — this
   * runs on a scheduler thread, where an escaping exception would silently kill the restore path.
   */
  private void autoRestore() {
    try {
      log.error(
          "{} bounded auto-restore firing — forcing candle re-subscription. The engine should resume"
              + " receiving bars; any stall alert raised during this drill was INJECTED.",
          MARKER);
      engine.forceResubscribe(MARKER + " bounded auto-restore");
    } catch (RuntimeException e) {
      log.error("{} bounded auto-restore FAILED: {}", MARKER, e.toString());
    } finally {
      synchronized (this) {
        pendingRestore = null;
      }
    }
  }

  /** Stops the restore thread with the context; a pending drill restore dies with the JVM anyway. */
  @PreDestroy
  void shutdown() {
    restoreScheduler.shutdownNow();
  }
}
