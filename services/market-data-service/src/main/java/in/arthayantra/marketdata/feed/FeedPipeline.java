package in.arthayantra.marketdata.feed;

import in.arthayantra.marketdata.kite.MarketFeed;
import in.arthayantra.marketdata.kite.RawTick;
import in.arthayantra.marketdata.kite.SessionGateway;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
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
  private final FeedStatusWriter statusWriter;
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
  // NEVER run under this lock: they go through FeedStatusWriter (a non-blocking enqueue), so a hung
  // loopback Redis can never hold the lock and stall a concurrent restartFeed self-heal — this holds
  // even for the connection-state callback, which fires SYNCHRONOUSLY inside marketFeed.start()/stop()
  // (MockMarketFeed / UpstoxMarketFeedClient) and would otherwise be a Redis call under the lock.
  private final java.util.concurrent.locks.ReentrantLock lifecycleLock =
      new java.util.concurrent.locks.ReentrantLock();

  public FeedPipeline(
      MarketFeed marketFeed,
      SessionGateway sessionGateway,
      IngressQueue ingressQueue,
      TickNormalizer normalizer,
      RedisTickPublisher publisher,
      LastTickStore lastTickStore,
      FeedStatusWriter statusWriter,
      java.util.List<NormalizedTickListener> listeners,
      @Value("${artha.feed.autostart:true}") boolean autostart) {
    this.marketFeed = marketFeed;
    this.sessionGateway = sessionGateway;
    this.ingressQueue = ingressQueue;
    this.normalizer = normalizer;
    this.publisher = publisher;
    this.lastTickStore = lastTickStore;
    this.statusWriter = statusWriter;
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
    lifecycleLock.lock();
    try {
      if (running) {
        return;
      }
      startUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Core start transition — caller MUST hold {@link #lifecycleLock}; precondition {@code !running}.
   * Mutates ONLY the serialized lifecycle state (running flag, connection-state callback, normalizer
   * thread, MarketFeed handle). Every Redis status write is a non-blocking {@link FeedStatusWriter}
   * enqueue, so NO Redis I/O runs under the lock — including the connection-state callback, which
   * fires SYNCHRONOUSLY inside {@code marketFeed.start()}. CONNECTING is seeded BEFORE the start so
   * that seed and the callback's CONNECTED reach the writer in order (CONNECTED wins).
   */
  private void startUnderLock() {
    running = true;
    statusWriter.session(sessionGateway.statusLabel());
    // Seeded CONNECTING; the feed's connect/disconnect callbacks own the CONNECTED/DISCONNECTED
    // transitions (an eager CONNECTED write here asserted a socket that a stale-token 403 never
    // opened — the status surface lied all day exactly when it mattered).
    statusWriter.ticker("CONNECTING");
    marketFeed.onConnectionState(
        connected -> statusWriter.ticker(connected ? "CONNECTED" : "DISCONNECTED"));
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
    lifecycleLock.lock();
    try {
      if (!running) {
        return;
      }
      log.info("re-arming feed after session change");
      // One atomic critical section (stop then start) — a concurrent lifecycle transition must not
      // slip between the two halves. Composed from the *UnderLock cores directly; all Redis status
      // writes are non-blocking enqueues, so this self-heal path never holds the lock on Redis.
      stopUnderLock();
      startUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
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

  @Override
  public void stop() {
    lifecycleLock.lock();
    try {
      stopUnderLock();
    } finally {
      lifecycleLock.unlock();
    }
  }

  /**
   * Core stop transition — caller MUST hold {@link #lifecycleLock}. Mutates ONLY the serialized
   * lifecycle state (running flag, MarketFeed handle, normalizer thread). The DISCONNECTED status is
   * a non-blocking {@link FeedStatusWriter} enqueue — belt-and-suspenders alongside the feed's own
   * disconnect callback — so no Redis I/O runs under the lock.
   */
  private void stopUnderLock() {
    running = false;
    marketFeed.stop();
    statusWriter.ticker("DISCONNECTED");
    if (normalizerThread != null) {
      normalizerThread.interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
