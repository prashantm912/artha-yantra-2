package in.arthayantra.marketdata.openalgo.canary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.kite.KiteCallExecutor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The daily OpenAlgo contract canary — the twin of {@link
 * in.arthayantra.marketdata.kite.canary.ContractCanary}. Direct {@code RestClient} POST probes with
 * the static OpenAlgo API key ({@code quotes}, {@code history}, {@code optionchain}) BYPASSING the
 * domain mapping, recursively diffed against {@code openalgo-contract-manifest.json} (sentinels for
 * CONSUMED fields only — crucially the per-strike {@code chain[].ce.oi}/{@code chain[].pe.oi} the OI
 * capture depends on, plan §17.11 / M0). Missing/type-changed -> ntfy critical; newly added
 * top-level block -> warning. Idempotent once per trading day via a Redis marker.
 *
 * <p>OpenAlgo greeks are NOT probed for VALUES here — IV/greeks come from {@code libs/black76-math}
 * (plan §17.9); this canary only guards the wire SHAPE of the consumed capture fields.
 */
public class OpenAlgoContractCanary {

  /** The recorded outcome (status surface + Redis). */
  public record CanaryResult(OffsetDateTime lastContractCheck, List<String> drift) {}

  static final String MARKER_KEY_PREFIX = "openalgo:canary:";
  static final String RESULT_KEY = "openalgo:contract:check";

  private static final Logger log = LoggerFactory.getLogger(OpenAlgoContractCanary.class);
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final RestClient restClient;
  private final String apiKey;
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
  public OpenAlgoContractCanary(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      KiteCallExecutor executor,
      org.springframework.data.redis.core.StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.apiKey = apiKey;
    this.executor = executor;
    this.redis = redis;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.driftCounter = meterRegistry.counter("ay_openalgo_contract_drift_total");
    try (InputStream in = getClass().getResourceAsStream("/openalgo-contract-manifest.json")) {
      this.manifest = objectMapper.readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("openalgo contract manifest unreadable", e);
    }
  }

  /** Trading-day-gated, once per day via the Redis marker. */
  public void maybeRunDaily() {
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    if (!isTradingDaySafe(today)) {
      return;
    }
    Boolean first = redis.opsForValue().setIfAbsent(MARKER_KEY_PREFIX + today, "RUNNING");
    if (!Boolean.TRUE.equals(first)) {
      return; // already ran today — idempotent
    }
    runNow();
  }

  /** One forced canary pass (tests + ops). */
  public CanaryResult runNow() {
    List<String> drift = new ArrayList<>();
    LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
    String from = DATE.format(today.minusDays(7));
    String to = DATE.format(today);
    try {
      diffJson(
          "quote",
          post("/api/v1/quotes", Map.of("apikey", apiKey, "symbol", "NIFTY", "exchange", "NSE_INDEX")),
          drift);
      diffJson(
          "history",
          post(
              "/api/v1/history",
              Map.of(
                  "apikey", apiKey, "symbol", "NIFTY", "exchange", "NSE_INDEX",
                  "interval", "D", "start_date", from, "end_date", to)),
          drift);
      // The live /optionchain OI-coverage probe (per-strike chain[].ce.oi/pe.oi — the M0 guard) is
      // Phase 1 (§17.11): /optionchain requires `underlying` + a resolved `expiry_date` (DDMMMYY via
      // /api/v1/expiry), which arrives with the routing cutover. The optionchain WIRE SHAPE is
      // guarded meanwhile by OpenAlgoWireContractTest.
    } catch (Exception probeFailure) {
      drift.add("PROBE_FAILED:" + probeFailure.getMessage());
    }
    CanaryResult result = new CanaryResult(OffsetDateTime.now(clock), List.copyOf(drift));
    lastResult.set(result);
    publish(result);
    return result;
  }

  /** Last outcome for the status surface. */
  public java.util.Optional<CanaryResult> lastResult() {
    return java.util.Optional.ofNullable(lastResult.get());
  }

  private String post(String uri, Map<String, ?> body) {
    String requestBody;
    try {
      requestBody = objectMapper.writeValueAsString(body);
    } catch (Exception e) {
      throw new IllegalStateException("openalgo canary request serialize failed", e);
    }
    KiteCallExecutor.Family family =
        uri.endsWith("/history")
            ? KiteCallExecutor.Family.OPENALGO_HISTORICAL
            : KiteCallExecutor.Family.OPENALGO_QUOTE;
    return executor.execute(
        family,
        () ->
            restClient
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
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
    java.util.Set<String> knownPaths = new java.util.HashSet<>();
    for (JsonNode k : manifest.path("knownTopLevel")) {
      knownPaths.add(k.asText());
    }
    for (Iterator<String> it2 = expected.fieldNames(); it2.hasNext(); ) {
      knownPaths.add(it2.next());
    }
    collectNewPaths(actual, "", probe, knownPaths, drift, 0);
  }

  /** Recursively flags actual paths (depth <= 3) the manifest does not know about. */
  private static void collectNewPaths(
      JsonNode node,
      String prefix,
      String probe,
      java.util.Set<String> known,
      List<String> drift,
      int depth) {
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

  /** Path resolution with {@code *} = the first element of an array/object (e.g. {@code chain.*}). */
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
        result.drift().stream()
            .filter(
                d ->
                    d.startsWith("MISSING:")
                        || d.startsWith("TYPE:")
                        || d.startsWith("PROBE_FAILED:"))
            .count();
    long added = result.drift().stream().filter(d -> d.startsWith("NEW:")).count();
    if (missingOrChanged > 0) {
      driftCounter.increment((double) missingOrChanged);
      ntfy.send("OpenAlgo contract drift", "urgent", String.join("\n", result.drift()));
    } else if (added > 0) {
      driftCounter.increment((double) added);
      ntfy.send("OpenAlgo contract: new fields", "default", String.join("\n", result.drift()));
    }
    try {
      redis.opsForValue().set(RESULT_KEY, objectMapper.writeValueAsString(result));
    } catch (Exception e) {
      log.warn("openalgo canary result publish failed: {}", e.getMessage());
    }
    log.info("openalgo contract canary: {} drift entries", result.drift().size());
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
