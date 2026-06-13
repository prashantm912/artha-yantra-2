package in.arthayantra.strategysignal.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@code strategy.changed} on publish/rollback/archive (COMMON 7.3.1) — the Phase 23
 * signal engine hot-swaps at the NEXT bar boundary on this event, never mid-bar.
 */
@Component
public class StrategyChangedPublisher {

  /** The pub/sub channel name. */
  public static final String CHANNEL = "strategy.changed";

  private static final Logger log = LoggerFactory.getLogger(StrategyChangedPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires Redis. */
  public StrategyChangedPublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** Emits one change event; failures log and never break the registry write. */
  public void publish(UUID strategyId, String slug, String event, String version) {
    try {
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("strategyId", strategyId.toString());
      payload.put("slug", slug);
      payload.put("event", event);
      payload.put("version", version);
      redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(payload));
    } catch (Exception e) {
      log.warn("strategy.changed publish failed for {}: {}", slug, e.getMessage());
    }
  }
}
