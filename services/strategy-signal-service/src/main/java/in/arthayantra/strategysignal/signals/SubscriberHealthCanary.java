package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Subscriber-side liveness watchdog for the live signal engine's {@code candles.1m.*} Redis
 * subscription (audit {@code signal-eval-redis-subscriber-watchdog}; RCA
 * {@code docs/signal-analysis/2026-07-07-session-findings.md} §8).
 *
 * <p>On 2026-07-07 the {@code signal-eval} loop went silent twice mid-session while the feed stayed
 * healthy — NOT an eval-thread hang and NOT a feed death: {@link SignalEngine}'s
 * {@code RedisMessageListenerContainer} silently dropped its subscription (once recovered, once did
 * not), so the eval executor starved with no error logged and no alert. The market-data
 * {@code DataHealthCanary} could not catch it because it watches bar CLOSES on the PRODUCER side, not
 * consumer receipt.
 *
 * <p>Every minute in-session, if the engine has 1m subscriptions yet has received no candle for
 * {@code bar-gap-ms} WHILE the feed is provably alive (market-data's {@code ticks:last-at} heartbeat
 * is fresh within {@code feed-fresh-ms}), this force-re-subscribes (overlap-safe) and pages ntfy. The
 * feed-fresh cross-check is the discriminator: a genuine feed outage is the market-data canary's job,
 * so this stays silent then (no false page, no pointless re-subscribe churn). Reading
 * {@code ticks:last-at} on the shared Redis keeps it HTTP-free. Safety net, default ON — a
 * default-OFF net that nobody arms is exactly how the original stall stayed silent; disable via
 * {@code artha.signal.subscriber-watchdog.enabled=false}.
 *
 * <p><b>Why one global heartbeat is sufficient (not per-channel):</b> {@link SignalEngine} subscribes
 * every candle channel through a SINGLE {@code RedisMessageListenerContainer} on one connection
 * ({@code resubscribe()} builds one container + N listeners on one {@code connectionFactory}), so
 * Redis pub/sub multiplexes all channels over that one subscription — a connection drop takes down
 * ALL channels together, never one in isolation. The 2026-07-07 incident confirms this empirically:
 * NIFTY and SENSEX scalper evaluation both stopped at the SAME instant (14:22:45), not NIFTY-only. So
 * the global {@code lastBarReceivedAtMs} (stamped by any channel) stales exactly when the container
 * drops, and stays fresh while any liquid future (always in a loaded scalper universe) keeps
 * delivering — which is also why a quiet minute can't false-page. Revisit per-channel tracking only
 * if the engine ever moves to per-channel connections.
 */
@Component
public class SubscriberHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(SubscriberHealthCanary.class);
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 20); // after warmup + the first 1m bars
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);
  private static final String FEED_HEARTBEAT_KEY = "ticks:last-at"; // epoch millis, written per tick

  /** In-process alert event — the notifier module listens (signals must not import notifier). */
  public record SubscriberStallAlert(String title, String message) {}

  private final SignalEngine engine;
  private final StringRedisTemplate redis;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final long barGapMs;
  private final long feedFreshMs;

  // Single-writer (the @Scheduled thread never overlaps under fixedDelay); volatile for test/read.
  private volatile boolean stalled;
  private long lastResubscribeAtMs;

  /** Wires the engine, the shared Redis, the event bus, and the (tunable) gap/freshness thresholds. */
  public SubscriberHealthCanary(
      SignalEngine engine,
      StringRedisTemplate redis,
      ApplicationEventPublisher events,
      Clock clock,
      @Value("${artha.signal.subscriber-watchdog.enabled:true}") boolean enabled,
      @Value("${artha.signal.subscriber-watchdog.bar-gap-ms:180000}") long barGapMs,
      @Value("${artha.signal.subscriber-watchdog.feed-fresh-ms:90000}") long feedFreshMs) {
    this.engine = engine;
    this.redis = redis;
    this.events = events;
    this.clock = clock;
    this.enabled = enabled;
    this.barGapMs = barGapMs;
    this.feedFreshMs = feedFreshMs;
  }

  /** The per-minute in-session receive-gap check. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
  public void sweep() {
    try {
      if (!enabled) {
        return;
      }
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      if (!inSession(now) || !engine.hasOneMinuteSubscriptions()) {
        return; // out of session, or nothing to receive — no false alarm
      }
      long nowMs = clock.millis();
      long barGap = nowMs - engine.lastBarReceivedAtMs();
      if (barGap < barGapMs) {
        if (stalled) {
          stalled = false;
          log.info("subscriber watchdog: candle receipt RECOVERED (last bar {}s ago)", barGap / 1000);
        }
        return; // receiving normally
      }
      long feedAge = feedAgeMs(nowMs);
      if (feedAge > feedFreshMs) {
        return; // the FEED itself is stale/down/unreadable — market-data's canary owns that
      }
      // Feed provably fresh but this consumer received no bar for barGap ⇒ the subscription dropped.
      String detail =
          "no candle received for " + (barGap / 1000) + "s while the feed is live (ticks "
              + (feedAge / 1000) + "s old) — Redis candles.1m subscription dropped; re-subscribing";
      if (!stalled) {
        // First detection: latch, re-subscribe, and page ONCE.
        stalled = true;
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: signal engine STARVED — {}", detail);
        engine.forceResubscribe(detail);
        publish(detail);
      } else if (nowMs - lastResubscribeAtMs >= barGapMs) {
        // Still starved after a full window: retry the re-subscribe (throttled), no repeat page.
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: still STARVED — retrying re-subscription ({})", detail);
        engine.forceResubscribe(detail);
      }
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog sweep failed: {}", e.toString());
    }
  }

  private void publish(String detail) {
    try {
      events.publishEvent(
          new SubscriberStallAlert(
              "ArthaYantra subscriber watchdog: signal engine STARVED",
              detail + " (live scalper eval was silently stalled)."));
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog alert failed: {}", e.getMessage());
    }
  }

  /** ms since market-data's {@code ticks:last-at} heartbeat; MAX ⇒ unknown/down: DO NOT act. */
  private long feedAgeMs(long nowMs) {
    try {
      String raw = redis.opsForValue().get(FEED_HEARTBEAT_KEY);
      if (raw == null) {
        return Long.MAX_VALUE;
      }
      return Math.max(0, nowMs - Long.parseLong(raw));
    } catch (RuntimeException unreadable) {
      return Long.MAX_VALUE; // can't confirm the feed is alive ⇒ stay quiet (conservative)
    }
  }

  private boolean inSession(ZonedDateTime now) {
    try {
      return calendar.isOpen(now.toInstant())
          && !now.toLocalTime().isBefore(ARMED_FROM)
          && now.toLocalTime().isBefore(SESSION_END);
    } catch (IllegalArgumentException uncoveredYear) {
      return false; // the calendar cliff has its own canary
    }
  }
}
