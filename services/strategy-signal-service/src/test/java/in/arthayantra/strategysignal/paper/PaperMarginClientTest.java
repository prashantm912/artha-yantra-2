package in.arthayantra.strategysignal.paper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Wire test for {@link PaperMarginClient}: market-data's {@code MarginController.MarginResponse}
 * carries TEN components, and the client record must mirror every one of them or Jackson silently
 * drops the difference (a missing component is not an upstream absence — it is an invisible loss on
 * OUR side of the wire).
 *
 * <p>{@code equityMargin} / {@code netBuyPremium} / {@code additionalMargin} are the three that were
 * blind. {@code netBuyPremium} is the load-bearing one: a LONG option basket's capital requirement
 * lives there rather than in SPAN, and the scalper book is long-only — so a heat read keyed on
 * {@code spanMargin} alone cannot see it. This test only proves the field ARRIVES; nothing re-bases
 * onto it (that is a deferred owner decision).
 *
 * <p>The fixture sends decimals as JSON STRINGS, which is the real wire shape: {@code
 * ArthaJacksonAutoConfiguration} registers {@code ToStringSerializer} for {@code BigDecimal}
 * platform-wide, so market-data emits {@code "337004.85"}, not {@code 337004.85}.
 */
class PaperMarginClientTest {

  private static WireMockServer wireMock;

  private static final String MARGIN_PATH = "/api/v1/market/margin";

  /** A faithful priced response — all ten components, each a DISTINCT value so none can alias. */
  private static final String PRICED_TEN_FIELD =
      """
      {"priced":true,"unpricedReason":null,
       "spanMargin":"337004.85","exposureMargin":"50000.10","equityMargin":"1200.20",
       "netBuyPremium":"7350.30","additionalMargin":"430.40","totalMargin":"387004.95",
       "requiredMargin":"387004.95","finalMargin":"188604.45"}
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

  private static PaperMarginClient client() {
    return new PaperMarginClient(RestClient.builder(), wireMock.baseUrl());
  }

  private static List<PaperMarginClient.Leg> oneLeg() {
    return List.of(new PaperMarginClient.Leg("NFO", "NIFTY2570725000CE", 65, "SELL", "D"));
  }

  private static void stub(String body) {
    wireMock.stubFor(
        post(urlPathEqualTo(MARGIN_PATH))
            .willReturn(
                aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void deserializesAllTenComponentsOfTheMarketDataMarginResponse() {
    stub(PRICED_TEN_FIELD);

    PaperMarginClient.Quote q = client().margin(oneLeg());

    assertThat(q.priced()).isTrue();
    assertThat(q.unpricedReason()).isNull();
    assertThat(q.spanMargin()).isEqualByComparingTo("337004.85");
    assertThat(q.exposureMargin()).isEqualByComparingTo("50000.10");
    // The three components the 7-field record silently dropped on the wire.
    assertThat(q.equityMargin()).as("equityMargin must survive the wire").isEqualByComparingTo("1200.20");
    assertThat(q.netBuyPremium())
        .as("netBuyPremium — a long-only book's capital requirement lives here, not in SPAN")
        .isEqualByComparingTo("7350.30");
    assertThat(q.additionalMargin())
        .as("additionalMargin must survive the wire")
        .isEqualByComparingTo("430.40");
    assertThat(q.totalMargin()).isEqualByComparingTo("387004.95");
    assertThat(q.requiredMargin()).isEqualByComparingTo("387004.95");
    assertThat(q.finalMargin()).isEqualByComparingTo("188604.45");
  }

  @Test
  void anUnpricedResponseCarriesTheReasonAndNullsEveryAmount() {
    stub("{\"priced\":false,\"unpricedReason\":\"margin service not configured\"}");

    PaperMarginClient.Quote q = client().margin(oneLeg());

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("margin service not configured");
    // Absent components deserialize to null — including the three added ones.
    assertThat(q.spanMargin()).isNull();
    assertThat(q.equityMargin()).isNull();
    assertThat(q.netBuyPremium()).isNull();
    assertThat(q.additionalMargin()).isNull();
    assertThat(q.finalMargin()).isNull();
  }

  @Test
  void transportFailureDegradesToAnUnpricedQuoteNeverThrows() {
    // The heat read is advisory: market-data down must never propagate out of the client.
    wireMock.stubFor(
        post(urlPathEqualTo(MARGIN_PATH)).willReturn(aResponse().withStatus(500).withBody("boom")));

    PaperMarginClient.Quote q = client().margin(oneLeg());

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("margin service unavailable");
    assertThat(q.netBuyPremium()).isNull();
  }

  @Test
  void anEmptyBasketIsUnpricedWithoutCallingMarketData() {
    PaperMarginClient.Quote q = client().margin(List.of());

    assertThat(q.priced()).isFalse();
    assertThat(q.unpricedReason()).isEqualTo("no open positions");
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }
}
