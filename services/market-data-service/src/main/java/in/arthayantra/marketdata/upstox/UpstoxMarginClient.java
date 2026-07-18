package in.arthayantra.marketdata.upstox;

import com.fasterxml.jackson.annotation.JsonProperty;
import in.arthayantra.marketdata.upstox.wire.UpstoxMargin;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Direct-Upstox pre-trade SPAN margin client (ADR-0002, the SAME hand-rolled {@code RestClient}
 * shape as {@link UpstoxOptionChainClient}). One call — {@code POST /v2/charges/margin} — asks
 * Upstox to compute SPAN + exposure margin for a basket of ≤20 F&amp;O legs on the login-free 1-yr
 * analytics token, so the platform never needs an {@code .spn} file (the F9 risk-layer SPAN source,
 * verified live 2026-07-04). The marginism appliance stays the offline/backtest fallback.
 *
 * <p>Fail-soft by contract: any transport / HTTP / mapping failure returns an {@link
 * MarginQuote#unpriced(String)} carrying the reason (never throws into the caller) — advisory
 * sizing must never break the paper path. Upstox's own 400s (e.g. a quantity that is not a lot
 * multiple, {@code UDAPI1104}) surface as the unpriced reason.
 */
public final class UpstoxMarginClient {

  private static final Logger log = LoggerFactory.getLogger(UpstoxMarginClient.class);

  private final RestClient restClient;
  private final UpstoxAnalyticsProperties properties;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxMarginClient(RestClient.Builder builder, UpstoxAnalyticsProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(20_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
  }

  /** A resolved basket leg: the Upstox {@code instrument_key}, signed quantity, side, product. */
  public record Leg(String instrumentKey, int quantity, String side, String product) {}

  /** The mapped basket margin (summed breakdown + Upstox's authoritative basket totals). */
  public record MarginQuote(
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

    static MarginQuote unpriced(String reason) {
      return new MarginQuote(false, reason, null, null, null, null, null, null, null, null);
    }
  }

  /** The request body Upstox expects — one instrument per basket leg. */
  private record RequestBody(List<Instrument> instruments) {}

  private record Instrument(
      @JsonProperty("instrument_key") String instrumentKey,
      int quantity,
      @JsonProperty("transaction_type") String transactionType,
      String product) {}

  /**
   * SPAN+exposure margin for the basket, or {@link MarginQuote#unpriced(String)} on any failure.
   * The per-leg breakdown fields are summed across {@code margins[]}; {@code requiredMargin} /
   * {@code finalMargin} are Upstox's authoritative basket totals (net of margin benefit).
   */
  public MarginQuote quote(List<Leg> legs) {
    if (legs == null || legs.isEmpty()) {
      return MarginQuote.unpriced("no legs");
    }
    if (legs.size() > 20) {
      return MarginQuote.unpriced("more than 20 legs (Upstox basket cap)");
    }
    List<Instrument> instruments =
        legs.stream()
            .map(l -> new Instrument(l.instrumentKey(), l.quantity(), l.side(), l.product()))
            .toList();
    try {
      UpstoxMargin response =
          restClient
              .post()
              .uri("/v2/charges/margin")
              .header("Authorization", "Bearer " + properties.resolveToken())
              .contentType(MediaType.APPLICATION_JSON)
              .body(new RequestBody(instruments))
              .retrieve()
              .body(UpstoxMargin.class);
      if (response == null || response.data() == null || response.data().margins() == null) {
        return MarginQuote.unpriced("empty margin response");
      }
      return aggregate(response.data());
    } catch (RestClientResponseException http) {
      // Upstox validation/business errors (e.g. UDAPI1104 lot-multiple) carry a JSON error body.
      String reason = firstErrorMessage(http.getResponseBodyAsString());
      log.warn("Upstox margin {} — {}", http.getStatusCode(), reason);
      return MarginQuote.unpriced(reason);
    } catch (RuntimeException e) {
      log.warn("Upstox margin call failed: {}", e.toString());
      return MarginQuote.unpriced("margin call failed");
    }
  }

  private static MarginQuote aggregate(UpstoxMargin.Data data) {
    BigDecimal span = BigDecimal.ZERO;
    BigDecimal exposure = BigDecimal.ZERO;
    BigDecimal equity = BigDecimal.ZERO;
    BigDecimal premium = BigDecimal.ZERO;
    BigDecimal additional = BigDecimal.ZERO;
    BigDecimal total = BigDecimal.ZERO;
    for (UpstoxMargin.Margin m : data.margins()) {
      span = span.add(nz(m.spanMargin()));
      exposure = exposure.add(nz(m.exposureMargin()));
      equity = equity.add(nz(m.equityMargin()));
      premium = premium.add(nz(m.netBuyPremium()));
      additional = additional.add(nz(m.additionalMargin()));
      total = total.add(nz(m.totalMargin()));
    }
    // EXT-03 fail-soft-but-HONEST: a real F&O basket ALWAYS costs a positive margin — a short leg
    // needs SPAN+exposure, a long leg needs its premium. If Upstox drifts or returns empty/all-zero
    // margin fields (present-but-0, OR absent → null → summed as 0 by nz()), every summed component
    // is 0 AND both authoritative basket totals are null/0. Certifying THAT as priced=true,
    // spanMargin=0 would silently defeat the ARMED F9 heat cap: RiskService.currentHeatPct reads a
    // present-but-zero span as a genuine tiny margin → heat 0% < cap → the gate never trips. So the
    // client must NOT certify a no-margin basket as a real quote — with no positive margin anywhere
    // authoritative (summed per-leg total, OR either Upstox basket total) it returns UNPRICED, just
    // like the UDAPI1104 path, and the F9 governor routes to its VISIBLE fail-soft branch (audits
    // UNPRICED, never a phantom "under cap"). A LEGITIMATE zero — a long-only options book — keeps
    // span==0 but carries its premium in total/required/final, so it stays priced (heat then reads a
    // true 0%). A single-field span_margin RENAME while the other totals stay valid is a
    // CONSUMED-field drift that belongs to the daily UpstoxContractCanary layer, not this guard.
    if (!isPositive(total) && !isPositive(data.requiredMargin()) && !isPositive(data.finalMargin())) {
      return MarginQuote.unpriced("Upstox returned a zero/empty margin basket");
    }
    return new MarginQuote(
        true, null, span, exposure, equity, premium, additional, total,
        data.requiredMargin(), data.finalMargin());
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** A margin figure that is present and strictly positive (null / zero / negative → false). */
  private static boolean isPositive(BigDecimal v) {
    return v != null && v.signum() > 0;
  }

  /** Best-effort pull of the first Upstox error message from the JSON error body. */
  private static String firstErrorMessage(String body) {
    if (body == null) {
      return "margin request rejected";
    }
    int i = body.indexOf("\"message\"");
    if (i < 0) {
      return "margin request rejected";
    }
    int start = body.indexOf('"', body.indexOf(':', i) + 1);
    int end = start < 0 ? -1 : body.indexOf('"', start + 1);
    return (start < 0 || end < 0) ? "margin request rejected" : body.substring(start + 1, end);
  }
}
