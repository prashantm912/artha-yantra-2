package in.arthayantra.strategysignal.signals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.arthayantra.strategyengine.series.EngineCandle;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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

  /** Wires the configured base URL. */
  public MarketDataCandlesClient(
      RestClient.Builder builder,
      ObjectMapper objectMapper,
      @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
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
      JsonNode items = objectMapper.readTree(body).path("items");
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
      return candles;
    } catch (RestClientException | java.io.IOException e) {
      log.warn(
          "candle warm-up fetch failed for {}:{} {} — engine starts cold: {}",
          exchange, tradingsymbol, interval, e.getMessage());
      return List.of();
    }
  }
}
