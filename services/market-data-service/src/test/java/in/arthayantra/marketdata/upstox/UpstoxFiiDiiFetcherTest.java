package in.arthayantra.marketdata.upstox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import in.arthayantra.marketdata.nse.FiiDiiFetcher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * WireMock test for {@link UpstoxFiiDiiFetcher}: Upstox {@code /v2/market/fii} + {@code /dii} cash
 * activity maps to NSE-shaped {@code FiiDiiRow}s (INR→₹crore, IST trade date, FII/FPI + DII
 * categories), and any Upstox failure / empty response falls back to the NSE fetcher.
 */
class UpstoxFiiDiiFetcherTest {

  /** 2026-04-29T20:00:00Z is 2026-04-30 01:30 IST — proves the timestamp is read in IST, not UTC. */
  private static final long TS = Instant.parse("2026-04-29T20:00:00Z").toEpochMilli();

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
        new UpstoxAnalyticsProperties(wireMock.baseUrl(), null, "test-token"));
  }

  private static void stub(String endpoint, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/" + endpoint))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
  }

  private static String cashBody(long ts, double buyInr, double sellInr) {
    return "{\"status\":\"success\",\"data\":{\"NSE_EQ|CASH\":[{\"time_stamp\":"
        + ts
        + ",\"buy_amount\":"
        + buyInr
        + ",\"sell_amount\":"
        + sellInr
        + "}]}}";
  }

  @Test
  void mapsUpstoxCashToCroreRowsInIst() {
    // Upstox cash amounts are ALREADY in ₹ crore (verified byte-identical to NSE) — kept as-is.
    stub("fii", cashBody(TS, 10082.08, 10717.99));
    stub("dii", cashBody(TS, 17391.78, 16356.06));

    List<FiiDiiFetcher.FiiDiiRow> rows = fetcher(nseSentinel()).fetchLatest();

    assertThat(rows).hasSize(2);
    FiiDiiFetcher.FiiDiiRow fii = rows.get(0);
    assertThat(fii.category()).isEqualTo("FII/FPI");
    assertThat(fii.date()).isEqualTo(LocalDate.of(2026, 4, 30));
    assertThat(fii.buy()).isEqualByComparingTo("10082.08");
    assertThat(fii.sell()).isEqualByComparingTo("10717.99");
    assertThat(fii.net()).isEqualByComparingTo("-635.91");

    FiiDiiFetcher.FiiDiiRow dii = rows.get(1);
    assertThat(dii.category()).isEqualTo("DII");
    assertThat(dii.buy()).isEqualByComparingTo("17391.78");
    assertThat(dii.net()).isEqualByComparingTo("1035.72");

    wireMock.verify(
        getRequestedFor(urlPathEqualTo("/v2/market/fii"))
            .withHeader("Authorization", equalTo("Bearer test-token"))
            .withQueryParam("data_type", equalTo("NSE_EQ|CASH"))
            .withQueryParam("interval", equalTo("1D")));
  }

  @Test
  void fallsBackToNseWhenUpstoxErrors() {
    stub("dii", cashBody(TS, 5000000000.0, 4000000000.0));
    wireMock.stubFor(
        get(urlPathEqualTo("/v2/market/fii")).willReturn(aResponse().withStatus(500)));

    List<FiiDiiFetcher.FiiDiiRow> rows = fetcher(nseSentinel()).fetchLatest();

    assertThat(rows).singleElement().extracting(FiiDiiFetcher.FiiDiiRow::category).isEqualTo("NSE");
  }

  @Test
  void fallsBackToNseWhenUpstoxEmpty() {
    String empty = "{\"status\":\"success\",\"data\":{}}";
    stub("fii", empty);
    stub("dii", empty);

    List<FiiDiiFetcher.FiiDiiRow> rows = fetcher(nseSentinel()).fetchLatest();

    assertThat(rows).singleElement().extracting(FiiDiiFetcher.FiiDiiRow::category).isEqualTo("NSE");
  }

  private static UpstoxFiiDiiFetcher fetcher(FiiDiiFetcher fallback) {
    return new UpstoxFiiDiiFetcher(client(), fallback);
  }

  /** A sentinel NSE fetcher — its single "NSE"-category row proves the fallback path was taken. */
  private static FiiDiiFetcher nseSentinel() {
    return () ->
        List.of(
            new FiiDiiFetcher.FiiDiiRow(
                LocalDate.of(2026, 4, 30),
                "NSE",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ONE));
  }
}
