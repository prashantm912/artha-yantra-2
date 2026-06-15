package in.arthayantra.marketdata.kite.ticker;

import in.arthayantra.marketdata.kite.InstrumentKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed {@link SubscriptionStore}: one hash {@code marketdata:subscriptions}, field
 * {@code subscriber::exchange::tradingsymbol}, value {@code MODE::PRIORITY}. The {@code ::} delimiter
 * is absent from broker symbols (alphanumeric + space/&/-/.) and the subscriber ids the platform
 * mints, so it round-trips intact. Survives a market-data restart so non-pinned holds replay (B-6);
 * rides the single-owner Redis (D10).
 */
@Component
public class RedisSubscriptionStore implements SubscriptionStore {

  /** The hash all holds live in. */
  static final String HASH = "marketdata:subscriptions";

  private static final String DELIM = "::";

  private static final Logger log = LoggerFactory.getLogger(RedisSubscriptionStore.class);

  private final StringRedisTemplate redis;

  /** Wires the shared string template. */
  public RedisSubscriptionStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void put(
      String subscriber, InstrumentKey key, SubscriptionMode mode, SubscriptionPriority priority) {
    redis.opsForHash().put(HASH, field(subscriber, key), mode.name() + DELIM + priority.name());
  }

  @Override
  public void remove(String subscriber, InstrumentKey key) {
    redis.opsForHash().delete(HASH, field(subscriber, key));
  }

  @Override
  public List<PersistedHold> all() {
    Map<Object, Object> entries = redis.opsForHash().entries(HASH);
    List<PersistedHold> holds = new ArrayList<>(entries.size());
    for (Map.Entry<Object, Object> entry : entries.entrySet()) {
      String[] f = ((String) entry.getKey()).split(DELIM, -1);
      String[] v = ((String) entry.getValue()).split(DELIM, -1);
      if (f.length != 3 || v.length != 2) {
        log.warn("skipping malformed persisted subscription field '{}'", entry.getKey());
        continue;
      }
      try {
        holds.add(
            new PersistedHold(
                f[0],
                new InstrumentKey(f[1], f[2]),
                SubscriptionMode.valueOf(v[0]),
                SubscriptionPriority.valueOf(v[1])));
      } catch (IllegalArgumentException unparseable) {
        log.warn(
            "skipping unparseable persisted subscription '{}': {}",
            entry.getKey(),
            unparseable.getMessage());
      }
    }
    return holds;
  }

  private static String field(String subscriber, InstrumentKey key) {
    return subscriber + DELIM + key.exchange() + DELIM + key.tradingsymbol();
  }
}
