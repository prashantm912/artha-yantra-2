package in.arthayantra.strategysignal.signals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.strategysignal.signals.SubscriberHealthCanary.SubscriberStallAlert;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit-proves the subscriber watchdog's decision table. RECEIVE-STALL path: force-re-subscribe +
 * page ONLY when the engine has 1m subs, received no bar for {@code bar-gap-ms}, AND the feed is
 * provably fresh; silent on a feed outage, on healthy receipt, and when nothing is subscribed.
 * EVAL-STALL path (audit A13): page + capture the stack but do NOT re-subscribe when bars are
 * ARRIVING (receipt fresh) yet not evaluated for a full gap, and stay silent when a quiet market
 * freezes both heartbeats. Fixed clock at 10:00 IST on 2026-07-07 (a Tuesday NSE session).
 */
class SubscriberHealthCanaryTest {

  private static final long BAR_GAP = 180_000;
  private static final long FEED_FRESH = 90_000;
  // 2026-07-07T04:30:00Z == 10:00 IST, an open NSE session.
  private static final Instant IN_SESSION = Instant.parse("2026-07-07T04:30:00Z");
  private static final Clock CLOCK = Clock.fixed(IN_SESSION, ZoneOffset.UTC);
  private static final long NOW_MS = IN_SESSION.toEpochMilli();

  private final SignalEngine engine = mock(SignalEngine.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final SubscriberHealthTelemetry telemetry = mock(SubscriberHealthTelemetry.class);

  private SubscriberHealthCanary canary(boolean enabled) {
    return new SubscriberHealthCanary(
        engine, redis, events, telemetry, CLOCK, enabled, BAR_GAP, FEED_FRESH);
  }

  /** Eval keeps pace with receipt (evalLag ~0) — so the eval-stall branch never trips these tests. */
  private void evalKeepingUp(long receivedAtMs) {
    when(engine.lastBarReceivedAtMs()).thenReturn(receivedAtMs);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(receivedAtMs);
  }

  private void feedAgeMs(long ageMs) {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - ageMs));
  }

  @Test
  void freshFeedButNoBarReceived_resubscribesAndPagesOnce() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000); // 200s > 180s gap, eval kept pace before the drop
    feedAgeMs(10_000); // feed ticked 10s ago — provably alive

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep(); // second pass, same fixed clock: latched — must NOT re-page or re-subscribe

    verify(engine, times(1)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    // durable telemetry: one receive-stall row + one resubscribe row (latched → not repeated)
    verify(telemetry, times(1)).record(eq("receive-stall"), anyString());
    verify(telemetry, times(1)).record(eq("resubscribe"), anyString());
  }

  @Test
  void feedAlsoStale_doesNotActOrPage() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    feedAgeMs(200_000); // feed itself is stale — market-data's canary owns this, not us

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void feedHeartbeatMissing_doesNotAct() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(null); // unknown ⇒ conservative: stay quiet

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void receivingNormally_noAction() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    evalKeepingUp(NOW_MS - 30_000); // 30s < 180s gap

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void noOneMinuteSubscriptions_noAction() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(false); // nothing to receive

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  @Test
  void disabled_noAction() {
    canary(false).sweep();

    verify(engine, never()).hasOneMinuteSubscriptions();
    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
  }

  /** Still starved a full window later: re-subscribe is retried (throttled) but the page fires ONCE. */
  @Test
  void stillStarved_retriesResubscribeButDoesNotRepage() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    // heartbeats fixed in the past — receiveGap keeps growing as the clock advances (stays starved);
    // evaluated == received so evalLag is 0 (this is a RECEIVE drop, not an eval stall).
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenAnswer(inv -> Long.toString(advancing.millis() - 10_000));

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(
            engine, redis, events, telemetry, advancing, true, BAR_GAP, FEED_FRESH);
    c.sweep(); // first detection: re-subscribe #1 + page #1
    advancing.advanceMs(BAR_GAP); // a full window later, still no bar
    c.sweep(); // retry: re-subscribe #2, NO repeat page

    verify(engine, times(2)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    verify(telemetry, times(2)).record(eq("resubscribe"), anyString()); // one row per attempt
  }

  /**
   * EVAL STALL (audit A13): bars ARRIVING (receipt fresh) but the signal-eval thread stopped
   * processing for a full gap. Must page + capture the stack, but must NOT re-subscribe (a blocked
   * eval thread cannot be fixed that way), and must page only ONCE per episode.
   */
  @Test
  void evalStalled_pagesAndDumpsButNeverResubscribes() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 5_000); // receipt FRESH — bars arriving
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000); // eval frozen ⇒ evalLag 195s > gap

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep(); // latched — no re-page

    verify(engine, never()).forceResubscribe(anyString()); // a stalled eval thread is NOT re-subscribed
    verify(events, times(1)).publishEvent(any(SubscriberStallAlert.class));
    verify(telemetry, times(1)).record(eq("eval-stall"), anyString());
  }

  /** Quiet market: both heartbeats frozen together (evalLag ~0) — the eval alarm must stay silent. */
  @Test
  void quietMarketBothFrozen_noEvalAlarm() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000); // frozen
    when(engine.lastBarEvaluatedAtMs()).thenReturn(NOW_MS - 200_000); // frozen ≈ received ⇒ evalLag 0
    feedAgeMs(200_000); // feed also quiet (no ticks) — nothing to act on

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(any());
    verify(telemetry, never()).record(eq("eval-stall"), anyString());
  }

  /** A test clock whose instant can be advanced, to drive the multi-sweep throttle path. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advanceMs(long ms) {
      this.instant = this.instant.plusMillis(ms);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
