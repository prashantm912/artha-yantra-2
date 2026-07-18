package in.arthayantra.marketdata.feed;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Serialized, NON-BLOCKING writer for the canonical feed-status Redis keys ({@code
 * kite:session:status} / {@code kite:ticker:status}). Every producer — the {@link FeedPipeline}
 * lifecycle transitions AND the feed's connection-state callback — publishes the LATEST desired
 * value per key (O(1), never blocks); a single background thread owns the Redis {@code set}. Three
 * properties this buys (task_ff56fd20):
 *
 * <ol>
 *   <li><b>Off the lock.</b> {@code FeedPipeline.startUnderLock/stopUnderLock} run under a
 *       {@code lifecycleLock}, and the connection-state callback fires SYNCHRONOUSLY inside {@code
 *       marketFeed.start()}/{@code stop()} (see {@code MockMarketFeed}/{@code UpstoxMarketFeedClient})
 *       — i.e. still under the lock. A direct Redis {@code set} there would let a hung loopback Redis
 *       (bounded only by the ~60s Lettuce command timeout) hold the lock and stall a concurrent
 *       {@code FeedWatchdog.restartFeed} self-heal. Publishing is O(1), so no lifecycle path ever
 *       waits on Redis.
 *   <li><b>Newest ALWAYS wins — never lost.</b> Instead of a bounded event queue (whose saturation
 *       handler could drop a TERMINAL status while an older one still applied, leaving Redis stale),
 *       each key holds ONE pending slot carrying the latest {@code (seq, value)}. A newer status
 *       COALESCES over the pending one (at most one pending entry per key — bounded, no drop). The
 *       writer drains the latest per key and, as a final safety net against a slow producer that
 *       stamped an old {@code seq} but published late, applies only if the {@code seq} exceeds the
 *       last applied for that key.
 *   <li><b>Ordered.</b> CONNECTING is seeded (in {@code startUnderLock}) BEFORE {@code
 *       marketFeed.start()}, so a synchronous CONNECTED coalesces OVER it (CONNECTED wins); a stop's
 *       DISCONNECTED is the newest, so no stale CONNECTING can regress it.
 * </ol>
 *
 * <p>Fail-soft end to end: a Redis failure is logged by the writer thread, never thrown into the
 * caller. On shutdown the pending slots are DRAINED before the thread stops. {@code
 * ay_feed_status_write_dropped_total} counts COALESCED intermediates (a still-pending status
 * superseded by a newer one before the writer applied it) — it moves only when the writer cannot
 * keep up (a slow/hung Redis), so ~never in normal operation, and it is visibility, not loss: the
 * newest value is always the one that reaches Redis.
 */
@Component
public class FeedStatusWriter {

  private static final Logger log = LoggerFactory.getLogger(FeedStatusWriter.class);

  /** On shutdown, let the pending status writes DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  /** One pending status value + the seq it was stamped with at publish time. */
  private record Pending(long seq, String value) {}

  private final StringRedisTemplate redis;
  private final Counter coalesced;
  private final AtomicLong sequence = new AtomicLong();

  // One pending slot per key — the LATEST published value coalesces over any un-applied predecessor.
  private final AtomicReference<Pending> pendingSession = new AtomicReference<>();
  private final AtomicReference<Pending> pendingTicker = new AtomicReference<>();

  // A single-permit doorbell: publish rings it (offer, O(1), ignored if already pending); the writer
  // takes it, then drains the slots. Never a lost wakeup (publish sets the slot BEFORE ringing; the
  // writer re-drains after every take) and never unbounded (capacity 1).
  private final ArrayBlockingQueue<Object> doorbell = new ArrayBlockingQueue<>(1);
  private static final Object SIGNAL = new Object();

  /** Newest applied seq per key. Touched ONLY by the single writer thread ⇒ no synchronization. */
  private final Map<String, Long> lastAppliedSeq = new HashMap<>();

  /** High-water mark of the largest seq the writer has processed; drives {@link #awaitDrained}. */
  private final AtomicLong processedHighWater = new AtomicLong();
  private final Object drainMonitor = new Object();

