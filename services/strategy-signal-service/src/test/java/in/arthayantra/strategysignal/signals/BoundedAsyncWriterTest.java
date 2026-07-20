package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The shutdown contract, pinned ONCE on the shared queue that {@link RejectionWriter},
 * {@link RiskSuppressionWriter} and {@link CompositeRejectionWriter} all run on.
 *
 * <p>This is the test that would have prevented the defects it was written for. Two of them:
 *
 * <ul>
 *   <li>the two-phase drain was worked out in {@code RiskSuppressionWriter}, documented, copied into
 *       {@code CompositeRejectionWriter} — and never reached {@code RejectionWriter}, which lost one
 *       diagnostic per graceful shutdown while its counter read 0;
 *   <li>the first fix counted only an insert that IGNORED the interrupt and ran past the grace, so an
 *       interrupt-RESPONSIVE insert (pgjdbc aborts on {@code Thread.interrupt()}) that failed fast and
 *       let the executor terminate was STILL lost with the counter reading 0 — the same class one
 *       layer in.
 * </ul>
 *
 * <p>Each writer still carries its own copy of these cases against its own metric names (those must
 * stay per-writer); this pins the shared mechanism so a FOURTH writer inherits it structurally.
 */
class BoundedAsyncWriterTest {

  private static final String DROPPED = "ay_test_writer_dropped_total";
  private static final String SHUTDOWN_DROPPED = "ay_test_writer_shutdown_dropped_total";

  private static BoundedAsyncWriter writer(SimpleMeterRegistry meters, int capacity) {
    return new BoundedAsyncWriter(
        "test",
        capacity,
        LoggerFactory.getLogger(BoundedAsyncWriterTest.class),
        meters.counter(DROPPED),
        meters.counter(SHUTDOWN_DROPPED));
  }

  @Test
  void aCleanDrainRunsEveryAcceptedTaskAndCountsNothingAsLost() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedAsyncWriter queue = writer(meters, 256);
    CountDownLatch ran = new CountDownLatch(5);
    for (int i = 0; i < 5; i++) {
      queue.submit(
          () -> {
            ran.countDown();
            return true;
          });
    }
    queue.drainAndShutdown(5_000);

