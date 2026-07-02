package in.arthayantra.backtest.dispatch;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * XADDs a job id onto its dispatch stream (D12 transport). The authoritative {@code jobs} row is
 * always INSERTed first (by the submitter); this only signals a worker to pick it up. Duplicate
 * XADDs are harmless — the worker's conditional claim dedups.
 */
@Component
public class JobStreamDispatcher {

  /**
   * Approximate stream cap (audit P2): acked entries were never trimmed, so months of dispatches
   * accumulated NON-EVICTABLE bytes (streams carry no TTL) inside the 48 MB shared Redis — under
   * volatile-lru that pressure evicts the owner's SESSION keys instead. 10k entries dwarfs any
   * real backlog (jobs are re-derivable from Postgres anyway).
   */
  static final long STREAM_MAXLEN = 10_000;

  private final StringRedisTemplate redis;

  /** Wires Redis. */
  public JobStreamDispatcher(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Dispatches a single backtest run. */
  public void dispatchBacktest(UUID jobId) {
    redis.opsForStream().add(Streams.BACKTEST, Map.of("jobId", jobId.toString()));
    redis.opsForStream().trim(Streams.BACKTEST, STREAM_MAXLEN, true);
  }
}
