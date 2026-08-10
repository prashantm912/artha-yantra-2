package in.arthayantra.strategysignal.paper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

  /**
   * Wires the configured market-data base URL (same seam as the other paper→market-data clients),
   * with an explicit short connect/read timeout so the F9 heat-cap gate — which prices synchronously
   * on the entry-emission path when enforcement is on — can never stall the tick thread on a slow (not
   * merely down) market-data; a timeout surfaces as an {@code unpriced} quote (fail-soft, never blocks).
   */
  public PaperMarginClient(
      RestClient.Builder builder, @Value("${artha.marketdata.base-url}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(1500));
    factory.setReadTimeout(Duration.ofMillis(2000));
    this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** One basket leg by symbol (the structured tuple is resolved market-data-side). */
  public record Leg(String exchange, String tradingsymbol, int quantity, String side, String product) {}

  /**
   * The margin aggregate mirrored from market-data ({@code priced=false} carries a reason). Mirrors
   * ALL ten components of market-data's {@code MarginController.MarginResponse} — Jackson matches by
   * name, so the three added here ({@code equityMargin} / {@code netBuyPremium} / {@code
   * additionalMargin}) were previously silently DROPPED on the wire rather than absent upstream.
   *
   * <p>{@code netBuyPremium} is the one that matters for this book: a LONG option basket ties up its
   * capital as debit premium, not SPAN, so a long-only book's {@code spanMargin} is structurally
   * near-zero while {@code netBuyPremium} carries the real requirement. Making the field VISIBLE is
   * the whole point of this record change — no consumer re-bases onto it here ({@code RiskService}'s
   * heat still reads {@code spanMargin}); that re-base is a deferred owner decision.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Quote(
      boolean priced,
      String unpricedReason,
      BigDecimal spanMargin,
      BigDecimal exposureMargin,
      BigDecimal equityMargin,
      BigDecimal netBuyPremium,
      BigDecimal additionalMargin,
      BigDecimal totalMargin,
      BigDecimal requiredMargin,
      BigDecimal finalMargin) {

    static Quote unpriced(String reason) {
      return new Quote(false, reason, null, null, null, null, null, null, null, null);
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
