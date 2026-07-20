package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The {@code signal_rejections} writer must never let a DB stall or a persistence failure reach the
 * sole {@code signal-eval} thread (task_084d4d01, the #866 starvation class). Saturation DROPS +
 * counts; a failing insert is swallowed — {@code record()} always returns promptly and never throws;
 * a graceful shutdown DRAINS the queued rows rather than losing them.
 */
class RejectionWriterTest {

  private static final OffsetDateTime BAR = OffsetDateTime.now(ZoneOffset.ofHoursMinutes(5, 30));

  private static void record(RejectionWriter writer) {
    writer.record(
        UUID.randomUUID(), "slug", "NSE", "SIGTEST", "3m", "BUY", "confluence", null, null, null,
        "blocked", null, null, "{}", BAR, null);
  }

  @Test
  void aStalledInsertNeverBlocksTheCallerAndSaturationDropsAndCounts() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    CountDownLatch block = new CountDownLatch(1);
    // The single writer thread parks on the first insert; the bounded queue then fills and the rest
    // are dropped — the caller (the eval thread) must still return in O(1) throughout.
    doAnswer(
            inv -> {
              block.await();
              return 1L;
            })
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    try {
      int n = RejectionWriter.QUEUE_CAPACITY * 8;
      long start = System.nanoTime();
      for (int i = 0; i < n; i++) {
        record(writer);
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      assertThat(elapsedMs)
          .as("the eval thread is never blocked by the write path (submitted %d records)", n)
          .isLessThan(2_000L);
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> meters.counter("ay_signal_rejection_dropped_total").count() > 0);
      assertThat(meters.counter("ay_signal_rejection_dropped_total").count())
          .as("a saturated queue drops rather than back-pressuring the eval loop")
          .isGreaterThan(0.0);
    } finally {
      block.countDown();
      writer.shutdown();
    }
  }

  @Test
  void aFailingInsertIsSwallowedAndNeverThrowsIntoTheCaller() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    doThrow(new RuntimeException("boom"))
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    try {
      assertThatCode(() -> record(writer)).doesNotThrowAnyException();
      // The background thread DID attempt the insert (fail-soft proven) and swallowed the exception —
      // the id-coupled shadow-book open is never reached when the insert itself blew up.
      verify(repo, timeout(2_000))
          .insert(
              any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
              any(), any(), any());
      verify(shadow, never())
          .maybeOpen(anyLong(), any(), any(), any(), any(), any(), any());
      assertThat(meters.counter("ay_signal_rejection_dropped_total").count())
          .as("a failing insert is swallowed, not a drop")
          .isZero();
    } finally {
      writer.shutdown();
    }
  }

  @Test
  void aFailingShadowOpenIsSwallowedAndNeverThrowsIntoTheCaller() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    // Insert succeeds and mints an id; the shadow-book open then blows up on the writer thread.
    doAnswer(inv -> 7L)
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    doThrow(new RuntimeException("shadow boom"))
        .when(shadow)
        .maybeOpen(anyLong(), any(), any(), any(), any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    try {
      assertThatCode(() -> record(writer)).doesNotThrowAnyException();
      verify(shadow, timeout(2_000))
          .maybeOpen(anyLong(), any(), any(), any(), any(), any(), any());
      assertThat(meters.counter("ay_signal_rejection_dropped_total").count())
          .as("a failing shadow open is swallowed, not a drop")
          .isZero();
    } finally {
      writer.shutdown();
    }
  }

  @Test
  void shutdownDrainsQueuedRecordsRatherThanDroppingThem() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    // A slow but SUCCEEDING insert: a graceful drain must WAIT for these, not discard them on redeploy.
    doAnswer(
            inv -> {
              Thread.sleep(30);
              return 1L;
            })
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    for (int i = 0; i < 5; i++) {
      record(writer);
    }
    writer.shutdown(); // graceful: awaits SHUTDOWN_DRAIN_MILLIS; 5 x 30ms << 5s so all drain

    verify(repo, times(5))
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    assertThat(meters.counter("ay_signal_rejection_shutdown_dropped_total").count())
        .as("a graceful drain persists every accepted record — nothing lost")
        .isZero();
  }

  @Test
  void aDrainExceedingTheTimeoutCountsTheLeftoverInsteadOfLosingItSilently()
      throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch block = new CountDownLatch(1);
    doAnswer(
            inv -> {
              started.countDown();
              try {
                block.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return 1L;
            })
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    try {
      for (int i = 0; i < 4; i++) {
        record(writer);
      }
      assertThat(started.await(2, TimeUnit.SECONDS))
          .as("the writer thread is confirmed stuck on the first insert")
          .isTrue();
      // Task 1 is running (blocked); tasks 2-4 sit in the queue — the drain times out and abandons
      // them. They must be COUNTED, never silently lost (the whole point of a durable audit trail).
      writer.drainAndShutdown(200);
      assertThat(meters.counter("ay_signal_rejection_shutdown_dropped_total").count())
          .as("the 3 undrained queued records are counted, not silently dropped")
          .isEqualTo(3.0);
    } finally {
      block.countDown();
    }
  }

  @Test
  void aStuckInFlightInsertThatIsTheSoleTaskIsCountedAndWarnedNeverSilentlyLost()
      throws InterruptedException {
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // The sole insert blocks AND ignores interruption (models a stuck native JDBC call). It is the
    // RUNNING task, never a queued one, so shutdownNow() returns an EMPTY list — without in-flight
    // accounting it would vanish at JVM exit uncounted, and with an empty queue no WARN would fire,
    // leaving ay_signal_rejection_shutdown_dropped_total reading 0 while a diagnostic is lost.
    doAnswer(
            inv -> {
              started.countDown();
              while (release.getCount() > 0) {
                try {
                  release.await();
                } catch (InterruptedException e) {
                  // Swallow: a stuck native call does NOT honour the interrupt from shutdownNow().
                }
              }
              return 1L;
            })
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());

    Logger writerLog = (Logger) LoggerFactory.getLogger(RejectionWriter.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    writerLog.addAppender(logs);

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ShadowBookService shadow = mock(ShadowBookService.class);
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    try {
      record(writer); // the ONLY task — it becomes the in-flight insert, nothing sits in the queue
      assertThat(started.await(2, TimeUnit.SECONDS))
          .as("the writer thread is confirmed stuck inside the sole insert")
          .isTrue();
      // Drain times out, shutdownNow() abandons an empty queue, the grace await times out on the
      // stuck insert — the loss must be COUNTED and a WARN must fire (not silent).
      writer.drainAndShutdown(200);

      assertThat(meters.counter("ay_signal_rejection_shutdown_dropped_total").count())
          .as("the stuck in-flight insert is counted, not silently lost")
          .isEqualTo(1.0);
      assertThat(logs.list)
          .as("a drain-timeout WARN fires even though the abandoned queue is empty")
          .anyMatch(
              e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("in-flight"));
    } finally {
      writerLog.detachAppender(logs);
      release.countDown();
    }
  }

  @Test
  void anInterruptResponsiveInsertAbortedByShutdownIsCountedNotSilentlyLost()
      throws InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SignalRejectionRepository repo = mock(SignalRejectionRepository.class);
    ShadowBookService shadow = mock(ShadowBookService.class);
    CountDownLatch started = new CountDownLatch(1);
    // Interrupt-RESPONSIVE insert: it blocks, and when shutdownNow() interrupts the writer thread it
    // ABORTS and throws (pgjdbc cancels a running statement on Thread.interrupt() — reachable, not
    // theoretical). It then unwinds WELL WITHIN the 1s in-flight grace, so the executor terminates
    // and a naive "(!terminated && inFlight)" check reads 0 — the record is lost with the counter
    // reading 0. This is the branch the interrupt-IGNORING test above does NOT exercise.
    doAnswer(
            inv -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
                return 1L;
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("insert aborted by interrupt");
              }
            })
        .when(repo)
        .insert(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any());
    RejectionWriter writer = new RejectionWriter(repo, shadow, meters);
    record(writer); // sole task → the in-flight insert
    assertThat(started.await(2, TimeUnit.SECONDS))
        .as("the writer thread is confirmed inside the insert")
        .isTrue();
    // Graceful drain (200ms) times out on the blocked insert; shutdownNow() interrupts it; it aborts
    // and fails FAST, so the executor terminates within the grace.
    writer.drainAndShutdown(200);
    assertThat(meters.counter("ay_signal_rejection_shutdown_dropped_total").count())
        .as("an insert aborted by the shutdown interrupt is counted, not silently lost")
        .isEqualTo(1.0);
  }
}
