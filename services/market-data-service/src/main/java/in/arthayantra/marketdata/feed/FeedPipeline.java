package in.arthayantra.marketdata.feed;

import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.SessionGateway;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Wires feed → ingress queue → single-writer normalizer → Redis publisher (A.7.1), and writes the
 * canonical {@code kite:session:status} key (COMMON §3) at startup. The normalizer loop is ONE
 * daemon thread — the single-writer guarantee behind monotonic per-instrument sequences.
 */
@Component
public class FeedPipeline implements SmartLifecycle, in.arthayantra.marketdata.kite.FeedRearm {

  /** Canonical Redis key for Kite session/ticker state (COMMON §3). */
  public static final String SESSION_STATUS_KEY = "kite:session:status";

  /** B-13 ticker sub-field: CONNECTING → CONNECTED/DISCONNECTED, driven by the socket callbacks. */
  public static final String TICKER_STATUS_KEY = "kite:ticker:status";

  private static final Logger log = LoggerFactory.getLogger(FeedPipeline.class);

  private final MarketFeed marketFeed;
  private final SessionGateway sessionGateway;
  private final IngressQueue ingressQueue;
  private final TickNormalizer normalizer;
  private final RedisTickPublisher publisher;
  private final LastTickStore lastTickStore;
  private final StringRedisTemplate redis;
  private final java.util.List<NormalizedTickListener> listeners;
  private final boolean autostart;

  private volatile boolean running;
  private Thread normalizerThread;

  public FeedPipeline(
      MarketFeed marketFeed,
      SessionGateway sessionGateway,
      IngressQueue ingressQueue,
      TickNormalizer normalizer,
      RedisTickPublisher publisher,
      LastTickStore lastTickStore,
      StringRedisTemplate redis,
      java.util.List<NormalizedTickListener> listeners,
      @Value("${artha.feed.autostart:true}") boolean autostart) {
    this.marketFeed = marketFeed;
    this.sessionGateway = sessionGateway;
    this.ingressQueue = ingressQueue;
    this.normalizer = normalizer;
    this.publisher = publisher;
    this.lastTickStore = lastTickStore;
    this.redis = redis;
    this.listeners = listeners;
    this.autostart = autostart;
  }

  @Override
  public void start() {
    if (!autostart) {
      return;
    }
    startFeed();
  }

  /** Explicit start (the Phase-16 09:10 ticker schedule) — bypasses the autostart gate. */
  public void startFeed() {
    if (running) {
      return;
    }
    running = true;
    redis.opsForValue().set(SESSION_STATUS_KEY, sessionGateway.statusLabel());
    // Seeded CONNECTING; the feed's connect/disconnect callbacks own the CONNECTED/DISCONNECTED
    // transitions (an eager CONNECTED write here asserted a socket that a stale-token 403 never
    // opened — the status surface lied all day exactly when it mattered).
    redis.opsForValue().set(TICKER_STATUS_KEY, "CONNECTING");
    marketFeed.onConnectionState(
        connected -> writeTickerStatus(connected ? "CONNECTED" : "DISCONNECTED"));
    normalizerThread = new Thread(this::normalizerLoop, "tick-normalizer");
    normalizerThread.setDaemon(true);
    normalizerThread.start();
    marketFeed.start(ingressQueue);
    log.info("feed pipeline started (status={})", sessionGateway.statusLabel());
  }

  /** Explicit stop (the Phase-16 15:35 ticker schedule). */
  public void stopFeed() {
    stop();
  }

  /**
   * Re-arm the ticker after a fresh login (B-2): cycle the feed so the handle is rebuilt with the
   * newly-stored access token. No-op unless the feed is already running — autostart/the 09:10 cron
   * own the first start, and a login must never start a feed that ops/tests deliberately left down.
   */
  @Override
  public void restartFeed() {
    if (!running) {
      return;
    }
    log.info("re-arming feed after session change");
    stop();
    startFeed();
  }

  private void normalizerLoop() {
    while (running) {
      try {
        RawTick raw = ingressQueue.poll(Duration.ofMillis(250));
        if (raw == null) {
          continue;
        }
        normalizer.normalize(raw).ifPresent(this::fanOut);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        log.error("normalizer loop error (continuing)", e);
      }
    }
  }

  /**
   * Per-tick fan-out: candle building must NEVER be lost to a Redis publish failure (and one
   * failing listener must never starve the others) — each step is isolated, so a brief Redis
   * hiccup degrades the WS bridge without dropping 1m bars from the store.
   */
  void fanOut(NormalizedTick tick) {
    lastTickStore.update(tick);
    try {
      publisher.publish(tick);
    } catch (RuntimeException publishFailure) {
      log.warn("tick publish failed (bar building continues): {}", publishFailure.getMessage());
    }
    for (NormalizedTickListener listener : listeners) {
      try {
        listener.onNormalizedTick(tick);
      } catch (RuntimeException listenerFailure) {
        log.warn(
            "tick listener {} failed: {}",
            listener.getClass().getSimpleName(),
            listenerFailure.getMessage());
      }
    }
  }

  private void writeTickerStatus(String state) {
    try {
      redis.opsForValue().set(TICKER_STATUS_KEY, state);
    } catch (RuntimeException redisGone) {
      log.warn("ticker status write failed: {}", redisGone.getMessage());
    }
  }

  @Override
  public void stop() {
    running = false;
    marketFeed.stop();
    try {
      redis.opsForValue().set(TICKER_STATUS_KEY, "DISCONNECTED");
    } catch (RuntimeException redisGone) {
      log.debug("ticker status write skipped on shutdown: {}", redisGone.getMessage());
    }
    if (normalizerThread != null) {
      normalizerThread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
