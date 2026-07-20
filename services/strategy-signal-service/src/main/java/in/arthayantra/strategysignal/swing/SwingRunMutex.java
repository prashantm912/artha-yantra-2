package in.arthayantra.strategysignal.swing;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * A per-family in-process run lock, so no two swing batch runs for the SAME family execute
 * concurrently (2026-07-17 review, Critical 2). The ~31 {@code @Scheduled} jobs share one
 * single-thread scheduler ({@code MonitorSchedulingConfig}), so the scheduled 20:00/20:05 runs, the
 * 08:35 catch-up, and the canary already serialize against each other — but a manual
 * {@code POST /run} lands on a Tomcat REQUEST thread and can overlap any of them. Two overlapping
 * runs both snapshot "symbol not held", both emit an ENTRY, and the second averages a doubled fill
 * into the first's paper position. This lock closes that window: {@link SwingBatchRecorder} holds it
 * for the whole run, and the catch-up {@code tryLock}s it (skipping a family whose run is already in
 * flight rather than blocking the scheduler thread).
 *
 * <p>Reentrant: the catch-up acquires the family lock, then calls the recorder which re-acquires it
 * on the same thread. Single-owner / single-instance, so an in-JVM lock is sufficient (the durable
 * {@code swing_catchup_runs} claim carries idempotency across restarts / instances).
 */
@Component
public class SwingRunMutex {

  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** The (stable, lazily-created) lock guarding all runs for one family batch. */
  public ReentrantLock lockFor(String batch) {
    return locks.computeIfAbsent(batch, b -> new ReentrantLock());
  }
}
