package in.arthayantra.backtest.replay.deepswing;

import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls market-data's SYNCHRONOUS DEEP_SWING endpoint ({@code POST
 * /api/v1/market/screener/deep-swing/run}) so the deep-sim STAYS where its ~11-year {@code candles}@1d
 * universe lives (the simulator is never ported — audit P0-3). Unlike the best-effort {@code
 * MarketDataClient} (which rides the shared 180 s {@code spring.http.client.read-timeout}), a full
 * deep-sim over the NSE-EQ universe is MINUTES, so this client uses its OWN request factory with a
 * generous read timeout ({@code artha.backtest.deep-swing.read-timeout-ms}, default 15 min). The
 * DEEP_SWING worker holds a platform thread for the call's duration by design — a 4xx/5xx from
 * market-data (unknown family/variant, or the 409 deep-sim-busy gate) propagates as a job failure.
 */
@Component
public class MarketDataDeepSwingClient {

  private final RestClient http;

  /** Builds the client against the market-data base URL with a deep-sim-sized read timeout. */
  public MarketDataDeepSwingClient(
      RestClient.Builder builder,
      @Value("${artha.marketdata.base-url}") String baseUrl,
      @Value("${artha.backtest.deep-swing.connect-timeout-ms:5000}") int connectTimeoutMs,
      @Value("${artha.backtest.deep-swing.read-timeout-ms:900000}") int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(connectTimeoutMs);
    factory.setReadTimeout(readTimeoutMs);
    this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** Runs the parameterized deep-sim and returns the mapped result (throws on a 4xx/5xx). */
  public DeepSwingReply run(String family, LocalDate from, String variant) {
    return http
        .post()
        .uri("/api/v1/market/screener/deep-swing/run")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ClientRequest(family, from, variant))
        .retrieve()
        .body(DeepSwingReply.class);
  }

  /** The request shape market-data expects ({@code from} rides as an ISO date via JavaTimeModule). */
  private record ClientRequest(String family, LocalDate from, String variant) {}
}
