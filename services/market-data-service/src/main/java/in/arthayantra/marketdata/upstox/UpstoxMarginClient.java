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
  /** The token-scoped budget shared across every analytics-token client (EXT-02); live path (full cap). */
  private final UpstoxRateLimiter limiter;

  /** Binds the wire client to the configured base URL (real Upstox, or WireMock in tests). */
  public UpstoxMarginClient(
      RestClient.Builder builder, UpstoxAnalyticsProperties properties, UpstoxRateLimiter limiter) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(15_000);
    factory.setReadTimeout(20_000);
    this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    this.properties = properties;
    this.limiter = limiter;
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
    limiter.acquire(); // live-critical pre-trade margin — draws from the token's live-reserved headroom
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
    // No per-leg breakdown at all (empty margins[]) for a non-empty basket request → we cannot assess
    // SPAN, so pricing with span=0 would understate heat (finding-1 invariant). Treat as unpriced.
    if (data.margins().isEmpty()) {
      return MarginQuote.unpriced("Upstox returned no per-leg margins");
    }
    BigDecimal span = BigDecimal.ZERO;
    BigDecimal exposure = BigDecimal.ZERO;
    BigDecimal equity = BigDecimal.ZERO;
    BigDecimal premium = BigDecimal.ZERO;
    BigDecimal additional = BigDecimal.ZERO;
    BigDecimal total = BigDecimal.ZERO;
    for (UpstoxMargin.Margin m : data.margins()) {
      // EXT-03 finding 1 — the RUNTIME guard for the ARMED F9 heat cap, which consumes span_margin.
      // A LEGITIMATE long-only leg reports span_margin PRESENT = 0.0 (margin = premium only); a
      // drift/rename of the field leaves it ABSENT → Jackson maps it to null. nz() would erase that
      // distinction (both → 0), so a span-only rename would still price the basket with span=0 and
      // silently defeat the cap (heat 0% < cap → never trips). So require span_margin PRESENT on
      // EVERY returned leg: any null (absent/renamed) → UNPRICED; a present 0.0 stays valid. Do NOT
      // compare span vs total — span=0 with total>0 is a legitimate long-only book, not a drift.
      if (m.spanMargin() == null) {
        return MarginQuote.unpriced("Upstox returned a leg with no span_margin — margin response drifted");
      }
      span = span.add(m.spanMargin());
      exposure = exposure.add(nz(m.exposureMargin()));
      equity = equity.add(nz(m.equityMargin()));
      premium = premium.add(nz(m.netBuyPremium()));
      additional = additional.add(nz(m.additionalMargin()));
      total = total.add(nz(m.totalMargin()));
    }
    // EXT-03 wholesale-empty guard (defense-in-depth alongside the per-leg span check above): a real
    // F&O basket ALWAYS costs a positive margin — a short leg needs SPAN+exposure, a long leg its
    // premium. If Upstox returns present-but-all-zero fields (span_margin 0.0 passes the null check
    // above, but every component and both basket totals are 0), certifying priced=true, spanMargin=0
    // would still silently defeat the heat cap. So with NO positive margin anywhere authoritative
    // (summed per-leg total, OR either Upstox basket total) → UNPRICED, like the UDAPI1104 path; the
    // F9 governor then routes to its VISIBLE fail-soft branch (audits UNPRICED, never a phantom
    // "under cap"). A long-only book keeps span==0 but carries its premium in total/required/final,
    // so it stays priced (heat then reads a true 0%).
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
