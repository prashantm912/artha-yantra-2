package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Series warm-up + cagg reads via market-data REST (D8 — this service holds no marketdata
 * grant). Decimal strings parse straight into BigDecimal — never a double hop.
 */
@Component
public class MarketDataCandlesClient {

  private static final Logger log = LoggerFactory.getLogger(MarketDataCandlesClient.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  /**
   * M14: candles endpoint responses carrying {@code stale:true} (CandlesController's B-3
   * fallback) — every caller of {@link #fetch} still gets the data unchanged, this is
   * VISIBILITY-only. See {@code ay_candle_fetch_stale_total} on {@code /actuator/prometheus}.
   */
  private final Counter staleResponses;

  /** Wires the configured base URL, registering the M14 stale-response counter. */
  @Autowired
  public MarketDataCandlesClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl,
      @Value("${artha.marketdata.candles-fetch-timeout-ms:10000}") long fetchTimeoutMs,
      MeterRegistry meterRegistry) {
    // Fail fast — this client runs on the SINGLE-THREADED signal-eval loop
    // (refreshFromRest fetches at every coarse bucket boundary). Without a bounded
    // TOTAL timeout, one slow-but-alive market-data parks the whole eval loop for the
    // rest of the session and never recovers (2026-07-14 incident: a post-restart cold
    // warmup fetch blocked ~229s → zero signals all day). JdkClientHttpRequestFactory's
    // read-timeout maps to the JDK HttpRequest total-response timeout, so a slow fetch
    // throws → the catch below returns empty → the engine keeps evaluating on the warm
    // series and self-heals once market-data recovers.
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofMillis(fetchTimeoutMs));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.objectMapper = objectMapper;
    this.staleResponses = meterRegistry.counter("ay_candle_fetch_stale_total");
  }

  /**
   * Test-only convenience (pre-M14 signature): a private, unshared registry absorbs the stale
   * counter, so every existing direct-construction call site keeps compiling byte-identical.
   */
  public MarketDataCandlesClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      String baseUrl,
      long fetchTimeoutMs) {
    this(builder, objectMapper, baseUrl, fetchTimeoutMs, new SimpleMeterRegistry());
  }

  /** Candles for an instrument+interval in {@code [from, to)}; empty on upstream failure. */
  public List<EngineCandle> fetch(
      String exchange, String tradingsymbol, String interval,
      OffsetDateTime from, OffsetDateTime to) {
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/market/candles")
                          .queryParam("exchange", exchange)
                          .queryParam("tradingsymbol", tradingsymbol)
                          .queryParam("interval", interval)
                          // UTC instants (…Z), NOT the +05:30 offset form: a literal '+' in a
                          // query value is decoded as a space by the receiver (x-www-form rules),
                          // mangling the timestamp into a 500. Same instant, no '+' to corrupt.
                          .queryParam("from", from.toInstant().toString())
                          .queryParam("to", to.toInstant().toString())
                          .queryParam("limit", 50_000)
                          .build())
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body);
      JsonNode items = root.path("items");
      List<EngineCandle> candles = new ArrayList<>(items.size());
      for (JsonNode item : items) {
        candles.add(
            new EngineCandle(
                OffsetDateTime.parse(item.path("bucket").asText()),
                new BigDecimal(item.path("open").asText()),
                new BigDecimal(item.path("high").asText()),
                new BigDecimal(item.path("low").asText()),
                new BigDecimal(item.path("close").asText()),
                item.path("volume").asLong(),
                item.hasNonNull("oi") ? new BigDecimal(item.path("oi").asText()) : null));
      }
      // M14: the envelope's `stale` flag (CandlesController's B-3 fallback marker) used to be
      // parsed by nobody — silently discarded at this HTTP boundary. Surfacing it here (a loud
      // log line + a counter) covers every caller of fetch() uniformly; the returned candles are
      // unchanged, so this is visibility-only, never a refusal gate.
      if (root.path("stale").asBoolean(false)) {
        staleResponses.increment();
        log.warn(
            "candle response STALE for {}:{} {} (asOf={}) — data used unchanged; visibility only",
            exchange, tradingsymbol, interval,
            root.hasNonNull("asOf") ? root.path("asOf").asText() : "unknown");
      }
      return candles;
    } catch (RestClientException | java.io.IOException e) {
      log.warn(
          "candle warm-up fetch failed for {}:{} {} — engine starts cold: {}",
          exchange, tradingsymbol, interval, e.getMessage());
      return List.of();
    }
  }

  /** The #11 straddle SL read: the live combined-premium slLevel + the latest combined premium. */
  public record StraddleSl(BigDecimal slLevel, BigDecimal combinedPremium) {}

  /**
   * The straddle-chart {@code slLevel} (the combined-prem session VWAP − the §3.11 buffer) + the latest
   * combined premium for a straddle strike — the live combined-prem exit monitor reads it. Empty on any
   * upstream failure / an empty session, so a missing read never triggers an exit.
   */
  public Optional<StraddleSl> straddleSl(String name, String expiry, BigDecimal strike) {
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder
                        .path("/api/v1/market/options/straddle-chart")
                        .queryParam("name", name)
                        .queryParam("strike", strike);
                    if (expiry != null && !expiry.isBlank()) {
                      uriBuilder.queryParam("expiry", expiry);
                    }
                    return uriBuilder.build();
                  })
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body);
      JsonNode items = root.path("items");
      if (!root.hasNonNull("slLevel") || !items.isArray() || items.isEmpty()) {
        return Optional.empty();
      }
      JsonNode last = items.get(items.size() - 1);
      if (!last.hasNonNull("close")) {
        return Optional.empty();
      }
      return Optional.of(
          new StraddleSl(
              new BigDecimal(root.path("slLevel").asText()),
              new BigDecimal(last.path("close").asText())));
    } catch (java.io.IOException | RuntimeException e) {
      log.warn(
          "straddle-chart slLevel fetch failed for {} {} {}: {}", name, expiry, strike, e.getMessage());
      return Optional.empty();
    }
  }
}
