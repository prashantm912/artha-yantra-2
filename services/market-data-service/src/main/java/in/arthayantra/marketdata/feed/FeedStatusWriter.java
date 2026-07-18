package in.arthayantra.marketdata.feed;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Serialized, NON-BLOCKING writer for the canonical feed-status Redis keys ({@code
 * kite:session:status} / {@code kite:ticker:status}). Every producer — the {@link FeedPipeline}
 * lifecycle transitions AND the feed's connection-state callback — ENQUEUES a status update (O(1),
 * never blocks) onto a bounded queue drained by a single background thread that owns the Redis
 * {@code set}. Two reasons this exists (task_ff56fd20):
 *
 * <ol>
 *   <li><b>Off the lock.</b> {@code FeedPipeline.startUnderLock/stopUnderLock} run under a
 *       {@code lifecycleLock}, and the connection-state callback fires SYNCHRONOUSLY inside {@code
 *       marketFeed.start()}/{@code stop()} (see {@code MockMarketFeed}/{@code UpstoxMarketFeedClient})
 *       — i.e. still under the lock. A direct Redis {@code set} there would let a hung loopback Redis
 *       (bounded only by the ~60s Lettuce command timeout) hold the lock and stall a concurrent
 *       {@code FeedWatchdog.restartFeed} self-heal. Enqueue is O(1), so no lifecycle path ever waits
 *       on Redis.
 *   <li><b>Correct order.</b> A single FIFO consumer preserves the order status transitions were
 *       decided in, so a start's CONNECTING seed then its callback CONNECTED land in order (CONNECTED
 *       wins), and a stop's DISCONNECTED is never clobbered by a stale later write. Because the
 *       callback is a SECOND producer that can race the lifecycle thread between stamping and
 *       enqueuing, each event also carries a monotonic {@code seq} captured at enqueue time and the
 *       consumer applies a key's write only if its {@code seq} is the newest seen for that key — so a
 *       reordered stale event can never regress a newer status.
 * </ol>
 *
 * <p>Fail-soft end to end: a Redis failure is logged by the writer thread, never thrown into the
 * caller. On shutdown the queue is DRAINED (bounded) before the executor stops. Saturation (a hung
 * Redis plus a burst past {@link #QUEUE_CAPACITY}) DROPS + counts ({@code
 * ay_feed_status_write_dropped_total}) on the enqueuing thread, never back-pressuring a lifecycle
 * transition — status is a latest-wins surface, so a dropped intermediate is tolerable.
 */
@Component
public class FeedStatusWriter {

  private static final Logger log = LoggerFactory.getLogger(FeedStatusWriter.class);

  /** Bounded backlog: status events fire only on lifecycle/connection transitions, so this is ample. */
  static final int QUEUE_CAPACITY = 256;

  /** On shutdown, let the queued status writes DRAIN up to this long before abandoning them. */
  static final long SHUTDOWN_DRAIN_MILLIS = 5_000L;

  private final StringRedisTemplate redis;
  private final Counter dropped;
  private final ThreadPoolExecutor executor;
  private final AtomicLong sequence = new AtomicLong();

  /** Newest applied seq per key. Touched ONLY by the single writer thread ⇒ no synchronization. */
  private final Map<String, Long> lastAppliedSeq = new HashMap<>();

  /** Wires the Redis template + the saturation drop counter. */
  public FeedStatusWriter(StringRedisTemplate redis, MeterRegistry meterRegistry) {
    this.redis = redis;
    this.dropped = meterRegistry.counter("ay_feed_status_write_dropped_total");
    this.executor =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            r -> {
              Thread t = new Thread(r, "feed-status-writer");
              t.setDaemon(true);
              return t;
            },
            // Queue full ⇒ DROP + count on the CALLING thread in O(1); never blocks, never throws.
            (r, exec) -> dropped.increment());
  }

  /** Enqueue a {@code kite:session:status} update. NON-BLOCKING. */
  public void session(String label) {
    enqueue(FeedPipeline.SESSION_STATUS_KEY, label);
  }

  /** Enqueue a {@code kite:ticker:status} update. NON-BLOCKING. */
  public void ticker(String state) {
    enqueue(FeedPipeline.TICKER_STATUS_KEY, state);
  }

  private void enqueue(String key, String value) {
    long seq = sequence.incrementAndGet(); // stamp event order at enqueue time (see class doc)
    executor.execute(() -> apply(key, value, seq));
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
   * Graceful shutdown (package-private so a test can use a short drain window). Stops accepting new
   * writes, lets the queued writes DRAIN for up to {@code drainMillis}, then abandons the rest.
   * Fail-soft: never throws out of shutdown.
   */
  void drainAndShutdown(long drainMillis) {
    executor.shutdown();
    boolean drained = false;
    try {
      drained = executor.awaitTermination(drainMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // restore the flag; abandon the rest below
    }
    if (drained) {
      return;
    }
    List<Runnable> abandoned = executor.shutdownNow();
    if (!abandoned.isEmpty()) {
      log.warn(
          "feed-status writer drain exceeded {}ms — {} status write(s) abandoned", drainMillis,
          abandoned.size());
    }
  }

  /**
   * Test hook: block until every write enqueued BEFORE this call has been applied. A no-op barrier
   * task rides the single-thread FIFO behind all prior work, so its completion proves the queue is
   * drained up to this point.
   */
  boolean awaitDrained(long millis) throws InterruptedException {
    CountDownLatch barrier = new CountDownLatch(1);
    executor.execute(barrier::countDown);
    return barrier.await(millis, TimeUnit.MILLISECONDS);
  }
}
