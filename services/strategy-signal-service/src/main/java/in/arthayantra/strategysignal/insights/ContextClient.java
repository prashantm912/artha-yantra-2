package in.arthayantra.strategysignal.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The insight plane's read seam to market-data's trust/context rails (INT design §9.2): a
 * {@code RestClient} to the configured market-data base URL, structurally the {@code MarketOiClient}
 * precedent but with the explicit short connect/read timeouts of {@code PaperMarginClient} (1.5s /
 * 2s) so a slow (not merely down) market-data can never stall an insight sweep. EVERY read is
 * fail-soft: any transport/parse failure yields {@link Optional#empty()} → the caller degrades that
 * family to a conservative state, never a 5xx (§7.1, "any fetch failure → conservative DEGRADED").
 */
@Component
public class ContextClient {

  private static final Logger log = LoggerFactory.getLogger(ContextClient.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the market-data base URL with explicit short timeouts (fail-soft, off the hot path). */
  public ContextClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1500));
    factory.setReadTimeout(Duration.ofMillis(2000));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.objectMapper = objectMapper;
  }

  /** The per-source EOD ingest health board ({@code GET /api/v1/market/health/ingest}), or empty. */
  public Optional<JsonNode> ingestHealth() {
    return get("/api/v1/market/health/ingest");
  }

  /** The live data-plane health ({@code GET /api/v1/market/health/data}), or empty. */
  public Optional<JsonNode> dataHealth() {
    return get("/api/v1/market/health/data");
  }

  private Optional<JsonNode> get(String path) {
    try {
      String body = restClient.get().uri(path).retrieve().body(String.class);
      if (body == null || body.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readTree(body));
    } catch (Exception e) {
      log.debug("insight trust read {} unavailable: {}", path, e.getMessage());
      return Optional.empty();
    }
  }
}
