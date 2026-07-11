package in.arthayantra.gateway.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.marketcalendar.MarketCalendar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * keys only — no per-service REST fan-out, ever (the {@code services[]} entries are therefore
 * derived from the shared keys those services maintain, not probed). Cached 5 s; `/topic/system`
 * deltas ride the {@code kite.status} channel through the existing WS bridge. {@code jobs} reads
 * {@code jobs:summary} (zeros until backtest-service ships it, Phase 28); {@code kite.rateBudget}
 * is the fraction of the Kite REST historical-limiter budget currently available in {@code [0,1]},
 * published to {@code kite:rate-budget} by market-data ({@code KiteRateBudgetPublisher}) — null only
 * when that key is absent/unparseable (no producer has run yet).
 */
@RestController
public class SystemStatusController {

  private record Cached(Instant at, Map<String, Object> body) {}

  private static final Duration CACHE_TTL = Duration.ofSeconds(5);
  // No fresh tick for 5 min DURING market hours ⇒ the feed/producer is likely dead (register §9-22).
  private static final long STALE_TICK_MS = 300_000;
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final List<String> KEYS =
      List.of(
          "kite:session:status",
          "kite:ticker:status",
          "marketdata:integrity:corporate-actions",
          "jobs:summary",
          "ticks:last-at",
          "kite:rate-budget");

  private final ReactiveStringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final MarketCalendar calendar = MarketCalendar.nse();
  private final AtomicReference<Cached> cache = new AtomicReference<>();

  /** Wires Redis. */
  public SystemStatusController(ReactiveStringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
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
        .map(v -> assemble(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5)))
        .doOnNext(body -> cache.set(new Cached(Instant.now(), body)));
  }

  private Map<String, Object> assemble(
      String kiteRaw,
      String tickerRaw,
      String integrity,
      String jobsSummary,
      String lastTickAt,
      String rateBudget) {
    String session =
        switch (kiteRaw == null ? "" : kiteRaw) {
          case "MOCK", "CONNECTED", "LIVE" -> "VALID";
          case "TOKEN_EXPIRED" -> "EXPIRED";
          default -> "ABSENT";
        };
    String ticker = tickerRaw == null ? "DISCONNECTED" : tickerRaw;
    Long tickAge = null;
    try {
      if (lastTickAt != null) {
        tickAge = Math.max(0, System.currentTimeMillis() - Long.parseLong(lastTickAt));
      }
    } catch (NumberFormatException ignored) {
      tickAge = null;
    }
    Map<String, Object> kite = new LinkedHashMap<>();
    kite.put("session", session);
    // Audit P1-11: the raw feed-state string ("MOCK"/"CONNECTED"/...). The B-13 rollup above
    // deliberately maps MOCK → VALID for the health view, which made the frontend's MOCK-mode
    // tag (session.store checks for "MOCK") permanently unreachable — the operator's UI showed
    // synthetic data with no marker. Map return ⇒ an added key does not drift the contract.
    kite.put("raw", kiteRaw);
    kite.put("ticker", ticker);
    kite.put("lastTickAgeMs", tickAge);
    // The Kite REST limiter headroom in [0,1], published to kite:rate-budget by market-data; null when
    // absent/unparseable (no producer has run yet). Map return ⇒ this key never drifts the contract.
    kite.put("rateBudget", parseRateBudget(rateBudget));

    Object jobs = Map.of("queued", 0, "running", 0);
    if (jobsSummary != null) {
      try {
        jobs = objectMapper.readValue(jobsSummary, Map.class);
      } catch (Exception unparseable) {
        // keep zeros — Phase 28 owns the producer
      }
    }
    boolean kiteHealthy = "VALID".equals(session);
    String phase = marketPhase();
    List<Map<String, Object>> services =
        List.of(
            mapOf("edge-gateway", "UP"),
            // derived from the shared keys market-data maintains — never a REST probe
            mapOf("market-data-service", marketDataStatus(kiteRaw, tickAge, phase)));

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("overall", kiteHealthy ? "UP" : "DEGRADED");
    body.put("status", kiteHealthy ? "UP" : "DEGRADED"); // back-compat alias
    body.put("services", services);
    body.put("kite", kite);
    body.put("market", Map.of("phase", phase));
    body.put("corporateActions", integrity == null ? "" : integrity);
    body.put("jobs", jobs);
    body.put("asOf", OffsetDateTime.now(IST).toString());
    return body;
  }

  private String marketPhase() {
    try {
      var nowIst = OffsetDateTime.now(IST);
      if (!calendar.isTradingDay(nowIst.toLocalDate())) {
        return "CLOSED";
      }
      LocalTime t = nowIst.toLocalTime();
      if (!t.isBefore(LocalTime.of(9, 0)) && t.isBefore(MarketCalendar.SESSION_OPEN)) {
        return "PRE_OPEN";
      }
      return calendar.isOpen(nowIst.toInstant()) ? "OPEN" : "CLOSED";
    } catch (IllegalArgumentException uncoveredYear) {
      return "CLOSED";
    }
  }

  /**
   * The market-data health for the rollup (register §9-22). It was UP forever from the mere PRESENCE
   * of the {@code kite:session:status} key — a lingering key survives a market-data crash, so a dead
   * feed still read UP with no freshness bound. During market hours (phase OPEN) require a FRESH tick
   * heartbeat ({@code ticks:last-at} within {@link #STALE_TICK_MS}); a stale/absent heartbeat then ⇒
   * DEGRADED. Off-hours there are no ticks, so the freshness check is skipped (no false DEGRADED). No
   * key ⇒ UNKNOWN, as before. Still no REST probe — purely the shared keys market-data maintains.
   */
  static String marketDataStatus(String kiteRaw, Long tickAgeMs, String phase) {
    if (kiteRaw == null) {
      return "UNKNOWN";
    }
    if ("OPEN".equals(phase) && (tickAgeMs == null || tickAgeMs > STALE_TICK_MS)) {
      return "DEGRADED";
    }
    return "UP";
  }

  /** The published {@code kite:rate-budget} as a Double, or null when absent/unparseable. */
  static Double parseRateBudget(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return Double.parseDouble(raw);
    } catch (NumberFormatException notANumber) {
      return null;
    }
  }

  private static Map<String, Object> mapOf(String name, String status) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("status", status);
    return m;
  }
}
