package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * Unit-proves the injector's window arithmetic, its refusal paths and its CONFIRMED restore. The
 * end-to-end fire path (inject → watchdog detects → re-subscribes → pages → writes the row) is proved
 * against a real engine, Redis and Postgres in {@link SubscriberFaultInjectionIntegrationTest}.
 */
class SignalFaultInjectorTest {

  private static final long BAR_GAP_MS = 180_000L;
  private static final long SWEEP_MS = SubscriberHealthCanary.SWEEP_INTERVAL_MS;

  private final SignalEngine engine = mock(SignalEngine.class);
  private final SubscriberHealthCanary canary = mock(SubscriberHealthCanary.class);

  private SignalFaultInjector injector() {
    when(canary.barGapMs()).thenReturn(BAR_GAP_MS);
    return new SignalFaultInjector(engine, canary);
  }

  /**
   * The defect that made a parameterless drill useless: detection needs the gap to exceed bar-gap-ms
   * AND a sweep to observe it, so anything under bar-gap + sweep restores before the watchdog can
   * ever fire. The default must clear that floor on its own.
   */
  @Test
  void defaultWindow_isDetectorCapable() {
    SignalFaultInjector injector = injector();

    assertThat(injector.detectionFloorMs()).isEqualTo(BAR_GAP_MS + SWEEP_MS);
    assertThat(injector.defaultAutoRestoreMs())
        .as("a parameterless drill must outlast the detection floor")
        .isGreaterThanOrEqualTo(injector.detectionFloorMs());
    assertThat(injector.clampAutoRestore(null)).isEqualTo(injector.defaultAutoRestoreMs());
    injector.shutdown();
  }

  /** The floor tracks the canary's OWN threshold rather than a constant that can drift out of step. */
  @Test
  void detectionFloor_followsTheCanarysConfiguredThreshold() {
    when(canary.barGapMs()).thenReturn(30_000L);
    SignalFaultInjector injector = new SignalFaultInjector(engine, canary);

    assertThat(injector.detectionFloorMs()).isEqualTo(30_000L + SWEEP_MS);
    injector.shutdown();
  }

  /** The whole point of the clamp: a forgotten or fat-fingered drill cannot outlive a session. */
  @Test
  void absurdlyLongWindow_isClampedToTheCeiling() {
    SignalFaultInjector injector = injector();

    assertThat(injector.clampAutoRestore(Long.MAX_VALUE))
        .isEqualTo(SignalFaultInjector.MAX_AUTO_RESTORE_MS);
    assertThat(SignalFaultInjector.MAX_AUTO_RESTORE_MS)
        .as("the ceiling must leave room above the detection floor")
        .isGreaterThan(injector.defaultAutoRestoreMs());
    injector.shutdown();
  }

  @Test
  void zeroOrNegativeWindow_isClampedToTheFloor() {
    SignalFaultInjector injector = injector();

    assertThat(injector.clampAutoRestore(0L)).isEqualTo(SignalFaultInjector.MIN_AUTO_RESTORE_MS);
    assertThat(injector.clampAutoRestore(-5_000L))
        .isEqualTo(SignalFaultInjector.MIN_AUTO_RESTORE_MS);
    injector.shutdown();
  }

  /** A sub-floor drill is allowed, but must SAY it cannot reach the detector. */
  @Test
  void shortWindow_isHonouredButLabelledNotDetectorCapable() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    when(engine.candleSubscriptionActive()).thenReturn(true);
    SignalFaultInjector injector = injector();

    SignalFaultInjector.SubscriptionStallInjection result =
        injector.injectSubscriptionStall(SignalFaultInjector.MIN_AUTO_RESTORE_MS);

    assertThat(result.injected()).isTrue();
    assertThat(result.detectorCapable())
        .as("1s is far below the 240s detection floor — an absent alert would mean nothing")
        .isFalse();
    injector.shutdown();
  }

  @Test
  void defaultInjection_reportsDetectorCapable() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    when(engine.candleSubscriptionActive()).thenReturn(true);
    SignalFaultInjector injector = injector();

    SignalFaultInjector.SubscriptionStallInjection result = injector.injectSubscriptionStall(null);

    assertThat(result.injected()).isTrue();
    assertThat(result.detectorCapable()).isTrue();
    assertThat(result.autoRestoreMs()).isGreaterThanOrEqualTo(BAR_GAP_MS + SWEEP_MS);
    injector.shutdown();
  }

  /** No container to stop ⇒ report it honestly and schedule nothing to "restore". */
  @Test
  void noListenerContainer_reportsNotInjectedAndNeverResubscribes() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(false);
    SignalFaultInjector injector = injector();

    SignalFaultInjector.SubscriptionStallInjection result =
        injector.injectSubscriptionStall(30_000L);

    assertThat(result.injected()).isFalse();
    assertThat(result.detail()).contains("no active listener container");
    verify(engine, never()).forceResubscribe(anyString());
    injector.shutdown();
  }

  /**
   * A second POST during an active drill must be REFUSED. Accepting it would cancel the pending
   * deadline and extend the outage — the bound is only a bound if a re-POST cannot push it out.
   */
  @Test
  void secondInjectionWhileADrillIsInFlight_isRefusedAndDoesNotReStop() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    when(engine.candleSubscriptionActive()).thenReturn(true);
    SignalFaultInjector injector = injector();

    SignalFaultInjector.SubscriptionStallInjection first = injector.injectSubscriptionStall(null);
    SignalFaultInjector.SubscriptionStallInjection second = injector.injectSubscriptionStall(null);

    assertThat(first.injected()).isTrue();
    assertThat(second.injected()).isFalse();
    assertThat(second.detail()).contains("already in flight");
    // exactly ONE stop: the refused call must not touch the engine at all
    verify(engine, times(1)).suspendCandleSubscriptionForFaultDrill();
    injector.shutdown();
  }

  /**
   * Restoration must be CONFIRMED, not fire-and-forget. forceResubscribe only enqueues a rebuild and
   * swallows its exception on another thread, so an unretried call can leave the engine stopped
   * indefinitely. Here the confirmation windows report the subscription still down at first; the
   * injector must keep retrying rather than declaring victory after one request.
   */
  @Test
  void boundedRestore_retriesUntilTheSubscriptionIsConfirmedBack() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    // One confirmation window polls at most ~25 times (5s deadline / 200ms sleep), so staying down
    // for 30 probes guarantees the FIRST attempt fails and a retry is required. Count-based rather
    // than time-based on purpose: a slow machine yields FEWER polls per window, which only adds
    // attempts — it can never accidentally confirm on attempt 1 and make this pass vacuously.
    AtomicInteger probes = new AtomicInteger();
    when(engine.candleSubscriptionActive()).thenAnswer(invocation -> probes.incrementAndGet() > 30);
    SignalFaultInjector injector = injector();

    injector.injectSubscriptionStall(SignalFaultInjector.MIN_AUTO_RESTORE_MS);

    Awaitility.await()
        .atMost(Duration.ofSeconds(90))
        .untilAsserted(() -> verify(engine, atLeast(2)).forceResubscribe(anyString()));
    injector.shutdown();
  }

  /** The happy path still confirms on the first attempt — one request, no needless retry churn. */
  @Test
  void boundedRestore_confirmsOnTheFirstAttemptWhenTheRebuildWorks() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    when(engine.candleSubscriptionActive()).thenReturn(true);
    SignalFaultInjector injector = injector();

    injector.injectSubscriptionStall(SignalFaultInjector.MIN_AUTO_RESTORE_MS);

    Awaitility.await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> verify(engine).forceResubscribe(anyString()));
    injector.shutdown();
  }
}
