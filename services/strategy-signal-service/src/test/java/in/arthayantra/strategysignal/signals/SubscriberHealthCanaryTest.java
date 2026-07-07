package in.arthayantra.strategysignal.signals;

import static org.mockito.ArgumentMatchers.anyString;
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
 * Unit-proves the subscriber watchdog's decision table: it force-re-subscribes + pages ONLY when the
 * engine has 1m subs, has received no bar for {@code bar-gap-ms}, AND the feed is provably fresh; and
 * stays silent on a feed outage, on healthy receipt, and when nothing is subscribed. Fixed clock at
 * 10:00 IST on 2026-07-07 (a Tuesday NSE session).
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

  private SubscriberHealthCanary canary(boolean enabled) {
    return new SubscriberHealthCanary(engine, redis, events, CLOCK, enabled, BAR_GAP, FEED_FRESH);
  }

  private void feedAgeMs(long ageMs) {
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(Long.toString(NOW_MS - ageMs));
  }

  @Test
  void freshFeedButNoBarReceived_resubscribesAndPagesOnce() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000); // 200s > 180s gap
    feedAgeMs(10_000); // feed ticked 10s ago — provably alive

    SubscriberHealthCanary c = canary(true);
    c.sweep();
    c.sweep(); // second pass, same fixed clock: latched — must NOT re-page or re-subscribe

    verify(engine, times(1)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(SubscriberStallAlert.class));
  }

  @Test
  void feedAlsoStale_doesNotActOrPage() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    feedAgeMs(200_000); // feed itself is stale — market-data's canary owns this, not us

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void feedHeartbeatMissing_doesNotAct() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenReturn(null); // unknown ⇒ conservative: stay quiet

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void receivingNormally_noAction() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 30_000); // 30s < 180s gap

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void noOneMinuteSubscriptions_noAction() {
    when(engine.hasOneMinuteSubscriptions()).thenReturn(false); // nothing to receive

    canary(true).sweep();

    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void disabled_noAction() {
    canary(false).sweep();

    verify(engine, never()).hasOneMinuteSubscriptions();
    verify(engine, never()).forceResubscribe(anyString());
    verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
  }

  /** Still starved a full window later: re-subscribe is retried (throttled) but the page fires ONCE. */
  @Test
  void stillStarved_retriesResubscribeButDoesNotRepage() {
    MutableClock advancing = new MutableClock(IN_SESSION);
    when(engine.hasOneMinuteSubscriptions()).thenReturn(true);
    // heartbeat fixed in the past — barGap keeps growing as the clock advances (stays starved)
    when(engine.lastBarReceivedAtMs()).thenReturn(NOW_MS - 200_000);
    when(redis.opsForValue()).thenReturn(valueOps);
    when(valueOps.get("ticks:last-at")).thenAnswer(inv -> Long.toString(advancing.millis() - 10_000));

    SubscriberHealthCanary c =
        new SubscriberHealthCanary(engine, redis, events, advancing, true, BAR_GAP, FEED_FRESH);
    c.sweep(); // first detection: re-subscribe #1 + page #1
    advancing.advanceMs(BAR_GAP); // a full window later, still no bar
    c.sweep(); // retry: re-subscribe #2, NO repeat page

    verify(engine, times(2)).forceResubscribe(anyString());
    verify(events, times(1)).publishEvent(org.mockito.ArgumentMatchers.any(SubscriberStallAlert.class));
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
