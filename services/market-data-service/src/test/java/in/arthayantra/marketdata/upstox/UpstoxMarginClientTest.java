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
}
