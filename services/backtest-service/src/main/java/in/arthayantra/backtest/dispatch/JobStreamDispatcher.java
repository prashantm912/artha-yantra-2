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

  private final StringRedisTemplate redis;

  /** Wires Redis. */
  public JobStreamDispatcher(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Dispatches a single backtest run. */
  public void dispatchBacktest(UUID jobId) {
    redis.opsForStream().add(Streams.BACKTEST, Map.of("jobId", jobId.toString()));
  }
}
