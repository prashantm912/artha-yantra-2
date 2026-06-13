package in.arthayantra.backtest.dispatch;

import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.jobs.JobStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Stage-D Phase 28 metrics (Stage-G dashboards consume them): queue depth, busy workers and the
 * Redis Streams pending-entry count.
 */
@Component
public class BacktestMetrics {

  private static final Logger log = LoggerFactory.getLogger(BacktestMetrics.class);

  private final MeterRegistry registry;
  private final JobRepository repository;
  private final WorkerPool workerPool;
  private final StringRedisTemplate redis;

  /** Wires the gauge sources. */
  public BacktestMetrics(
      MeterRegistry registry,
      JobRepository repository,
      WorkerPool workerPool,
      StringRedisTemplate redis) {
    this.registry = registry;
    this.repository = repository;
    this.workerPool = workerPool;
    this.redis = redis;
  }

  /** Registers the gauges. */
  @PostConstruct
  public void register() {
    Gauge.builder("ay_backtest_queue_depth", repository, r -> r.countByStatus(JobStatus.QUEUED))
        .description("backtest jobs in queued state")
        .register(registry);
    Gauge.builder("ay_backtest_workers_busy", workerPool, WorkerPool::busyCount)
        .description("worker threads actively replaying")
        .register(registry);
    Gauge.builder("ay_redis_stream_pending", this, BacktestMetrics::pendingCount)
        .description("unacked entries in the backtest consumer group")
        .register(registry);
  }

  private double pendingCount() {
    try {
      var summary = redis.opsForStream().pending(Streams.BACKTEST, Streams.GROUP_BACKTEST);
      return summary == null ? 0d : summary.getTotalPendingMessages();
    } catch (Exception e) {
      log.debug("pending count unavailable: {}", e.getMessage());
      return 0d;
    }
  }
}
