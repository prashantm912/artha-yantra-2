package in.arthayantra.marketdata.feed;

import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Feed-liveness watchdog (audit P1-12): a half-open WS freeze fires no disconnect callback, so the
 * gap scan (which runs only from {@code onConnected}) never triggered and the container stayed
 * "healthy" while candles silently stopped — no bars, no bar-driven exit evaluation, detection
 * fully manual. Every 60 s during a live session: if {@code ticks:last-at} (written on every
 * published tick) is older than the stale threshold, restart the feed ({@code restartFeed()}
 * rebuilds the handle with the current token → {@code onConnected} → registry replay + gap
 * backfill) and push an ops ntfy alert. A restart cooldown stops a token-less morning (or a dead
 * broker) from thrashing the socket; LIVE profile only — the mock feed is synthetic.
 */
@Component
@Profile("live")
public class FeedWatchdog {

  private static final Logger log = LoggerFactory.getLogger(FeedWatchdog.class);

  static final Duration STALE_AFTER = Duration.ofMinutes(3);
  static final Duration RESTART_COOLDOWN = Duration.ofMinutes(10);

  private final StringRedisTemplate redis;
  private final FeedPipeline pipeline;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final Clock clock;

  private volatile Instant lastRestart = Instant.EPOCH;

  /** Wires the shared-state Redis, the feed lifecycle and the ops alert channel. */
  public FeedWatchdog(StringRedisTemplate redis, FeedPipeline pipeline, NtfyClient ntfy) {
    this(redis, pipeline, ntfy, Clock.systemUTC());
  }

  FeedWatchdog(StringRedisTemplate redis, FeedPipeline pipeline, NtfyClient ntfy, Clock clock) {
    this.redis = redis;
    this.pipeline = pipeline;
    this.ntfy = ntfy;
    this.clock = clock;
  }

  /** The 60 s liveness check; initial delay covers the morning login/boot window. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 180_000)
  public void check() {
    try {
      if (!calendar.isOpen(clock.instant())) {
        return; // off-session silence is normal
      }
    } catch (IllegalArgumentException uncoveredYear) {
      return; // the calendar cliff has its own canary — never let the watchdog throw
    }
    long ageMs = lastTickAgeMs();
    if (ageMs < STALE_AFTER.toMillis()) {
      return;
    }
    if (clock.instant().isBefore(lastRestart.plus(RESTART_COOLDOWN))) {
      return; // already kicked recently — a token-less feed can't be revived by thrashing
    }
    lastRestart = clock.instant();
    String detail =
        "market open but the last tick is "
            + (ageMs == Long.MAX_VALUE ? "absent since boot" : ageMs / 1000 + "s old")
            + " — restarting the feed (registry replay + gap backfill follow on connect)";
    log.error("feed watchdog: {}", detail);
    try {
      ntfy.send("ArthaYantra feed stalled", "urgent", detail);
    } catch (Exception alertFailure) {
      log.warn("feed watchdog alert failed: {}", alertFailure.getMessage());
    }
    try {
      pipeline.restartFeed();
    } catch (Exception restartFailure) {
      log.error("feed watchdog restart failed: {}", restartFailure.getMessage());
    }
  }

  private long lastTickAgeMs() {
    try {
      String raw = redis.opsForValue().get("ticks:last-at");
      if (raw == null) {
        return Long.MAX_VALUE;
      }
      return Math.max(0, clock.millis() - Long.parseLong(raw));
    } catch (Exception unreadable) {
      return 0; // Redis down/garbled: the feed is degraded anyway — don't pile a restart on top
    }
  }
}
