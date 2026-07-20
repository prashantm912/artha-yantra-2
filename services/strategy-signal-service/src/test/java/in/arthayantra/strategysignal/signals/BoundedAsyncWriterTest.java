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
 * <p>This is the test that would have prevented the defect it was written for. The two-phase drain
 * was worked out in {@code RiskSuppressionWriter}, documented in its comments, copied into
 * {@code CompositeRejectionWriter} — and never reached {@code RejectionWriter}, which sat on the
 * live confluence path losing one diagnostic per graceful shutdown while
 * {@code ay_signal_rejection_shutdown_dropped_total} read 0 and nothing was logged. Each writer
 * still carries its own copy of these cases against its own metric names (those must stay
 * per-writer); this pins the shared mechanism so a FOURTH writer inherits it rather than
 * re-deriving it.
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
      queue.submit(ran::countDown);
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
          });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      // 1 running + 4 queued fills it; everything past that must DROP on the calling thread.
      long start = System.nanoTime();
      for (int i = 0; i < 50; i++) {
        queue.submit(() -> {});
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
  void aStuckSoleInFlightTaskIsCountedAndWarnedRatherThanVanishing() throws InterruptedException {
    // THE regression case. shutdownNow() returns only QUEUED tasks, so with the sole task RUNNING
    // the abandoned list is EMPTY: a naive implementation counts 0, logs nothing, and the record
    // dies at JVM exit with the metric reading healthy.
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
          });
      assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
      for (int i = 0; i < 3; i++) {
        queue.submit(() -> {});
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
  void aTaskThatThrowsStillClearsTheInFlightMarker() throws InterruptedException {
    // The marker is cleared in a finally, so one blown-up task cannot leave the writer permanently
    // "in flight" and make every later clean shutdown over-report a phantom loss.
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
        .as("a thrown task leaves no phantom in-flight loss behind")
        .isZero();
  }
}
