package in.arthayantra.strategysignal.registry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** RestClient-backed lookup against {@code GET /api/v1/instruments/{exchange}/{tradingsymbol}}. */
@Component
public class RestMarketDataInstrumentClient implements MarketDataInstrumentClient {

  private final RestClient restClient;

  /** Wires the configured base URL. */
  public RestMarketDataInstrumentClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  @Override
  public boolean exists(String exchange, String tradingsymbol) {
    try {
      restClient
          .get()
          .uri("/api/v1/instruments/{exchange}/{tradingsymbol}", exchange, tradingsymbol)
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.NotFound notFound) {
      return false;
    } catch (RestClientException e) {
      throw new MarketDataUnavailableException(
          "market-data unreachable for instrument check", e);
    }
  }
}
