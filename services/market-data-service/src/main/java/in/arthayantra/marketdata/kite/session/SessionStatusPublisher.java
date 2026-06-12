package in.arthayantra.marketdata.kite.session;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Writes the canonical {@code kite:session:status} key and publishes deltas on the
 * {@code kite.status} channel (COMMON §3 / D11) — publish-on-change only, so the gateway WS
 * surface flips the re-login banner without a poll.
 */
public class SessionStatusPublisher {

  /** Canonical Redis status key (COMMON §3). */
  public static final String STATUS_KEY = "kite:session:status";

  /** Canonical pub/sub channel (COMMON §3). */
  public static final String STATUS_CHANNEL = "kite.status";

  private static final Logger log = LoggerFactory.getLogger(SessionStatusPublisher.class);

  private final StringRedisTemplate redis;
  private final AtomicReference<String> lastPublished = new AtomicReference<>();

  /** Wires Redis. */
  public SessionStatusPublisher(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Sets the key; publishes only when the value actually changed. */
  public void publish(KiteSessionStore.State state) {
    String value = state.name();
    try {
      redis.opsForValue().set(STATUS_KEY, value);
      if (!value.equals(lastPublished.getAndSet(value))) {
        redis.convertAndSend(STATUS_CHANNEL, value);
        log.info("kite session status -> {}", value);
      }
    } catch (RuntimeException redisDown) {
      log.warn("kite status publish failed: {}", redisDown.getMessage());
    }
  }
}
