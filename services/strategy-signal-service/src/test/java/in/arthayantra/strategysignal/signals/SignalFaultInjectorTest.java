package in.arthayantra.strategysignal.signals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Unit-proves the injector's bounded-restore arithmetic and its refusal path. The end-to-end fire
 * path (inject → watchdog detects → re-subscribes → pages → writes the row) is proved against a real
 * engine, Redis and Postgres in {@link SubscriberFaultInjectionIntegrationTest}.
 */
class SignalFaultInjectorTest {

  private final SignalEngine engine = mock(SignalEngine.class);

  @Test
  void nullAutoRestore_usesTheDefault() {
    assertThat(SignalFaultInjector.clampAutoRestore(null))
        .isEqualTo(SignalFaultInjector.DEFAULT_AUTO_RESTORE_MS);
  }

  /** The whole point of the clamp: a forgotten or fat-fingered drill cannot outlive a session. */
  @Test
  void absurdlyLongAutoRestore_isClampedToTheCeiling() {
    assertThat(SignalFaultInjector.clampAutoRestore(Long.MAX_VALUE))
        .isEqualTo(SignalFaultInjector.MAX_AUTO_RESTORE_MS);
    assertThat(SignalFaultInjector.clampAutoRestore(86_400_000L))
        .isEqualTo(SignalFaultInjector.MAX_AUTO_RESTORE_MS);
  }

  /** Zero/negative must not mean "restore immediately" — the drill needs a window to be observed. */
  @Test
  void zeroOrNegativeAutoRestore_isClampedToTheFloor() {
    assertThat(SignalFaultInjector.clampAutoRestore(0L))
        .isEqualTo(SignalFaultInjector.MIN_AUTO_RESTORE_MS);
    assertThat(SignalFaultInjector.clampAutoRestore(-5_000L))
        .isEqualTo(SignalFaultInjector.MIN_AUTO_RESTORE_MS);
  }

  @Test
  void inRangeAutoRestore_isPassedThrough() {
    assertThat(SignalFaultInjector.clampAutoRestore(30_000L)).isEqualTo(30_000L);
  }

  /** No container to stop ⇒ report it honestly and schedule nothing to "restore". */
  @Test
  void noListenerContainer_reportsNotInjectedAndNeverResubscribes() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(false);
    SignalFaultInjector injector = new SignalFaultInjector(engine);

    SignalFaultInjector.SubscriptionStallInjection result =
        injector.injectSubscriptionStall(30_000L);

    assertThat(result.injected()).isFalse();
    assertThat(result.detail()).contains("no active listener container");
    verify(engine, never()).forceResubscribe(anyString());
    injector.shutdown();
  }

  /** A successful injection reports the clamped window it actually scheduled. */
  @Test
  void successfulInjection_reportsTheClampedWindow() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    SignalFaultInjector injector = new SignalFaultInjector(engine);

    SignalFaultInjector.SubscriptionStallInjection result =
        injector.injectSubscriptionStall(Long.MAX_VALUE);

    assertThat(result.injected()).isTrue();
    assertThat(result.autoRestoreMs()).isEqualTo(SignalFaultInjector.MAX_AUTO_RESTORE_MS);
    verify(engine).suspendCandleSubscriptionForFaultDrill();
    injector.shutdown();
  }

  /** The bounded auto-restore really fires (floor is 1s, so this is a real wait, not a mocked one). */
  @Test
  void boundedAutoRestore_forcesResubscribeWithoutTheWatchdog() {
    when(engine.suspendCandleSubscriptionForFaultDrill()).thenReturn(true);
    SignalFaultInjector injector = new SignalFaultInjector(engine);

    injector.injectSubscriptionStall(SignalFaultInjector.MIN_AUTO_RESTORE_MS);

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(10))
        .untilAsserted(() -> verify(engine).forceResubscribe(anyString()));
    injector.shutdown();
  }
}
