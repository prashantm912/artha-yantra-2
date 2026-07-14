package in.arthayantra.strategysignal.signals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** Unit-proves the evaluates-fast-but-emits-nothing starvation watchdog signature. */
class SignalStarvationCanaryTest {

  private static final long BAR_GAP = 180_000;
  private static final long WINDOW = 300_000;
  // 2026-07-07T04:30:00Z == 10:00 IST, an open NSE session.
  private static final Instant IN_SESSION = Instant.parse("2026-07-07T04:30:00Z");
  private static final Clock CLOCK = Clock.fixed(IN_SESSION, ZoneOffset.UTC);
  private static final long NOW_MS = IN_SESSION.toEpochMilli();

  private final SignalEngine engine = mock(SignalEngine.class);
  private final SubscriberHealthTelemetry telemetry = mock(SubscriberHealthTelemetry.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

  private SignalStarvationCanary canary(Clock clock) {
    return new SignalStarvationCanary(
        engine, events, telemetry, clock, true, BAR_GAP, WINDOW);
  }

  private void barsFreshAndEvalKeepingUp(long outputAtMs) {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 5_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 5_000);
    when(engine.lastGateOutputAtMs()).thenReturn(outputAtMs);
  }

  @Test
  void staleOutputLatchesRecoversAndRearms() {
    barsFreshAndEvalKeepingUp(NOW_MS - WINDOW - 1);
    when(engine.lastGateOutputAtMs())
        .thenReturn(
            NOW_MS - WINDOW - 1,
            NOW_MS - WINDOW - 1,
            NOW_MS - WINDOW,
            NOW_MS - WINDOW - 1);

    SignalStarvationCanary c = canary(CLOCK);
    c.sweep();
    c.sweep();
    c.sweep();
    c.sweep();

    verify(events, times(2)).publishEvent(any(StarvationAlert.class));
    verify(telemetry, times(2)).record(eq("output-stall"), anyString());
    verify(telemetry, times(1)).record(eq("recovery"), anyString());
  }

  @Test
  void uninitializedGateOutputDoesNotAlarm() {
    barsFreshAndEvalKeepingUp(0);

    canary(CLOCK).sweep();

    verify(events, never()).publishEvent(any());
    verify(telemetry, never()).record(anyString(), anyString());
  }

  @Test
  void outOfSessionOrReceiveStallDoesNotAlarm() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 5_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 5_000);
    when(engine.lastGateOutputAtMs()).thenReturn(NOW_MS - WINDOW - 1);

    canary(Clock.fixed(Instant.parse("2026-07-12T04:30:00Z"), ZoneOffset.UTC)).sweep();

    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - BAR_GAP);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - BAR_GAP);
    canary(CLOCK).sweep();

    verify(events, never()).publishEvent(any());
    verify(telemetry, never()).record(anyString(), anyString());
  }

  @Test
  void evalStallDoesNotAlarm() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 5_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 5_000 - BAR_GAP);
    when(engine.lastGateOutputAtMs()).thenReturn(NOW_MS - WINDOW - 1);

    canary(CLOCK).sweep();

    verify(events, never()).publishEvent(any());
    verify(telemetry, never()).record(eq("output-stall"), anyString());
  }
}
