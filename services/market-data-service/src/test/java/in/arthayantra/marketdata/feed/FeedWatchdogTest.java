package in.arthayantra.marketdata.feed;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.arthayantra.marketdata.alerts.NtfyClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Feed-liveness watchdog (audit P1-12): stale ticks during an open session restart the feed +
 * alert; fresh ticks, off-session silence, and the restart cooldown do nothing.
 */
class FeedWatchdogTest {

  // Wednesday 2026-07-01 11:00 IST = 05:30Z — a covered trading day, mid-session.
  private static final Instant OPEN_SESSION = Instant.parse("2026-07-01T05:30:00Z");
  // Same day 20:00 IST — market closed.
  private static final Instant OFF_SESSION = Instant.parse("2026-07-01T14:30:00Z");

  @SuppressWarnings("unchecked")
  private static StringRedisTemplate redisWithLastAt(String value) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get("ticks:last-at")).thenReturn(value);
    return redis;
  }

  @Test
  void staleTicksDuringAnOpenSessionRestartTheFeedAndAlert() {
    Clock clock = Clock.fixed(OPEN_SESSION, ZoneOffset.UTC);
    long fourMinAgo = clock.millis() - 4 * 60_000;
    FeedPipeline pipeline = mock(FeedPipeline.class);
    NtfyClient ntfy = mock(NtfyClient.class);

    new FeedWatchdog(redisWithLastAt(Long.toString(fourMinAgo)), pipeline, ntfy, clock).check();

    verify(pipeline).restartFeed();
    verify(ntfy).send(anyString(), anyString(), anyString());
  }

  @Test
  void freshTicksDoNothing() {
    Clock clock = Clock.fixed(OPEN_SESSION, ZoneOffset.UTC);
    FeedPipeline pipeline = mock(FeedPipeline.class);

    new FeedWatchdog(redisWithLastAt(Long.toString(clock.millis() - 10_000)), pipeline,
            mock(NtfyClient.class), clock)
        .check();

    verify(pipeline, never()).restartFeed();
  }

  @Test
  void offSessionSilenceDoesNothing() {
    Clock clock = Clock.fixed(OFF_SESSION, ZoneOffset.UTC);
    FeedPipeline pipeline = mock(FeedPipeline.class);

    new FeedWatchdog(redisWithLastAt(null), pipeline, mock(NtfyClient.class), clock).check();

    verify(pipeline, never()).restartFeed();
  }

  @Test
  void theCooldownStopsRestartThrashing() {
    Clock clock = Clock.fixed(OPEN_SESSION, ZoneOffset.UTC);
    FeedPipeline pipeline = mock(FeedPipeline.class);
    FeedWatchdog watchdog =
        new FeedWatchdog(redisWithLastAt(null), pipeline, mock(NtfyClient.class), clock);

    watchdog.check(); // absent-since-boot ticks → restart
    watchdog.check(); // within the cooldown → no second kick

    verify(pipeline).restartFeed();
  }
}
