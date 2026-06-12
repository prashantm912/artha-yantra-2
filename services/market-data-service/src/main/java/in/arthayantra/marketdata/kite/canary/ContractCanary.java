package in.arthayantra.marketdata.kite.canary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.AccessTokenProvider;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * The daily Kite contract canary (B-9, S2B): 3–4 direct {@code RestClient} probes with the stored
 * token — profile, one pinned-index quote, one historical page, the dump CSV header — BYPASSING
 * the SDK's Gson→POJO mapping (which silently discards unknown keys). Recursive field-set/type
 * diff against committed manifests derived from the same WireMock fixtures. Missing/type-changed
 * → ntfy critical; newly added → warning. Runs ONCE per trading day on the first LIVE transition
 * (the day's token cannot exist before the morning ritual — never a pre-dawn cron), idempotent
 * via a Redis daily-once marker; no-op under mock by profile binding.
 */
public class ContractCanary {

  /** The recorded outcome (status surface + Redis). */
  public record CanaryResult(OffsetDateTime lastContractCheck, List<String> drift) {}

  static final String MARKER_KEY_PREFIX = "kite:canary:";

  private static final Logger log = LoggerFactory.getLogger(ContractCanary.class);

  private final RestClient restClient;
  private final String apiKey;
  private final AccessTokenProvider tokenProvider;
  private final KiteCallExecutor executor;
  private final org.springframework.data.redis.core.StringRedisTemplate redis;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final Counter driftCounter;
  private final JsonNode manifest;
  private final AtomicReference<CanaryResult> lastResult = new AtomicReference<>();

  /** Wires the canary; the manifest loads from the committed classpath resource. */
  public ContractCanary(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      AccessTokenProvider tokenProvider,
      KiteCallExecutor executor,
      org.springframework.data.redis.core.StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.tokenProvider = tokenProvider;
    this.executor = executor;
    this.redis = redis;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.driftCounter = meterRegistry.counter("ay_kite_contract_drift_total");
    try (InputStream in = getClass().getResourceAsStream("/kite-contract-manifest.json")) {
      this.manifest = objectMapper.readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("kite contract manifest unreadable", e);
    }
  }

  /** First-LIVE-transition trigger: trading-day-gated, once per day via the Redis marker. */
  public void maybeRunDaily() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    Boolean first =
        redis.opsForValue().setIfAbsent(MARKER_KEY_PREFIX + today, "RUNNING");
    if (!Boolean.TRUE.equals(first)) {
      return; // already ran today — idempotent
    }
    runNow();
  }

  /** One forced canary pass (tests + ops). */
  public CanaryResult runNow() {
    Optional<String> token = tokenProvider.currentToken();
    if (token.isEmpty()) {
      log.warn("contract canary skipped — no live token");
      return lastResult.get();
    }
    List<String> drift = new ArrayList<>();
    try {
      diffJson("profile", get("/user/profile", token.get()), drift);
      diffJson("quote", get("/quote?i=NSE:NIFTY+50", token.get()), drift);
      diffJson("historical", get("/instruments/historical/256265/minute", token.get()), drift);
      diffDumpHeader(get("/instruments/NSE", token.get()), drift);
    } catch (Exception probeFailure) {
      drift.add("PROBE_FAILED:" + probeFailure.getMessage());
    }
    CanaryResult result = new CanaryResult(OffsetDateTime.now(clock), List.copyOf(drift));
    lastResult.set(result);
    publish(result);
    return result;
  }

  /** Last outcome for the status surface. */
  public Optional<CanaryResult> lastResult() {
    return Optional.ofNullable(lastResult.get());
  }

  private String get(String uri, String token) {
    return executor.execute(
        KiteCallExecutor.Family.MISC,
        () ->
            restClient
                .get()
                .uri(uri)
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + apiKey + ":" + token)
                .retrieve()
                .body(String.class));
  }

  private void diffJson(String probe, String body, List<String> drift) throws Exception {
    JsonNode actual = objectMapper.readTree(body);
    JsonNode expected = manifest.path(probe);
    for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      JsonNode node = resolve(actual, entry.getKey());
      if (node == null || node.isMissingNode()) {
        drift.add("MISSING:" + probe + "." + entry.getKey());
      } else if (!typeMatches(node, entry.getValue().asText())) {
        drift.add("TYPE:" + probe + "." + entry.getKey());
      }
    }
    // newly added fields are a warning — RECURSIVE key-set walk (B-9), not top-level only
    java.util.Set<String> knownPaths = new java.util.HashSet<>();
    for (JsonNode k : manifest.path("knownTopLevel")) {
      knownPaths.add(k.asText());
    }
    for (Iterator<String> it2 = expected.fieldNames(); it2.hasNext(); ) {
      knownPaths.add(it2.next());
    }
    collectNewPaths(actual, "", probe, knownPaths, drift, 0);
  }

  /** Recursively flags actual paths (depth ≤ 3) the manifest does not know about. */
  private static void collectNewPaths(
      JsonNode node, String prefix, String probe, java.util.Set<String> known, List<String> drift, int depth) {
    if (depth > 3 || !node.isObject()) {
      return;
    }
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> entry = it.next();
      String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
      boolean covered =
          known.stream()
              .anyMatch(
                  k -> {
                    String normalized = path.replaceFirst("^data\\.[^.]+\\.", "data.*.");
                    return k.equals(path)
                        || k.equals(normalized)
                        || k.startsWith(path + ".")
                        || k.startsWith(normalized + ".")
                        || path.startsWith(k + ".")
                        || normalized.startsWith(k + ".");
                  });
      if (!covered) {
        drift.add("NEW:" + probe + "." + path);
        continue; // one entry per new subtree
      }
      collectNewPaths(entry.getValue(), path, probe, known, drift, depth + 1);
    }
  }

  private void diffDumpHeader(String csv, List<String> drift) {
    String header = csv.lines().findFirst().orElse("");
    List<String> columns = List.of(header.split(","));
    for (JsonNode expected : manifest.path("dumpHeader")) {
      if (!columns.contains(expected.asText())) {
        drift.add("MISSING:dump." + expected.asText());
      }
    }
    for (String column : columns) {
      boolean known = false;
      for (JsonNode expected : manifest.path("dumpHeader")) {
        known |= expected.asText().equals(column.trim());
      }
      if (!known && !column.isBlank()) {
        drift.add("NEW:dump." + column.trim());
      }
    }
  }

  /** Path resolution with {@code *} = the first field of an object (quote's instrument key). */
  private static JsonNode resolve(JsonNode root, String path) {
    JsonNode node = root;
    for (String segment : path.split("\\.", -1)) {
      if (node == null) {
        return null;
      }
      if ("*".equals(segment)) {
        Iterator<JsonNode> elements = node.elements();
        node = elements.hasNext() ? elements.next() : null;
      } else {
        node = node.get(segment);
      }
    }
    return node;
  }

  private static boolean typeMatches(JsonNode node, String expectedType) {
    return switch (expectedType) {
      case "string" -> node.isTextual();
      case "number" -> node.isNumber();
      case "array" -> node.isArray();
      case "object" -> node.isObject();
      default -> true;
    };
  }

  private void publish(CanaryResult result) {
    long missingOrChanged =
        result.drift().stream().filter(d -> d.startsWith("MISSING:") || d.startsWith("TYPE:") || d.startsWith("PROBE_FAILED:")).count();
    long added = result.drift().stream().filter(d -> d.startsWith("NEW:")).count();
    if (missingOrChanged > 0) {
      driftCounter.increment((double) missingOrChanged);
      ntfy.send("Kite contract drift", "urgent", String.join("\n", result.drift()));
    } else if (added > 0) {
      driftCounter.increment((double) added);
      ntfy.send("Kite contract: new fields", "default", String.join("\n", result.drift()));
    }
    try {
      redis
          .opsForValue()
          .set("kite:contract:check", objectMapper.writeValueAsString(result));
    } catch (Exception e) {
      log.warn("canary result publish failed: {}", e.getMessage());
    }
    log.info("contract canary: {} drift entries", result.drift().size());
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
