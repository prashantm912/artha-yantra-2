package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the Redis last-tick map (the {@code ticks:last} hash that market-data-service writes,
 * keyed {@code EXCHANGE:TRADINGSYMBOL}). It is cross-service SHARED state (D-cache tier), not a
 * {@code marketdata} schema read — so it respects the D7/D10 grant model. Mark-to-market and the
 * next-tick fill reference both source from here.
 */
@Component
public class LastTickReader {

  private static final Logger log = LoggerFactory.getLogger(LastTickReader.class);
  private static final String LAST_TICK_HASH = "ticks:last";

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /** Wires the shared Redis template. */
  public LastTickReader(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  /** The latest LTP for a stable key, if a tick has arrived. */
  public Optional<BigDecimal> lastPrice(String exchange, String tradingsymbol) {
    Object json = redis.opsForHash().get(LAST_TICK_HASH, exchange + ":" + tradingsymbol);
    if (json == null) {
      return Optional.empty();
    }
    try {
      Tick tick = objectMapper.readValue(json.toString(), Tick.class);
      return Optional.ofNullable(tick.lastPrice()).map(BigDecimal::new);
    } catch (Exception e) {
      log.warn("unparseable last tick for {}:{}: {}", exchange, tradingsymbol, e.getMessage());
      return Optional.empty();
    }
  }

  /** The fields the paper ledger reads off a normalized tick (prices are decimal strings). */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record Tick(String lastPrice) {}
}
