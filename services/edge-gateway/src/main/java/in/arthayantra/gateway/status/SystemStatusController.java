package in.arthayantra.gateway.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The aggregated system status (Phase 17 / B-13): a WORST-OF rollup over the Redis shared-state
 * keys only — no per-service REST fan-out, ever. Cached 5 s; `/topic/system` deltas ride the
 * {@code kite.status} channel through the existing WS bridge. The {@code jobs} field reads
 * {@code jobs:summary} and renders zeros until backtest-service ships it (Phase 28).
 */
@RestController
public class SystemStatusController {

  private record Cached(Instant at, Map<String, Object> body) {}

  private static final Duration CACHE_TTL = Duration.ofSeconds(5);
  private static final List<String> KEYS =
      List.of("kite:session:status", "marketdata:integrity:corporate-actions", "jobs:summary");

  private final ReactiveStringRedisTemplate redis;
  private final AtomicReference<Cached> cache = new AtomicReference<>();

  /** Wires Redis. */
  public SystemStatusController(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  /** The rollup; served from the 5 s cache between Redis reads. */
  @GetMapping("/api/v1/system/status")
  public Mono<Map<String, Object>> status() {
    Cached cached = cache.get();
    if (cached != null && cached.at().plus(CACHE_TTL).isAfter(Instant.now())) {
      return Mono.just(cached.body());
    }
    return redis
        .opsForValue()
        .multiGet(KEYS)
        .map(values -> assemble(values.get(0), values.get(1), values.get(2)))
        .doOnNext(body -> cache.set(new Cached(Instant.now(), body)));
  }

  private static Map<String, Object> assemble(String kite, String integrity, String jobsSummary) {
    String kiteState = kite == null ? "UNKNOWN" : kite;
    boolean kiteHealthy =
        "MOCK".equals(kiteState) || "CONNECTED".equals(kiteState) || "LIVE".equals(kiteState);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", kiteHealthy ? "UP" : "DEGRADED"); // worst-of: kite is the only red input yet
    body.put("kite", kiteState);
    body.put("corporateActions", integrity == null ? "" : integrity);
    body.put("jobs", jobsSummary == null ? Map.of("queued", 0, "running", 0) : jobsSummary);
    body.put("asOf", Instant.now().toString());
    return body;
  }
}
