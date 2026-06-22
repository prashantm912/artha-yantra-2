package in.arthayantra.marketdata.upstox;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(UpstoxAnalyticsClient.class);

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

  /**
   * Fetches one Market-Information activity series: {@code GET /v2/market/{endpoint}} for a single
   * {@code data_type} segment and interval (U2 consumes {@code fii}/{@code dii} with {@code
   * NSE_EQ|CASH} + {@code 1D}). Returns the segment's activity records (empty if Upstox returns no
   * data for it); transport / HTTP errors propagate so the caller decides whether to fall back.
   */
  public List<UpstoxMarketActivity.Activity> marketActivity(
      String endpoint, String dataType, String interval) {
    UpstoxMarketActivity response = fetch(endpoint, List.of(dataType), interval);
    if (response == null || response.data() == null) {
      return List.of();
    }
    List<UpstoxMarketActivity.Activity> series = response.data().get(dataType);
    if (series == null) {
      // 200 OK but the requested segment is absent — surface the keys Upstox DID return so a
      // data_type/key-shape mismatch is diagnosable, not silently swallowed as a fallback.
      log.warn(
          "Upstox /v2/market/{} returned no '{}' segment (keys present: {})",
          endpoint,
          dataType,
          response.data().keySet());
      return List.of();
    }
    return series;
  }

  /**
   * Fetches several segments in ONE call (U6 requests the four {@code NSE_FO|*} F&amp;O segments
   * together — Upstox accepts repeated {@code data_type} params and returns each keyed in {@code
   * data}). Returns the whole {@code data} map (segment → activity records), empty on a null body;
   * transport / HTTP errors propagate so the caller decides whether to skip.
   */
  public Map<String, List<UpstoxMarketActivity.Activity>> marketActivitySegments(
      String endpoint, List<String> dataTypes, String interval) {
    UpstoxMarketActivity response = fetch(endpoint, dataTypes, interval);
    return response == null || response.data() == null ? Map.of() : response.data();
  }

  /**
   * Max-pain for {@code (instrumentKey, expiry, date)} — {@code GET /v2/market/max-pain}. Returns
   * the {@code data} block (final {@code max_pain} + intraday {@code insights}); {@code null} when
   * Upstox has no data for that expiry/date (a {@code 200} with {@code data:null}).
   */
  public UpstoxOptionAnalytics.Data maxPain(
      String instrumentKey, LocalDate expiry, LocalDate date, int bucketMinutes) {
    return optionAnalytics("max-pain", instrumentKey, expiry, date, bucketMinutes);
  }

  /**
   * Put-call ratio for {@code (instrumentKey, expiry, date)} — {@code GET /v2/market/pcr}. Returns
   * the {@code data} block (final {@code pcr} + intraday {@code insights}); {@code null} when Upstox
   * has no data for that expiry/date.
   */
  public UpstoxOptionAnalytics.Data pcr(
      String instrumentKey, LocalDate expiry, LocalDate date, int bucketMinutes) {
    return optionAnalytics("pcr", instrumentKey, expiry, date, bucketMinutes);
  }

  private UpstoxOptionAnalytics.Data optionAnalytics(
      String endpoint, String instrumentKey, LocalDate expiry, LocalDate date, int bucketMinutes) {
    UpstoxOptionAnalytics response =
        restClient
            .get()
            .uri(
                builder ->
                    builder
                        .path("/v2/market/" + endpoint)
                        .queryParam("instrument_key", instrumentKey)
                        .queryParam("expiry", expiry.toString())
                        .queryParam("date", date.toString())
                        .queryParam("bucket_interval", bucketMinutes)
                        .build())
            .header("Authorization", "Bearer " + properties.resolveToken())
            .header("Accept", "application/json")
            .retrieve()
            .body(UpstoxOptionAnalytics.class);
    return response == null ? null : response.data();
  }

  private UpstoxMarketActivity fetch(String endpoint, List<String> dataTypes, String interval) {
    return restClient
        .get()
        .uri(
            builder -> {
              builder.path("/v2/market/" + endpoint);
              dataTypes.forEach(dt -> builder.queryParam("data_type", dt));
              return builder.queryParam("interval", interval).build();
            })
        .header("Authorization", "Bearer " + properties.resolveToken())
        .header("Accept", "application/json")
        .retrieve()
        .body(UpstoxMarketActivity.class);
  }
}
