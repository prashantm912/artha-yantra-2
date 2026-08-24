package in.arthayantra.strategysignal.swing;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * "Is any swing batch activity in flight right now?" — the observable
 * {@code PaperReconciliationScheduler}'s bounded pre-open wait keys on, so a read-only reporter never
 * reads a book mid-change.
 *
 * <h2>Why this is a UNION of two signals, and why neither alone is enough</h2>
 *
 * <p><b>The catch-up POOL</b> ({@code swingCatchUpTaskScheduler}) answers the SWEEP-WIDE question. Its
 * worker holds the task for the entire {@code SwingBatchCatchUp.catchUp()} invocation, so it stays
 * active across every family and — the part that matters — across the windows BETWEEN them.
 *
 * <p><b>The per-family LOCKS</b> ({@link SwingRunMutex}) answer the other half: a manual
 * {@code POST /run} lands on a Tomcat request thread and the scheduled recorder passes run elsewhere,
 * so neither ever touches that pool, and {@link SwingBatchRecorder} holds the family lock for the
 * whole run.
 *
 * <p>⚠️ <b>The per-family lock alone was the first attempt, and it was WRONG in a specific and
 * instructive way.</b> {@code SwingBatchCatchUp} unlocks one family ({@code :233}), returns to the
 * doctrine loop ({@code :196}), does marker reads and window seeding ({@code :200-218}), and only then
 * locks the next family ({@code :224}). Throughout that window NO family lock is held, so a
 * lock-only gate reports IDLE in the middle of a live sweep and the reconciler overlaps the SECOND
 * family. That is the exact same defect as keying on {@code swing_catchup_runs} — which was rejected
 * because it carries no RUNNING row between claimed sessions — reproduced in a different mechanism.
 * The lesson is that <b>the observable's lifetime must match the ACTIVITY's lifetime</b>: the sweep is
 * per-INVOCATION, so a per-FAMILY signal can never bound it, however correct it is about families.
 *
 * <p>Both members are pure OBSERVATIONS — {@code getActiveCount()} and {@code isLocked()} — never
 * acquisitions. Nothing here can queue behind, delay, or reorder the run it is watching, which is the
 * property that lets the reconciler wait at all without reintroducing the withdrawn shared-lane
 * failure mode (a hung catch-up silently blocking two money jobs).
 *
 * <p>⚠️ <b>The pool's DEDICATION is load-bearing.</b> {@code SwingBatchCatchUp.catchUp} is the only
 * {@code @Scheduled} bound to {@code swingCatchUpTaskScheduler}, which is what makes "the pool is
 * active" mean "a sweep is running" — pinned by {@code SwingRunActivityTest}. A second job added to
 * that pool would only make this over-report (the reconciler waits for something harmless), which is
 * the safe direction; MOVING the catch-up OFF the pool is the dangerous one, because this gate would
 * not fail — it would go permanently, silently idle.
 */
@Component
public class SwingRunActivity {

  private final SwingRunMutex mutex;
  private final ThreadPoolTaskScheduler catchUpScheduler;

  /** Wires the per-family locks and the dedicated catch-up pool whose activity bounds a whole sweep. */
  public SwingRunActivity(
      SwingRunMutex mutex,
      @Qualifier("swingCatchUpTaskScheduler") ThreadPoolTaskScheduler catchUpScheduler) {
    this.mutex = mutex;
    this.catchUpScheduler = catchUpScheduler;
  }

  /**
   * True while a catch-up sweep is executing OR any family run holds its lock.
   *
   * <p>A hard constructor dependency on the pool, deliberately not an {@code ObjectProvider}: if that
   * bean ever went missing, a fail-soft lookup would silently drop the sweep-wide half and leave a
   * gate that still compiles, still passes its own tests, and no longer guards anything. A startup
   * failure is the correct direction for an operand this load-bearing.
   */
  public boolean anyRunInFlight() {
    return catchUpScheduler.getActiveCount() > 0 || mutex.anyRunInFlight();
  }
}
