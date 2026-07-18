package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient.Leg;
import in.arthayantra.marketdata.upstox.UpstoxMarginClient.MarginQuote;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the Wave-F9 direct-Upstox margin path: {@link UpstoxMarginClient} POSTs the
 * basket to {@code /v2/charges/margin} with the analytics Bearer token, sums the per-leg breakdown,
 * and passes through Upstox's authoritative basket totals — and fails SOFT (never throws) on an
 * Upstox 400, surfacing the error message as the unpriced reason (the real {@code UDAPI1104}
 * lot-multiple rejection shape observed live 2026-07-04).
 */
class UpstoxMarginClientTest {

  private static WireMockServer wireMock;

  // The exact live-verified single-leg shape (1-lot short, span+exposure, basket totals).
  private static final String MARGIN_BODY =
      """
      {"status":"success","data":{"margins":[
        {"span_margin":337004.85,"exposure_margin":35224.61,"equity_margin":0.0,
         "net_buy_premium":0.0,"additional_margin":0.0,"total_margin":372229.45,"tender_margin":0.0}],
       "required_margin":372229.45,"final_margin":188604.45}}
      """;

  private static final String LOT_ERROR_BODY =
      """
      {"status":"error","errors":[{"errorCode":"UDAPI1104",
        "message":"Quantity should be multiple of lot size"}]}
      """;

  // EXT-03 drift shape A: Upstox returns 200 but every margin field is present-and-ZERO and both
  // basket totals are 0 — the "empty computation" the armed F9 heat cap must never read as a real
  // (tiny) SPAN.
  private static final String ALL_ZERO_BODY =
      """
      {"status":"success","data":{"margins":[
        {"span_margin":0.0,"exposure_margin":0.0,"equity_margin":0.0,
         "net_buy_premium":0.0,"additional_margin":0.0,"total_margin":0.0,"tender_margin":0.0}],
       "required_margin":0.0,"final_margin":0.0}}
      """;

  // EXT-03 drift shape B: Upstox renames/removes the margin fields — span_margin ABSENT → Jackson
  // maps it to null, distinct from a PRESENT 0.0. Trips the per-leg span-null guard (finding 1).
  private static final String ABSENT_FIELDS_BODY =
      """
      {"status":"success","data":{"margins":[{"tender_margin":0.0}]}}
      """;

  // EXT-03 finding 1 — the span-only drift that the wholesale guard alone would MISS: leg 1 prices
  // normally (span present), leg 2 is missing span_margin (renamed/absent → null) but still carries a
  // positive total, and the basket totals are positive. Certifying this as priced would sum span from
  // leg 1 only and under-count the heat. An absent span_margin on ANY leg → unpriced.
  private static final String MIXED_LEG_MISSING_SPAN_BODY =
      """
      {"status":"success","data":{"margins":[
        {"span_margin":100000.0,"exposure_margin":10000.0,"total_margin":110000.0},
        {"exposure_margin":10000.0,"total_margin":110000.0}],
       "required_margin":220000.0,"final_margin":150000.0}}
      """;

  // EXT-03 finding 1 (degenerate): a positive basket total but NO per-leg breakdown (empty margins[]).
  // We cannot assess per-leg SPAN, so pricing with span=0 would understate heat → must be unpriced.
  private static final String EMPTY_MARGINS_BODY =
      """
      {"status":"success","data":{"margins":[],"required_margin":220000.0,"final_margin":150000.0}}
      """;

  // A LEGITIMATE zero-SPAN basket: a long-only options leg costs only its premium, so span/exposure
  // are 0 but net_buy_premium + total_margin + the basket totals are positive. Must stay PRICED (the
  // span-null guard accepts a PRESENT 0.0; the wholesale guard passes because a total is positive) —
  // heat then reads a true 0%.
  private static final String LONG_ONLY_BODY =
      """
      {"status":"success","data":{"margins":[
        {"span_margin":0.0,"exposure_margin":0.0,"equity_margin":0.0,
         "net_buy_premium":9375.0,"additional_margin":0.0,"total_margin":9375.0,"tender_margin":0.0}],
       "required_margin":9375.0,"final_margin":9375.0}}
      """;

  @BeforeAll
  static void start() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stop() {
    wireMock.stop();
  }

  @BeforeEach
  void reset() {
    wireMock.resetAll();
  }

  private static UpstoxMarginClient client() {
    return new UpstoxMarginClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
  }

