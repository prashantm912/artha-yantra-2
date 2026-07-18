package in.arthayantra.marketdata.upstox.canary;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.common.web.time.Ist;
import in.arthayantra.marketcalendar.MarketCalendar;
import in.arthayantra.marketdata.alerts.NtfyClient;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import in.arthayantra.marketdata.upstox.UpstoxFnoMasterClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

/**
 * The daily Upstox Market-Information contract canary (ADR-0002 U7) — the twin of {@link
 * in.arthayantra.marketdata.openalgo.canary.OpenAlgoContractCanary}. Direct {@code RestClient} GET
 * probes with the dedicated analytics token ({@code get-fii} cash + {@code get-fii} F&amp;O + {@code
 * get-dii} cash) BYPASSING the domain mapping, recursively diffed against {@code
 * upstox-contract-manifest.json} (sentinels for the CONSUMED fields only — {@code buy_amount}/{@code
 * sell_amount}/{@code time_stamp}/{@code buy_contracts}, which U2 + U6 depend on). A missing or
 * type-changed consumed field -&gt; ntfy critical; a newly added top-level block -&gt; warning.
 * Idempotent once per trading day via a Redis marker; the wire SHAPE is also guarded at build time
 * by {@code UpstoxWireContractTest}.
 *
 * <p><b>W-U4 cutover-prep:</b> the canary also probes the <b>live-capture</b> shapes the cutover
 * flips onto — {@code GET /v2/option/chain} (the {@code source.optionchain=upstox} OI capture),
 * {@code GET /v2/market-quote/quotes} (the {@code source.quotes=upstox} spot/FUT quotes), and {@code
 * GET /v3/feed/market-data-feed/authorize} (the {@code source.ticker=upstox} v3 WS handshake) — so a
 * Upstox-side rename/remove/retype of a field the LIVE capture consumes is caught here, off the
 * critical path, exactly like the Market-Information probes. The WS market-data FEED itself is a
 * binary protobuf ({@code MarketDataFeedV3.FeedResponse}), NOT JSON, so its field-number drift is
 * guarded off-band by the {@code .proto} reference + {@code FeedFrameDecoderTest}; only the JSON
 * authorize-GET is canaried. These probes use known-good index fixtures so a stale weekly expiry is
 * never a false alarm.
 *
 * <p><b>Margin probe (EXT-03 residual):</b> the canary also POSTs a 1-lot NIFTY short-CE basket to
 * {@code POST /v2/charges/margin} — the endpoint the ARMED F9 heat cap consumes ({@code
 * UpstoxMarginClient}). This is the ONE shape the client itself cannot guard: {@code
 * UpstoxMarginClient.aggregate} SUMS {@code span_margin} across legs, so a rename of just that
 * per-leg field (with {@code total_margin}/{@code required_margin}/{@code final_margin} still valid
 * and positive — the wholesale-empty guard never trips) silently sums to a FALSE ₹0 SPAN → a false
 * 0% heat that defeats the cap. The probe resolves a near-ATM front-weekly NIFTY key off the {@link
 * UpstoxFnoMasterClient} (the expiry re-resolves each run off the calendar, so it never goes stale)
 * and diffs the RAW response; it is fully tolerant like the option-chain probe — an unresolved key,
 * an empty/off-hours margins body, or any Upstox 4xx (stale probe contract, a lot-size change →
 * {@code UDAPI1104}) is skipped, never an alarm.
 */
public class UpstoxContractCanary {

  /** The recorded outcome (status surface + Redis). */
  public record CanaryResult(OffsetDateTime lastContractCheck, List<String> drift) {}

  static final String MARKER_KEY_PREFIX = "upstox:canary:";
  static final String RESULT_KEY = "upstox:contract:check";

  // A fixed known-good HISTORICAL sample for the max-pain/pcr probes — Upstox keeps Market-Information
  // history from 1 Apr 2026, so this (instrument_key, expiry, date) always returns data: no weekly-
  // expiry staleness. If Upstox ever purges it (data:null), the probe is skipped, never a false alarm.
  private static final String OPTION_PROBE_KEY = "NSE_INDEX|Nifty 50";
  private static final String OPTION_PROBE_EXPIRY = "2026-06-23";
  private static final String OPTION_PROBE_DATE = "2026-06-22";

