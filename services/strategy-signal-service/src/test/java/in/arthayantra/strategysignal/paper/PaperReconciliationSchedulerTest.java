package in.arthayantra.strategysignal.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.paper.PaperReconciliationService.ReconciliationResult;
import in.arthayantra.strategysignal.signals.SwingBatchAlert;
import in.arthayantra.strategysignal.swing.SwingRunActivity;
import in.arthayantra.strategysignal.swing.SwingRunMutex;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The pre-open reconciler's bounded wait for the 08:35 swing entry pass (cross-vendor review Major on
 * PR #1358). The two jobs run on SEPARATE thread pools deliberately — sharing the catch-up's lane was
 * tried and withdrawn because a hung catch-up then silently blocked two money jobs — so a cron minute
 * is the only thing separating them, and a multi-session recovery can overrun the 08:35→08:50 gap and
 * leave this read-only reporter reading a book mid-change.
 *
 * <p>What these pin is the FAILURE DIRECTION: on breach the reconciler must DECLINE loudly (log +
 * ops alert + counter), never silently skip and never quietly reconcile torn state. A reconciliation
 * that did not happen is otherwise indistinguishable from one that found nothing wrong.
 *
 * <p>Clock is fixed per test: 08:50 IST is inside the wait window (deadline 09:00 = the 09:15
 * {@code MarketCalendar.SESSION_OPEN} less the default 15-minute reserve), 09:05 IST is past it.
 * Poll interval is 1 ms so the wait loop is instant rather than the production 5 s.
 */
class PaperReconciliationSchedulerTest {

  /** 2026-08-12 08:50 IST — the production cron minute, inside the wait window. */
  private static final Instant AT_0850_IST = Instant.parse("2026-08-12T03:20:00Z");
  /** 2026-08-12 09:05 IST — past the 09:00 deadline, before the 09:15 open. */
  private static final Instant AT_0905_IST = Instant.parse("2026-08-12T03:35:00Z");

  private final PaperReconciliationService reconciliation = mock(PaperReconciliationService.class);
  private final SwingRunActivity swingRuns = mock(SwingRunActivity.class);
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final List<SwingBatchAlert> alerts = new ArrayList<>();

  private final ApplicationEventPublisher events =
      new ApplicationEventPublisher() {
        @Override
        public void publishEvent(Object event) {
          if (event instanceof SwingBatchAlert alert) {
            alerts.add(alert);
          }
        }
      };

  private PaperReconciliationScheduler schedulerAt(Instant now) {
    return new PaperReconciliationScheduler(
        reconciliation, events, swingRuns, Clock.fixed(now, ZoneOffset.UTC), meters, 15, 1L);
  }

  private double declined() {
    return meters.counter("ay_paper_recon_declined_total").count();
  }

  private static ReconciliationResult cleanResult() {
    OffsetDateTime t = OffsetDateTime.parse("2026-08-11T09:15:00+05:30");
    return new ReconciliationResult(
        t, t, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @Test
  @DisplayName("no swing run in flight: reconciles immediately, never consults the deadline")
  void reconcilesImmediatelyWhenSwingBatchIsIdle() {
    when(swingRuns.anyRunInFlight()).thenReturn(false);
    when(reconciliation.reconcile()).thenReturn(cleanResult());

    // Deliberately clocked PAST the deadline: the fast path must not depend on the wall clock at
    // all, or a cron override (or a manual invocation) outside the pre-open window would refuse to
    // reconcile a perfectly quiet book.
    schedulerAt(AT_0905_IST).run();

    verify(reconciliation).reconcile();
    assertThat(alerts).isEmpty();
    assertThat(declined()).isZero();
  }

  @Test
  @DisplayName("swing run finishes before the deadline: waits, then reconciles, and stays quiet")
  void waitsForAnOverrunningCatchUpThenReconciles() {
    when(swingRuns.anyRunInFlight()).thenReturn(true, true, false);
    when(reconciliation.reconcile()).thenReturn(cleanResult());

    schedulerAt(AT_0850_IST).run();

    verify(reconciliation).reconcile();
    assertThat(alerts).isEmpty();
    assertThat(declined()).isZero();
  }

  @Test
  @DisplayName("swing run still in flight at the deadline: DECLINES — no reconcile, loud on 3 channels")
  void declinesLoudlyWhenTheCatchUpIsStillRunningAtTheDeadline() {
    when(swingRuns.anyRunInFlight()).thenReturn(true);

    schedulerAt(AT_0905_IST).run();

    // The whole point: a torn read is not persisted and not reported as a result.
    verify(reconciliation, never()).reconcile();
    // ...and the absence is ATTRIBUTABLE, not silent. A skip that looks like a clean run is the
    // success-shaped-nothing failure mode this codebase has been burned by repeatedly.
    assertThat(declined()).isEqualTo(1.0d);
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).batch()).isEqualTo("reconciliation");
    assertThat(alerts.get(0).title()).contains("DECLINED");
    assertThat(alerts.get(0).message())
        .contains("NOTHING WAS RECONCILED TODAY")
        .contains("09:00");
  }

