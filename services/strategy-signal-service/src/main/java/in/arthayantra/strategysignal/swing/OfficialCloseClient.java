package in.arthayantra.strategysignal.swing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reads the OFFICIAL NSE closing prices for a session over market-data REST (ledger H9). D8: this
 * service holds no marketdata grant, so REST is the only seam — modelled on
 * {@code MarketDataCandlesClient}, including the bounded total-response timeout.
 *
 * <p><b>Fail-soft to EMPTY, always.</b> Its one caller is the swing exit settle, and an exit may
 * never refuse ("entries need fresh truth — you can always NOT enter; exits need the best available
 * truth — you cannot refuse to leave forever"). An empty map means every symbol takes the caller's
 * counted, alerted fallback to the candle close, which is strictly no worse than 100% of the
 * behaviour that preceded H9. It must never throw onto that path.
 */
@Component
public class OfficialCloseClient {

  private static final Logger log = LoggerFactory.getLogger(OfficialCloseClient.class);

  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  /** Wires the configured market-data base URL with a bounded read timeout. */
  @Autowired
  public OfficialCloseClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl,
      @Value("${artha.marketdata.eod-close-timeout-ms:5000}") long fetchTimeoutMs) {
    // Bounded for the same reason MarketDataCandlesClient is: this runs inside the swing settle,
    // which holds a run mutex and races a deadline. A slow-but-alive market-data must degrade to the
    // fallback quickly rather than park the settle. JdkClientHttpRequestFactory's read timeout maps
    // to the JDK total-response timeout, so a hung server throws and the catch below returns empty.
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
    factory.setReadTimeout(Duration.ofMillis(fetchTimeoutMs));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    this.objectMapper = objectMapper;
  }

  /**
   * Official closes for {@code symbols} on {@code date}, keyed by tradingsymbol. A symbol the
   * exchange has not published is ABSENT from the map — never present with a null.
   *
   * <p>⚠️ Every value is passed as a URI VARIABLE, not baked into the query template. {@code &} is a
   * RESERVED character that {@code EncodingMode.TEMPLATE_AND_VALUES} leaves alone inside a template
   * but strictly encodes inside a variable — and {@code J&KBANK} is a live swing holding, so the
   * template form would split its symbol into a second query parameter and silently drop it.
   *
   * <p>⚠️ The response's own {@code tradeDate} is re-checked against the requested {@code date}. The
   * server answers the date it was asked for, so this cannot currently fire; it is here because the
   * whole point of H9 is that a price is only meaningful with its session attached, and a
   * wrong-session close would be indistinguishable from a right one downstream.
   */
  public Map<String, BigDecimal> closesOn(
      String exchange, LocalDate date, Collection<String> symbols) {
    List<String> distinct =
        symbols.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    if (distinct.isEmpty()) {
      return Map.of();
    }
    try {
      String body =
          restClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/market/eod-close")
                          .queryParam("exchange", "{exchange}")
                          .queryParam("date", "{date}")
                          .queryParam("symbols", "{symbols}")
                          .build(exchange, date.toString(), String.join(",", distinct)))
              .retrieve()
              .body(String.class);
      JsonNode root = objectMapper.readTree(body);
      Map<String, BigDecimal> out = new HashMap<>();
      for (JsonNode item : root.path("items")) {
        if (!item.hasNonNull("closePrice") || !item.hasNonNull("tradingsymbol")) {
          continue;
        }
        if (!date.toString().equals(item.path("tradeDate").asText())) {
          continue;
        }
        // Decimal STRINGS parse straight into BigDecimal — never a double hop.
        out.put(item.path("tradingsymbol").asText(), new BigDecimal(item.path("closePrice").asText()));
      }
      return out;
    } catch (java.io.IOException | RuntimeException e) {
      log.error(
          "official-close fetch FAILED for {} {} ({} symbols) — every swing exit this run falls back"
              + " to the candle close, which excludes the closing auction: {}",
          exchange, date, distinct.size(), e.getMessage());
      return Map.of();
    }
  }
}
