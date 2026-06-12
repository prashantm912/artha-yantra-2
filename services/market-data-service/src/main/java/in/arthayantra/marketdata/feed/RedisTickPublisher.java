package in.arthayantra.marketdata.feed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes normalized ticks to Redis pub/sub on the D9 channels —
 * {@code ticks.{exchange}.{tradingsymbol}} — and mirrors the latest value into the
 * {@code ticks:last} hash (A.7.1). Prices serialize as strings, timestamps with {@code +05:30}
 * (the platform Jackson conventions).
 */
@Component
public class RedisTickPublisher {

  /** The D9 channel prefix — verbatim {@code ticks.{exchange}.{tradingsymbol}}. */
  public static final String CHANNEL_PREFIX = "ticks.";

  /** The last-tick hash key. */
  public static final String LAST_TICK_HASH = "ticks:last";

  private static final Logger log = LoggerFactory.getLogger(RedisTickPublisher.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final Timer publishLatency;

  public RedisTickPublisher(
      StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.publishLatency = meterRegistry.timer("ay_tick_publish_latency_seconds");
  }

  /** Publishes one tick; called only from the single-writer pipeline thread. */
  public void publish(NormalizedTick tick) {
    publishLatency.record(
        () -> {
          try {
            String json = objectMapper.writeValueAsString(tick);
            String channel = CHANNEL_PREFIX + tick.exchange() + "." + tick.tradingsymbol();
            redis.convertAndSend(channel, json);
            redis.opsForHash().put(LAST_TICK_HASH, tick.exchange() + ":" + tick.tradingsymbol(), json);
            // B-13 kite.lastTickAgeMs source — one cheap SET per tick
            redis.opsForValue().set("ticks:last-at", Long.toString(System.currentTimeMillis()));
          } catch (JsonProcessingException e) {
            log.error("tick serialization failed for {}:{}", tick.exchange(), tick.tradingsymbol(), e);
          }
        });
  }
}
