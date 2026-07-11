package in.arthayantra.backtest.dispatch;

import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.replay.BacktestRunner;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The bounded pool of {@code max(1, cores-2)} PLATFORM worker threads (D12 — virtual threads are for
 * IO only) that consume {@code jobs.backtest} (interactive runs) and {@code jobs.backtest.trials}
 * (optimizer fan-out) via their consumer groups. Each record is reconciled against the authoritative
 * table: a conditional claim wins or loses; losing (duplicate / already-terminal) drops the record.
 * XACK happens on a terminal state OR when dropping a duplicate (§D.2). Started by {@link
 * StreamBootstrap} after crash recovery; stopped on shutdown.
 *
 * <p><b>Interactive-reservation (B16 / audit A6):</b> at most {@link #maxTrialWorkers()} workers may
 * be processing TRIAL jobs at once, so {@code interactive-reserved} slots always stay free to serve
 * the owner's interactive backtests during an EVO campaign trial storm. A worker reserves its trial
 * slot BEFORE reading the trials stream; when the trial budget is full it serves only the
 * interactive stream that iteration.
 */
@Component
public class WorkerPool {

  private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

  private final StringRedisTemplate redis;
  private final JobRepository repository;
  private final BacktestRunner runner;
  private final ProgressPublisher progress;
  private final int configuredWorkers;
  private final boolean enabled;
  private final int reservedInteractive;
  private final String consumer;
  private final AtomicInteger busy = new AtomicInteger();
  private final AtomicInteger trials = new AtomicInteger();

  private volatile boolean running;
  private ExecutorService pool;

  /** Wires the worker collaborators. */
  public WorkerPool(
      StringRedisTemplate redis,
      JobRepository repository,
      BacktestRunner runner,
      ProgressPublisher progress,
      @Value("${artha.backtest.workers:0}") int configuredWorkers,
      @Value("${artha.backtest.worker-pool-enabled:true}") boolean enabled,
      @Value("${artha.backtest.interactive-reserved:1}") int reservedInteractive) {
    this.redis = redis;
    this.repository = repository;
    this.runner = runner;
    this.progress = progress;
    this.configuredWorkers = configuredWorkers;
    this.enabled = enabled;
    this.reservedInteractive = Math.max(0, reservedInteractive);
    this.consumer = resolveConsumerName();
  }

  /** {@code max(1, cores-2)} unless explicitly overridden (D12). */
  public int workerCount() {
    return configuredWorkers > 0
        ? configuredWorkers
        : Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
  }

  /** Stable consumer name per instance (so this group's PEL survives a restart). */
  public String consumerName() {
    return consumer;
  }

  /** Worker threads currently inside a replay (gauge source). */
  public int busyCount() {
    return busy.get();
  }

  /**
   * The most workers that may run TRIAL (optimizer fan-out) jobs at once — {@code max(1,
   * workerCount - interactive-reserved)} (B16 / audit A6). The remaining {@code interactive-reserved}
   * slots are never occupied by trials, so an owner-submitted interactive backtest always finds a
   * free worker during a campaign trial storm. {@code interactive-reserved=0} restores the pre-B16
   * full-pool trial behaviour; a single-worker pool cannot reserve, so trials still get their one
   * slot.
   */
  public int maxTrialWorkers() {
    return Math.max(1, workerCount() - reservedInteractive);
  }

  /** Workers currently holding a trial slot (gauge / the interactive-reservation invariant). */
  public int trialsInFlightCount() {
    return trials.get();
  }

  /** Atomically reserves a trial slot iff the trial budget ({@link #maxTrialWorkers()}) has room. */
  boolean tryReserveTrialSlot() {
    int cap = maxTrialWorkers();
    int cur;
    do {
      cur = trials.get();
      if (cur >= cap) {
        return false;
      }
    } while (!trials.compareAndSet(cur, cur + 1));
    return true;
  }

  /** Releases a trial slot reserved by {@link #tryReserveTrialSlot()}. */
  void releaseTrialSlot() {
    trials.decrementAndGet();
  }

  /** Starts the platform-thread pool reading the backtest stream. */
  public synchronized void start() {
    if (running || !enabled) {
      return;
    }
    running = true;
    int n = workerCount();
    pool = Executors.newFixedThreadPool(n, namedFactory());
    for (int i = 0; i < n; i++) {
      pool.submit(this::loop);
    }
    log.info("backtest worker pool started: {} platform workers, consumer={}", n, consumer);
  }

  /** A (stream, consumer-group) pair this pool consumes. */
  private record StreamSource(String stream, String group) {}

  /** Both dispatch streams: single runs (cg-backtest) and optimizer trial fan-out (cg-trials). A
   * TRIAL run emits its result onto optimizations.results (§D.7); requeued jobs always re-dispatch
   * onto jobs.backtest, so a single worker reading both covers crash recovery. */
  private static final List<StreamSource> SOURCES =
      List.of(
          new StreamSource(Streams.BACKTEST, Streams.GROUP_BACKTEST),
          new StreamSource(Streams.TRIALS, Streams.GROUP_TRIALS));

  private void loop() {
    StreamReadOptions options = StreamReadOptions.empty().count(1).block(Duration.ofSeconds(1));
    while (running && !Thread.currentThread().isInterrupted()) {
      for (StreamSource source : SOURCES) {
        if (!running) {
          break;
        }
        // B16 / audit A6: cap concurrent TRIAL processing at maxTrialWorkers() so the
        // interactive-reserved slots stay free for the owner's backtests. Reserve BEFORE the
        // (blocking) read — when the trial budget is full this worker short-circuits and serves only
        // the interactive stream this iteration instead of parking on trials.
        if (Streams.TRIALS.equals(source.stream())) {
          if (!tryReserveTrialSlot()) {
            continue;
          }
          try {
            pump(source, options);
          } finally {
            releaseTrialSlot();
          }
        } else {
          pump(source, options);
        }
      }
    }
  }

  /** Reads at most one record off {@code source} and processes it, isolating stream read errors. */
  private void pump(StreamSource source, StreamReadOptions options) {
    try {
      List<MapRecord<String, Object, Object>> records =
          redis
              .opsForStream()
              .read(
                  Consumer.from(source.group(), consumer),
                  options,
                  StreamOffset.create(source.stream(), ReadOffset.lastConsumed()));
      if (records != null) {
        for (MapRecord<String, Object, Object> record : records) {
          process(record, source);
        }
      }
    } catch (Exception e) {
      if (running) {
        log.warn("worker read error on {}: {}", source.stream(), e.getMessage());
        sleepQuiet();
      }
    }
  }

  /** Processes a record off the backtest stream (startup pending-drain entry point). */
  public void process(MapRecord<String, Object, Object> record) {
    process(record, SOURCES.get(0));
  }

  /** Processes one record off a given source, acking to that source's group. */
  public void process(MapRecord<String, Object, Object> record, StreamSource source) {
    Object raw = record.getValue().get("jobId");
    UUID jobId = parseJobId(raw);
    try {
      if (jobId != null && repository.claim(jobId, consumer)) {
        busy.incrementAndGet();
        try {
          runClaimed(jobId);
        } finally {
          busy.decrementAndGet();
        }
      }
      // else: not queued (duplicate / running elsewhere / terminal) -> drop
    } finally {
      acknowledge(record, source);
    }
  }

  private void runClaimed(UUID jobId) {
    Job job = repository.find(jobId).orElse(null);
    if (job == null) {
      return;
    }
    try {
      runner.run(job, pct -> progress.publish(jobId, pct), () -> isCancelRequested(jobId));
      repository.markCompleted(jobId);
      progress.publishTerminal(jobId, "completed", 100);
    } catch (JobCancelledException ce) {
      repository.markCancelled(jobId);
      redis.delete(Streams.cancelKey(jobId));
      progress.publishTerminal(jobId, "cancelled", currentProgress(jobId));
    } catch (Exception e) {
      log.warn("job {} failed: {}", jobId, e.toString());
      repository.markFailed(
          jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      progress.publishTerminal(jobId, "failed", currentProgress(jobId));
    }
  }

  private boolean isCancelRequested(UUID jobId) {
    return Boolean.TRUE.equals(redis.hasKey(Streams.cancelKey(jobId)));
  }

  private int currentProgress(UUID jobId) {
    return repository.find(jobId).map(Job::progress).orElse(0);
  }

  private void acknowledge(MapRecord<String, Object, Object> record, StreamSource source) {
    try {
      redis.opsForStream().acknowledge(source.stream(), source.group(), record.getId());
    } catch (Exception e) {
      log.debug("ack failed for {}: {}", record.getId(), e.getMessage());
    }
  }

  private static UUID parseJobId(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return UUID.fromString(raw.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void sleepQuiet() {
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private ThreadFactory namedFactory() {
    AtomicInteger seq = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, "bt-worker-" + seq.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  private static String resolveConsumerName() {
    try {
      return "bt-" + InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "bt-worker";
    }
  }

  /** Stops the pool on shutdown. */
  @PreDestroy
  public synchronized void stop() {
    running = false;
    if (pool != null) {
      pool.shutdownNow();
      try {
        pool.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
