package in.arthayantra.backtest.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.arthayantra.backtest.jobs.JobRepository;
import in.arthayantra.backtest.jobs.JobStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes job progress to the authoritative table AND publishes a delta on the per-job pub/sub
 * channel (D9/D12). Polling {@code GET /jobs/{id}} remains the source of truth; the pub/sub frame is
 * fire-and-forget for live progress bars. Also refreshes the {@code jobs:summary} key that the
 * Stage-B system-status rollup reports.
 */
@Component
public class ProgressPublisher {

  private static final Logger log = LoggerFactory.getLogger(ProgressPublisher.class);

  private final JobRepository repository;
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires the table, Redis and Jackson. */
  public ProgressPublisher(
      JobRepository repository, StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.repository = repository;
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Persists running progress and publishes the delta. */
  public void publish(UUID jobId, int progress) {
    repository.updateProgress(jobId, progress);
    emit(jobId, "running", progress);
  }

  /** Publishes a terminal frame (table already transitioned by the worker). */
  public void publishTerminal(UUID jobId, String status, int progress) {
    emit(jobId, status, progress);
    refreshSummary();
  }

  private void emit(UUID jobId, String status, int progress) {
    try {
      ObjectNode frame = objectMapper.createObjectNode();
      frame.put("jobId", jobId.toString());
      frame.put("status", status);
      frame.put("progress", progress);
      redis.convertAndSend(Streams.progressChannel(jobId), objectMapper.writeValueAsString(frame));
    } catch (Exception e) {
      log.warn("progress publish failed for {}: {}", jobId, e.getMessage());
    }
  }

  /** Recomputes queued/running counts into {@code jobs:summary} (consumed by system-status). */
  public void refreshSummary() {
    try {
      ObjectNode summary = objectMapper.createObjectNode();
      summary.put("queued", repository.countByStatus(JobStatus.QUEUED));
      summary.put("running", repository.countByStatus(JobStatus.RUNNING));
      redis.opsForValue().set(Streams.SUMMARY_KEY, objectMapper.writeValueAsString(summary));
    } catch (Exception e) {
      log.warn("jobs:summary refresh failed: {}", e.getMessage());
    }
  }
}
