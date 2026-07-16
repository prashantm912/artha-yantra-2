package in.arthayantra.strategysignal.signals;

import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.strategysignal.registry.StrategyRepository;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>Every minute in-session, if enabled+published strategies exist yet the engine has received no
 * candle for {@code bar-gap-ms} WHILE the feed is provably alive (market-data's
 * {@code ticks:last-at} heartbeat is fresh within {@code feed-fresh-ms}), this force-re-subscribes
 * (overlap-safe) and pages ntfy. The
 * feed-fresh cross-check is the discriminator: a genuine feed outage is the market-data canary's job,
 * so this stays silent then (no false page, no pointless re-subscribe churn). Reading
 * {@code ticks:last-at} on the shared Redis keeps it HTTP-free. Safety net, default ON — a
 * default-OFF net that nobody arms is exactly how the original stall stayed silent; disable via
 * {@code artha.signals.subscriber-watchdog.enabled=false}.
 *
 * <p><b>Eval-side blind spot (audit A13, RC-1, confirmed 2026-07-10):</b> the receive heartbeat
 * ({@code lastBarReceivedAtMs}) is stamped on the Redis DISPATCH thread as the first line of
 * {@code onCandleMessage}, but evaluation runs on a SEPARATE single-thread {@code signal-eval}
 * executor. So a stall INSIDE evaluation keeps the receive heartbeat fresh — the 14:52-IST stall
 * that day paged nobody because the canary read "receiving normally" the whole time. This sweep also
 * compares {@code lastBarReceivedAtMs − lastBarEvaluatedAtMs}: an EVAL stall is bars ARRIVING but not
 * processed for a full {@code bar-gap-ms}. It is compared receipt-to-eval (NOT to wall-clock) so a
 * quiet market that freezes both heartbeats never false-alarms. A blocked eval thread cannot be fixed
 * by re-subscribing (the rebuild would queue behind the block), so on an eval stall this does NOT
 * re-subscribe — it logs a distinct ERROR, captures the {@code signal-eval} stack trace (the forensic
 * evidence the wiped 15:57 logs cost us), pages with a distinct title, and latches once per episode.
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
@ConditionalOnProperty(
    value = "artha.signals.engine-enabled",
    havingValue = "true",
    matchIfMissing = true) // shares SignalEngine's lifecycle — meaningless (and unwireable) without it
public class SubscriberHealthCanary {

  private static final Logger log = LoggerFactory.getLogger(SubscriberHealthCanary.class);
  private static final LocalTime ARMED_FROM = LocalTime.of(9, 20); // after warmup + the first 1m bars
  private static final LocalTime SESSION_END = LocalTime.of(15, 30);
  private static final String FEED_HEARTBEAT_KEY = "ticks:last-at"; // epoch millis, written per tick

  /** In-process alert event — the notifier module listens (signals must not import notifier). */
  public record SubscriberStallAlert(String title, String message) {}

  private final SignalEngine engine;
  private final StrategyRepository registry;
  private final StringRedisTemplate redis;
  private final ApplicationEventPublisher events;
  private final SubscriberHealthTelemetry telemetry;
  private final Clock clock;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final boolean enabled;
  private final long barGapMs;
  private final long feedFreshMs;

  // Single-writer (the @Scheduled thread never overlaps under fixedDelay); volatile for test/read.
  private volatile boolean stalled; // receive-side latch (subscription drop)
  private volatile boolean evalStalled; // eval-side latch (bars arriving, not processed)
  private long lastResubscribeAtMs;

  /** Wires the engine, the shared Redis, the event bus, telemetry, and the (tunable) thresholds. */
  public SubscriberHealthCanary(
      SignalEngine engine,
      StrategyRepository registry,
      StringRedisTemplate redis,
      ApplicationEventPublisher events,
      SubscriberHealthTelemetry telemetry,
      Clock clock,
      @Value("${artha.signals.subscriber-watchdog.enabled:true}") boolean enabled,
      @Value("${artha.signals.subscriber-watchdog.bar-gap-ms:180000}") long barGapMs,
      @Value("${artha.signals.subscriber-watchdog.feed-fresh-ms:90000}") long feedFreshMs) {
    this.engine = engine;
    this.registry = registry;
    this.redis = redis;
    this.events = events;
    this.telemetry = telemetry;
    this.clock = clock;
    this.enabled = enabled;
    this.barGapMs = barGapMs;
    this.feedFreshMs = feedFreshMs;
  }

