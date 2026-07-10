package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
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
  private final Clock clock;

  /** Wires the shared Redis template + the (test-overridable) clock used for tick-age computation. */
  public LastTickReader(StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** The latest LTP for a stable key, if a tick has arrived. */
  public Optional<BigDecimal> lastPrice(String exchange, String tradingsymbol) {
    return lastTick(exchange, tradingsymbol).map(TickView::price);
  }

  /**
   * The last tick's price + wall-clock age (audit V3). {@code age} is the distance from the tick's
   * own {@code timestamp} to now; it is {@code null} when the tick carries no parseable timestamp —
   * a freshness guard treats an unknown age as "usable" so a malformed tick behaves exactly as it
   * did before this change. Empty when no tick has arrived (or its price is unparseable/absent).
   */
  public Optional<TickView> lastTick(String exchange, String tradingsymbol) {
    Object json = redis.opsForHash().get(LAST_TICK_HASH, exchange + ":" + tradingsymbol);
    if (json == null) {
      return Optional.empty();
    }
    try {
      Tick tick = objectMapper.readValue(json.toString(), Tick.class);
      if (tick.lastPrice() == null) {
        return Optional.empty();
      }
      Duration age =
          tick.timestamp() == null
              ? null
              : Duration.between(OffsetDateTime.parse(tick.timestamp()).toInstant(), clock.instant());
      return Optional.of(new TickView(new BigDecimal(tick.lastPrice()), age));
    } catch (Exception e) {
      log.warn("unparseable last tick for {}:{}: {}", exchange, tradingsymbol, e.getMessage());
      return Optional.empty();
    }
  }

  /** A last-tick price with its wall-clock {@code age} ({@code null} when the tick has no timestamp). */
  public record TickView(BigDecimal price, Duration age) {}

  /** The fields the paper ledger reads off a normalized tick (price + IST timestamp are strings). */
  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  private record Tick(String lastPrice, String timestamp) {}
}
