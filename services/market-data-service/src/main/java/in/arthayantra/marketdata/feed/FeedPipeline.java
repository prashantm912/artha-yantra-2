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

  // Serializes ALL feed-lifecycle transitions (start / startFeed / restartFeed / stop). Since the
  // BEJ-01 scheduler split, FeedWatchdog.restartFeed() runs on the monitor thread while the morning
  // start (TickerSchedule) and SmartLifecycle start/stop run on the default thread — pre-split they
  // shared one scheduler thread and could never interleave. Without this lock a restart's
  // stop+start can interleave a concurrent start, overwriting normalizerThread and leaking a
  // second, un-interruptible tick-normalizer thread — a silent violation of the single-writer
  // guarantee. restartFeed holds the lock across stopUnderLock()+startUnderLock() as ONE atomic
  // critical section. A dedicated ReentrantLock (NOT synchronized(this)) keeps the monitor private;
  // no async/hot path (fanOut, the connection-state callback) ever takes it. The Redis status writes
  // are performed OUTSIDE this lock (writeSeedStatus / writeDisconnectedStatus) so a hung loopback
  // Redis (bounded by the ~60s Lettuce command timeout) can never hold the lock and stall a
  // concurrent restartFeed self-heal — and are swallowed so a Redis failure never propagates out of
  // a lifecycle transition.
  private final java.util.concurrent.locks.ReentrantLock lifecycleLock =
      new java.util.concurrent.locks.ReentrantLock();

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
    String sessionStatus;
    lifecycleLock.lock();
    try {
      if (running) {
        return;
      }
      sessionStatus = startUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
    // Status I/O runs OUTSIDE the lock (see writeSeedStatus): a hung loopback Redis must never hold
    // lifecycleLock and block a concurrent FeedWatchdog.restartFeed self-heal.
    writeSeedStatus(sessionStatus);
  }

  /**
   * Core start transition — caller MUST hold {@link #lifecycleLock}; precondition {@code !running}.
   * Mutates ONLY the serialized lifecycle state (running flag, connection-state callback, normalizer
   * thread, MarketFeed handle) and does NO Redis I/O. Returns the session status label the caller
   * seeds into Redis after releasing the lock.
   */
  private String startUnderLock() {
    running = true;
    String sessionStatus = sessionGateway.statusLabel();
    marketFeed.onConnectionState(
        connected -> writeTickerStatus(connected ? "CONNECTED" : "DISCONNECTED"));
    normalizerThread = new Thread(this::normalizerLoop, "tick-normalizer");
    normalizerThread.setDaemon(true);
    normalizerThread.start();
    marketFeed.start(ingressQueue);
    log.info("feed pipeline started (status={})", sessionStatus);
    return sessionStatus;
  }

  /**
   * Seeds the canonical session/ticker status keys after a start — performed OUTSIDE
   * {@link #lifecycleLock} so a slow/failing loopback Redis (bounded by the ~60s Lettuce command
   * timeout) can never hold the lock and stall a concurrent lifecycle transition, and swallowed so a
   * Redis failure never propagates out of the transition.
   */
  private void writeSeedStatus(String sessionStatus) {
    try {
      redis.opsForValue().set(SESSION_STATUS_KEY, sessionStatus);
      // Seeded CONNECTING; the feed's connect/disconnect callbacks own the CONNECTED/DISCONNECTED
      // transitions (an eager CONNECTED write here asserted a socket that a stale-token 403 never
      // opened — the status surface lied all day exactly when it mattered).
      redis.opsForValue().set(TICKER_STATUS_KEY, "CONNECTING");
    } catch (RuntimeException redisGone) {
      log.warn("feed status seed write failed: {}", redisGone.getMessage());
    }
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
    String sessionStatus;
    lifecycleLock.lock();
    try {
      if (!running) {
        return;
      }
      log.info("re-arming feed after session change");
      // One atomic critical section (stop then start) — a concurrent lifecycle transition must not
      // slip between the two halves. Composed from the *UnderLock cores directly, so no Redis I/O
      // happens while the lock is held.
      stopUnderLock();
      sessionStatus = startUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
    // Status I/O OUTSIDE the lock — this IS the self-heal path, so it must never block on a hung
    // Redis while holding lifecycleLock. Mirrors stop()+startFeed()'s write order.
    writeDisconnectedStatus();
    writeSeedStatus(sessionStatus);
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
    lifecycleLock.lock();
    try {
      stopUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
    // Status I/O OUTSIDE the lock (see writeDisconnectedStatus).
    writeDisconnectedStatus();
  }

  /**
   * Core stop transition — caller MUST hold {@link #lifecycleLock}. Mutates ONLY the serialized
   * lifecycle state (running flag, MarketFeed handle, normalizer thread) and does NO Redis I/O.
   */
  private void stopUnderLock() {
    running = false;
    marketFeed.stop();
    if (normalizerThread != null) {
      normalizerThread.interrupt();
    }
  }

  /**
   * Writes the DISCONNECTED ticker status after a stop — performed OUTSIDE {@link #lifecycleLock}
   * so a hung Redis cannot stall a concurrent lifecycle transition, and swallowed on failure.
   */
  private void writeDisconnectedStatus() {
    try {
      redis.opsForValue().set(TICKER_STATUS_KEY, "DISCONNECTED");
    } catch (RuntimeException redisGone) {
      log.debug("ticker status write skipped on shutdown: {}", redisGone.getMessage());
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