  /** The per-minute in-session receive-gap + eval-gap check. */
  @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
  public void sweep() {
    try {
      if (!enabled) {
        return;
      }
      ZonedDateTime now = clock.instant().atZone(Ist.ZONE);
      if (!inSession(now) || registry.countEnabledPublished() == 0) {
        return; // out of session, or genuinely idle by registry intent
      }
      long nowMs = clock.millis();
      long received = engine.lastBarReceivedAtMs();
      long evaluated = engine.lastBarEvaluatedAtMs();
      long receiveGap = nowMs - received; // wall-clock since the last bar RECEIVED
      long evalLag = received - evaluated; // receipt-vs-eval (NOT wall-clock: quiet market freezes both)

      // (1) EVAL STALL — bars ARRIVING but the signal-eval thread is not processing them. Checked
      // FIRST and independently of the receive path: during an eval block receipt is FRESH, so the
      // "receiving normally" early-return below would otherwise mask it entirely. A pure receive-drop
      // can't trip this (evaluated tracks received to ~0 once the last drain completes), and both-frozen
      // quiet markets give evalLag ~0 — so this only fires on the real bars-arriving-not-processed
      // signature. A blocked eval thread cannot be fixed by re-subscribing, so NO re-subscribe here.
      if (evalLag >= barGapMs) {
        if (!evalStalled) {
          evalStalled = true;
          String detail =
              "signal-eval STALLED — bars arriving but not evaluated for " + (evalLag / 1000)
                  + "s (receipt " + (receiveGap / 1000) + "s old)";
          log.error("subscriber watchdog: {}", detail);
          logEvalThreadStack(); // forensic evidence the wiped 15:57 logs cost us (audit A13)
          telemetry.record("eval-stall", detail);
          publish(
              "ArthaYantra subscriber watchdog: signal-eval STALLED",
              detail + " — NOT re-subscribing (a blocked eval thread cannot be fixed by re-subscribe).");
        }
        return; // pure eval stall: no re-subscribe attempt
      }
      if (evalStalled) {
        evalStalled = false;
        log.info("subscriber watchdog: signal-eval RECOVERED (eval lag {}s)", evalLag / 1000);
        telemetry.record("recovery", "signal-eval caught up (lag " + (evalLag / 1000) + "s)");
      }

      // (2) RECEIVE GAP (the 2026-07-07 path) — the container silently dropped its subscription.
      if (receiveGap < barGapMs) {
        if (stalled) {
          stalled = false;
          log.info(
              "subscriber watchdog: candle receipt RECOVERED (last bar {}s ago)", receiveGap / 1000);
          telemetry.record("recovery", "candle receipt recovered (" + (receiveGap / 1000) + "s)");
        }
        return; // receiving normally
      }
      long feedAge = feedAgeMs(nowMs);
      if (feedAge != Long.MAX_VALUE && feedAge > feedFreshMs) {
        return; // a known stale producer is market-data's ownership
      }
      // Feed is fresh, or its heartbeat is unavailable; this consumer received no bar for receiveGap.
      String detail =
          "no candle received for " + (receiveGap / 1000) + "s while the feed is live (ticks "
              + (feedAge / 1000) + "s old) — Redis candles.1m subscription dropped; re-subscribing";
      if (!stalled) {
        if (feedAge == Long.MAX_VALUE) {
          detail =
              "no candle received for "
                  + (receiveGap / 1000)
                  + "s while the feed heartbeat is unavailable; Redis candles.1m subscription is suspicious; re-subscribing";
        }
        // First detection: latch, re-subscribe, and page ONCE.
        stalled = true;
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: signal engine STARVED — {}", detail);
        telemetry.record("receive-stall", detail);
        engine.forceResubscribe(detail);
        telemetry.record("resubscribe", detail);
        publish(
            "ArthaYantra subscriber watchdog: signal engine STARVED",
            detail + " (live scalper eval was silently stalled).");
      } else if (nowMs - lastResubscribeAtMs >= barGapMs) {
        // Still starved after a full window: retry the re-subscribe (throttled), no repeat page.
        lastResubscribeAtMs = nowMs;
        log.error("subscriber watchdog: still STARVED — retrying re-subscription ({})", detail);
        engine.forceResubscribe(detail);
        telemetry.record("resubscribe", detail);
      }
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog sweep failed: {}", e.toString());
    }
  }

  private void publish(String title, String message) {
    try {
      events.publishEvent(new SubscriberStallAlert(title, message));
    } catch (RuntimeException e) {
      log.warn("subscriber watchdog alert failed: {}", e.getMessage());
    }
  }

  /**
   * Dumps the {@code signal-eval} thread's stack at ERROR — the forensic evidence a container
   * re-create would otherwise erase (audit A13). Best-effort: never throws into the sweep.
   */
  private void logEvalThreadStack() {
    try {
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        if ("signal-eval".equals(entry.getKey().getName())) {
          StringBuilder sb =
              new StringBuilder("signal-eval thread stack (state=")
                  .append(entry.getKey().getState())
                  .append("):");
          for (StackTraceElement frame : entry.getValue()) {
            sb.append("\n\tat ").append(frame);
          }
          log.error(sb.toString());
          return;
        }
      }
      log.error("subscriber watchdog: signal-eval thread not found in the live thread set");
    } catch (RuntimeException dumpFailed) {
      log.warn("subscriber watchdog: signal-eval stack capture failed: {}", dumpFailed.toString());
    }
  }

  /** Age in ms of market-data's {@code ticks:last-at} heartbeat. Returns MAX when unknown/down. */
  private long feedAgeMs(long nowMs) {
    try {
      String raw = redis.opsForValue().get(FEED_HEARTBEAT_KEY);
      if (raw == null) {
        return Long.MAX_VALUE;
      }
      return Math.max(0, nowMs - Long.parseLong(raw));
    } catch (RuntimeException unreadable) {
      log.warn("subscriber watchdog: feed heartbeat unreadable; treating it as suspicious");
      return Long.MAX_VALUE; // unknown feed age is suspicious; the caller must not stay quiet
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