    assertThat(ran.getCount()).as("a graceful drain runs every accepted task").isZero();
    assertThat(meters.counter(SHUTDOWN_DROPPED).count())
        .as("nothing is counted as lost on a clean drain")
        .isZero();
  }

  @Test
  void aSaturatedQueueDropsAndCountsWithoutBlockingTheCaller() throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedAsyncWriter queue = writer(meters, 4);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch block = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            started.countDown();
            try {
              block.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return true;
          });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      // 1 running + 4 queued fills it; everything past that must DROP on the calling thread.
      long start = System.nanoTime();
      for (int i = 0; i < 50; i++) {
        queue.submit(() -> true);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      assertThat(elapsedMs).as("the calling thread is never blocked").isLessThan(2_000L);
      assertThat(meters.counter(DROPPED).count())
          .as("saturation sheds rather than back-pressuring the caller")
          .isEqualTo(46.0);
    } finally {
      block.countDown();
      queue.drainAndShutdown(2_000);
    }
  }

  @Test
  void anInterruptIgnoringSoleInFlightTaskIsCountedAndWarned() throws InterruptedException {
    // Loss mode 1: the task NEVER honours the interrupt and is still running when the grace expires.
    // shutdownNow() returns an EMPTY list (the task is running, not queued), so without the
    // stuck-task branch it would vanish uncounted with no WARN.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    ch.qos.logback.classic.Logger log =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BoundedAsyncWriterTest.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    log.addAppender(logs);

    BoundedAsyncWriter queue = writer(meters, 256);
    try {
      queue.submit(
          () -> {
            started.countDown();
            while (release.getCount() > 0) {
              try {
                release.await();
              } catch (InterruptedException e) {
                // Swallow: a stuck native JDBC call does NOT honour shutdownNow()'s interrupt.
              }
            }
            return true;
          });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      queue.drainAndShutdown(200);

      assertThat(meters.counter(SHUTDOWN_DROPPED).count())
          .as("the stuck in-flight record is counted, not silently lost")
          .isEqualTo(1.0);
      assertThat(logs.list)
          .as("the drain-timeout WARN fires unconditionally, even with an empty abandoned queue")
          .anyMatch(
              e ->
                  e.getLevel() == Level.WARN
                      && e.getFormattedMessage().contains("in-flight")
                      && e.getFormattedMessage().contains(SHUTDOWN_DROPPED));
    } finally {
      log.detachAppender(logs);
      release.countDown();
    }
  }

  @Test
  void anInterruptResponsiveTaskThatFailsFastAndTerminatesWithinGraceIsStillCounted()
      throws InterruptedException {
    // Loss mode 2 — the branch the first fix missed. The task ABORTS on the interrupt, fails, and
    // unwinds within the grace, so the executor TERMINATES cleanly and "(!terminated && inFlight)"
    // reads 0. It must still be counted, via the task reporting its failed outcome.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    CountDownLatch started = new CountDownLatch(1);
    BoundedAsyncWriter queue = writer(meters, 256);
    queue.submit(
        () -> {
          started.countDown();
          try {
            Thread.sleep(60_000);
            return true;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false; // aborted by the shutdown interrupt → a failed persist
          }
        });
    assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    queue.drainAndShutdown(200);

    assertThat(meters.counter(SHUTDOWN_DROPPED).count())
        .as("an interrupt-responsive task that fails fast within the grace is counted, not lost")
        .isEqualTo(1.0);
  }

  @Test
  void queuedResidueAndAStuckInFlightTaskAreBothCounted() throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedAsyncWriter queue = writer(meters, 256);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      queue.submit(
          () -> {
            started.countDown();
            while (release.getCount() > 0) {
              try {
                release.await();
              } catch (InterruptedException e) {
                // Stuck: ignores the interrupt.
              }
            }
            return true;
          });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 3; i++) {
        queue.submit(() -> true);
      }
      queue.drainAndShutdown(200);

      assertThat(meters.counter(SHUTDOWN_DROPPED).count())
          .as("3 queued + 1 stuck in-flight are all accounted for")
          .isEqualTo(4.0);
    } finally {
      release.countDown();
    }
  }

  @Test
  void aNormalOperationFailureBeforeShutdownIsNotCountedAsAShutdownLoss() throws InterruptedException {
    // A persistence failure during NORMAL running (fail-soft, already logged) is NOT a shutdown loss.
    // Only failures after the drain begins are shutdown-caused, so this clean drain counts zero.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedAsyncWriter queue = writer(meters, 256);
    CountDownLatch ran = new CountDownLatch(1);
    queue.submit(
        () -> {
          ran.countDown();
          return false; // failed its insert, but nowhere near shutdown
        });
    assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    queue.drainAndShutdown(2_000); // drains cleanly — the failed task already finished

    assertThat(meters.counter(SHUTDOWN_DROPPED).count())
        .as("a normal-operation fail-soft failure is not a shutdown loss")
        .isZero();
  }

  @Test
  void aTaskThatThrowsBeforeShutdownLeavesNoPhantomShutdownLoss() throws InterruptedException {
    // The attempt is contracted to swallow, but an escaped exception must still be treated as a
    // failure (not silently corrupt the count) and must not leave the writer wedged.
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    BoundedAsyncWriter queue = writer(meters, 256);
    CountDownLatch ran = new CountDownLatch(1);
    queue.submit(
        () -> {
          ran.countDown();
          throw new IllegalStateException("boom");
        });
    assertThat(ran.await(2, TimeUnit.SECONDS)).isTrue();
    queue.drainAndShutdown(2_000);

    assertThat(meters.counter(SHUTDOWN_DROPPED).count())
        .as("a thrown task that finished before shutdown leaves no phantom shutdown loss")
        .isZero();
  }
}
