package in.arthayantra.marketdata.upstox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Hand-rolled Upstox <b>Company Fundamentals</b> REST client (ADR-0002 / ADR-0004) — the
 * ISIN-keyed financial endpoints on the analytics token: {@code share-holdings} (→ free-float %),
 * {@code key-ratios} (→ P/E, ROE), {@code income-statement} (→ revenue, net-profit in crore). Same
 * anti-corruption shape as {@link UpstoxAnalyticsClient}; each call degrades to {@code null} on a
 * transport/HTTP failure (the caller then skips that symbol) and never propagates out of a fetch.
 */
public final class UpstoxFundamentalsClient {

  private static final Logger log = LoggerFactory.getLogger(UpstoxFundamentalsClient.class);

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxFundamentalsClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    // The Minervini universe fundamentals refresh is a BULK walk (3 calls/symbol), so every call
    // debits the ONE shared token budget (EXT-02) on the BATCH path — paced below the live reserve and
    // paused during market hours so it never competes with live capture.
    this.restClient =
        builder
            .baseUrl(properties.baseUrl())
            .requestInterceptor(
                (request, body, execution) -> {
                  limiter.acquireForBatch();
                  return execution.execute(request, body);
                })
            .build();
    this.properties = properties;
  }

  /** {@code GET /v2/fundamentals/{isin}/share-holdings} — promoter/FII/DII/public %. */
  public UpstoxShareHoldings shareHoldings(String isin) {
    return get("/v2/fundamentals/" + isin + "/share-holdings", UpstoxShareHoldings.class);
  }

  /** {@code GET /v2/fundamentals/{isin}/key-ratios} — P/E, P/B, ROE, ... vs sector. */
  public UpstoxKeyRatios keyRatios(String isin) {
    return get("/v2/fundamentals/" + isin + "/key-ratios", UpstoxKeyRatios.class);
  }

  /** {@code GET /v2/fundamentals/{isin}/income-statement?time_period=yearly} — revenue/net-profit (crore). */
  public UpstoxIncomeStatement incomeStatementYearly(String isin) {
    try {
      return restClient
          .get()
          .uri(
              b ->
                  b.path("/v2/fundamentals/" + isin + "/income-statement")
                      .queryParam("time_period", "yearly")
                      .queryParam("type", "consolidated")
                      .build())
          .header("Authorization", "Bearer " + properties.resolveToken())
          .header("Accept", "application/json")
          .retrieve()
          .body(UpstoxIncomeStatement.class);
    } catch (RestClientException e) {
      log.warn("Upstox income-statement fetch failed for {}: {}", isin, e.getMessage());
      return null;
    }
  }

  private <T> T get(String path, Class<T> type) {
    try {
      return restClient
          .get()
          .uri(path)
          .header("Authorization", "Bearer " + properties.resolveToken())
          .header("Accept", "application/json")
          .retrieve()
          .body(type);
    } catch (RestClientException e) {
      log.warn("Upstox fundamentals fetch failed ({}): {}", path, e.getMessage());
      return null;
    }
  }
}
