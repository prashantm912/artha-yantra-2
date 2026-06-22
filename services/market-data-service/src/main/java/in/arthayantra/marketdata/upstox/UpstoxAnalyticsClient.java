package in.arthayantra.marketdata.upstox;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Hand-rolled Upstox Market-Information REST client (ADR-0002 — the same anti-corruption pattern as
 * the Kite REST adapters; the broker SDK is never imported, and the Java SDK does not wrap these
 * endpoints anyway). For U1 it carries only the <b>entitlement probe</b>: one live call whose HTTP
 * status answers the gating question — "does this Upstox Plus analytics token cover the
 * Market-Information APIs?" ({@code 200} = entitled, {@code 403} = not, so the NSE fallback stays
 * primary). Typed full-mirror wire DTOs land per consuming step (U2+).
 */
public final class UpstoxAnalyticsClient {

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxAnalyticsClient(RestClient.Builder builder, UpstoxAnalyticsProperties properties) {
    this.restClient = builder.baseUrl(properties.baseUrl()).build();
    this.properties = properties;
  }

  /** The outcome of the entitlement probe (never throws — the status itself is the signal). */
  public record Entitlement(boolean covered, int httpStatus, String detail) {}

  /**
   * Probes {@code GET /v2/market/fii} with the smallest valid request. A {@code 200} means the token
   * is entitled to Market-Information; a {@code 403} means it is not (and the NSE fallback stays
   * primary). Any other HTTP status, or a transport failure, is reported verbatim for diagnosis and
   * treated as not-covered (we never trust the feed on an ambiguous signal).
   */
  public Entitlement probe() {
    try {
      restClient
          .get()
          .uri(
              builder ->
                  builder
                      .path("/v2/market/fii")
                      .queryParam("data_type", "NSE_EQ|CASH")
                      .queryParam("interval", "1D")
                      .build())
          .header("Authorization", "Bearer " + properties.resolveToken())
          .header("Accept", "application/json")
          .retrieve()
          .toBodilessEntity();
      return new Entitlement(true, 200, "Upstox Market-Information is entitled (HTTP 200)");
    } catch (RestClientResponseException e) {
      int status = e.getStatusCode().value();
      String detail =
          status == 403
              ? "Upstox Plus does not cover Market-Information (HTTP 403) — NSE fallback stays primary"
              : "Upstox Market-Information probe returned HTTP " + status;
      return new Entitlement(false, status, detail);
    } catch (RestClientException e) {
      return new Entitlement(false, 0, "Upstox Market-Information probe failed: " + e.getMessage());
    }
  }
}