  @Test
  void mapsWireMarginAndSendsBearerToken() {
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(MARGIN_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 65, "SELL", "D")));

    assertThat(q.priced()).isTrue();
    assertThat(q.spanMargin()).isEqualByComparingTo("337004.85");
    assertThat(q.exposureMargin()).isEqualByComparingTo("35224.61");
    assertThat(q.totalMargin()).isEqualByComparingTo("372229.45");
    assertThat(q.requiredMargin()).isEqualByComparingTo("372229.45");
    assertThat(q.finalMargin()).isEqualByComparingTo("188604.45");

    wireMock.verify(
        postRequestedFor(urlPathEqualTo("/v2/charges/margin"))
            .withHeader("Authorization", equalTo("Bearer test-token")));
  }

  @Test
  void aLotMultipleRejectionFailsSoftWithTheUpstoxMessage() {
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(LOT_ERROR_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 50, "SELL", "D")));

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("Quantity should be multiple of lot size");
    assertThat(q.totalMargin()).isNull();
  }

  @Test
  void anEmptyBasketIsUnpricedWithoutCallingUpstox() {
    MarginQuote q = client().quote(List.of());
    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("no legs");
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v2/charges/margin")));
  }

  @Test
  void aBasketOverTwentyLegsIsRejectedBeforeCallingUpstox() {
    List<Leg> legs = java.util.stream.IntStream.range(0, 21)
        .mapToObj(i -> new Leg("NSE_FO|" + i, 75, "SELL", "D"))
        .toList();
    MarginQuote q = client().quote(legs);
    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).contains("20 legs");
    wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v2/charges/margin")));
  }

  @Test
  void anAllZeroMarginBasketIsUnpricedNotPricedZero() {
    // EXT-03: present-but-zero fields must NOT certify as priced=true, spanMargin=0 (that silently
    // defeats the armed F9 heat cap). The client returns unpriced so the governor sees "cannot assess".
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(ALL_ZERO_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 65, "SELL", "D")));

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("Upstox returned a zero/empty margin basket");
    assertThat(q.spanMargin()).isNull();
  }

  @Test
  void absentSpanMarginFieldIsUnpricedNotPricedZero() {
    // A drift that RENAMES/REMOVES span_margin (→ null, distinct from a present 0.0) trips the
    // per-leg span-null guard — the same silent-defeat vector as all-zero, caught earlier + louder.
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(ABSENT_FIELDS_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 65, "SELL", "D")));

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).contains("no span_margin");
    assertThat(q.spanMargin()).isNull();
  }

  @Test
  void aPositiveTotalBasketWithOneLegMissingSpanMarginIsUnpriced() {
    // EXT-03 finding 1: the span-only drift the wholesale guard would MISS — leg 2 has no span_margin
    // but positive totals exist, so it would price with an under-counted span. The per-leg null guard
    // catches it so the armed heat cap can never read a partial (understated) SPAN.
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(MIXED_LEG_MISSING_SPAN_BODY)));

    MarginQuote q =
        client()
            .quote(
                List.of(
                    new Leg("NSE_FO|44454", 65, "SELL", "D"),
                    new Leg("NSE_FO|44455", 65, "SELL", "D")));

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).contains("no span_margin");
    assertThat(q.spanMargin()).isNull();
  }

  @Test
  void aBasketWithNoPerLegMarginsIsUnpriced() {
    // Empty margins[] with positive basket totals → cannot assess per-leg SPAN → unpriced, not span=0.
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(EMPTY_MARGINS_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 65, "SELL", "D")));

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).contains("no per-leg margins");
    assertThat(q.spanMargin()).isNull();
  }

  @Test
  void aLongOnlyBasketWithZeroSpanButPositivePremiumStaysPriced() {
    // Guards against over-triggering: a genuine long-only options book has span=0 (margin = premium),
    // but carries a positive premium/total — it must stay PRICED so the heat cap reads a true 0%.
    wireMock.stubFor(
        post(urlPathEqualTo("/v2/charges/margin"))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(LONG_ONLY_BODY)));

    MarginQuote q = client().quote(List.of(new Leg("NSE_FO|44454", 75, "BUY", "D")));

    assertThat(q.priced()).isTrue();
    assertThat(q.spanMargin()).isEqualByComparingTo("0");
    assertThat(q.totalMargin()).isEqualByComparingTo("9375.0");
    assertThat(q.finalMargin()).isEqualByComparingTo("9375.0");
  }
}