  private final Thread writerThread;
  private volatile boolean active = true;

  /** Wires the Redis template + the coalesced-write counter, and starts the single writer thread. */
  public FeedStatusWriter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
    this.redis = redis;
    this.coalesced = meterRegistry.counter("ay_feed_status_write_dropped_total");
    this.writerThread = new Thread(this::runLoop, "feed-status-writer");
    this.writerThread.setDaemon(true);
    this.writerThread.start();
  }

  /** Publish the latest {@code kite:session:status}. NON-BLOCKING. */
  public void session(String label) {
    publish(pendingSession, label);
  }

  /** Publish the latest {@code kite:ticker:status}. NON-BLOCKING. */
  public void ticker(String state) {
    publish(pendingTicker, state);
  }

  private void publish(AtomicReference<Pending> slot, String value) {
    long seq = sequence.incrementAndGet(); // stamp event order at publish time
    Pending mine = new Pending(seq, value);
    // Coalesce: keep whichever has the higher seq. getAndAccumulate returns the PRIOR slot value.
    Pending prior = slot.getAndAccumulate(mine, (cur, next) -> cur == null || next.seq() >= cur.seq() ? next : cur);
    if (prior != null && prior.seq() < seq) {
      coalesced.increment(); // a still-pending intermediate was superseded before the writer applied it
    }
    doorbell.offer(SIGNAL);
  }

  private void runLoop() {
    while (active) {
      try {
        doorbell.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      drainOnce();
    }
    drainOnce(); // final best-effort flush of the latest-per-key after shutdown flips active=false
  }

  private void drainOnce() {
    applyPending(FeedPipeline.SESSION_STATUS_KEY, pendingSession);
    applyPending(FeedPipeline.TICKER_STATUS_KEY, pendingTicker);
  }

  private void applyPending(String key, AtomicReference<Pending> slot) {
    Pending p = slot.getAndSet(null); // atomically take the latest and clear
    if (p == null) {
      return;
    }
    apply(key, p.value(), p.seq());
    processedHighWater.accumulateAndGet(p.seq(), Math::max);
    synchronized (drainMonitor) {
      drainMonitor.notifyAll();
    }
  }

  /** Runs ONLY on the single writer thread (package-private for a direct seq-gate unit test). */
  void apply(String key, String value, long seq) {
    Long last = lastAppliedSeq.get(key);
    if (last != null && seq <= last) {
      return; // a newer status for this key already reached Redis — never regress it
    }
    lastAppliedSeq.put(key, seq);
    try {
      redis.opsForValue().set(key, value);
    } catch (RuntimeException redisGone) {
      log.warn("feed status write failed (key={}): {}", key, redisGone.getMessage());
    }
  }

  @PreDestroy
  void shutdown() {
    drainAndShutdown(SHUTDOWN_DRAIN_MILLIS);
  }

  /**
   * Graceful shutdown (package-private so a test can use a short drain window). Stops the loop, wakes
   * the writer so it runs a final drain of the pending slots, then joins for up to {@code
   * drainMillis}; a writer still stuck (e.g. parked in a hung Redis {@code set}) is interrupted and
   * abandoned. Fail-soft: never throws out of shutdown.
   */
  void drainAndShutdown(long drainMillis) {
    active = false;
    doorbell.offer(SIGNAL); // wake the writer so it exits the loop and runs the final drain
    try {
      writerThread.join(drainMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (writerThread.isAlive()) {
      writerThread.interrupt();
      log.warn("feed-status writer drain exceeded {}ms — interrupting", drainMillis);
    }
  }

  /**
   * Test hook: block until every write published BEFORE this call has been applied (or superseded by
   * a newer one). Waits until the writer's processed high-water mark reaches the seq high-water at
   * call time.
   */
  boolean awaitDrained(long millis) throws InterruptedException {
    long target = sequence.get();
    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    synchronized (drainMonitor) {
      while (processedHighWater.get() < target) {
        long remainMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        if (remainMillis <= 0) {
          return false;
        }
        drainMonitor.wait(remainMillis);
      }
    }
    return true;
  }
}
