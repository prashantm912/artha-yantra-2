package in.arthayantra.strategysignal.paper;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls market-data's pre-trade SPAN margin endpoint ({@code POST /api/v1/market/margin}) for a
 * basket of paper option legs — the F9 advisory heat read. Legs are given by {@code tradingsymbol}
 * (market-data resolves the structured tuple from the instrument master). Fail-soft: a transport or
 * mapping failure yields an {@code unpriced} quote (never throws) — the heat read is advisory only.
 */
@Component
public class PaperMarginClient {

  private static final Logger log = LoggerFactory.getLogger(PaperMarginClient.class);

  private final RestClient restClient;

  /** Wires the configured market-data base URL (same seam as the other paper→market-data clients). */
  public PaperMarginClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    this.restClient = builder.baseUrl(baseUrl).build();
  }

  /** One basket leg by symbol (the structured tuple is resolved market-data-side). */
  public record Leg(String exchange, String tradingsymbol, int quantity, String side, String product) {}

  /** The margin aggregate mirrored from market-data ({@code priced=false} carries a reason). */
  public record Quote(
      boolean priced,
      String unpricedReason,
      BigDecimal spanMargin,
      BigDecimal exposureMargin,
      BigDecimal totalMargin,
      BigDecimal requiredMargin,
      BigDecimal finalMargin) {

    static Quote unpriced(String reason) {
      return new Quote(false, reason, null, null, null, null, null);
    }
  }

  private record Request(List<Leg> legs) {}

  /** SPAN margin for the basket, or an {@code unpriced} quote on any failure. */
  public Quote margin(List<Leg> legs) {
    if (legs == null || legs.isEmpty()) {
      return Quote.unpriced("no open positions");
    }
    try {
      Quote q =
          restClient
              .post()
              .uri("/api/v1/market/margin")
              .contentType(MediaType.APPLICATION_JSON)
              .body(new Request(legs))
              .retrieve()
              .body(Quote.class);
      return q == null ? Quote.unpriced("empty margin response") : q;
    } catch (RestClientException e) {
      log.warn("paper margin heat call failed: {}", e.getMessage());
      return Quote.unpriced("margin service unavailable");
    }
  }
}
