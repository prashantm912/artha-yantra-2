package in.arthayantra.backtest.dispatch;

import in.arthayantra.backtest.jobs.Job;
import in.arthayantra.backtest.jobs.JobRepository;
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
 * IO only) that consume {@code jobs.backtest} via the {@code cg-backtest} consumer group. Each
 * record is reconciled against the authoritative table: a conditional claim wins or loses; losing
 * (duplicate / already-terminal) drops the record. XACK happens on a terminal state OR when dropping
 * a duplicate (§D.2). Started by {@link StreamBootstrap} after crash recovery; stopped on shutdown.
 */
@Component
public class WorkerPool {

  private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

  private final StringRedisTemplate redis;
  private final JobRepository repository;
  private final ReplayStub replay;
  private final ProgressPublisher progress;
  private final int configuredWorkers;
  private final String consumer;
  private final AtomicInteger busy = new AtomicInteger();

  private volatile boolean running;
  private ExecutorService pool;

  /** Wires the worker collaborators. */
  public WorkerPool(
      StringRedisTemplate redis,
      JobRepository repository,
      ReplayStub replay,
      ProgressPublisher progress,
      @Value("${artha.backtest.workers:0}") int configuredWorkers) {
    this.redis = redis;
    this.repository = repository;
    this.replay = replay;
    this.progress = progress;
    this.configuredWorkers = configuredWorkers;
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

  /** Starts the platform-thread pool reading the backtest stream. */
  public synchronized void start() {
    if (running) {
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

  private void loop() {
    StreamReadOptions options = StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2));
    StreamOffset<String> offset = StreamOffset.create(Streams.BACKTEST, ReadOffset.lastConsumed());
    while (running && !Thread.currentThread().isInterrupted()) {
      try {
        List<MapRecord<String, Object, Object>> records =
            redis
                .opsForStream()
                .read(Consumer.from(Streams.GROUP_BACKTEST, consumer), options, offset);
        if (records != null) {
          for (MapRecord<String, Object, Object> record : records) {
            process(record);
          }
        }
      } catch (Exception e) {
        if (running) {
          log.warn("worker read error: {}", e.getMessage());
          sleepQuiet();
        }
      }
    }
  }

  /** Processes one record (also used by startup pending-drain). */
  public void process(MapRecord<String, Object, Object> record) {
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
      acknowledge(record);
    }
  }

  private void runClaimed(UUID jobId) {
    try {
      replay.run(jobId, pct -> progress.publish(jobId, pct), () -> isCancelRequested(jobId));
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

  private void acknowledge(MapRecord<String, Object, Object> record) {
    try {
      redis.opsForStream().acknowledge(Streams.BACKTEST, Streams.GROUP_BACKTEST, record.getId());
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