  // Live-capture probes (W-U4). The option-chain probe is on the index spot key + a near expiry;
  // like the max-pain/pcr probes it is TOLERANT of an empty/null `data` (a stale weekly expiry is
  // skipped, never an alarm) — the shape is still validated whenever the chain returns rows. The
  // quote + WS-authorize probes are expiry-free.
  private static final String CHAIN_PROBE_KEY = "NSE_INDEX|Nifty 50";
  private static final String CHAIN_PROBE_EXPIRY = "2026-06-30";
  private static final String QUOTE_PROBE_KEYS = "NSE_INDEX|Nifty 50";

  // Margin probe (EXT-03 residual). The weekly expiry is resolved FRESH each run off the calendar
  // (never a stale hardcode); a small round-strike ladder spans the plausible NIFTY band so one
  // near-ATM strike stays listed even as spot drifts (all unlisted -> the probe skips, never alarms).
  // qty MUST be a lot multiple or Upstox 400s UDAPI1104 (tolerated as a skip). The lot is resolved
  // FROM the F&O master alongside the key (UpstoxFnoMasterClient.resolve), so an NSE lot change (e.g.
  // NIFTY 75→65, NSE FAOP/70616) flows through automatically and never blinds the probe. The hardcoded
  // fallback below is used ONLY if the master row omits lot_size (never seen for a listed contract).
  private static final String MARGIN_PROBE_UNDERLYING = "NIFTY";
  private static final List<BigDecimal> MARGIN_PROBE_STRIKES =
      List.of(
          new BigDecimal("24000"),
          new BigDecimal("25000"),
          new BigDecimal("23000"),
          new BigDecimal("26000"),
          new BigDecimal("22000"));
  // Fallback tradable lot when the master omits lot_size — the documented NIFTY 2026 lot (65).
  private static final int MARGIN_PROBE_QTY_FALLBACK = 65;

  private static final Logger log = LoggerFactory.getLogger(UpstoxContractCanary.class);

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;
  private final StringRedisTemplate redis;
  private final NtfyClient ntfy;
  private final MarketCalendar calendar;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<UpstoxFnoMasterClient> fnoMaster;
  private final Counter driftCounter;
  private final JsonNode manifest;
  private final AtomicReference<CanaryResult> lastResult = new AtomicReference<>();

  /** Wires the canary; the manifest loads from the committed classpath resource. */
  public UpstoxContractCanary(
      RestClient.Builder builder,
      UpstoxAnalyticsProperties properties,
      StringRedisTemplate redis,
      NtfyClient ntfy,
      MarketCalendar calendar,
      Clock clock,
      ObjectMapper objectMapper,
      ObjectProvider<UpstoxFnoMasterClient> fnoMaster,
      MeterRegistry meterRegistry) {
    this.restClient = builder.baseUrl(properties.baseUrl()).build();
    this.properties = properties;
    this.redis = redis;
    this.ntfy = ntfy;
    this.calendar = calendar;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.fnoMaster = fnoMaster;
    this.driftCounter = meterRegistry.counter("ay_upstox_contract_drift_total");
    try (InputStream in = getClass().getResourceAsStream("/upstox-contract-manifest.json")) {
      this.manifest = objectMapper.readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("upstox contract manifest unreadable", e);
    }
  }

