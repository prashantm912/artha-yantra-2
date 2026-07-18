package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for the {@link UpstoxAnalyticsClient} entitlement probe: a {@code 200} is reported as
 * entitled, a {@code 403} as not-entitled (NSE fallback), any other status / transport error as
 * not-covered with the status preserved. Also asserts the Bearer token + the request shape.
 */
class UpstoxAnalyticsClientTest {

  private static WireMockServer wireMock;

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

  private static UpstoxAnalyticsClient client() {
    return new UpstoxAnalyticsClient(
        RestClient.builder(),
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"),
        new UpstoxRateLimiter());
  }

  @Test
  void reportsEntitledOn200AndSendsBearerTokenAndParams() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":{}}")));

    UpstoxAnalyticsClient.Entitlement e = client().probe();

    assertThat(e.covered()).isTrue();
    assertThat(e.httpStatus()).isEqualTo(200);
    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market/fii"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .withQueryParam("interval", equalTo("1D")));
  }

  @Test
  void reportsNotEntitledOn403() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/fii"))
            .willReturn(aResponse().withStatus(403).withBody("{\"status\":\"error\"}")));

    UpstoxAnalyticsClient.Entitlement e = client().probe();

    assertThat(e.covered()).isFalse();
    assertThat(e.httpStatus()).isEqualTo(403);
    assertThat(e.detail()).contains("403").contains("NSE fallback");
  }

  @Test
  void reportsNotCoveredOnOtherStatus() {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/fii")).willReturn(aResponse().withStatus(500)));

    UpstoxAnalyticsClient.Entitlement e = client().probe();

    assertThat(e.covered()).isFalse();
    assertThat(e.httpStatus()).isEqualTo(500);
  }
}