  @Test
  @DisplayName("a thrown reconciliation still pages the owner (pre-existing behaviour, unchanged)")
  void publishesTheFailureAlertWhenReconciliationThrows() {
    when(swingRuns.anyRunInFlight()).thenReturn(false);
    doThrow(new IllegalStateException("boom")).when(reconciliation).reconcile();

    schedulerAt(AT_0850_IST).run();

    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).title()).isEqualTo("Paper reconciliation FAILED");
    assertThat(alerts.get(0).message()).contains("boom");
    // A FAILURE is not a DECLINE — the two must stay distinguishable on the counter.
    assertThat(declined()).isZero();
  }

  @Test
  @DisplayName("the wait OBSERVES swing activity and does nothing else to it")
  void neverQueuesBehindTheCatchUp() {
    // Regression guard for the constraint the withdrawn revision violated: putting this job on the
    // catch-up's own lane made a hung catch-up silently block two money jobs. Waiting is only safe
    // while it stays a non-blocking observation, so the scheduler touches anyRunInFlight() and
    // nothing else — anything that could acquire would let a hung sweep take this job down with it.
    when(swingRuns.anyRunInFlight()).thenReturn(false);
    when(reconciliation.reconcile()).thenReturn(cleanResult());

    schedulerAt(AT_0850_IST).run();

    verify(swingRuns).anyRunInFlight();
    verifyNoMoreInteractions(swingRuns);
  }

  @Test
  @DisplayName("REAL sweep parked BETWEEN families: the scheduler still declines (no mock anywhere)")
  void declinesOnARealSweepSittingInTheInterFamilyWindow() throws Exception {
    // The end-to-end half of the cross-vendor Major. Every other test here stubs the observable, so
    // all of them stayed green while the gate reported idle between families and the reconciler
    // overlapped the second one. This wires the REAL SwingRunActivity to a REAL catch-up pool and
    // parks a task exactly in that window: no family lock is held, so the per-family signal alone
    // says "quiet", and the scheduler must still refuse.
    ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
    pool.setPoolSize(1);
    pool.setThreadNamePrefix("swing-catchup-sched-");
    pool.setDaemon(true);
    pool.initialize();
    CountDownLatch betweenFamilies = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      SwingRunMutex mutex = new SwingRunMutex();
      SwingRunActivity realActivity = new SwingRunActivity(mutex, pool);
      pool.execute(
          () -> {
            ReentrantLock first = mutex.lockFor("minervini");
            first.lock();
            first.unlock();
            betweenFamilies.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(betweenFamilies.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(mutex.anyRunInFlight()).isFalse();

      new PaperReconciliationScheduler(
              reconciliation,
              events,
              realActivity,
              Clock.fixed(AT_0905_IST, ZoneOffset.UTC),
              meters,
              15,
              1L)
          .run();

      verify(reconciliation, never()).reconcile();
      assertThat(declined()).isEqualTo(1.0d);
      assertThat(alerts).hasSize(1);
      assertThat(alerts.get(0).title()).contains("DECLINED");
    } finally {
      release.countDown();
      pool.shutdown();
    }
  }

  @Test
  @DisplayName("the run clears during the final sleep but the clock slipped past: still declines")
  void declinesWhenTheDeadlinePassedDuringTheFinalSleep() {
    // The loop tests the RUN before the CLOCK, so a sweep finishing mid-sleep exits it with no
    // deadline check since before that sleep — reconciling up to a poll interval past the cutoff and
    // eating into the pre-open budget the reconciliation and past-expiry recovery need.
    MutableClock clock = new MutableClock(Instant.parse("2026-08-12T03:29:58Z")); // 08:59:58 IST
    AtomicInteger calls = new AtomicInteger();
    when(swingRuns.anyRunInFlight())
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() < 3) {
                return true; // the entry guard, then one loop pass that sleeps
              }
              clock.set(Instant.parse("2026-08-12T03:30:03Z")); // 09:00:03 IST — slipped past
              return false; // ...and the sweep finished during that same sleep
            });

    new PaperReconciliationScheduler(reconciliation, events, swingRuns, clock, meters, 15, 1L).run();

    verify(reconciliation, never()).reconcile();
    assertThat(declined()).isEqualTo(1.0d);
  }

  /** A clock whose instant the test moves; {@code withZone} SHARES the state the caller advances. */
  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    private MutableClock(Instant start) {
      this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    void set(Instant instant) {
      now.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
      return new MutableClock(now, other);
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