  /** Trading-day-gated, once per day via the Redis marker (default 19:30 IST, after EOD settles). */
  @Scheduled(cron = "${artha.upstox.canary-cron:0 30 19 * * MON-FRI}", zone = "Asia/Kolkata")
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
    try {
      diffJson("fii_cash", get("fii", "NSE_EQ|CASH"), drift);
      diffJson("fii_fno", get("fii", "NSE_FO|INDEX_OPTIONS"), drift);
      diffJson("dii_cash", get("dii", "NSE_EQ|CASH"), drift);
      diffTolerant("max_pain", getOptionAnalytics("max-pain"), drift);
      diffTolerant("pcr", getOptionAnalytics("pcr"), drift);
      // Live-capture shapes (W-U4 cutover-prep). Chain tolerates an empty `data` (stale expiry).
      diffTolerant("option_chain", getOptionChain(), drift);
      diffJson("market_quote", getMarketQuote(), drift);
      diffJson("ws_authorize", getWsAuthorize(), drift);
      // The ARMED F9 SPAN-margin shape (EXT-03 residual) — tolerant, self-fresh expiry.
      diffMargin("margin", drift);
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

  private String get(String endpoint, String dataType) {
    return restClient
        .get()
        .uri(
            builder ->
                builder
                    .path("/v2/market/" + endpoint)
                    .queryParam("data_type", dataType)
                    .queryParam("interval", "1D")
                    .build())
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(String.class);
  }

  private String getOptionAnalytics(String endpoint) {
    return restClient
        .get()
        .uri(
            builder ->
                builder
                    .path("/v2/market/" + endpoint)
                    .queryParam("instrument_key", OPTION_PROBE_KEY)
                    .queryParam("expiry", OPTION_PROBE_EXPIRY)
                    .queryParam("date", OPTION_PROBE_DATE)
                    .queryParam("bucket_interval", 60)
                    .build())
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(String.class);
  }

  /** The live OI-capture shape — {@code GET /v2/option/chain} the {@code source.optionchain} flip consumes. */
  private String getOptionChain() {
    return restClient
        .get()
        .uri(
            builder ->
                builder
                    .path("/v2/option/chain")
                    .queryParam("instrument_key", CHAIN_PROBE_KEY)
                    .queryParam("expiry_date", CHAIN_PROBE_EXPIRY)
                    .build())
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(String.class);
  }

  /** The live spot/FUT quote shape — {@code GET /v2/market-quote/quotes} the {@code source.quotes} flip consumes. */
  private String getMarketQuote() {
    return restClient
        .get()
        .uri(
            builder ->
                builder
                    .path("/v2/market-quote/quotes")
                    .queryParam("instrument_key", QUOTE_PROBE_KEYS)
                    .build())
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(String.class);
  }

  /** The v3 WS handshake shape — {@code GET /v3/feed/market-data-feed/authorize} the {@code source.ticker} flip consumes. */
  private String getWsAuthorize() {
    return restClient
        .get()
        .uri("/v3/feed/market-data-feed/authorize")
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(String.class);
  }

  /**
   * The live SPAN-margin shape — {@code POST /v2/charges/margin} the ARMED F9 heat cap consumes.
   * Resolves a near-ATM front-weekly NIFTY option to its Upstox key off the F&amp;O master, prices a
   * 1-lot short-CE basket, and diffs the RAW response so a {@code span_margin} rename (which {@code
   * UpstoxMarginClient.aggregate} would silently sum to a false ₹0) is caught off the live path.
   * Fully tolerant, like {@link #diffTolerant}: an unresolved key (master off / strike unlisted), an
   * empty/off-hours margins body, or any Upstox 4xx (stale probe contract, a lot-size change →
   * {@code UDAPI1104}) is skipped, never an alarm — the shape is checked only when Upstox prices it.
   */
  private void diffMargin(String probe, List<String> drift) throws Exception {
    UpstoxFnoMasterClient master = fnoMaster.getIfAvailable();
    if (master == null) {
      log.info("upstox canary: {} probe skipped (F&O master unavailable)", probe);
      return;
    }
    LocalDate expiry = calendar.nextWeeklyIndexExpiry(LocalDate.now(clock.withZone(Ist.ZONE)));
    UpstoxFnoMasterClient.FnoLeg leg = null;
    for (BigDecimal strike : MARGIN_PROBE_STRIKES) {
      leg = master.resolve("NFO", MARGIN_PROBE_UNDERLYING, "CE", expiry, strike);
      if (leg != null) {
        break;
      }
    }
    if (leg == null) {
      log.info("upstox canary: {} probe skipped (no listed NIFTY strike for weekly expiry {})", probe, expiry);
      return;
    }
    // The tradable lot from the master survives the next NSE lot change; fall back to the documented
    // default only when the master row omits lot_size (never seen for a listed F&O contract).
    int qty = leg.lotSize() != null ? leg.lotSize() : MARGIN_PROBE_QTY_FALLBACK;
    String body;
    try {
      body = postMargin(leg.instrumentKey(), qty);
    } catch (HttpClientErrorException http) {
      // A stale probe contract / lot-size change 4xx (UDAPI1104) — skip like an empty chain, never alarm.
      // A 5xx is NOT caught here: it propagates to the outer probe handler → PROBE_FAILED alert (an
      // Upstox server fault is a real signal, not benign drift-tolerance).
      log.info("upstox canary: {} probe skipped (Upstox {})", probe, http.getStatusCode());
      return;
    }
    JsonNode margins = objectMapper.readTree(body).path("data").path("margins");
    if (!margins.isArray() || margins.isEmpty()) {
      log.info("upstox canary: {} probe has no margins (skipped, not drift)", probe);
      return;
    }
    diffJson(probe, body, drift);
  }

  /** POSTs the 1-lot NIFTY short-CE basket to {@code /v2/charges/margin} and returns the raw JSON. */
  private String postMargin(String instrumentKey, int quantity) {
    return restClient
        .post()
        .uri("/v2/charges/margin")
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new MarginProbeRequest(
                List.of(new MarginProbeLeg(instrumentKey, quantity, "SELL", "D"))))
        .retrieve()
        .body(String.class);
  }

