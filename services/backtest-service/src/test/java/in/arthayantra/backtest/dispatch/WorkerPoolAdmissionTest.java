package in.arthayantra.backtest.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * B16 / audit A6 unit proof of the interactive-reservation admission — no Spring, no Redis. {@link
 * WorkerPool#maxTrialWorkers()} = {@code max(1, workerCount - interactive-reserved)} and {@link
 * WorkerPool#tryReserveTrialSlot()} hands out at most that many trial slots (atomically, via CAS),
 * so the {@code interactive-reserved} slots always stay free for owner-submitted backtests during a
 * campaign trial storm. The math here is the discriminating check; the {@code
 * WorkerPoolPriorityIntegrationTest} proves the same cap holds under real concurrent stream load.
 */
class WorkerPoolAdmissionTest {

  /** Collaborators are unused by the admission math, so a null-wired pool is enough. */
  private static WorkerPool pool(int workers, int reserved) {
    return new WorkerPool(null, null, null, null, null, workers, true, reserved);
  }

  @Test
  void deepSwingBudgetIsSingleFlightAndConsumesATrialSlot() {
    // Audit P0-3: the deep-swing budget = ONE in-flight deep-swing AND one slot of the trial (batch)
    // budget, so a minutes-long deep-sim never occupies the interactive-reserved slots and a second
    // deep-swing queues instead of failing on market-data's busy 409.
    WorkerPool pool = pool(4, 1); // trial budget 3
    assertThat(pool.tryReserveDeepSwingBudget()).isTrue();
    assertThat(pool.deepSwingInFlight()).isTrue();
    assertThat(pool.trialsInFlightCount()).isEqualTo(1); // counts against the batch budget
    // single-flight: a second deep-swing is refused while the first runs
    assertThat(pool.tryReserveDeepSwingBudget()).isFalse();
    // trials still fit in the REMAINING batch budget (3 - 1 deep-swing = 2)
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isFalse(); // batch budget exhausted; reserved slot intact
    // release restores both the flag and the batch slot
    pool.releaseDeepSwingBudget();
    assertThat(pool.deepSwingInFlight()).isFalse();
    assertThat(pool.trialsInFlightCount()).isEqualTo(2);
    assertThat(pool.tryReserveTrialSlot()).isTrue();
  }

  @Test
  void deepSwingBudgetRefusedWhenTrialBudgetIsFullAndLeavesNoDanglingFlag() {
    WorkerPool pool = pool(2, 1); // trial budget 1
    assertThat(pool.tryReserveTrialSlot()).isTrue(); // a trial holds the whole batch budget
    assertThat(pool.tryReserveDeepSwingBudget()).isFalse(); // no batch room -> refused
    assertThat(pool.deepSwingInFlight()).isFalse(); // the flag was rolled back, not stranded
    pool.releaseTrialSlot();
    assertThat(pool.tryReserveDeepSwingBudget()).isTrue(); // and it recovers once budget frees
  }

  @Test
  void defaultReservesOneSlotSoTrialsGetPoolMinusOne() {
    WorkerPool pool = pool(4, 1);
    assertThat(pool.workerCount()).isEqualTo(4);
    assertThat(pool.maxTrialWorkers()).isEqualTo(3);

    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.trialsInFlightCount()).isEqualTo(3);
    // the 4th (reserved-for-interactive) slot is NEVER handed to a trial
    assertThat(pool.tryReserveTrialSlot()).isFalse();
    assertThat(pool.trialsInFlightCount()).isEqualTo(3);

    // freeing a trial slot re-opens exactly one
    pool.releaseTrialSlot();
    assertThat(pool.trialsInFlightCount()).isEqualTo(2);
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isFalse();
  }

  @Test
  void singleWorkerPoolCannotReserveButStillRunsTrials() {
    WorkerPool pool = pool(1, 1);
    assertThat(pool.maxTrialWorkers()).isEqualTo(1);
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isFalse();
  }

  @Test
  void reservedZeroRestoresPreB16FullPoolForTrials() {
    WorkerPool pool = pool(2, 0);
    assertThat(pool.maxTrialWorkers()).isEqualTo(2);
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isFalse();
  }

  @Test
  void reservedNeverExceedsPoolMinusOneEvenWhenMisconfiguredHigh() {
    // reserving MORE than the pool would leave zero trial workers — clamp keeps at least one so
    // trials can never be fully starved.
    WorkerPool pool = pool(3, 10);
    assertThat(pool.maxTrialWorkers()).isEqualTo(1);
    assertThat(pool.tryReserveTrialSlot()).isTrue();
    assertThat(pool.tryReserveTrialSlot()).isFalse();
  }

  @Test
  void negativeReservedIsClampedToZero() {
    WorkerPool pool = pool(3, -5);
    assertThat(pool.maxTrialWorkers()).isEqualTo(3);
  }
}
