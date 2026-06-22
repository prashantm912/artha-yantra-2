package in.arthayantra.marketdata.options.analytics;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsClient;
import in.arthayantra.marketdata.upstox.UpstoxAnalyticsProperties;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link UpstoxOptionAnalyticsSource}: maps Upstox {@code /max-pain} + {@code /pcr}
 * to native-scaled {@code Stats} (pcr 4dp, max-pain 2dp), maps the underlying to the right {@code
 * instrument_key}, and returns {@code null} (⇒ caller keeps native) for an unknown index or empty
 * Upstox data.
 */
class UpstoxOptionAnalyticsSourceTest {

  private static final LocalDate EXPIRY = LocalDate.of(2026, 6, 23);
  private static final LocalDate DATE = LocalDate.of(2026, 6, 22);

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

  private static UpstoxOptionAnalyticsSource source() {
    UpstoxAnalyticsClient client =
        new UpstoxAnalyticsClient(
            RestClient.builder(),
            new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
    return new UpstoxOptionAnalyticsSource(client);
  }

  private static void stub(String endpoint, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/" + endpoint))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  @Test
  void mapsMaxPainAndPcrToNativeScales() {
    stub(
        "max-pain",
        "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
            + "\"expiry_date\":\"23-06-2026\",\"max_pain\":24100.0,\"spot_closing_price\":24087.2,"
            + "\"insights\":[]}}");
    stub(
        "pcr",
        "{\"status\":\"success\",\"data\":{\"instrument_key\":\"NSE_INDEX|Nifty 50\","
            + "\"expiry_date\":\"23-06-2026\",\"pcr\":0.8761612548694148,\"insights\":[]}}");

    UpstoxOptionAnalyticsSource.Stats s = source().statsOrNull("NIFTY 50", EXPIRY, DATE);

    assertThat(s).isNotNull();
    assertThat(s.maxPain()).isEqualByComparingTo("24100.00");
    assertThat(s.pcr()).isEqualByComparingTo("0.8762"); // 0.87616125... → 4 dp HALF_UP

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market/pcr"))
            .withQueryParam("instrument_key", equalTo("NSE_INDEX|Nifty 50"))
            .withQueryParam("expiry", equalTo("2026-06-23"))
            .withQueryParam("date", equalTo("2026-06-22")));
  }

  @Test
  void unknownUnderlyingReturnsNull() {
    assertThat(source().statsOrNull("NIFTY NEXT 50", EXPIRY, DATE)).isNull();
  }

  @Test
  void emptyUpstoxDataReturnsNull() {
    stub("max-pain", "{\"status\":\"success\",\"data\":null}");
    stub("pcr", "{\"status\":\"success\",\"data\":null}");

    assertThat(source().statsOrNull("SENSEX", EXPIRY, DATE)).isNull();
  }

  @Test
  void mapsPcrSeriesFromInsights() {
    stub(
        "pcr",
        "{\"status\":\"success\",\"data\":{\"pcr\":0.88,\"insights\":["
            + "{\"pcr\":0.7832266,\"spot_price\":24075.1,\"time\":\"09:15\"},"
            + "{\"pcr\":1.05,\"spot_price\":24153.1,\"time\":\"10:15\"}]}}");

    List<PcrSeriesPoint> series = source().pcrSeries("NIFTY 50", EXPIRY, DATE, 60);

    assertThat(series).hasSize(2);
    assertThat(series.get(0).time()).isEqualTo("09:15");
    assertThat(series.get(0).pcr()).isEqualByComparingTo("0.7832"); // 4 dp HALF_UP
    assertThat(series.get(0).spot()).isEqualByComparingTo("24075.1");
  }

  @Test
  void pcrSeriesEmptyOnUnknownIndex() {
    assertThat(source().pcrSeries("NIFTY NEXT 50", EXPIRY, DATE, 60)).isEmpty();
  }
}