  /** The {@code /v2/charges/margin} request body — one instrument per basket leg. */
  private record MarginProbeRequest(List<MarginProbeLeg> instruments) {}

  private record MarginProbeLeg(
      @JsonProperty("instrument_key") String instrumentKey,
      int quantity,
      @JsonProperty("transaction_type") String transactionType,
      String product) {}

  /** Like {@link #diffJson} but tolerant of {@code data:null}/empty (a purged sample or stale expiry) — skip, never alarm. */
  private void diffTolerant(String probe, String body, List<String> drift) throws Exception {
    JsonNode actual = objectMapper.readTree(body);
    JsonNode data = actual.path("data");
    if (data.isNull() || data.isMissingNode() || (data.isArray() && data.isEmpty())) {
      log.info("upstox canary: {} probe has no data (skipped, not drift)", probe);
      return;
    }
    diffJson(probe, body, drift);
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
    Set<String> knownPaths = new HashSet<>();
    for (JsonNode k : manifest.path("knownTopLevel")) {
      knownPaths.add(k.asText());
    }
    for (Iterator<String> it2 = expected.fieldNames(); it2.hasNext(); ) {
      knownPaths.add(it2.next());
    }
    collectNewPaths(actual, "", probe, knownPaths, drift, 0);
  }

  /** Recursively flags actual paths (depth &lt;= 3) the manifest does not know about. */
  private static void collectNewPaths(
      JsonNode node, String prefix, String probe, Set<String> known, List<String> drift, int depth) {
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

  /** Path resolution with {@code *} = the first element of an array/object. */
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
      ntfy.send("Upstox contract drift", "urgent", String.join("\n", result.drift()));
    } else if (added > 0) {
      driftCounter.increment((double) added);
      ntfy.send("Upstox contract: new fields", "default", String.join("\n", result.drift()));
    }
    try {
      redis.opsForValue().set(RESULT_KEY, objectMapper.writeValueAsString(result));
    } catch (Exception e) {
      log.warn("upstox canary result publish failed: {}", e.getMessage());
    }
    log.info("upstox contract canary: {} drift entries", result.drift().size());
  }

  private boolean isTradingDaySafe(LocalDate day) {
    try {
      return calendar.isTradingDay(day);
    } catch (IllegalArgumentException uncoveredYear) {
      return false;
    }
  }
}
