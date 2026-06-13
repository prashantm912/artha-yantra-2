package in.arthayantra.backtest.dispatch;

import in.arthayantra.backtest.jobs.JobRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D12 startup sequence (the architecture's crash-safety guarantee): declare the streams + consumer
 * groups, re-queue stale {@code running} rows orphaned by a crash, drain the consumer's pending
 * entries (PEL hygiene), re-dispatch every {@code queued} job so each has a fresh stream entry, then
 * start the worker pool. A mid-run {@code docker compose restart} therefore loses nothing — the
 * Postgres table is authoritative and exactly-once is enforced by the conditional claim.
 */
@Component
public class StreamBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(StreamBootstrap.class);

  private final StringRedisTemplate redis;
  private final JobRepository repository;
  private final JobStreamDispatcher dispatcher;
  private final WorkerPool workerPool;
  private final ProgressPublisher progress;

  /** Wires the recovery collaborators. */
  public StreamBootstrap(
      StringRedisTemplate redis,
      JobRepository repository,
      JobStreamDispatcher dispatcher,
      WorkerPool workerPool,
      ProgressPublisher progress) {
    this.redis = redis;
    this.repository = repository;
    this.dispatcher = dispatcher;
    this.workerPool = workerPool;
    this.progress = progress;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensureGroup(Streams.BACKTEST, Streams.GROUP_BACKTEST);
    ensureGroup(Streams.TRIALS, Streams.GROUP_TRIALS);
    ensureGroup(Streams.OPT_RESULTS, Streams.GROUP_OPTUNA);

    int requeued = repository.requeueStaleRunning();
    if (requeued > 0) {
      log.info("re-queued {} stale running job(s) — D12 crash recovery", requeued);
    }
    drainPending();

    List<UUID> queued = repository.findQueuedIds();
    queued.forEach(dispatcher::dispatchBacktest);
    if (!queued.isEmpty()) {
      log.info("re-dispatched {} queued job(s)", queued.size());
    }

    progress.refreshSummary();
    workerPool.start();
  }

  /** Creates the stream + group with MKSTREAM; a pre-existing group (BUSYGROUP) is fine. */
  private void ensureGroup(String stream, String group) {
    try {
      redis.execute(
          (org.springframework.data.redis.connection.RedisConnection conn) -> {
            conn.streamCommands()
                .xGroupCreate(
                    stream.getBytes(StandardCharsets.UTF_8), group, ReadOffset.latest(), true);
            return null;
          });
    } catch (Exception e) {
      String message = e.getMessage();
      if (message == null || !message.contains("BUSYGROUP")) {
        log.warn("ensureGroup {}/{}: {}", stream, group, message);
      }
    }
  }

  /** Reprocesses this consumer's already-delivered-but-unacked entries (clears the PEL). */
  private void drainPending() {
    StreamReadOptions options = StreamReadOptions.empty().count(50);
    StreamOffset<String> offset = StreamOffset.create(Streams.BACKTEST, ReadOffset.from("0"));
    int drained = 0;
    while (true) {
      List<MapRecord<String, Object, Object>> pending;
      try {
        pending =
            redis
                .opsForStream()
                .read(
                    Consumer.from(Streams.GROUP_BACKTEST, workerPool.consumerName()),
                    options,
                    offset);
      } catch (Exception e) {
        log.debug("pending drain skipped: {}", e.getMessage());
        return;
      }
      if (pending == null || pending.isEmpty()) {
        break;
      }
      for (MapRecord<String, Object, Object> record : pending) {
        workerPool.process(record);
        drained++;
      }
      if (pending.size() < 50) {
        break;
      }
    }
    if (drained > 0) {
      log.info("drained {} pending stream entr(ies) on startup", drained);
    }
  }

  /** Keeps {@code jobs:summary} fresh even when no job transitions occur. */
  @Scheduled(fixedDelayString = "${artha.backtest.summary-refresh-ms:5000}")
  public void refreshSummary() {
    progress.refreshSummary();
  }
}
