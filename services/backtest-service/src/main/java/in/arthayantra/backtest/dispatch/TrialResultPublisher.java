package in.arthayantra.backtest.dispatch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Emits a completed TRIAL's metrics onto {@link Streams#OPT_RESULTS} (§D.7, Flow 5) — the
 * back-half of the optimizer ask/tell loop. optimizer-service reads this via {@code cg-optuna},
 * {@code study.tell()}s the objective, and (Phase 34) feeds the per-fold OOS objectives to the
 * MedianPruner. The full metric catalog is emitted so the optimizer picks whichever metric the
 * sweep's {@code objective} names; {@code oosFoldMean}/{@code oosFoldObjectives} carry the
 * walk-forward objective and the fold breakdown when the trial ran fold-context.
 */
@Component
public class TrialResultPublisher {

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires Redis + Jackson. */
  public TrialResultPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Publishes one trial's result; {@code sweepId}/{@code oosFoldMean} may be null. */
  public void publish(
      UUID trialId,
      UUID sweepId,
      UUID runId,
      ObjectNode metrics,
      BigDecimal oosFoldMean,
      ArrayNode oosFoldObjectives) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("trialId", trialId.toString());
    if (sweepId != null) {
      entry.put("sweepId", sweepId.toString());
    }
    entry.put("runId", runId.toString());
    entry.put("metrics", json(metrics));
    if (oosFoldMean != null) {
      entry.put("oosFoldMean", oosFoldMean.toPlainString());
    }
    entry.put("oosFoldObjectives", json(oosFoldObjectives));
    redis.opsForStream().add(Streams.OPT_RESULTS, entry);
  }

  private String json(Object node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("trial result not serializable", e);
    }
  }
}
